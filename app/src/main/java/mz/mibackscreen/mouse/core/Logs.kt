package mz.mibackscreen.mouse.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 极简诊断日志（内存环形缓冲，供设置页的“诊断日志”分区展示）。 */
object Logs {

    private const val MAX_LINES = 400

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val buffer = ArrayDeque<String>()

    var lines by mutableStateOf<List<String>>(emptyList())
        private set

    fun d(tag: String, message: String) {
        val line = "${timeFormat.format(Date())} [$tag] $message"
        android.util.Log.i("BackScreenMouse/$tag", message)
        synchronized(buffer) {
            buffer.addLast(line)
            while (buffer.size > MAX_LINES) buffer.removeFirst()
            lines = buffer.toList()
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
