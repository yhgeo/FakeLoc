package com.mo.fakeloc.xposed

import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import com.mo.fakeloc.data.LocConfig
import de.robv.android.xposed.XposedHelpers
import java.util.Random

/**
 * 伪造 [Location] 的工厂。
 *
 * 关键点在于**抹掉"这是模拟位置"的一切痕迹**：
 *  - API 31+ 的 `Location.isMock`；
 *  - 老版本的 `Location.isFromMockProvider()`；
 *  - extras 里的 `mockLocation` 键（框架给模拟位置打的标记）；
 *  - provider 名里带 mock / test / fake 的情况，统一改写成 `gps`。
 *
 * 另外补上 `makeComplete()`：API 31+ 起框架会校验 Location 是否"完整"，
 * 字段缺太多会抛 IllegalArgumentException，所以造完必须补齐。
 */
object FakeLocationFactory {

    /** 我们自己打的标记，用于避免对同一个 Location 反复注入（会造成死循环）。 */
    const val EXTRA_MARK = "fakeloc_mark"

    private const val METERS_PER_DEG_LAT = 111_320.0

    /** 外推的时间上限：配置来源可能是旧副本，不封顶位置会一路飞出去。 */
    private const val MAX_EXTRAPOLATE_MS = 3_000L

    private val rnd = Random()

    // ---- 抖动状态：慢速漂移 + 快速噪声，模拟真实 GPS 的"呼吸感" ----
    private var driftNorth = 0.0
    private var driftEast = 0.0
    private var lastDriftAt = 0L

    /** 是否已经是本模块处理过的 Location。 */
    fun isMarked(loc: Location?): Boolean {
        val b = loc?.extras ?: return false
        return try {
            b.getBoolean(EXTRA_MARK, false)
        } catch (_: Throwable) {
            false
        }
    }

    /** 把 mock/test/fake 之类的 provider 名规范化成 gps。 */
    fun normalizeProvider(provider: String?): String {
        val p = provider ?: LocationManager.GPS_PROVIDER
        return if (p.contains("mock", true) || p.contains("test", true) || p.contains("fake", true)) {
            LocationManager.GPS_PROVIDER
        } else {
            p
        }
    }

