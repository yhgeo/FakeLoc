package com.mo.fakeloc.xposed.hooks

import android.location.Criteria
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import com.mo.fakeloc.xposed.ConfigBridge
import com.mo.fakeloc.xposed.FakeLocationFactory
import com.mo.fakeloc.xposed.HookLog
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executor
import java.util.function.Consumer

/**
 * **应用进程侧**的定位拦截。
 *
 * 这是覆盖面最广的一层：只要 App 用 `LocationManager` 拿位置（绝大多数 App 都是，
 * 包括 Google Play Services 自己的融合定位进程），都会经过这里。
 *
 * 拦截点分四类：
 *  1. **同步查询** —— `getLastKnownLocation`，直接换掉返回值；
 *  2. **异步回调** —— `requestLocationUpdates` / `requestSingleUpdate` /
 *     `getCurrentLocation`，把调用方的 listener / consumer 包一层再传下去；
 *  3. **状态查询** —— `isProviderEnabled` / `getProviders` / `hasProvider` /
 *     `getBestProvider` / `isLocationEnabled`，让 GPS 看起来一直是开的
 *     （很多 App 拿不到位置就怪"定位没开"，会直接不请求）；
 *  4. **权限/开关前置检查** —— 同上，避免 App 在发起定位前就放弃。
 *
 * listener 包装做了双向映射，保证 `removeUpdates(原始listener)` 仍然能取消。
 *
 * 所有取值统一走 [ConfigBridge.currentFor]，作用域白名单在这一层真正生效。
 */
object LocationManagerHook {

    /** 原始 listener -> 包装后 listener */
    private val forward = ConcurrentHashMap<Any, Any>()

    /** 包装后 listener -> 原始 listener */
    private val backward = ConcurrentHashMap<Any, Any>()

    /** 包装后 listener -> 位置补发定时器 */
    private val pumps = ConcurrentHashMap<Any, Pump>()

    /**
     * 记录顺序，用于淘汰最旧的映射。
     *
     * 这三张表原本只增不减：应用反复注册监听器（很多 SDK 会这么做）时，
     * map 会无界增长，而且每个 pump 都是一个永不停止的每秒定时器。
     * 长时间跑下来既是内存泄漏也是耗电源。加个上限，超了就淘汰最旧的。
     */
    private val order = ConcurrentLinkedQueue<Any>()

    private const val LOG_TAG = "LM"

    /** 同时跟踪的监听器上限。 */
    private const val MAX_TRACKED_LISTENERS = 64

    /** 补发间隔的上下限（毫秒）。 */
    private const val MIN_PUMP_MS = 1000L
    private const val MAX_PUMP_MS = 10_000L

    fun install(cl: ClassLoader): Int {
        val clazz = XposedHelpers.findClassIfExists("android.location.LocationManager", cl)
        if (clazz == null) {
            HookLog.e("$LOG_TAG: android.location.LocationManager not found")
            return 0
        }

        var n = 0
        n += hookSyncQueries(clazz)
        n += hookAsyncUpdates(clazz)
        n += hookProviderState(clazz)

        HookLog.i("$LOG_TAG: LocationManager hooks installed=$n")
        return n
    }

    // ------------------------------------------------------------------ 1. 同步查询

    private fun hookSyncQueries(clazz: Class<*>): Int = count(
        after(clazz, "getLastKnownLocation", arrayOf(String::class.java)) { p ->
            val orig = p.result as? Location
            if (orig != null && FakeLocationFactory.isMarked(orig)) return@after
            val fake = fakeFor(p.args[0] as? String, orig) ?: return@after
            p.result = fake
        },
        after(clazz, "getLastKnownLocation", arrayOf(LocationRequest::class.java)) { p ->
            val orig = p.result as? Location
            if (orig != null && FakeLocationFactory.isMarked(orig)) return@after
            val fake = fakeFor(orig?.provider, orig) ?: return@after
            p.result = fake
        }
    )

    // ------------------------------------------------------------------ 2. 异步回调

