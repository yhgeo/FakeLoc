package com.mo.fakeloc.xposed

import android.app.AndroidAppHelper
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import com.mo.fakeloc.BuildConfig
import com.mo.fakeloc.data.ConfigProvider
import com.mo.fakeloc.data.ConfigStore
import com.mo.fakeloc.data.LocConfig
import de.robv.android.xposed.XSharedPreferences
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * hook 端配置读取桥。
 *
 * ## 为什么必须用后台线程 + 内存快照
 * hook 的触发点很多在**持锁路径**上：system_server 的 LocationProviderManager、
 * ActivityManagerService 监视器等。如果在那里同步发 binder 去问 App 要配置，
 * 一旦 App 卡顿/被冻结，就会把系统锁占住几十秒 → watchdog 判定 system_server
 * 阻塞 → 整机无响应。
 *
 * 所以这里的策略是：**后台线程按需拉取，写进内存快照；hook 只读内存。**
 * 读快照是纳秒级操作，永不阻塞调用方。
 *
 * ## 五级通道（实测出来的，不是拍脑袋）
 *
 * 真机上验证过一件事：**Android 11+ 的包可见性会让第三方应用进程根本解析不到
 * 模块的 ContentProvider**。证据：`dumpsys activity providers` 里
 * `com.mo.fakeloc.config` 的 Connections 只有 `1910:system/1000`，
 * 其它进程一律 `Failed to find provider info for com.mo.fakeloc.config`。
 *
 * 于是通道改成五级，按"能不能被普通应用进程用到"排序：
 *
 * | 通道 | system_server | 普通应用进程 | 说明 |
 * |------|---------------|--------------|------|
 * | 1. ContentProvider | ✅ | ❌ 被包可见性拦 | 只有 uid 1000 / 同 uid 可用 |
 * | 2. **Settings.Global 中继** | ✅ | ✅ | ★ 主力：system_server 写入，所有进程可读 |
 * | 3. XSharedPreferences | ✅ | 取决于 LSPosed 重定向 | 官方机制，做兜底 |
 * | 4. 直读文件 | 看权限 | 看权限 | /data/local/tmp 等，root 写的世界可读副本 |
 * | 5. 无 | — | — | 保持上一次快照，绝不清空 |
 *
 * ### 通道 2 的原理（两跳中继）
 * ```
 * App ──Provider──> system_server(uid 1000，不受可见性限制)
 *                        │ 写入 Settings.Global["fakeloc_cfg"]
 *                        ▼
 *                  任意应用进程读 Settings.Global（读它不需要任何权限）
 * ```
 * 读 `Settings.Global` 是公开 API、不需要权限、也不受包可见性限制，所以这一跳稳。
 *
 * ## 回执
 * 每次拉取时把「我是谁 / 我挂了哪些 hook / 我读到的配置 / 用的哪条通道」
 * 塞进 extras 一起发出去，Provider 收下后记在 App 进程里。App 里因此能**看见**
 * hook 侧的真实状态，不用再靠猜。
 */
object ConfigBridge {

    /** 开启状态下的轮询间隔：路线模拟要跟手，1 秒。 */
    private const val REFRESH_ENABLED_MS = 1000L

    /** 关闭状态下的轮询间隔：只需要及时感知"被打开"，2 秒够用，省点电。 */
    private const val REFRESH_DISABLED_MS = 2000L

    /** Settings.Global 中继用的键。 */
    const val RELAY_KEY = "fakeloc_cfg"

    /**
     * 诊断键。
     *
     * system_server 侧把「当前通道 / 配置序号 / 开关 / 各通道可用性」写进这里，
     * 于是**不需要 root**，一条 `adb shell settings get global fakeloc_diag`
     * 就能看到 hook 侧的真实状态。排查这类跨进程问题时这是最省事的窗口。
     */
    const val DIAG_KEY = "fakeloc_diag"

    /** 中继写入的最小间隔，避免路线模拟时每秒写一次 Settings。 */
    private const val RELAY_MIN_INTERVAL_MS = 900L

    private val snapshot = AtomicReference<LocConfig?>(null)
    private val lastFingerprint = AtomicReference(Int.MIN_VALUE)

    @Volatile
    private var started = false

    @Volatile
    private var providerAlive = false

    /** 当前进程身份，由入口注入。 */
    @Volatile
    private var selfPackage: String = "?"

    @Volatile
    private var selfProcess: String = "?"

    /** 已装载的 hook 摘要，如 "SS:17,LM:20"。 */
    private val hookTags = LinkedHashSet<String>()

