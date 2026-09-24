package com.mo.fakeloc.xposed.hooks

import android.net.wifi.ScanResult
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.telephony.CellInfo
import android.telephony.CellLocation
import android.telephony.TelephonyManager
import com.mo.fakeloc.xposed.ConfigBridge
import com.mo.fakeloc.xposed.HookLog
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * 阻断**网络定位**（WiFi / 基站指纹上报服务端）。
 *
 * ## 为什么必须单独做这一层
 * 腾讯、高德、百度这些定位 SDK 拿位置有两条路：
 *  1. 读 Android 的 `LocationManager`（我们已经在系统层伪造了）；
 *  2. 把扫到的 **WiFi BSSID 列表 + 基站信息** 打包上报自己的服务器，
 *     由服务端根据指纹库算出坐标。
 *
 * **第 2 条路完全绕过 Android 定位框架**，所以在 system_server 里怎么改 `Location`
 * 都没用 —— 表现就是"位置被拉回真实坐标"。
 *
 * ## 做法
 * 在目标进程里把指纹源掐掉：
 *  - `WifiManager.getScanResults()` → 空列表
 *  - `WifiInfo.getBSSID()` / `getSSID()` → 打码成和"无定位权限"时一样的占位值
 *  - `TelephonyManager.getAllCellInfo()` / `getCellLocation()` / `getNeighboringCellInfo()` → 空
 *
 * 服务端拿不到可用指纹就算不出位置，SDK 只能退回系统定位 —— 也就是我们伪造的那个。
 *
 * 只在**目标应用进程**里装，不碰 system_server（那边还管着 WiFi 设置界面，
 * 清空扫描结果会让用户自己的 WiFi 列表也空掉）。
 */
object NetworkPosHook {

    private const val TAG = "NetPos"

    /** 无定位权限时系统给的红acted 占位值，保持一致才不显眼。 */
    private const val FAKE_BSSID = "02:00:00:00:00:00"
    private const val FAKE_SSID = "<unknown ssid>"

    fun install(cl: ClassLoader): Int {
        var n = 0
        n += hookWifi(cl)
        n += hookTelephony(cl)
        if (n > 0) HookLog.i("$TAG: network-positioning hooks installed=$n")
        return n
    }

    /** 配置开着才动手；读的是内存快照，纳秒级，不会阻塞调用方。 */
    private fun active(): Boolean =
        ConfigBridge.currentFor(null)?.blockNetworkPos == true

    // ------------------------------------------------------------------ WiFi

    private fun hookWifi(cl: ClassLoader): Int {
        var n = 0

        val wifiManager = XposedHelpers.findClassIfExists("android.net.wifi.WifiManager", cl)
        if (wifiManager != null) {
            n += hook(wifiManager, "getScanResults", after = { p ->
                p.result = emptyList<ScanResult>()
            })
            // 兼容某些 ROM 上的隐藏重载
            n += hook(wifiManager, "getScanResults", arrayOf(String::class.java), after = { p ->
                p.result = emptyList<ScanResult>()
            })
        }

        val wifiInfo = XposedHelpers.findClassIfExists("android.net.wifi.WifiInfo", cl)
        if (wifiInfo != null) {
            n += hook(wifiInfo, "getBSSID", after = { p -> p.result = FAKE_BSSID })
            n += hook(wifiInfo, "getSSID", after = { p -> p.result = FAKE_SSID })
        }

        if (n == 0) HookLog.v("$TAG: WifiManager/WifiInfo 没找到，跳过")
        return n
    }

    // ------------------------------------------------------------------ 基站

    private fun hookTelephony(cl: ClassLoader): Int {
        var n = 0

        val tm = XposedHelpers.findClassIfExists("android.telephony.TelephonyManager", cl)
        if (tm != null) {
            n += hook(tm, "getAllCellInfo", after = { p ->
                p.result = emptyList<CellInfo>()
            })
            n += hook(tm, "getCellLocation", after = { p ->
                p.result = null as CellLocation?
            })
            n += hook(tm, "getNeighboringCellInfo", after = { p ->
                p.result = emptyList<Any>()
            })
        }

        if (n == 0) HookLog.v("$TAG: TelephonyManager 没找到，跳过")
        return n
    }

    // ------------------------------------------------------------------ 工具

    private fun hook(
        clazz: Class<*>,
        name: String,
        argTypes: Array<out Class<*>>? = null,
        after: (XC_MethodHook.MethodHookParam) -> Unit
    ): Int {
        return try {
            val cb = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!active()) return
                    if (param.hasThrowable()) return
                    try {
                        after(param)
                    } catch (t: Throwable) {
                        HookLog.v("$TAG: $name 处理失败: ${t.javaClass.simpleName}")
                    }
                }
            }

            if (argTypes == null) {
                // 无参重载（getScanResults() / getAllCellInfo() 等）
                XposedHelpers.findAndHookMethod(clazz, name, cb)
            } else {
                val all = arrayOfNulls<Any>(argTypes.size + 1)
                argTypes.forEachIndexed { i, c -> all[i] = c }
                all[argTypes.size] = cb
                XposedHelpers.findAndHookMethod(clazz, name, *all)
            }
            1
        } catch (t: Throwable) {
            HookLog.v("$TAG: skip $name(${argTypes?.joinToString { it.simpleName } ?: ""}): ${t.javaClass.simpleName}")
            0
        }
    }
}
