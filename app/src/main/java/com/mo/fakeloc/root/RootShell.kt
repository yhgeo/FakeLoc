package com.mo.fakeloc.root

import java.io.BufferedReader
import java.io.BufferedWriter
import java.util.concurrent.TimeUnit

/**
 * 极简 root shell 封装。
 *
 * 设计取舍：
 *  - 只走 `su`，不内置任何提权漏洞利用，设备必须已经 root（Magisk / KernelSU）；
 *  - 合并 stderr 到 stdout，避免管道写满导致死锁；
 *  - 所有调用都带超时，防止 su 弹窗等待把 App 卡死。
 *
 * ## 为什么还要一个"常驻 shell"
 * 路线模拟时每秒都要把新坐标写出去（hook 侧靠这个文件拿实时位置）。
 * 每次 `su -c` 都要 spawn 一个进程，实测 50~150ms 起，每秒来一发太重。
 * 所以额外维持一条长连接：命令写进 stdin，靠哨兵行判断执行完毕。
 * 常驻 shell 一旦挂了会自动回退到一次性 `su -c`。
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
        closeSession()
    }

    // ------------------------------------------------------------------ 一次性执行

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

    // ------------------------------------------------------------------ 常驻会话

    /** 一条常驻 root shell。单线程串行使用。 */
    private class Session(val proc: Process) {
        val out: BufferedReader = proc.inputStream.bufferedReader()
        val stdin: BufferedWriter = proc.outputStream.bufferedWriter()
        private val lock = Any()
        private var counter = 0L

        @Volatile
        var alive = true

        fun run(command: String, timeoutMs: Long): Boolean = synchronized(lock) {
            if (!alive) return false
            return try {
                val marker = "__FKLOC_${++counter}__"
                stdin.write(command)
                stdin.write("\n")
                stdin.write("echo $marker\n")
                stdin.flush()

                val deadline = System.currentTimeMillis() + timeoutMs
                while (System.currentTimeMillis() < deadline) {
                    if (out.ready()) {
                        val line = out.readLine() ?: break
                        if (line.contains(marker)) return true
                    } else {
                        Thread.sleep(4)
                    }
                }
                false
            } catch (t: Throwable) {
                alive = false
                false
            }
        }

        fun close() {
            alive = false
            runCatching { stdin.close() }
            runCatching { proc.destroy() }
        }
    }

    @Volatile
    private var session: Session? = null

    private fun ensureSession(): Session? {
        session?.let { if (it.alive) return it }
        synchronized(this) {
            session?.let { if (it.alive) return it }
            val s = try {
                val pb = ProcessBuilder("su")
                pb.redirectErrorStream(true)
                Session(pb.start())
            } catch (_: Throwable) {
                null
            }
            session = s
            return s
        }
    }

    fun closeSession() {
        synchronized(this) {
            session?.close()
            session = null
        }
    }

    /**
     * 高频执行：优先走常驻 shell（毫秒级），失败自动回退到一次性 `su -c`。
     *
     * @return 命令是否执行成功
     */
    fun execFast(command: String, timeoutMs: Long = 1500): Boolean {
        val s = ensureSession()
        if (s != null && s.run(command, timeoutMs)) return true

        // 常驻 shell 不可用/超时 —— 丢掉它，用一次性方式兜底
        synchronized(this) {
            session?.close()
            session = null
        }
        return exec(command, timeoutSec = 8).ok
    }
}