    /** 当前生效的通道名，用于回执展示。 */
    @Volatile
    private var activeChannel: String = "none"

    /** 上一次写进 Settings.Global 的内容与时间。 */
    @Volatile
    private var lastRelayJson: String? = null

    @Volatile
    private var lastRelayAt = 0L

    @Volatile
    private var relayLogged = false

    @Volatile
    private var lastDiagAt = 0L

    /** system_server 这类没有 Application 的进程，由入口注入 Context 提供者。 */
    @Volatile
    private var contextProvider: (() -> Context?)? = null

    @Volatile
    private var xprefs: XSharedPreferences? = null

    @Volatile
    private var xprefsPkg: String? = null

    /** 由入口在拿到 Context 后调用（可多次调用，后者覆盖前者）。 */
    fun attachContextProvider(p: () -> Context?) {
        contextProvider = p
    }

    /** 由入口在 handleLoadPackage 最早期调用，标记本进程身份。 */
    fun setProcess(pkg: String, process: String) {
        selfPackage = pkg
        selfProcess = process
    }

    /** 登记一个成功装载的 hook 组，供回执展示。 */
    @Synchronized
    fun markHook(tag: String) {
        hookTags.add(tag)
    }

    /** 幂等启动。进程内只会有一个拉取线程。 */
    fun start() {
        if (started) return
        synchronized(this) {
            if (started) return
            started = true
            val t = Thread(Runnable { loop() }, "fakeloc-config")
            t.isDaemon = true
            t.start()
            HookLog.i("ConfigBridge started in $selfPackage/$selfProcess")
        }
    }

    /** 只读内存快照 —— 可以在任意线程、任意锁内安全调用。 */
    fun current(): LocConfig? = snapshot.get()

    /**
     * 按进程过滤后的快照：作用域白名单在这里真正生效。
     */
    fun currentFor(packageName: String?): LocConfig? {
        val cfg = snapshot.get() ?: return null
        val pkg = packageName ?: selfPackage
        return if (cfg.appliesTo(pkg)) cfg else null
    }

    /** 当前通道名（诊断用）。 */
    fun channel(): String = activeChannel

    private fun loop() {
        while (!Thread.currentThread().isInterrupted) {
            try {
                val cfg = LocConfig.fromJson(fetch())
                if (cfg != null) {
                    val fp = cfg.fingerprint()
                    if (fp != lastFingerprint.get()) {
                        lastFingerprint.set(fp)
                        snapshot.set(cfg)
                        HookLog.i("config changed [$activeChannel] -> ${cfg.toJson()}")
                    }
                }
            } catch (t: Throwable) {
                HookLog.e("refresh failed: ${t.message}", t)
            }
            try {
                Thread.sleep(nextInterval())
            } catch (e: InterruptedException) {
                return
            }
        }
    }

    /** 开/关两档轮询，带 ±15% 抖动，避免多进程同一时刻一起醒来。 */
    private fun nextInterval(): Long {
        val base = if (snapshot.get()?.enabled == true) REFRESH_ENABLED_MS else REFRESH_DISABLED_MS
        val jitter = (base * 0.15 * (Math.random() * 2 - 1)).toLong()
        return (base + jitter).coerceAtLeast(300L)
    }

    // ------------------------------------------------------------------ 取配置

    private fun fetch(): String? {
        val ctx = resolveContext()

        // 通道 1：ContentProvider —— 只有 system_server / 同 uid 能过包可见性
        var json: String? = null
        if (ctx != null) {
            json = providerFetch(ctx)
            if (json != null) activeChannel = "provider"
        }

        // 通道 2/3：两个"本地副本"通道。
        // 它们刷新频率不同（文件每秒、Settings 中继每 5 秒），所以**不能按固定顺序取**，
        // 要按配置里的 seq 挑更新的那个 —— 否则可能拿到一份更旧的位置，
        // 表现就是"位置对但单点不动"。
        if (json == null) {
            val fileJson = readConfigFiles()
            val relayJson = if (ctx != null) settingsRelayFetch(ctx) else null
            val picked = fresherOf(fileJson, relayJson)
            json = picked.first
            if (json != null) activeChannel = picked.second
        }

        // 通道 4：XSharedPreferences
        if (json == null) {
            json = readXPrefs()
            if (json != null) activeChannel = "xprefs"
        }

        if (json == null) {
            // 所有通道都断了，但可能只是暂时的（App 被冻结），不清空快照
            HookLog.v("all channels failed (last=$activeChannel)")
            return null
        }

        // 只要 system_server 拿到了配置，就写一份到 Settings.Global 供其它进程读。
        // 注意：这里**不限定走的是哪条通道** —— 之前的版本只在 provider 成功时写，
        // 结果 system_server 一旦走 XSharedPreferences，中继就永远是空的。
        if (ctx != null) {
            relayToSettings(ctx, json)
            writeDiag(ctx, json)
        }
        return json
    }

