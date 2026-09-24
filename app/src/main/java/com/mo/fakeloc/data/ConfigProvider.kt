package com.mo.fakeloc.data

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.util.Log
import com.mo.fakeloc.BuildConfig

/**
 * App → hook 的**配置下发通道**。
 *
 * 为什么必须用 ContentProvider：
 *  hook 代码运行在被注入的目标进程里，uid 与被 hook 的应用相同，既读不到 App 的
 *  私有目录（SELinux 的 app_data_file 跨应用拒绝），也过不了签名级权限校验。
 *  ContentProvider 的 [call] 是唯一在 Android 14 上稳定可用的跨 uid 单工通道。
 *
 * 安全性：这里只暴露坐标与开关这类非敏感数据；provider 本身不做写操作。
 * 另外故意**不在调用方线程做耗时操作**，实现是纯内存读，微秒级返回。
 */
class ConfigProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        // 冷启动时把上次的 hook 回执捞回内存，UI 一打开就能看到历史接入情况
        context?.let { HookReportRegistry.loadIfNeeded(it) }
        return true
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val ctx = context ?: return null

        // 任何一次调用都先把 hook 的心跳收下来。
        // 这是"App 能否看见 hook 状态"的唯一入口 —— 搭在既有请求上，零额外 IPC。
        if (extras != null) {
            try {
                HookReportRegistry.record(ctx, extras)
            } catch (t: Throwable) {
                Log.w(TAG, "record hook report failed: ${t.message}")
            }
        }

        return when (method) {
            METHOD_CONFIG -> Bundle().apply {
                putString("json", ConfigStore.rawJson(ctx))
            }
            METHOD_PING -> Bundle().apply {
                putBoolean("alive", true)
                putString("pkg", ctx.packageName)
                putInt("ver", BuildConfig.VERSION_CODE)
            }
            else -> {
                Log.w(TAG, "unknown method: $method")
                null
            }
        }
    }

    // ---- 以下均不支持（provider 只做只读通道） ----
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        private const val TAG = "FakeLoc/Provider"

        const val METHOD_CONFIG = "config"
        const val METHOD_PING = "ping"

        // ---- hook 回执（hook 端塞进 extras，Provider 侧收下来） ----
        /** 被注入进程的包名。 */
        const val KEY_HOOK_PKG = "hookPkg"

        /** 被注入进程的进程名。 */
        const val KEY_HOOK_PROC = "hookProc"

        /** 已装载的 hook 摘要，如 "SS:6,LM:14"。 */
        const val KEY_HOOK_TAGS = "hookTags"

        /** 模块 versionCode。 */
        const val KEY_HOOK_VER = "hookVer"

        /** 该进程读到的配置序号。 */
        const val KEY_HOOK_SEQ = "hookSeq"

        /** 该进程读到的配置总开关。 */
        const val KEY_HOOK_ENABLED = "hookEnabled"

        /** 该进程实际走通的是哪条配置通道（provider / settings-relay / xprefs / file）。 */
        const val KEY_HOOK_CHANNEL = "hookChannel"

        /** release 的 authority。debug 包会用带后缀的 authority。 */
        const val AUTHORITY = "com.mo.fakeloc.config"

        /** hook 端依次尝试的 authority 列表（覆盖 debug 包）。 */
        val AUTHORITY_CANDIDATES = listOf(
            "com.mo.fakeloc.config",
            "com.mo.fakeloc.debug.config"
        )
    }
}
