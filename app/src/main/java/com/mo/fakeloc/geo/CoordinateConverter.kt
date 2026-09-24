package com.mo.fakeloc.geo

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 坐标系转换。
 *
 * ## 为什么必须做这件事
 * 中国境内的地图服务**不通用 WGS-84**：
 *  - 系统定位 API（`Location.getLatitude()`）给的是 **WGS-84**；
 *  - 高德 / 腾讯 / 绝大多数国内 App 内部用 **GCJ-02**（"火星坐标"）；
 *  - 百度用 **BD-09**。
 *
 * GCJ-02 与 WGS-84 在国内偏差可达 **100~700 米**。如果在地图上选点后
 * 不做换算直接喂给目标 App，落点会明显偏。所以：
 *
 *  - 在地图上选点时，按图层所属坐标系换算回 WGS-84 再存；
 *  - 需要直接输出 GCJ-02 / BD-09 的场景，用 [convert] 正向换算。
 *
 * 算法为国家测绘局公开的经典实现（GCJ-02 加密偏移公式 + 迭代反解）。
 */
object CoordinateConverter {

    /** 目标坐标系。 */
    enum class Datum(val label: String) {
        WGS84("WGS-84（系统 / GPS 原始）"),
        GCJ02("GCJ-02（高德 / 腾讯）"),
        BD09("BD-09（百度）")
    }

    private const val PI = Math.PI
    private const val A = 6378245.0              // 克拉索夫斯基椭球长半轴
    private const val EE = 0.00669342162296594323 // 偏心率平方

    /** 中国大陆粗略边界之外不加密（港澳台及境外为真实坐标）。 */
    fun outOfChina(lat: Double, lon: Double): Boolean =
        lon < 72.004 || lon > 137.8347 || lat < 0.8293 || lat > 55.8271

    fun wgs84ToGcj02(lat: Double, lon: Double): Pair<Double, Double> {
        if (outOfChina(lat, lon)) return lat to lon
        var dLat = transformLat(lon - 105.0, lat - 35.0)
        var dLon = transformLon(lon - 105.0, lat - 35.0)
        val radLat = lat / 180.0 * PI
        var magic = sin(radLat)
        magic = 1 - EE * magic * magic
        val sqrtMagic = sqrt(magic)
        dLat = (dLat * 180.0) / ((A * (1 - EE)) / (magic * sqrtMagic) * PI)
        dLon = (dLon * 180.0) / (A / sqrtMagic * cos(radLat) * PI)
        return (lat + dLat) to (lon + dLon)
    }

    fun gcj02ToWgs84(lat: Double, lon: Double): Pair<Double, Double> {
        if (outOfChina(lat, lon)) return lat to lon
        // 迭代反解，6 次足够收敛到 ~1e-8 度（亚厘米级）
        var wLat = lat
        var wLon = lon
        repeat(6) {
            val (gLat, gLon) = wgs84ToGcj02(wLat, wLon)
            wLat += lat - gLat
            wLon += lon - gLon
        }
        return wLat to wLon
    }

    fun gcj02ToBd09(lat: Double, lon: Double): Pair<Double, Double> {
        val z = sqrt(lon * lon + lat * lat) + 0.00002 * sin(lat * PI * 3000.0 / 180.0)
        val theta = Math.atan2(lat, lon) + 0.000003 * cos(lon * PI * 3000.0 / 180.0)
        return (z * sin(theta) + 0.006) to (z * cos(theta) + 0.0065)
    }

    fun bd09ToGcj02(lat: Double, lon: Double): Pair<Double, Double> {
        val x = lon - 0.0065
        val y = lat - 0.006
        val z = sqrt(x * x + y * y) - 0.00002 * sin(y * PI * 3000.0 / 180.0)
        val theta = Math.atan2(y, x) - 0.000003 * cos(x * PI * 3000.0 / 180.0)
        return (z * sin(theta)) to (z * cos(theta))
    }

    fun wgs84ToBd09(lat: Double, lon: Double): Pair<Double, Double> {
        val (gLat, gLon) = wgs84ToGcj02(lat, lon)
        return gcj02ToBd09(gLat, gLon)
    }

    fun bd09ToWgs84(lat: Double, lon: Double): Pair<Double, Double> {
        val (gLat, gLon) = bd09ToGcj02(lat, lon)
        return gcj02ToWgs84(gLat, gLon)
    }

    /** 把 WGS-84 坐标转换到目标坐标系。 */
    fun convert(lat: Double, lon: Double, to: Datum): Pair<Double, Double> = when (to) {
        Datum.WGS84 -> lat to lon
        Datum.GCJ02 -> wgs84ToGcj02(lat, lon)
        Datum.BD09 -> wgs84ToBd09(lat, lon)
    }

    /** 把某个坐标系的坐标转回 WGS-84。 */
    fun toWgs84(lat: Double, lon: Double, from: Datum): Pair<Double, Double> = when (from) {
        Datum.WGS84 -> lat to lon
        Datum.GCJ02 -> gcj02ToWgs84(lat, lon)
        Datum.BD09 -> bd09ToWgs84(lat, lon)
    }

    /** 两点距离（米），用于界面显示与偏差自检。 */
    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val rLat1 = Math.toRadians(lat1)
        val rLat2 = Math.toRadians(lat2)
        val h = sin(dLat / 2).let { it * it } + cos(rLat1) * cos(rLat2) * sin(dLon / 2).let { it * it }
        return 2 * 6_371_000.0 * kotlin.math.asin(sqrt(h).coerceAtMost(1.0))
    }

    /** 简单合法性校验。 */
    fun isValid(lat: Double, lon: Double): Boolean =
        !lat.isNaN() && !lon.isNaN() && abs(lat) <= 90.0 && abs(lon) <= 180.0

    // ---- GCJ-02 加密偏移量 ----

    private fun transformLat(x: Double, y: Double): Double {
        var ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * sin(y * PI) + 40.0 * sin(y / 3.0 * PI)) * 2.0 / 3.0
        ret += (160.0 * sin(y / 12.0 * PI) + 320.0 * sin(y * PI / 30.0)) * 2.0 / 3.0
        return ret
    }

    private fun transformLon(x: Double, y: Double): Double {
        var ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * sin(x * PI) + 40.0 * sin(x / 3.0 * PI)) * 2.0 / 3.0
        ret += (150.0 * sin(x / 12.0 * PI) + 300.0 * sin(x / 30.0 * PI)) * 2.0 / 3.0
        return ret
    }
}
