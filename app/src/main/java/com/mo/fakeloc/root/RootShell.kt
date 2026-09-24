package com.mo.fakeloc.root

import java.util.concurrent.TimeUnit

/**
 * 极简 root shell 封装。
 *
 * 设计取舍：
 *  - 只走 `su -c`，不内置任何提权漏洞利用，设备必须已经 root（Magisk / KernelSU）；
 *  - 合并 stderr 到 stdout，避免管道写满导致死锁；
 *  - 所有调用都带超时，防止 su 弹窗等待把 App 卡死。
 */
object RootShell {

    data class Result(val exitCode: Int, val output: String) {
        val ok: Boolean get() = exitCode == 0
    }

    @Volatile
    private var cachedRoot: Boolean? = null

    /** 是否已 root。结果会缓存，避免反复触发 su 授权弹窗。 */
    fun hasRoot(): Boolean {
        cachedRoot?.let { return it }
        val r = exec("id", timeoutSec = 6)
        val root = r.ok && r.output.contains("uid=0")
        cachedRoot = root
        return root
    }

    /** 清掉 root 状态缓存（用户手动授权后重新探测）。 */
    fun invalidate() {
        cachedRoot = null
    }

    fun exec(command: String, timeoutSec: Long = 15): Result = try {
        val pb = ProcessBuilder("su", "-c", command)
        pb.redirectErrorStream(true)
        val proc = pb.start()

        val output = proc.inputStream.bufferedReader().use { it.readText() }

        val finished = proc.waitFor(timeoutSec, TimeUnit.SECONDS)
        if (!finished) {
            proc.destroy()
            Result(-1, "$output\n[timeout after ${timeoutSec}s]")
        } else {
            Result(proc.exitValue(), output)
        }
    } catch (t: Throwable) {
        Result(-1, t.message ?: "exec failed")
    }
}
