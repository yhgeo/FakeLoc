package com.mo.fakeloc.xposed.hooks

import android.location.Location
import android.location.LocationManager
import com.mo.fakeloc.xposed.ConfigBridge
import com.mo.fakeloc.xposed.FakeLocationFactory
import com.mo.fakeloc.xposed.HookLog
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * **system_server 侧**的定位拦截。
 *
 * 为什么必须动 system_server：
 *  - 有些应用/服务直接跟系统定位服务打交道，不经过 App 进程的 LocationManager 包装；
 *  - GNSS / 融合定位的数据源在系统侧，从源头改写，所有下游（包括 Play Services
 *    的融合算法、PendingIntent 形式的定位回调）拿到的都是假数据。
 *
 * ## 为什么用"泛化扫描"而不是写死方法名
 * AOSP 的定位模块在 Android 12 / 13 / 14 之间改过好几轮：
 *  - 类从 `com.android.server.location.*` 搬到了 `com.android.server.location.provider.*`；
 *  - GNSS 从 `com.android.server.location.GnssLocationProvider` 搬到了
 *    `com.android.server.location.gnss.GnssLocationProvider`；
 *  - 方法签名（`onReportLocation(Location)` vs `onReportLocation(LocationResult)`）也在变。
 *
 * 写死任何一个都会在某个版本上直接失效，而且失效是**静默**的 —— 这正是
 * "LSPosed 模式好像没成功"最难查的地方。
 *
 * 所以这里改成**按类型特征扫描**，四条规则覆盖全部可能：
 *  - 返回值是 `Location`       → 换掉返回值
 *  - 返回值是 `LocationResult` → 换掉返回值
 *  - 参数里有 `Location`       → 换掉该参数
 *  - 参数里有 `LocationResult` → 换掉该参数
 *
 * 匹配不到就自动跳过，不会因为某个 ROM 改了实现就崩。
 *
 * ## 递归保护
 * 我们造出来的 Location 都带 [FakeLocationFactory.EXTRA_MARK] 标记，
 * [buildFake] 遇到带标记的直接返回 null，所以即使 hook 到自己的上游也不会死循环。
 */
object SystemServerHook {

    private const val CLS_LOCATION = "android.location.Location"
    private const val CLS_LOCATION_RESULT = "android.location.LocationResult"

    private const val TAG = "SS"

    /**
     * 需要扫描的类。同一逻辑类在不同 AOSP 版本下的多个包名都列上，
     * 找不到的自动跳过。
     */
    private val TARGET_CLASSES = listOf(
        // Android 12+ 的定位分发中枢：所有 provider 的结果都从这里出去
        "com.android.server.location.provider.LocationProviderManager",
        // 包住真实 provider，负责 mock / 测试 provider 的切换
        "com.android.server.location.provider.MockableLocationProvider",
        "com.android.server.location.MockableLocationProvider",
        // GNSS 数据源（Android 13 起搬进 gnss 子包）
        "com.android.server.location.gnss.GnssLocationProvider",
        "com.android.server.location.GnssLocationProvider",
        // provider 抽象基类，reportLocation 之类的入口在这
        "com.android.server.location.provider.AbstractLocationProvider",
        // 网络定位 / 代理 provider
        "com.android.server.location.provider.ProxyLocationProvider",
        "com.android.server.location.provider.NetworkLocationProvider",
        // 同步查询路径（老版本走这里）
        "com.android.server.location.LocationManagerService"
    )

    /** 已经挂过的 Method，避免同一个方法被挂两次。 */
    private val hooked = HashSet<String>()

    fun install(cl: ClassLoader): Int {
        var total = 0
        TARGET_CLASSES.forEach { name ->
            val clazz = XposedHelpers.findClassIfExists(name, cl) ?: return@forEach
            val n = scan(clazz, allowResult = true, allowArgs = true)
            if (n > 0) {
                total += n
                HookLog.i("$TAG: $name hooked=$n")
            }
        }
        if (total == 0) {
            HookLog.e("$TAG: 没有挂到任何方法 —— 该 ROM 的定位实现可能不在已知类里")
        }
        return total
    }

