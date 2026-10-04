package mz.mibackscreen.mouse.core

import java.util.concurrent.TimeUnit

/** 特权命令执行层：按需通过 KernelSU 执行命令，不常驻 root 进程、不开机自启。 */
object RootShell {

    private val suCandidates = arrayOf(
        "su",
        "/system/bin/su",
        "/system/xbin/su",
        "/data/adb/ksu/bin/su",
    )

    private val suPath: String? by lazy {
        for (candidate in suCandidates) {
            val r = exec(6, candidate, "-c", "id")
            Logs.d("Root", "尝试 $candidate -> [${r.code}] ${r.output.take(160)}")
            if (r.code == 0 && r.output.contains("uid=0")) {
                Logs.d("Root", "使用 su: $candidate")
                return@lazy candidate
            }
        }
        Logs.d("Root", "未找到可用的 su（可能尚未在 KernelSU 中授权本应用）")
        null
    }

    data class Result(val code: Int, val output: String) {
        val ok: Boolean get() = code == 0
    }

    fun isRootAvailable(): Boolean = suPath != null

    fun run(command: String, timeoutSeconds: Long = 10): Result {
        val su = suPath ?: return Result(-1, "su unavailable")
        return exec(timeoutSeconds, su, "-c", command)
    }

    private fun exec(timeoutSeconds: Long, program: String, vararg args: String): Result {
        return try {
            val process = ProcessBuilder(listOf(program) + args)
                .redirectErrorStream(true)
                .start()
            val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                Result(-1, "timeout")
            } else {
                val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
                Result(process.exitValue(), output)
            }
        } catch (t: Throwable) {
            Result(-1, "${t.javaClass.simpleName}: ${t.message}")
        }
    }
}
