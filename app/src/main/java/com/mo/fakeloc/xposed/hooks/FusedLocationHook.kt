package com.mo.fakeloc.xposed.hooks

import android.location.Location
import com.mo.fakeloc.xposed.ConfigBridge
import com.mo.fakeloc.xposed.FakeLocationFactory
import com.mo.fakeloc.xposed.HookLog
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers

/**
 * **Google Play Services 融合定位侧**的拦截。
 *
 * 背景：`FusedLocationProviderClient` 拿到的结果最终被包装成
 * `com.google.android.gms.location.LocationResult`（Parcelable，跨进程传递）。
 * 这个类会同时存在于 **App 进程**（反序列化后）和 **GMS 进程**（构造时）。
 *
 * 所以这里采取"能挂就挂"的策略：任何进程里只要找得到这个类就挂上，
 * 找不到（非 GMS 设备 / 没装 Play 服务）就静默跳过。
 * 这样国产 ROM 上 `com.android.location.fused` 走系统融合时，由
 * [LocationManagerHook] 和 [SystemServerHook] 覆盖，不会互相打架。
 */
object FusedLocationHook {

    private const val TAG = "Fused"
    private const val CLS_GMS_RESULT = "com.google.android.gms.location.LocationResult"

    fun install(cl: ClassLoader): Int {
        val clazz = XposedHelpers.findClassIfExists(CLS_GMS_RESULT, cl) ?: return 0

        var n = 0

        // List<Location> getLocations()
        try {
            XposedHelpers.findAndHookMethod(clazz, "getLocations", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val list = param.result as? List<*> ?: return
                    if (list.isEmpty()) return
                    val cfg = ConfigBridge.currentFor(null) ?: return
                    if (list.all { it is Location && FakeLocationFactory.isMarked(it) }) return

                    var changed = false
                    val out = list.map { item ->
                        val loc = item as? Location ?: return@map item
                        if (FakeLocationFactory.isMarked(loc)) return@map loc
                        try {
                            FakeLocationFactory.build(cfg, loc.provider, loc).also { changed = true }
                        } catch (t: Throwable) {
                            loc
                        }
                    }
                    if (changed) param.result = out
                }
            })
            n++
        } catch (t: Throwable) {
            HookLog.v("$TAG: hook getLocations failed: ${t.message}")
        }

        // Location getLastLocation()
        try {
            XposedHelpers.findAndHookMethod(clazz, "getLastLocation", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val loc = param.result as? Location ?: return
                    if (FakeLocationFactory.isMarked(loc)) return
                    val cfg = ConfigBridge.currentFor(null) ?: return
                    try {
                        param.result = FakeLocationFactory.build(cfg, loc.provider, loc)
                    } catch (_: Throwable) {
                    }
                }
            })
            n++
        } catch (t: Throwable) {
            HookLog.v("$TAG: hook getLastLocation failed: ${t.message}")
        }

        if (n > 0) HookLog.i("$TAG: GMS LocationResult hooked=$n")
        return n
    }
}
