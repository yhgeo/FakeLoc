package com.mo.fakeloc.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * 虚拟定位配置。
 *
 * 这个类被两端共用：
 *  - App 端（主进程）写入；
 *  - hook 端（被注入的目标进程 / system_server）读取。
 * 因为两者加载的是同一个 APK 的同一个 ClassLoader，所以可以共用同一个类。
 *
 * 设计原则：只放 hook 需要的最小字段。路线、收藏等 App 侧状态不放进来。
 */
data class LocConfig(
    /** 总开关。false 时 hook 直接放行真实位置。 */
    var enabled: Boolean = false,

    var latitude: Double = 39.908722,
    var longitude: Double = 116.397499,
    var altitude: Double = 50.0,

    /** 水平精度（米），越小越"可信"。 */
    var accuracy: Float = 5.0f,
    /** 移动速度（米/秒）。 */
    var speed: Float = 0.0f,
    /** 航向角（度，正北为 0，顺时针）。 */
    var bearing: Float = 0.0f,

    var verticalAccuracy: Float = 3.0f,
    var speedAccuracy: Float = 1.0f,
    var bearingAccuracy: Float = 1.0f,

    /** true = 抹掉 Location.isMock / 各类 mock extras，规避简单检测。 */
    var hideMock: Boolean = true,

    /** 位置自然抖动，避免"坐标一动不动"这种典型特征。 */
    var jitterEnabled: Boolean = true,
    var jitterMeters: Double = 2.0,

    /** 上报的可见卫星数（GNSS extras）。 */
    var satelliteCount: Int = 24,

    /**
     * 阻断"网络定位"（WiFi / 基站上报服务端算位置）。
     *
     * 腾讯、高德、百度这些定位 SDK 除了读系统位置，还会把扫到的 **WiFi BSSID 列表 +
     * 基站信息** 上报自己的服务器，由服务端算出坐标 —— 这条路径**完全绕过 Android 的
     * LocationManager**，所以在系统层伪造位置对它们无效，表现为"位置被拉回真实坐标"。
     *
     * 打开后会在目标进程里把 WiFi 扫描结果清空、BSSID/SSID 打码、基站信息清空，
     * 让服务端拿不到可用指纹，SDK 只能退回系统定位（也就是我们伪造的那个）。
     */
    var blockNetworkPos: Boolean = true,

    /** 作用域白名单：仅当为空或开关关闭时对所有已注入进程生效。 */
    var scopeWhitelistEnabled: Boolean = false,
    var scopePackages: List<String> = emptyList(),

    /**
     * 单调递增序号。每次保存自增。
     * hook 端用它判断"配置有没有变"，避免重复做无谓的 Location 重建。
     */
    var seq: Long = 0L
) {

    /** 判断当前进程是否在作用范围内。 */
    fun appliesTo(packageName: String?): Boolean {
        if (!enabled) return false
        if (!scopeWhitelistEnabled) return true
        if (scopePackages.isEmpty()) return true
        val pkg = packageName ?: return true
        // system_server / 融合定位这类系统进程永远放行，否则整机定位会失真到不可用
        if (pkg == "android" || pkg == "com.android.location.fused") return true
        return scopePackages.contains(pkg)
    }

    fun toJson(): String {
        val o = JSONObject()
        o.put("enabled", enabled)
        o.put("lat", latitude)
        o.put("lon", longitude)
        o.put("alt", altitude)
        o.put("acc", accuracy.toDouble())
        o.put("speed", speed.toDouble())
        o.put("bearing", bearing.toDouble())
        o.put("vAcc", verticalAccuracy.toDouble())
        o.put("sAcc", speedAccuracy.toDouble())
        o.put("bAcc", bearingAccuracy.toDouble())
        o.put("hideMock", hideMock)
        o.put("jitter", jitterEnabled)
        o.put("jitterM", jitterMeters)
        o.put("sats", satelliteCount)
        o.put("blockNetPos", blockNetworkPos)
        o.put("wlEnabled", scopeWhitelistEnabled)
        o.put("seq", seq)
        val arr = JSONArray()
        scopePackages.forEach { arr.put(it) }
        o.put("wl", arr)
        return o.toString()
    }

    /** 一份用于比对"内容是否变化"的指纹（不含 seq）。 */
    fun fingerprint(): Int = toJson().hashCode()

    companion object {
        fun fromJson(json: String?): LocConfig? {
            if (json.isNullOrBlank()) return null
            return try {
                val o = JSONObject(json)
                val wl = mutableListOf<String>()
                o.optJSONArray("wl")?.let { arr ->
                    for (i in 0 until arr.length()) wl.add(arr.optString(i))
                }
                LocConfig(
                    enabled = o.optBoolean("enabled", false),
                    latitude = o.optDouble("lat", 39.908722),
                    longitude = o.optDouble("lon", 116.397499),
                    altitude = o.optDouble("alt", 50.0),
                    accuracy = o.optDouble("acc", 5.0).toFloat(),
                    speed = o.optDouble("speed", 0.0).toFloat(),
                    bearing = o.optDouble("bearing", 0.0).toFloat(),
                    verticalAccuracy = o.optDouble("vAcc", 3.0).toFloat(),
                    speedAccuracy = o.optDouble("sAcc", 1.0).toFloat(),
                    bearingAccuracy = o.optDouble("bAcc", 1.0).toFloat(),
                    hideMock = o.optBoolean("hideMock", true),
                    jitterEnabled = o.optBoolean("jitter", true),
                    jitterMeters = o.optDouble("jitterM", 2.0),
                    satelliteCount = o.optInt("sats", 24),
                    blockNetworkPos = o.optBoolean("blockNetPos", true),
                    scopeWhitelistEnabled = o.optBoolean("wlEnabled", false),
                    scopePackages = wl,
                    seq = o.optLong("seq", 0L)
                )
            } catch (t: Throwable) {
                null
            }
        }
    }
}

/** 路线途经点。 */
data class RoutePoint(val lat: Double, val lon: Double)