    // ------------------------------------------------------------------ 扫描

    private fun scan(clazz: Class<*>, allowResult: Boolean, allowArgs: Boolean): Int {
        var n = 0
        clazz.declaredMethods.forEach { m ->
            if (!isHookable(m)) return@forEach
            if (hookOne(m, allowResult, allowArgs)) n++
        }
        return n
    }

    private fun isHookable(m: Method): Boolean {
        if (m.isSynthetic || m.isBridge) return false
        if (Modifier.isAbstract(m.modifiers)) return false
        if (Modifier.isNative(m.modifiers)) return false
        // 构造器不处理
        if (m.name == "<init>" || m.name == "<clinit>") return false
        return true
    }

    private fun hookOne(m: Method, allowResult: Boolean, allowArgs: Boolean): Boolean {
        val returnsLocation = m.returnType.name == CLS_LOCATION
        val returnsResult = m.returnType.name == CLS_LOCATION_RESULT
        val hasLocationArg = m.parameterTypes.any { it.name == CLS_LOCATION }
        val hasResultArg = m.parameterTypes.any { it.name == CLS_LOCATION_RESULT }

        val wantResult = allowResult && (returnsLocation || returnsResult)
        val wantArgs = allowArgs && (hasLocationArg || hasResultArg)
        if (!wantResult && !wantArgs) return false

        val key = "${m.declaringClass.name}#${m.name}(${m.parameterTypes.joinToString { it.name }})"
        synchronized(hooked) {
            if (hooked.contains(key)) return false
            hooked.add(key)
        }

        return try {
            XposedBridge.hookMethod(m, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (wantArgs) replaceArgs(param)
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!wantResult) return
                    if (param.hasThrowable()) return

                    val r = param.result
                    if (r == null) {
                        // 真机上最常见的失败形态：室内 GPS 没有定位，
                        // `gps provider: last location=null`，应用拿到 null 就认为定位失败。
                        // 所以对"查询类"方法，null 也要能造一个出来。
                        if (!isQueryMethod(m.name)) return
                        param.result = fabricate(returnsResult, m.declaringClass.classLoader)
                        return
                    }

                    if (returnsResult && isEmptyResult(r)) {
                        if (!isQueryMethod(m.name)) return
                        param.result = fabricate(true, m.declaringClass.classLoader) ?: r
                        return
                    }

                    param.result = when {
                        r is Location -> buildFake(r, r.provider) ?: r
                        r.javaClass.name == CLS_LOCATION_RESULT ->
                            replaceLocationResult(r, m.declaringClass.classLoader) ?: r
                        else -> r
                    }
                }
            })
            true
        } catch (t: Throwable) {
            HookLog.v("$TAG: hook ${m.name} failed: ${t.message}")
            false
        }
    }

    /**
     * 只对"查询当前位置"的方法做 null→伪造。
     *
     * 不能对所有返回 Location 的方法都这么干 —— 有些方法返回 null 是有语义的
     * （例如"当前没有 mock provider"），乱填会改变系统行为。
     */
    private fun isQueryMethod(name: String): Boolean =
        name.startsWith("getLast") ||
            name.startsWith("getCurrent") ||
            name == "getLocation" ||
            name.contains("LastLocation") ||
            name.contains("CurrentLocation")

    private fun isEmptyResult(r: Any): Boolean = try {
        val list = XposedHelpers.callMethod(r, "getLocations") as? List<*>
        list == null || list.isEmpty()
    } catch (_: Throwable) {
        false
    }

    /** 凭空造一个位置（或 LocationResult）。配置关着就返回 null，让调用方原样放行。 */
    private fun fabricate(asResult: Boolean, cl: ClassLoader?): Any? {
        val cfg = ConfigBridge.currentFor(SYSTEM_PACKAGE) ?: return null
        val loc = try {
            FakeLocationFactory.build(cfg, LocationManager.GPS_PROVIDER, null)
        } catch (t: Throwable) {
            HookLog.e("$TAG: fabricate failed: ${t.message}", t)
            return null
        }
        if (!asResult) return loc
        return try {
            val cls = (cl?.let { XposedHelpers.findClassIfExists(CLS_LOCATION_RESULT, it) })
                ?: Class.forName(CLS_LOCATION_RESULT)
            XposedHelpers.callStaticMethod(cls, "create", listOf(loc))
        } catch (t: Throwable) {
            HookLog.e("$TAG: fabricate LocationResult failed: ${t.message}", t)
            null
        }
    }

    // ------------------------------------------------------------------ 替换

    private fun replaceArgs(param: XC_MethodHook.MethodHookParam) {
        for (i in param.args.indices) {
            when (val a = param.args[i]) {
                is Location -> {
                    val fake = buildFake(a, a.provider) ?: continue
                    param.args[i] = fake
                }
                null -> continue
                else -> {
                    if (a.javaClass.name == CLS_LOCATION_RESULT) {
                        val replaced = replaceLocationResult(a, a.javaClass.classLoader) ?: continue
                        param.args[i] = replaced
                    }
                }
            }
        }
    }

    private fun buildFake(base: Location?, provider: String?): Location? {
        // 作用域白名单在这里生效：system_server 的包名是 "android"，
        // appliesTo 对系统进程恒定放行（否则整机定位会失真到不可用）。
        val cfg = ConfigBridge.currentFor(SYSTEM_PACKAGE) ?: return null
        if (base != null && FakeLocationFactory.isMarked(base)) return null
        return try {
            FakeLocationFactory.build(cfg, provider, base)
        } catch (t: Throwable) {
            HookLog.e("$TAG: build fake failed: ${t.message}", t)
            null
        }
    }

    /** 把一个 LocationResult 里的所有位置换成伪造值，再重新打包。 */
    private fun replaceLocationResult(result: Any, cl: ClassLoader?): Any? {
        val cfg = ConfigBridge.currentFor(SYSTEM_PACKAGE) ?: return null

        val raw = try {
            XposedHelpers.callMethod(result, "getLocations") as? List<*>
        } catch (t: Throwable) {
            null
        } ?: return null
        if (raw.isEmpty()) return null

        val replaced = ArrayList<Location>(raw.size)
        var changed = false
        raw.forEach { item ->
            val loc = item as? Location ?: return@forEach
            if (FakeLocationFactory.isMarked(loc)) {
                replaced.add(loc)
                return@forEach
            }
            val fake = buildFake(loc, loc.provider)
            if (fake != null) {
                changed = true
                replaced.add(fake)
            } else {
                replaced.add(loc)
            }
        }
        // 一个假点都没造出来（配置关着 / 全带标记），不必重新打包
        if (!changed || replaced.isEmpty()) return null

        return try {
            val cls = (cl?.let { XposedHelpers.findClassIfExists(CLS_LOCATION_RESULT, it) })
                ?: Class.forName(CLS_LOCATION_RESULT)
            // AOSP 13+ 有两个重载：create(List) 与 create(List, boolean)
            try {
                XposedHelpers.callStaticMethod(cls, "create", replaced)
            } catch (_: Throwable) {
                XposedHelpers.callStaticMethod(
                    cls, "create", replaced, result.isLastLocationCompat()
                )
            }
        } catch (t: Throwable) {
            HookLog.e("$TAG: LocationResult.create failed: ${t.message}", t)
            null
        }
    }

    private fun Any.isLastLocationCompat(): Boolean = try {
        XposedHelpers.callMethod(this, "isLastLocation") as? Boolean ?: false
    } catch (_: Throwable) {
        false
    }

    private const val SYSTEM_PACKAGE = "android"
}
