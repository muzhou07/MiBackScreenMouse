package mz.mibackscreen.mouse.core

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 极简诊断日志（内存环形缓冲 + 落盘，供设置页展示、也便于事后用 adb 排查）。 */
object Logs {

    private const val MAX_LINES = 400

    /** 落盘上限：超过就清空重写，避免无限增长。 */
    private const val MAX_FILE_BYTES = 512L * 1024L

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val buffer = ArrayDeque<String>()

    /** 落盘文件（外部私有目录，adb 可直接读取；系统日志缓冲区刷掉后仍能查）。 */
    private var file: File? = null

    var lines by mutableStateOf<List<String>>(emptyList())
        private set

    /** 挂上落盘文件；重复调用无副作用。 */
    fun attachFile(context: Context) {
        if (file != null) return
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        file = File(dir, "bsm-log.txt")
    }

    fun d(tag: String, message: String) {
        val line = "${timeFormat.format(Date())} [$tag] $message"
        android.util.Log.i("BackScreenMouse/$tag", message)
        synchronized(buffer) {
            buffer.addLast(line)
            while (buffer.size > MAX_LINES) buffer.removeFirst()
            lines = buffer.toList()
        }
        val f = file ?: return
        runCatching {
            if (f.length() > MAX_FILE_BYTES) f.writeText("")
            f.appendText(line + "\n")
        }
    }

    fun text(): String = synchronized(buffer) { lines.joinToString("\n") }

    fun clear() {
        synchronized(buffer) {
            buffer.clear()
            lines = emptyList()
        }
    }
}
