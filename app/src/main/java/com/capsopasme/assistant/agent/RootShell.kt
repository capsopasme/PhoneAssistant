package com.capsopasme.assistant.agent

import java.util.concurrent.TimeUnit

/**
 * Runs fixed, app-built commands with root (KernelSU). One short-lived `su -c` process per
 * command: nothing stays running between commands, and a hung command is killed after its
 * timeout. Commands are never taken from the model: tools assemble them from validated enums
 * and numbers only.
 */
object RootShell {

    class Result(val code: Int, val output: String) {
        val ok get() = code == 0
    }

    fun run(command: String, timeoutMs: Long = 6_000): Result {
        val p = try {
            ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        } catch (e: Exception) {
            return Result(-1, "无法执行 su：${e.message}")
        }
        return try {
            // outputs are tiny (well under the pipe buffer), so waiting first can't deadlock
            if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                p.destroyForcibly()
                return Result(-2, "命令超时")
            }
            val out = p.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }.trim()
            Result(p.exitValue(), out)
        } catch (e: InterruptedException) {
            p.destroyForcibly()
            Thread.currentThread().interrupt()
            Result(-3, "已取消")
        } finally {
            p.destroy()
        }
    }

    /**
     * For commands with a large output (`settings list`, tens of KB): the output is read while
     * the command runs, so a full pipe can't stall it. Killed after [timeoutMs].
     */
    fun runReading(command: String, timeoutMs: Long = 10_000): Result {
        val p = try {
            ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        } catch (e: Exception) {
            return Result(-1, "无法执行 su：${e.message}")
        }
        val killer = Thread {
            try {
                Thread.sleep(timeoutMs)
                p.destroyForcibly()
            } catch (_: InterruptedException) {
            }
        }.apply {
            isDaemon = true
            start()
        }
        return try {
            val out = p.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            if (!p.waitFor(1, TimeUnit.SECONDS)) {
                p.destroyForcibly()
                return Result(-2, "命令超时")
            }
            Result(p.exitValue(), out)
        } catch (e: Exception) {
            p.destroyForcibly()
            Result(-2, "命令超时或被中断")
        } finally {
            killer.interrupt()
            p.destroy()
        }
    }

    fun isAvailable(): Boolean = run("id", 30_000).let { it.ok && it.output.contains("uid=0") }

    /** single-quoted for sh */
    fun quote(s: String) = "'" + s.replace("'", "'\\''") + "'"
}
