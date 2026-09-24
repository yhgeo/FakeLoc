package com.mo.fakeloc.service

import com.mo.fakeloc.data.RoutePoint
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 路线播放引擎。
 *
 * 思路：把折线路径按"已行进距离"参数化，App 每秒算一次当前点写进配置，
 * hook 端 1 秒后就能读到新坐标 —— 于是位置就"动"起来了。
 *
 * 这么做的好处是 hook 侧完全不需要知道路线，只认一个静态坐标，
 * 复杂度全部留在 App 里，hook 保持无状态、无定时器。
 */
object RouteEngine {

    private const val EARTH_R = 6_371_000.0

    data class Pose(
        val lat: Double,
        val lon: Double,
        /** 航向角（度） */
        val bearing: Float,
        /** 是否已走完全程（非循环模式） */
        val finished: Boolean
    )

    /** 折线总长度（米）。 */
    fun totalLength(points: List<RoutePoint>): Double {
        if (points.size < 2) return 0.0
        var sum = 0.0
        for (i in 0 until points.size - 1) {
            sum += distance(points[i], points[i + 1])
        }
        return sum
    }

    /**
     * 取行进 [travelled] 米后的位姿。
     * @param loop 走完是否回到起点继续
     */
    fun poseAt(points: List<RoutePoint>, travelled: Double, loop: Boolean): Pose? {
        if (points.isEmpty()) return null
        if (points.size == 1) {
            return Pose(points[0].lat, points[0].lon, 0f, finished = false)
        }

        val total = totalLength(points)
        if (total <= 0.0) {
            return Pose(points[0].lat, points[0].lon, 0f, finished = false)
        }

        var d = travelled
        var finished = false
        if (d < 0) d = 0.0
        if (d > total) {
            if (loop) {
                d %= total
            } else {
                d = total
                finished = true
            }
        }

        var acc = 0.0
        for (i in 0 until points.size - 1) {
            val a = points[i]
            val b = points[i + 1]
            val seg = distance(a, b)
            if (seg <= 0.0) continue

            if (acc + seg >= d) {
                val t = (d - acc) / seg
                return Pose(
                    lat = a.lat + (b.lat - a.lat) * t,
                    lon = a.lon + (b.lon - a.lon) * t,
                    bearing = bearing(a, b),
                    finished = finished
                )
            }
            acc += seg
        }

        val last = points.last()
        val prev = points[points.size - 2]
        return Pose(last.lat, last.lon, bearing(prev, last), finished = true)
    }

    /** Haversine 距离（米）。 */
    fun distance(a: RoutePoint, b: RoutePoint): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val lat1 = Math.toRadians(a.lat)
        val lat2 = Math.toRadians(b.lat)
        val h = sin(dLat / 2).let { it * it } +
            cos(lat1) * cos(lat2) * sin(dLon / 2).let { it * it }
        return 2 * EARTH_R * kotlin.math.asin(sqrt(h).coerceAtMost(1.0))
    }

    /** 方位角（度，正北 0，顺时针）。 */
    fun bearing(a: RoutePoint, b: RoutePoint): Float {
        val lat1 = Math.toRadians(a.lat)
        val lat2 = Math.toRadians(b.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        val deg = Math.toDegrees(atan2(y, x))
        return (((deg + 360.0) % 360.0)).toFloat()
    }

    /** 经纬度粗校验。 */
    fun isValid(lat: Double, lon: Double): Boolean =
        !lat.isNaN() && !lon.isNaN() && abs(lat) <= 90.0 && abs(lon) <= 180.0

    /**
     * 把配速（min/km）格式化成跑步界通用的写法，例如 `6'42"/km`。
     *
     * 跑步类应用和跑者都按这个说法交流，"9.0 km/h" 反而要心算。
     */
    fun formatPace(minPerKm: Double): String {
        if (minPerKm <= 0.0 || !minPerKm.isFinite()) return "--'--\"/km"
        val totalSec = Math.round(minPerKm * 60).toInt()
        return String.format("%d'%02d\"/km", totalSec / 60, totalSec % 60)
    }

    /** 配速 → km/h，界面上附带给一个直观对照。 */
    fun paceToKmh(minPerKm: Double): Double =
        if (minPerKm <= 0.0) 0.0 else 60.0 / minPerKm
}
