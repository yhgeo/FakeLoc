package com.mo.fakeloc.data

import android.content.Context
import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * 单个被注入进程的"心跳回执"。
 *
 * ## 为什么需要它
 * LSPosed 模块最难受的一点是**单向**：hook 跑在别的进程里，App 完全看不到
 * 它有没有装载、装了几个 hook、读到的是哪份配置。用户只能靠"定位没变"
 * 反推，而"没变"的原因可能有一打（模块没启用 / 作用域没勾 / 目标 App 没重启 /
 * 配置没下发到 / 坐标本来就没改）。
 *
 * 所以这里让 hook 每次向 [ConfigProvider] 要配置时，**顺路**把自己的状态
 * 塞进 `extras` 带过来。Provider 运行在 App 自己的进程里，收到就记下来，
 * UI 一读就能把链路摊开给用户看。
 *
 * 额外好处：**零额外 IPC**。本来就要每秒拉一次配置，回执只是搭了个便车。
 */
data class HookReport(
    /** 被注入进程所属包名，system_server 为 "android"。 */
    val pkg: String,
    /** 进程名（可能是 :remote 之类的子进程）。 */
    val process: String,
    /** 已装载的 hook 摘要，如 `SS:6,LM:14`。 */
    val hooks: String,
    /** 模块 versionCode，用来确认跑的是不是当前这版。 */
    val moduleVersion: Int,
    /** 该进程读到的配置序号。 */
    val cfgSeq: Long,
    /** 该进程读到的配置总开关。 */
    val cfgEnabled: Boolean,
    /** 实际走通的配置通道（provider / settings-relay / xprefs / file / none）。 */
    val channel: String,
    /** 最后一次收到回执的本地时间。 */
    val seenAt: Long,
    /** 收到回执的次数（用来判断是"活着"还是"只闪了一下"）。 */
    val hits: Int
) {
    /** 距离上次回执过了多久。 */
    fun ageMs(now: Long = System.currentTimeMillis()): Long = now - seenAt

    fun isAlive(now: Long = System.currentTimeMillis()): Boolean = ageMs(now) < 15_000L

    fun toJson(): JSONObject = JSONObject().apply {
        put("pkg", pkg)
        put("proc", process)
        put("hooks", hooks)
        put("ver", moduleVersion)
        put("seq", cfgSeq)
        put("en", cfgEnabled)
        put("ch", channel)
        put("ts", seenAt)
        put("hits", hits)
    }

    companion object {
        fun fromJson(o: JSONObject): HookReport = HookReport(
            pkg = o.optString("pkg", "?"),
            process = o.optString("proc", "?"),
            hooks = o.optString("hooks", ""),
            moduleVersion = o.optInt("ver", 0),
            cfgSeq = o.optLong("seq", 0L),
            cfgEnabled = o.optBoolean("en", false),
            channel = o.optString("ch", ""),
            seenAt = o.optLong("ts", 0L),
            hits = o.optInt("hits", 1)
        )
    }
}

/**
 * 回执登记簿（**只存在于 App 自己的进程里**）。
 *
 * 内存是主存储，磁盘只是为了让 App 被杀掉重开后还能看到"上次谁来过"。
 * 磁盘写入做了节流：hook 端每秒来一次，不能每秒写一次盘。
 */
object HookReportRegistry {

    private const val FILE_NAME = "hook_reports.json"
    private const val FLUSH_INTERVAL_MS = 3_000L
    private const val MAX_KEEP = 48

    private val map = ConcurrentHashMap<String, HookReport>()

    @Volatile
    private var lastFlushAt = 0L

    @Volatile
    private var loaded = false

    /** 从 Provider 的 extras 里收一份回执。在 binder 线程调用，必须快。 */
    fun record(ctx: Context, extras: Bundle) {
        val proc = extras.getString(ConfigProvider.KEY_HOOK_PROC) ?: return
        val now = System.currentTimeMillis()
        val incoming = HookReport(
            pkg = extras.getString(ConfigProvider.KEY_HOOK_PKG) ?: "?",
            process = proc,
            hooks = extras.getString(ConfigProvider.KEY_HOOK_TAGS).orEmpty(),
            moduleVersion = extras.getInt(ConfigProvider.KEY_HOOK_VER, 0),
            cfgSeq = extras.getLong(ConfigProvider.KEY_HOOK_SEQ, 0L),
            cfgEnabled = extras.getBoolean(ConfigProvider.KEY_HOOK_ENABLED, false),
            channel = extras.getString(ConfigProvider.KEY_HOOK_CHANNEL).orEmpty(),
            seenAt = now,
            hits = (map[proc]?.hits ?: 0) + 1
        )
        map[proc] = incoming

        if (now - lastFlushAt >= FLUSH_INTERVAL_MS) {
            lastFlushAt = now
            flush(ctx)
        }
    }

    /** 按"最近活跃"排序的快照。 */
    fun snapshot(): List<HookReport> =
        map.values.sortedByDescending { it.seenAt }

    fun clear(ctx: Context) {
        map.clear()
        lastFlushAt = 0L
        try {
            ctx.filesDir.resolve(FILE_NAME).delete()
        } catch (_: Throwable) {
        }
    }

    private fun flush(ctx: Context) {
        try {
            val arr = JSONArray()
            map.values.sortedByDescending { it.seenAt }
                .take(MAX_KEEP)
                .forEach { arr.put(it.toJson()) }
            ctx.filesDir.resolve(FILE_NAME).writeText(arr.toString())
        } catch (_: Throwable) {
            // 落盘失败不影响内存态
        }
    }

    /** 冷启动时把上次的回执捞回来，UI 里显示成"历史"。 */
    fun loadIfNeeded(ctx: Context) {
        if (loaded) return
        loaded = true
        try {
            val f = ctx.filesDir.resolve(FILE_NAME)
            if (!f.exists()) return
            val arr = JSONArray(f.readText())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val r = HookReport.fromJson(o)
                map[r.process] = r
            }
        } catch (_: Throwable) {
        }
    }
}
