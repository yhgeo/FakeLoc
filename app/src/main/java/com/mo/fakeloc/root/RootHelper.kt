package com.mo.fakeloc.root

import android.content.Context
import com.mo.fakeloc.data.ConfigStore
import java.io.File

/**
 * root 侧辅助通道。
 *
 * ## 定位说明
 * 本模块**真正干活的是 LSPosed**，Magisk 模块的角色是"root 辅助"：
 *
 * 1. `/data/adb/fakeloc/` —— 配置备份目录（仅 root 可读写）。
 *    App 通过 `su` 把当前配置落一份到这里，好处是：
 *    - 清 App 数据 / 重装后可以恢复；
 *    - 出问题时可以直接 `cat` 出来排查 hook 到底读到了什么。
 * 2. 模块状态探测 —— 读取 `/data/adb/modules/fakeloc/module.prop` 判断
 *    Magisk 模块是否已刷入，在界面上给出明确提示。
 * 3. 权限修正 —— 见 `magisk/service.sh`，开机时把 App 的 prefs 置为可读，
 *    让 XSharedPreferences 兜底通道也能生效。
 *
 * 所有操作都先探测 root，无 root 时安静降级，不影响 LSPosed 主通道。
 */
object RootHelper {

    const val DIR = "/data/adb/fakeloc"
    const val CONFIG_FILE = "$DIR/config.json"
    const val MODULE_PROP = "/data/adb/modules/fakeloc/module.prop"

    /**
     * 世界可读的副本目录。
     *
     * 为什么需要：hook 跑在目标应用进程里，读不到 App 私有目录，
     * 而 ContentProvider 又被 Android 11+ 的包可见性拦住了。
     * `/data/local/tmp` 是少数几个"root 可写、其它 uid 有机会读"的位置，
     * 所以在这里放一份 0644 的配置副本当兜底通道。
     * 目录用 0711（可穿过、不可列举），文件用 0644。
     */
    const val PUBLIC_DIR = "/data/local/tmp/fakeloc"
    const val PUBLIC_CONFIG = "$PUBLIC_DIR/config.json"

    /** Magisk 模块是否已刷入。 */
    fun isMagiskModuleInstalled(): Boolean {
        if (!RootShell.hasRoot()) return false
        return RootShell.exec("test -f $MODULE_PROP && echo yes || echo no", 8)
            .output.contains("yes")
    }

    /**
     * LSPosed 框架是否装了。
     *
     * 为什么不能靠包名判断：LSPosed 自带「隐藏管理器」，开启后管理器会用随机包名安装，
     * `pm list packages | grep lsposed` 什么都查不到。但它的数据目录 `/data/adb/lspd/`
     * 一定存在，用 root 看一眼最可靠。
     */
    fun isLsposedInstalled(): Boolean {
        if (!RootShell.hasRoot()) return false
        return RootShell.exec("test -d /data/adb/lspd && echo yes || echo no", 8)
            .output.contains("yes")
    }

    /** 把配置备份到 root 目录，同时写一份世界可读副本供 hook 兜底读取。 */
    fun backupConfig(ctx: Context, json: String): Boolean {
        if (!RootShell.hasRoot()) return false

        // 先写到 App 缓存目录（root 可读），再 cp 过去。
        // 这样避免把带引号的 JSON 直接拼进 shell 命令里引发转义问题。
        val tmp = File(ctx.cacheDir, "fakeloc_config.json")
        try {
            tmp.writeText(json)
        } catch (t: Throwable) {
            return false
        }

        val src = quote(tmp.absolutePath)
        val cmd = buildString {
            // 私有备份（仅 root）
            append("mkdir -p $DIR")
            append(" && cp $src $CONFIG_FILE")
            append(" && chmod 600 $CONFIG_FILE")
            append(" && chown root:root $CONFIG_FILE")
            // 世界可读副本（hook 兜底通道）
            append(" && mkdir -p $PUBLIC_DIR")
            append(" && chmod 0711 $PUBLIC_DIR")
            append(" && cp $src $PUBLIC_CONFIG")
            append(" && chmod 0644 $PUBLIC_CONFIG")
            append(" && chown root:root $PUBLIC_CONFIG")
        }
        val ok = RootShell.exec(cmd, 15).ok
        try {
            tmp.delete()
        } catch (_: Throwable) {
        }
        return ok
    }

    /** 读回备份的配置（用于恢复 / 排查）。 */
    fun readBackupConfig(): String? {
        if (!RootShell.hasRoot()) return null
        val r = RootShell.exec("cat $CONFIG_FILE 2>/dev/null", 8)
        return r.output.trim().takeIf { it.isNotEmpty() && r.ok }
    }

    /**
     * 把配置发布到 `Settings.Global`，作为 hook 侧的**跨进程配置中继**。
     *
     * 为什么由 App 用 root 写，而不是让 system_server 里的 hook 写：
     * 实测 system_server 调 `Settings.Global.putString` 写不进去（返回后值仍是 null），
     * 而 `settings put` 走 shell/root 是稳定可用的。
     *
     * 读 `Settings.Global` 不需要任何权限、也不受 Android 11+ 包可见性限制，
     * 所以这是 hook（跑在任意应用进程里）能拿到配置的最可靠通道。
     */
    fun publishRelay(json: String): Boolean {
        if (!RootShell.hasRoot()) return false
        // JSON 里不会有单引号，但保险起见还是清掉，避免拼 shell 时被截断
        val safe = json.replace("'", "")
        return RootShell.execFast("settings put global fakeloc_cfg '$safe'")
    }

    /**
     * 把当前配置**实时**推到世界可读副本。
     *
     * 这是 hook 侧（跑在目标应用进程里）拿到**实时坐标**的关键：
     * 系统层每秒都在更新位置，但目标进程只能通过这个文件（或 Settings 中继）拿到配置。
     * 如果这个文件只在用户操作时写一次，应用侧看到的就是"一根木桩"——
     * 位置定在路线起点，永远不动。
     *
     * 走常驻 shell，所以每秒调用一次的开销可以忽略。
     */
    fun pushLiveConfig(json: String): Boolean {
        if (!RootShell.hasRoot()) return false
        val safe = json.replace("'", "")
        // 第一次（或 App 进程重启后）先把目录和权限摆好，之后只写内容
        val cmd = if (publicReady) {
            "echo '$safe' > $PUBLIC_CONFIG"
        } else {
            "mkdir -p $PUBLIC_DIR && chmod 0711 $PUBLIC_DIR" +
                " && echo '$safe' > $PUBLIC_CONFIG && chmod 0644 $PUBLIC_CONFIG"
        }
        val ok = RootShell.execFast(cmd)
        if (ok) publicReady = true
        return ok
    }

    @Volatile
    private var publicReady = false

    /** 一键把备份配置恢复到 App。 */
    fun restoreInto(ctx: Context): Boolean {
        val json = readBackupConfig() ?: return false
        val cfg = com.mo.fakeloc.data.LocConfig.fromJson(json) ?: return false
        ConfigStore.save(ctx, cfg)
        return true
    }

    private fun quote(path: String): String = "'" + path.replace("'", "'\\''") + "'"
}