    /**
     * 造一个伪造 Location。
     * @param base 真实位置（可为 null），用来继承一些我们不想伪造的字段。
     */
    fun build(cfg: LocConfig, provider: String?, base: Location? = null): Location {
        val p = normalizeProvider(provider)
        val loc = Location(p)

        val (lat, lon) = positionAt(cfg)
        loc.latitude = lat
        loc.longitude = lon
        loc.altitude = cfg.altitude

        loc.accuracy = if (cfg.accuracy > 0f) cfg.accuracy else (base?.accuracy ?: 5f)
        loc.speed = cfg.speed
        loc.bearing = ((cfg.bearing % 360f) + 360f) % 360f

        loc.time = System.currentTimeMillis()
        loc.elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            loc.verticalAccuracyMeters = cfg.verticalAccuracy
            loc.speedAccuracyMetersPerSecond = cfg.speedAccuracy
            loc.bearingAccuracyDegrees = if (cfg.bearingAccuracy > 0f) cfg.bearingAccuracy else 1f
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            loc.elapsedRealtimeUncertaintyNanos = 0.0
        }
        // Android 14：海拔的海平面基准字段
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                XposedHelpers.callMethod(loc, "setMslAltitudeMeters", cfg.altitude)
                XposedHelpers.callMethod(loc, "setMslAltitudeAccuracyMeters", cfg.verticalAccuracy)
            } catch (_: Throwable) {
                // 部分 ROM 没这两个方法，忽略
            }
        }

        // ---- extras：模拟真实 GNSS 上报 ----
        val extras = Bundle()
        extras.putInt("satellites", cfg.satelliteCount)
        extras.putInt("maxCn0", 30 + rnd.nextInt(15))
        extras.putInt("meanCn0", 20 + rnd.nextInt(12))
        extras.putBoolean(EXTRA_MARK, true)
        // 关键：清掉框架给模拟位置打的标记
        extras.remove("mockLocation")
        loc.extras = extras

        applyMockMask(loc, cfg)

        // 补齐所有派生字段，避免 API 31+ 抛 "incomplete location"
        try {
            XposedHelpers.callMethod(loc, "makeComplete")
        } catch (_: Throwable) {
        }

        return loc
    }

    /**
     * 当前位置 = 配置里的基准点 + 按速度/航向做**时间外推**。
     *
     * ## 为什么需要外推
     * App 侧每秒才写一次配置，hook 侧按轮询拿到的是一个个离散的"路标点"。
     * 如果直接把路标点当成当前位置返回，应用看到的就是**每秒跳一格** ——
     * 跑步类小程序会判定轨迹不连续。
     *
     * 配置里的 `seq` 就是 App 保存它时的 `System.currentTimeMillis()`，
     * 拿它当时间戳，配合 `speed`（米/秒）和 `bearing`（度）往前推，
     * 于是**无论应用以多高的频率采样，位置都是连续推进的**。
     *
     * 外推上限 [MAX_EXTRAPOLATE_MS]：配置来源可能是几十秒前的旧副本
     * （比如服务已经停了），不封顶的话位置会一路飞出去。
     */
    private fun positionAt(cfg: LocConfig): Pair<Double, Double> {
        val (baseLat, baseLon) = jittered(cfg)
        if (cfg.speed <= 0.05f) return baseLat to baseLon
        if (cfg.seq <= 0L) return baseLat to baseLon

        val ageMs = (System.currentTimeMillis() - cfg.seq).coerceIn(0L, MAX_EXTRAPOLATE_MS)
        if (ageMs <= 0L) return baseLat to baseLon

        return advance(baseLat, baseLon, cfg.speed * (ageMs / 1000.0), cfg.bearing)
    }

    /** 从 (lat,lon) 沿 bearing 方向前进 meters 米（等距圆柱近似，短距离足够准）。 */
    private fun advance(
        lat: Double,
        lon: Double,
        meters: Double,
        bearingDeg: Float
    ): Pair<Double, Double> {
        if (meters <= 0.0) return lat to lon
        val br = Math.toRadians(bearingDeg.toDouble())
        val dNorth = meters * Math.cos(br)
        val dEast = meters * Math.sin(br)
        val cosLat = Math.cos(Math.toRadians(lat)).coerceAtLeast(1e-6)
        return (lat + dNorth / METERS_PER_DEG_LAT) to
            (lon + dEast / (METERS_PER_DEG_LAT * cosLat))
    }

    /** 抹掉 / 伪造 mock 标记。 */
    private fun applyMockMask(loc: Location, cfg: LocConfig) {
        if (cfg.hideMock) {
            // 新建的 Location 默认就是 false，这里再显式压一遍，
            // 防止某些 ROM 在构造时按 provider 名做了标记。
            trySetBooleanField(loc, "mIsMock", false)
            trySetBooleanField(loc, "mIsFromMockProvider", false)
            try {
                XposedHelpers.callMethod(loc, "setMock", false)
            } catch (_: Throwable) {
            }
            loc.extras?.remove("mockLocation")
        } else {
            // 调试模式：显式标成模拟位置，方便对照验证 hook 是否生效
            try {
                XposedHelpers.callMethod(loc, "setMock", true)
            } catch (_: Throwable) {
            }
            loc.extras?.putBoolean("mockLocation", true)
        }
    }

    private fun trySetBooleanField(target: Any, name: String, value: Boolean) {
        try {
            XposedHelpers.setBooleanField(target, name, value)
        } catch (_: Throwable) {
            // 字段不存在（版本差异），忽略
        }
    }

    /** 慢漂移 + 快噪声，避免坐标"一动不动"。 */
    private fun jittered(cfg: LocConfig): Pair<Double, Double> {
        if (!cfg.jitterEnabled || cfg.jitterMeters <= 0.0) {
            return cfg.latitude to cfg.longitude
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastDriftAt > 3_000L) {
            lastDriftAt = now
            driftNorth = (rnd.nextDouble() - 0.5) * cfg.jitterMeters
            driftEast = (rnd.nextDouble() - 0.5) * cfg.jitterMeters
        }
        val noiseN = (rnd.nextDouble() - 0.5) * cfg.jitterMeters * 0.4
        val noiseE = (rnd.nextDouble() - 0.5) * cfg.jitterMeters * 0.4

        val dNorth = driftNorth + noiseN
        val dEast = driftEast + noiseE

        val lat = cfg.latitude + dNorth / METERS_PER_DEG_LAT
        val cosLat = Math.cos(Math.toRadians(cfg.latitude)).coerceAtLeast(1e-6)
        val lon = cfg.longitude + dEast / (METERS_PER_DEG_LAT * cosLat)
        return lat to lon
    }
}