    /** 两份配置里挑 seq 更大的（更实时的那份）。 */
    private fun fresherOf(fileJson: String?, relayJson: String?): Pair<String?, String> {
        if (fileJson == null && relayJson == null) return null to "none"
        if (fileJson == null) return relayJson to "settings-relay"
        if (relayJson == null) return fileJson to "file"
        val seqFile = LocConfig.fromJson(fileJson)?.seq ?: 0L
        val seqRelay = LocConfig.fromJson(relayJson)?.seq ?: 0L
        return if (seqFile >= seqRelay) fileJson to "file" else relayJson to "settings-relay"
    }

    /** 通道 1：ContentProvider。 */
    private fun providerFetch(ctx: Context): String? {
        val extras = buildReportExtras()
        for (auth in ConfigProvider.AUTHORITY_CANDIDATES) {
            try {
                val reply = ctx.contentResolver.call(
                    Uri.parse("content://$auth"),
                    ConfigProvider.METHOD_CONFIG, null, extras
                )
                val json = reply?.getString("json")
                if (!json.isNullOrEmpty()) {
                    if (!providerAlive) {
                        providerAlive = true
                        HookLog.i("provider channel online ($auth)")
                    }
                    return json
                }
            } catch (t: Throwable) {
                // 换下一个 authority（debug 包）；失败原因在第一次时打一次日志
                if (!providerAlive && t !is IllegalArgumentException) {
                    HookLog.v("provider call failed ($auth): ${t.javaClass.simpleName}")
                }
            }
        }
        if (providerAlive) {
            providerAlive = false
            HookLog.i("provider channel offline")
        }
        return null
    }

    /** 通道 2：读 system_server 写进 Settings.Global 的中继副本。 */
    private fun settingsRelayFetch(ctx: Context): String? = try {
        Settings.Global.getString(ctx.contentResolver, RELAY_KEY)?.takeIf { it.isNotEmpty() }
    } catch (_: Throwable) {
        null
    }