    private fun hookAsyncUpdates(clazz: Class<*>): Int {
        var n = 0

        // getCurrentLocation —— Consumer 是最后一个参数
        val currentLocationSigs: List<Array<Class<*>>> = listOf(
            arrayOf<Class<*>>(
                String::class.java, LocationRequest::class.java,
                CancellationSignal::class.java, Executor::class.java, Consumer::class.java
            ),
            arrayOf<Class<*>>(
                String::class.java, CancellationSignal::class.java,
                Executor::class.java, Consumer::class.java
            ),
            arrayOf<Class<*>>(
                LocationRequest::class.java, CancellationSignal::class.java,
                Executor::class.java, Consumer::class.java
            )
        )
        currentLocationSigs.forEach { sig ->
            n += count(
                before(clazz, "getCurrentLocation", sig) { p ->
                    val idx = p.args.size - 1
                    @Suppress("UNCHECKED_CAST")
                    val consumer = p.args[idx] as? Consumer<Location> ?: return@before
                    p.args[idx] = Consumer<Location> { loc ->
                        consumer.accept(fakeFor(loc?.provider, loc) ?: loc)
                    }
                }
            )
        }

        // requestLocationUpdates —— LocationListener 的位置各不相同，逐个声明。
        //
        // 这里刻意不用 `Long::class.javaPrimitiveType`：它返回可空 Class?，
        // 会让整个数组推断出「元素可空」的类型，进而匹配不上 hook 的签名数组。
        // 用 `!!` 收窄成非空，类型干净。
        val longType: Class<*> = Long::class.javaPrimitiveType!!
        val floatType: Class<*> = Float::class.javaPrimitiveType!!

        val listenerSigs: List<Array<Class<*>>> = listOf(
            arrayOf<Class<*>>(String::class.java, longType, floatType, LocationListener::class.java),
            arrayOf<Class<*>>(String::class.java, longType, floatType, LocationListener::class.java, Looper::class.java),
            arrayOf<Class<*>>(longType, floatType, Criteria::class.java, LocationListener::class.java, Looper::class.java),
            arrayOf<Class<*>>(LocationRequest::class.java, LocationListener::class.java, Looper::class.java),
            arrayOf<Class<*>>(LocationRequest::class.java, Executor::class.java, LocationListener::class.java),
            arrayOf<Class<*>>(String::class.java, LocationRequest::class.java, Executor::class.java, LocationListener::class.java),
            arrayOf<Class<*>>(String::class.java, longType, floatType, Executor::class.java, LocationListener::class.java)
        )
        listenerSigs.forEach { sig ->
            n += count(before(clazz, "requestLocationUpdates", sig) { p -> wrapListenerArg(p) })
        }

        // requestSingleUpdate
        val singleUpdateSigs: List<Array<Class<*>>> = listOf(
            arrayOf<Class<*>>(String::class.java, LocationListener::class.java, Looper::class.java),
            arrayOf<Class<*>>(Criteria::class.java, LocationListener::class.java, Looper::class.java)
        )
        singleUpdateSigs.forEach { sig ->
            n += count(before(clazz, "requestSingleUpdate", sig) { p -> wrapListenerArg(p) })
        }

        // removeUpdates —— 把包装层换回原始对象，否则取消不掉。
        //
        // 注意方向：系统侧登记的是**我们包装后的 proxy**，而应用传进来的通常是
        // **原始 listener**。所以要做的是「原始 -> proxy」的正向翻译，
        // 反过来做的话取消请求永远匹配不上，监听器会泄漏。
        n += count(
            before(clazz, "removeUpdates", arrayOf(LocationListener::class.java)) { p ->
                val arg = p.args[0] ?: return@before
                val proxy = forward[arg]
                if (proxy != null) {
                    stopPump(proxy)
                    p.args[0] = proxy
                } else {
                    stopPump(arg)
                }
            }
        )

        return n
    }

    /** 找到参数里的 LocationListener 并替换成包装版。 */
    private fun wrapListenerArg(p: XC_MethodHook.MethodHookParam) {
        if (ConfigBridge.currentFor(null) == null) return

        // 从参数里抠出 looper 与最小上报间隔，供"补发"定时器复用
        var looper: Looper? = null
        var minTimeMs = 1000L
        for (i in p.args.indices) {
            val a = p.args[i] ?: continue
            if (a is Looper) looper = a
            if (i == 1 && a is Long && a > 0L) minTimeMs = a
            if (i == 0 && a is LocationRequest) {
                try {
                    val v = XposedHelpers.callMethod(a, "getIntervalMillis") as? Long
                    if (v != null && v > 0L) minTimeMs = v
                } catch (_: Throwable) {
                }
            }
        }

        for (i in p.args.indices) {
            val a = p.args[i] as? LocationListener ?: continue
            p.args[i] = wrap(a, looper, minTimeMs)
            return
        }
    }

