package com.mo.fakeloc.xposed

import de.robv.android.xposed.XposedBridge

/**
 * hook 端日志。统一走 XposedBridge.log，同时落一份到 logcat，
 * 方便用 `logcat -s FakeLoc` 直接看。
 */
object HookLog {

    const val TAG = "FakeLoc"

    @Volatile
    var verbose: Boolean = true

    fun i(msg: String) = write("I", msg, null)

    fun e(msg: String, t: Throwable? = null) = write("E", msg, t)

    /** 高频日志（每次定位回调都会打的那种），默认只在 verbose 打开时输出。 */
    fun v(msg: String) {
        if (verbose) write("V", msg, null)
    }

    private fun write(level: String, msg: String, t: Throwable?) {
        val line = "[$TAG][$level] $msg"
        try {
            XposedBridge.log(line)
        } catch (_: Throwable) {
            // XposedBridge 不可用（理论上不会发生）时静默
        }
        try {
            if (level == "E") android.util.Log.e(TAG, msg, t) else android.util.Log.i(TAG, msg)
        } catch (_: Throwable) {
        }
        if (t != null) {
            try {
                XposedBridge.log(t)
            } catch (_: Throwable) {
            }
        }
    }
}
