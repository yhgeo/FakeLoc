package com.mo.fakeloc.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * App 侧配置仓库：SharedPreferences + JSON。
 *
 * 刻意不引入 Room / DataStore：
 *  - 数据结构简单，一个 JSON 字符串就够；
 *  - 少一个注解处理器，构建更快、依赖更少；
 *  - prefs 文件名固定为 [PREFS_NAME]，方便 LSPosed 的 XSharedPreferences 兜底通道读取。
 */
object ConfigStore {

    const val PREFS_NAME = "fakeloc"
    private const val KEY_CONFIG = "config_json"
    private const val KEY_FAVORITES = "favorites_json"
    private const val KEY_ROUTE = "route_json"
    private const val KEY_ROUTE_SPEED = "route_speed_kmh"
    private const val KEY_ROUTE_LOOP = "route_loop"
    private const val KEY_ENGINE_MODE = "engine_mode"

    /** LSPosed 通道：hook 层拦截，覆盖面最广、最隐蔽（推荐）。 */
    const val MODE_LSPOSED = 0

    /** 开发者模拟位置通道：不依赖 LSPosed，但需要开发者选项里选本应用。 */
    const val MODE_MOCK_PROVIDER = 1

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * 首次启动时把默认配置落盘。
     *
     * 为什么必须做：XSharedPreferences 兜底通道读的是 **prefs 文件**，
     * 用户装完如果一次都没点过「应用坐标」，`fakeloc.xml` 根本不存在，
     * 兜底通道直接失效。主动写一次，让两条通道从第一秒起都在。
     *
     * 用 `commit()` 而不是 `apply()`：这里要求"写完才算数"，
     * 而且只在首次安装时执行一次，同步落盘的开销可以忽略。
     */
    fun ensureInitialized(ctx: Context) {
        val p = prefs(ctx)
        if (!p.contains(KEY_CONFIG)) {
            p.edit().putString(KEY_CONFIG, LocConfig().toJson()).commit()
        }
        if (!p.contains(KEY_ENGINE_MODE)) {
            p.edit().putInt(KEY_ENGINE_MODE, MODE_LSPOSED).commit()
        }
    }

    // ---------------------------------------------------------------- 主配置

    fun load(ctx: Context): LocConfig {
        val raw = prefs(ctx).getString(KEY_CONFIG, null)
        return LocConfig.fromJson(raw) ?: LocConfig()
    }

    /**
     * 保存并刷新序号，让 hook 端能识别"配置变了"。
     *
     * 用 `apply()`：路线模拟时这个方法每秒被调用一次，
     * `commit()` 会在主线程同步落盘。App 自己的进程读的是内存里的
     * SharedPreferences（Provider 通道），XSharedPreferences 侧
     * 异步落盘后自然能读到，两者都不受影响。
     */
    fun save(ctx: Context, cfg: LocConfig) {
        cfg.seq = System.currentTimeMillis()
        prefs(ctx).edit().putString(KEY_CONFIG, cfg.toJson()).apply()
    }

    /** 给 Provider 通道用的原始 JSON（不做任何加工）。 */
    fun rawJson(ctx: Context): String = prefs(ctx).getString(KEY_CONFIG, null) ?: LocConfig().toJson()

    // ---------------------------------------------------------------- 收藏

    data class Favorite(val name: String, val lat: Double, val lon: Double, val addedAt: Long)

    fun loadFavorites(ctx: Context): List<Favorite> {
        val raw = prefs(ctx).getString(KEY_FAVORITES, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                Favorite(
                    name = o.optString("name", "未命名"),
                    lat = o.optDouble("lat", 0.0),
                    lon = o.optDouble("lon", 0.0),
                    addedAt = o.optLong("ts", 0L)
                )
            }
        } catch (t: Throwable) {
            emptyList()
        }
    }

    fun saveFavorites(ctx: Context, list: List<Favorite>) {
        val arr = JSONArray()
        list.forEach { f ->
            arr.put(JSONObject().apply {
                put("name", f.name)
                put("lat", f.lat)
                put("lon", f.lon)
                put("ts", f.addedAt)
            })
        }
        prefs(ctx).edit().putString(KEY_FAVORITES, arr.toString()).commit()
    }

    // ---------------------------------------------------------------- 路线

    fun loadRoute(ctx: Context): List<RoutePoint> {
        val raw = prefs(ctx).getString(KEY_ROUTE, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                RoutePoint(o.optDouble("lat", 0.0), o.optDouble("lon", 0.0))
            }
        } catch (t: Throwable) {
            emptyList()
        }
    }

    fun saveRoute(ctx: Context, points: List<RoutePoint>) {
        val arr = JSONArray()
        points.forEach { arr.put(JSONObject().apply { put("lat", it.lat); put("lon", it.lon) }) }
        prefs(ctx).edit().putString(KEY_ROUTE, arr.toString()).commit()
    }

    fun routeSpeedKmh(ctx: Context): Double = prefs(ctx).getFloat(KEY_ROUTE_SPEED, 30f).toDouble()

    fun saveRouteSpeed(ctx: Context, kmh: Double) {
        prefs(ctx).edit().putFloat(KEY_ROUTE_SPEED, kmh.toFloat()).commit()
    }

    fun routeLoop(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_ROUTE_LOOP, true)

    fun saveRouteLoop(ctx: Context, loop: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_ROUTE_LOOP, loop).commit()
    }

    // ---------------------------------------------------------------- 工作模式

    fun engineMode(ctx: Context): Int = prefs(ctx).getInt(KEY_ENGINE_MODE, MODE_LSPOSED)

    fun saveEngineMode(ctx: Context, mode: Int) {
        prefs(ctx).edit().putInt(KEY_ENGINE_MODE, mode).commit()
    }
}