    /**
     * 只有 system_server 有资格写中继。
     *
     * 节流策略：内容变了才写，且两次写入至少间隔 [RELAY_MIN_INTERVAL_MS]，
     * 避免路线模拟（每秒变一次）把 Settings 数据库写爆。
     */
    private fun relayToSettings(ctx: Context, json: String) {
        if (selfPackage != SYSTEM_PACKAGE) return
        val now = System.currentTimeMillis()
        if (json == lastRelayJson) return
        if (now - lastRelayAt < RELAY_MIN_INTERVAL_MS) return
        try {
            val ok = Settings.Global.putString(ctx.contentResolver, RELAY_KEY, json)
            lastRelayJson = json
            lastRelayAt = now
            if (!relayLogged) {
                relayLogged = true
                HookLog.i("relay -> Settings.Global ok=$ok")
            }
        } catch (t: Throwable) {
            if (!relayLogged) {
                relayLogged = true
                HookLog.e("relay write failed: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
    }

    /**
     * 把 hook 侧状态写进 Settings.Global，供 `settings get global fakeloc_diag` 查看。
     *
     * 这是**不需要 root 的诊断窗口**：LSPosed 自己的日志在 /data/adb 下，
     * 排查时经常因为 su 授权被重置而读不到；写进 Settings 就没有这个问题。
     */
    private fun writeDiag(ctx: Context, json: String) {
        if (selfPackage != SYSTEM_PACKAGE) return
        val now = System.currentTimeMillis()
        if (now - lastDiagAt < 5_000L) return
        lastDiagAt = now
        val cfg = LocConfig.fromJson(json)
        val text = buildString {
            append("v=").append(BuildConfig.VERSION_CODE)
            append(" proc=").append(selfProcess)
            append(" chan=").append(activeChannel)
            append(" hooks=").append(synchronized(this@ConfigBridge) { hookTags.joinToString(",") })
            append(" provider=").append(providerAlive)
            append(" xprefs=").append(xprefsLogged)
            append(" file=").append(fileLogged)
            append(" seq=").append(cfg?.seq ?: 0L)
            append(" enabled=").append(cfg?.enabled ?: false)
            append(" relay=").append(lastRelayJson != null)
            append(" ts=").append(now)
        }
        try {
            Settings.Global.putString(ctx.contentResolver, DIAG_KEY, text)
        } catch (_: Throwable) {
        }
    }

    // ------------------------------------------------------------------ 兜底通道

    private fun readXPrefs(): String? {
        for (pkg in PACKAGE_CANDIDATES) {
            try {
                var p = xprefs
                if (p == null || xprefsPkg != pkg) {
                    p = XSharedPreferences(pkg, ConfigStore.PREFS_NAME)
                    xprefs = p
                    xprefsPkg = pkg
                }
                p.reload()
                val json = p.getString("config_json", null)
                if (!json.isNullOrEmpty()) {
                    if (!xprefsLogged) {
                        xprefsLogged = true
                        HookLog.i("XSharedPreferences channel online ($pkg)")
                    }
                    return json
                }
            } catch (t: Throwable) {
                if (!xprefsLogged) {
                    xprefsLogged = true
                    HookLog.e("XSharedPreferences failed: ${t.javaClass.simpleName}: ${t.message}")
                }
            }
        }
        return null
    }

    @Volatile
    private var xprefsLogged = false

    /**
     * 通道 4：直读文件。
     *
     * 这些路径由 App 侧用 root 写出来（`/data/local/tmp` 是少数对其它应用
     * 可读的目录之一）。能不能读到取决于 SELinux，读不到就安静跳过 ——
     * 所以这里逐个试、结果只在 debug 级别记一次。
     */
    private fun readConfigFiles(): String? {
        for (path in FILE_CANDIDATES) {
            try {
                val f = File(path)
                if (!f.exists() || !f.canRead()) continue
                val text = f.readText()
                val json = if (path.endsWith(".xml")) extractConfigJson(text) else text.trim()
                if (!json.isNullOrEmpty()) {
                    if (!fileLogged) {
                        fileLogged = true
                        HookLog.i("file channel online ($path)")
                    }
                    return json
                }
            } catch (t: Throwable) {
                if (!fileLogged) {
                    fileLogged = true
                    HookLog.e("file channel failed ($path): ${t.javaClass.simpleName}")
                }
            }
        }
        return null
    }

    @Volatile
    private var fileLogged = false

    /** 从 SharedPreferences 的 XML 里抠出 config_json（值里的引号是 XML 转义过的）。 */
    private fun extractConfigJson(xml: String): String? {
        val m = Regex("""<string name="config_json">([\s\S]*?)</string>""").find(xml) ?: return null
        return m.groupValues[1]
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
            .trim()
    }

    // ------------------------------------------------------------------ 回执

    /**
     * 随配置请求一起发出去的心跳。
     *
     * 这里报的是**上一次**读到的配置状态（本次结果还没解析出来），
     * 但 1~2 秒后就会自愈，对"链路是否打通"这个判断完全够用。
     */
    private fun buildReportExtras(): Bundle {
        val cur = snapshot.get()
        val tags = synchronized(this) { hookTags.joinToString(",") }
        return Bundle().apply {
            putString(ConfigProvider.KEY_HOOK_PKG, selfPackage)
            putString(ConfigProvider.KEY_HOOK_PROC, selfProcess)
            putString(ConfigProvider.KEY_HOOK_TAGS, tags)
            putInt(ConfigProvider.KEY_HOOK_VER, BuildConfig.VERSION_CODE)
            putLong(ConfigProvider.KEY_HOOK_SEQ, cur?.seq ?: 0L)
            putBoolean(ConfigProvider.KEY_HOOK_ENABLED, cur?.enabled ?: false)
            putString(ConfigProvider.KEY_HOOK_CHANNEL, activeChannel)
        }
    }

    private fun resolveContext(): Context? {
        try {
            contextProvider?.invoke()?.let { return it }
        } catch (_: Throwable) {
        }
        return try {
            AndroidAppHelper.currentApplication()
        } catch (_: Throwable) {
            null
        }
    }

    private const val SYSTEM_PACKAGE = "android"

    private val PACKAGE_CANDIDATES = listOf("com.mo.fakeloc", "com.mo.fakeloc.debug")

    /** App 侧用 root 写出来的世界可读副本，按可读概率排序。 */
    private val FILE_CANDIDATES = listOf(
        "/data/local/tmp/fakeloc/config.json",
        "/data/local/tmp/fakeloc/fakeloc.xml",
        "/data/adb/fakeloc/config.json",
        "/data/data/com.mo.fakeloc/shared_prefs/fakeloc.xml"
    )
}