    private fun wrap(original: LocationListener, looper: Looper?, minTimeMs: Long): LocationListener {
        forward[original]?.let { return it as LocationListener }

        val proxy = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                val fake = fakeFor(location.provider, location) ?: location
                try {
                    original.onLocationChanged(fake)
                } catch (t: Throwable) {
                    HookLog.e("$LOG_TAG: listener.onLocationChanged failed", t)
                }
            }

            override fun onLocationChanged(locations: MutableList<Location>) {
                val fakes = locations.map { fakeFor(it.provider, it) ?: it }
                try {
                    original.onLocationChanged(fakes)
                } catch (t: Throwable) {
                    HookLog.e("$LOG_TAG: listener.onLocationChanged(list) failed", t)
                }
            }

            override fun onProviderEnabled(provider: String) {
                try {
                    original.onProviderEnabled(provider)
                } catch (_: Throwable) {
                }
            }

            override fun onProviderDisabled(provider: String) {
                try {
                    original.onProviderDisabled(provider)
                } catch (_: Throwable) {
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
                try {
                    original.onStatusChanged(provider, status, extras)
                } catch (_: Throwable) {
                }
            }

            override fun onFlushComplete(requestCode: Int) {
                try {
                    original.onFlushComplete(requestCode)
                } catch (_: Throwable) {
                }
            }
        }

