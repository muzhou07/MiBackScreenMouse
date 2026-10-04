package mz.mibackscreen.mouse.core

import android.content.Context
import java.io.File
import java.util.zip.ZipFile

/** root 助手 bsm_helper 的释放与进程管理：从 APK 取出到 filesDir，用 su 启动。 */
object RootHelper {

    private const val APK_ENTRY = "lib/arm64-v8a/libbsm_helper.so"

    /** 控制口 socket（App 私有目录内，权限 0600）：别的 App 既连不上、也抢不到 */
    const val SOCKET_NAME = "bsm.sock"

    /** 一次性 token 文件（0600）：token 不走命令行，助手读完立即删除 */
    private const val TOKEN_NAME = ".bsm-token"

    fun socketFile(context: Context): File = File(context.filesDir, SOCKET_NAME)

    private fun tokenFile(context: Context): File = File(context.filesDir, TOKEN_NAME)

    /** 本次会话的鉴权串：助手只接受带它的客户端，防止同机其它 App 抢占控制口 */
    @Volatile
    var sessionToken: String = ""
        private set

    /** 生成新的会话鉴权串（每次启动会话都换一个） */
    fun newSessionToken(): String {
        val bytes = ByteArray(16)
        java.security.SecureRandom().nextBytes(bytes)
        val token = bytes.joinToString("") { "%02x".format(it) }
        sessionToken = token
        return token
    }

    @Volatile
    private var process: Process? = null

    @Volatile
    var running: Boolean = false
        private set

    fun helperFile(context: Context): File = File(context.filesDir, "bsm_helper")

    /** 把助手释放到 filesDir/bsm_helper（内容变化时覆盖），返回可执行文件。 */
    fun ensureInstalled(context: Context): File? {
        val out = helperFile(context)
        return try {
            val apk = File(context.applicationInfo.sourceDir)
            ZipFile(apk).use { zip ->
                val entry = zip.getEntry(APK_ENTRY) ?: run {
                    Logs.d("Helper", "APK 内找不到 $APK_ENTRY")
                    return null
                }
                if (!out.exists() || out.length() != entry.size) {
                    zip.getInputStream(entry).use { input ->
                        out.outputStream().use { input.copyTo(it) }
                    }
                    Logs.d("Helper", "助手已释放: ${out.absolutePath} (${out.length()} bytes)")
                }
            }
            out.setExecutable(true, false)
            out
        } catch (t: Throwable) {
            Logs.d("Helper", "释放助手失败: ${t.message}")
            null
        }
    }

    /** 以 root 启动助手守护进程。 */
    fun start(context: Context): Boolean {
        if (process?.isAlive == true) return true
        if (!RootShell.isRootAvailable()) {
            Logs.d("Helper", "没有 root，无法启动助手")
            return false
        }
        val helper = ensureInstalled(context) ?: return false
        val token = sessionToken
        if (token.isEmpty()) {
            Logs.d("Helper", "没有会话 token，拒绝启动助手")
            return false
        }
        // token 写进私有文件（0600），助手读完即删 —— 命令行里不再出现 token
        val tokenFile = tokenFile(context)
        try {
            tokenFile.writeText(token)
            tokenFile.setReadable(false, false)
            tokenFile.setReadable(true, true)
            tokenFile.setWritable(false, false)
            tokenFile.setWritable(true, true)
        } catch (t: Throwable) {
            Logs.d("Helper", "写入 token 文件失败: ${t.message}")
            return false
        }
        val cmd = "${helper.absolutePath} --daemon" +
            " --sock ${socketFile(context).absolutePath}" +
            " --uid ${android.os.Process.myUid()}" +
            " --token-file ${tokenFile.absolutePath}"
        return try {
            val p = ProcessBuilder("su", "-c", cmd)
                .redirectErrorStream(true)
                .start()
            process = p
            running = true
            // 助手已读到 token（一次性文件），稍后删掉，避免长期留在磁盘上
            Thread {
                try {
                    Thread.sleep(2000)
                } catch (_: InterruptedException) {
                }
                tokenFile.delete()
            }.also {
                it.isDaemon = true
                it.name = "bsm-token-cleanup"
                it.start()
            }
            Thread {
                try {
                    p.inputStream.bufferedReader().forEachLine { Logs.d("Helper", it) }
                } catch (_: Throwable) {
                }
                running = false
                Logs.d("Helper", "助手进程结束")
            }.also {
                it.isDaemon = true
                it.name = "bsm-helper-log"
                it.start()
            }
            Logs.d("Helper", "助手已启动(root)")
            true
        } catch (t: Throwable) {
            Logs.d("Helper", "启动失败: ${t.message}")
            false
        }
    }

    /**
     * 停止助手。
     *
     * 助手的真实进程是 su 的子进程，destroy() 只杀 su；这里额外做 root 兜底清理并确认无残留。
     */
    fun stop(context: Context? = null) {
        try {
            process?.destroy()
        } catch (_: Throwable) {
        }
        process = null
        running = false
        if (context == null) return
        tokenFile(context).delete()
        socketFile(context).delete()
        killAllHelpers()
        val left = RootShell.run("pidof bsm_helper").output.trim()
        Logs.d("Helper", if (left.isEmpty()) "助手已完全退出（无残留进程）" else "助手仍有残留：pid=$left")
    }

    /** root 兜底：杀掉所有 bsm_helper 进程（SIGTERM，必要时 SIGKILL）。 */
    fun killAllHelpers() {
        if (!RootShell.isRootAvailable()) return
        RootShell.run("killall bsm_helper 2>/dev/null", 6)
        val left = RootShell.run("pidof bsm_helper").output.trim()
        if (left.isNotEmpty()) {
            RootShell.run("killall -9 bsm_helper 2>/dev/null", 6)
        }
    }
}