        forward[original] = proxy
        backward[proxy] = original
        order.add(proxy)
        evictOldestIfNeeded()
        startPump(proxy, original, looper, minTimeMs)
        return proxy
    }

    /** 超出上限就淘汰最旧的一条（同时停掉它的补发定时器）。 */
    private fun evictOldestIfNeeded() {
        var guard = 0
        while (order.size > MAX_TRACKED_LISTENERS && guard++ < MAX_TRACKED_LISTENERS) {
            val oldest = order.poll() ?: break
            stopPump(oldest)
            val original = backward.remove(oldest)
            if (original != null) forward.remove(original)
        }
    }

    // ------------------------------------------------------------------ 位置补发

    /**
     * 给被包装的 listener 挂一个"补发"定时器。
     *
     * 为什么需要：hook 只能**替换**真实上报的位置，没法凭空让 provider 上报。
     * 真机上很常见的形态是室内 GPS 完全没信号 —— `gps provider: last location=null`，
     * 于是 `requestLocationUpdates` 注册的监听器永远收不到回调，应用就认为定位失败。
     *
     * 这里由我们主动按请求的间隔把伪造位置推给原始监听器，位置就"活"了。
     * 真实上报到达时依然会走上面的替换路径，两边都是伪造值，不会互相打架。
     */
    private fun startPump(
        proxy: LocationListener,
        original: LocationListener,
        looper: Looper?,
        minTimeMs: Long
    ) {
        stopPump(proxy)
        val interval = minTimeMs.coerceIn(MIN_PUMP_MS, MAX_PUMP_MS)
        val handler = Handler(looper ?: Looper.getMainLooper())
        val runnable = object : Runnable {
            override fun run() {
                val cfg = ConfigBridge.currentFor(null)
                if (cfg == null) {
                    // 配置关掉/读不到了，停止补发
                    pumps.remove(proxy)
                    return
                }
                try {
                    original.onLocationChanged(
                        FakeLocationFactory.build(cfg, LocationManager.GPS_PROVIDER, null)
                    )
                } catch (_: Throwable) {
                }
                handler.postDelayed(this, interval)
            }
        }
        pumps[proxy] = Pump(handler, runnable)
        handler.postDelayed(runnable, interval)
    }

    private fun stopPump(proxy: Any) {
        order.remove(proxy)
        pumps.remove(proxy)?.let { p ->
            try {
                p.handler.removeCallbacks(p.runnable)
            } catch (_: Throwable) {
            }
        }
    }

    private class Pump(val handler: Handler, val runnable: Runnable)

    // ------------------------------------------------------------------ 3. 状态查询

    private fun hookProviderState(clazz: Class<*>): Int {
        val gps = LocationManager.GPS_PROVIDER
        val net = LocationManager.NETWORK_PROVIDER

        return count(
            // isProviderEnabled(String) -> Boolean
            after(clazz, "isProviderEnabled", arrayOf(String::class.java)) { p ->
                if (ConfigBridge.currentFor(null) == null) return@after
                if ((p.result as? Boolean) == true) return@after
                val provider = p.args[0] as? String
                if (provider == gps || provider == net) p.result = true
            },

            // isLocationEnabled() -> Boolean（总开关）
            after(clazz, "isLocationEnabled") { p ->
                if (ConfigBridge.currentFor(null) == null) return@after
                if ((p.result as? Boolean) == false) p.result = true
            },

            // hasProvider(String) -> Boolean
            after(clazz, "hasProvider", arrayOf(String::class.java)) { p ->
                if (ConfigBridge.currentFor(null) == null) return@after
                if ((p.result as? Boolean) == true) return@after
                val provider = p.args[0] as? String
                if (provider == gps || provider == net) p.result = true
            },

            // getProviders(Boolean) -> List<String>
            after(clazz, "getProviders", arrayOf(Boolean::class.javaPrimitiveType!!)) { p ->
                p.result = ensureProviders(p.result, gps, net)
            },

            // getAllProviders() -> List<String>
            after(clazz, "getAllProviders") { p ->
                p.result = ensureProviders(p.result, gps, net)
            },

            // getBestProvider(Criteria, Boolean) -> String
            after(
                clazz, "getBestProvider",
                arrayOf(Criteria::class.java, Boolean::class.javaPrimitiveType!!)
            ) { p ->
                if (ConfigBridge.currentFor(null) == null) return@after
                val cur = p.result as? String
                if (cur == null || cur == net) p.result = gps
            }
        )
    }

    /** 保证 provider 列表里有 gps / network（去重、保持原顺序）。 */
    private fun ensureProviders(result: Any?, gps: String, net: String): Any? {
        if (ConfigBridge.currentFor(null) == null) return result
        @Suppress("UNCHECKED_CAST")
        val list = (result as? List<String>)?.toMutableList() ?: return result
        if (!list.contains(gps)) list.add(0, gps)
        if (!list.contains(net)) list.add(net)
        return list
    }

    // ------------------------------------------------------------------ 工具

    /** 造一个伪造位置；配置关着 / 不在作用域 / 已处理过则返回 null（调用方原样放行）。 */
    private fun fakeFor(provider: String?, base: Location?): Location? {
        val cfg = ConfigBridge.currentFor(null) ?: return null
        if (base != null && FakeLocationFactory.isMarked(base)) return null
        return try {
            FakeLocationFactory.build(cfg, provider, base)
        } catch (t: Throwable) {
            HookLog.e("$LOG_TAG: build fake location failed", t)
            null
        }
    }

    private fun count(vararg ok: Boolean): Int = ok.count { it }

    private fun findAndHook(
        clazz: Class<*>,
        name: String,
        argTypes: Array<out Class<*>>,
        cb: XC_MethodHook
    ): Boolean {
        return try {
            val all = arrayOfNulls<Any>(argTypes.size + 1)
            argTypes.forEachIndexed { i, c -> all[i] = c }
            all[argTypes.size] = cb
            XposedHelpers.findAndHookMethod(clazz, name, *all)
            true
        } catch (t: Throwable) {
            // 不同 Android 版本方法签名有增删，缺失的直接跳过
            HookLog.v(
                "$LOG_TAG: skip $name(${argTypes.joinToString { it.simpleName }}): " +
                    t.javaClass.simpleName
            )
            false
        }
    }

    private fun after(
        clazz: Class<*>,
        name: String,
        argTypes: Array<out Class<*>> = emptyArray(),
        body: (XC_MethodHook.MethodHookParam) -> Unit
    ): Boolean = findAndHook(clazz, name, argTypes, object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) = body(param)
    })

    private fun before(
        clazz: Class<*>,
        name: String,
        argTypes: Array<out Class<*>>,
        body: (XC_MethodHook.MethodHookParam) -> Unit
    ): Boolean = findAndHook(clazz, name, argTypes, object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) = body(param)
    })
}
