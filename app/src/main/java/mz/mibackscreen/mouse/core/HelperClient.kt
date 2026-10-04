package mz.mibackscreen.mouse.core

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.SystemClock
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

/**
 * 与 root 助手的通信客户端：优先 127.0.0.1 TCP，失败回退抽象 unix socket @bsm-helper，
 * 断开后自动重连，用心跳判断链路是否存活。协议见 README。
 */
class HelperClient(
    private val onFrame: (TouchFrame) -> Unit,
    private val onNotice: (String) -> Unit,
    private val onGeometry: (Int, Int) -> Unit = { _, _ -> },
) : MouseSink {

    data class Point(val id: Int, val x: Int, val y: Int)

    data class TouchFrame(val seq: Long, val timeMs: Long, val points: List<Point>)

    private interface Channel {
        val input: InputStream
        val output: OutputStream
        fun close()
    }

    private class LocalChannel(val socket: LocalSocket) : Channel {
        override val input: InputStream get() = socket.inputStream
        override val output: OutputStream get() = socket.outputStream
        override fun close() {
            runCatching { socket.close() }
        }
    }

    private class TcpChannel(val socket: Socket) : Channel {
        override val input: InputStream get() = socket.inputStream
        override val output: OutputStream get() = socket.outputStream
        override fun close() {
            runCatching { socket.close() }
        }
    }

    @Volatile
    private var channel: Channel? = null
    private var writer: BufferedWriter? = null
    private var worker: Thread? = null
    private var heartbeat: Thread? = null
    private var writerThread: Thread? = null

    /** 待发送指令队列（send 只入队，由写线程真正写出） */
    private val sendQueue = java.util.concurrent.LinkedBlockingQueue<String>(512)

    @Volatile
    private var closed = false

    @Volatile
    var connected: Boolean = false
        private set

    /** 触摸设备的像素范围（助手 H 行上报，默认按背屏） */
    @Volatile
    var srcWidth: Int = DEFAULT_SRC_W
        private set

    @Volatile
    var srcHeight: Int = DEFAULT_SRC_H
        private set

    @Volatile
    private var lastAliveAt: Long = 0L

    private var statFrames = 0
    private var statAt = SystemClock.uptimeMillis()

    /** 启动守护线程：连接 → 读帧 → 断开 → 自动重连，直到 [close]。 */
    fun start() {
        if (worker?.isAlive == true) return
        closed = false
        startWriter()
        worker = Thread(::supervise, "bsm-client").also {
            it.isDaemon = true
            it.start()
        }
    }

    private fun supervise() {
        var backoff = 200L
        while (!closed) {
            val ch = openChannel()
            if (ch == null) {
                if (closed) break
                Logs.d("Client", "连接助手失败，${backoff}ms 后重试")
                sleepQuiet(backoff)
                backoff = (backoff * 2).coerceAtMost(2000L)
                continue
            }
            backoff = 200L
            channel = ch
            synchronized(this) { writer = BufferedWriter(OutputStreamWriter(ch.output)) }
            lastAliveAt = SystemClock.uptimeMillis()
            connected = true
            Logs.d("Client", "已连接助手")
            // 先过鉴权（助手只接受带本会话 token 的客户端），再上报 pid/uid：App 一结束，助手立即退出
            val token = RootHelper.sessionToken
            if (token.isNotEmpty()) send("A $token")
            send("V $PROTO_VER ${android.os.Process.myPid()} ${android.os.Process.myUid()}")
            startHeartbeat()

            readLoop(ch.input)

            connected = false
            stopHeartbeat()
            closeChannel()
            if (!closed) {
                Logs.d("Client", "与助手断开，1 秒后自动重连")
                onNotice("与助手断开，正在重连…")
                sleepQuiet(1000)
            }
        }
        connected = false
        Logs.d("Client", "通信线程退出")
    }

    /** 阻塞式读循环：读到 EOF / 异常即视为断开。 */
    private fun readLoop(input: InputStream) {
        try {
            BufferedReader(InputStreamReader(input)).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    lastAliveAt = SystemClock.uptimeMillis()
                    parseLine(line)
                }
            }
        } catch (t: Throwable) {
            if (!closed) Logs.d("Client", "读取结束: ${t.javaClass.simpleName} ${t.message}")
        }
    }

    private fun openChannel(): Channel? {
        // 1) TCP（本机回环，最稳）
        runCatching {
            val sk = Socket()
            sk.tcpNoDelay = true
            sk.keepAlive = true
            sk.connect(InetSocketAddress("127.0.0.1", RootHelper.TCP_PORT), 500)
            return TcpChannel(sk)
        }

        // 2) 抽象 unix socket
        runCatching {
            val s = LocalSocket()
            s.connect(LocalSocketAddress(RootHelper.ABSTRACT_NAME, LocalSocketAddress.Namespace.ABSTRACT))
            return LocalChannel(s)
        }

        return null
    }

    private fun parseLine(line: String) {
        if (line.isEmpty()) return
        when (line[0]) {
            'K' -> Unit // 就绪标志
            'H' -> {
                val p = line.substring(1).trim().split(' ')
                val ver = p.getOrNull(0)?.toIntOrNull() ?: 0
                val w = p.getOrNull(1)?.toIntOrNull() ?: 0
                val h = p.getOrNull(2)?.toIntOrNull() ?: 0
                if (w > 0 && h > 0) {
                    srcWidth = w
                    srcHeight = h
                }
                Logs.d("Client", "助手协议 v$ver，触摸设备 ${srcWidth}x${srcHeight}")
                if (ver < PROTO_VER) Logs.d("Client", "助手协议较旧(v$ver)，建议重启助手")
                onGeometry(srcWidth, srcHeight)
            }
            'S' -> onNotice(line.substring(1).trim())
            'E' -> Logs.d("Client", "助手错误: ${line.substring(1).trim()}")
            'R' -> Unit // 心跳回显
            'T' -> parseFrame(line)
            else -> Logs.d("Client", "未知行: $line")
        }
    }

    private fun parseFrame(line: String) {
        val parts = line.split(' ')
        if (parts.size < 3) return
        val seq = parts[1].toLongOrNull() ?: return
        val n = parts[2].toIntOrNull() ?: return
        val points = ArrayList<Point>(n)
        var idx = 3
        for (i in 0 until n) {
            val id = parts.getOrNull(idx)?.toIntOrNull() ?: break
            val x = parts.getOrNull(idx + 1)?.toIntOrNull() ?: break
            val y = parts.getOrNull(idx + 2)?.toIntOrNull() ?: break
            points.add(Point(id, x, y))
            idx += 3
        }
        statFrames++
        val now = SystemClock.uptimeMillis()
        if (now - statAt >= 1000) {
            Logs.d("Client", "触点帧 ${statFrames}帧/秒，当前 ${points.size} 指")
            statFrames = 0
            statAt = now
        }
        onFrame(TouchFrame(seq, now, points))
    }

    // ------------------------------------------------------------------ 发送

    /** 只入队，由写线程真正写出（发送方可能是主线程，不能做网络 IO）。 */
    fun send(command: String) {
        if (closed) return
        if (!sendQueue.offer(command)) {
            // 队列满：优先丢一条可丢的移动/滚轮指令，保住按键与 Q 这类控制指令
            val victim = sendQueue.firstOrNull {
                it.isNotEmpty() && (it[0] == 'M' || it[0] == 'W' || it[0] == 'H')
            }
            if (victim != null) sendQueue.remove(victim) else sendQueue.poll()
            sendQueue.offer(command)
        }
    }

    private fun startWriter() {
        stopWriter()
        sendQueue.clear()
        writerThread = Thread({
            while (!closed) {
                val cmd = try {
                    sendQueue.poll(200, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    null
                } ?: continue
                writeNow(cmd)
            }
        }, "bsm-client-writer").also {
            it.isDaemon = true
            it.start()
        }
    }

    private fun stopWriter() {
        writerThread?.interrupt()
        writerThread = null
        sendQueue.clear()
    }

    private fun writeNow(command: String) {
        try {
            synchronized(this) {
                val w = writer ?: return
                w.write(command)
                w.write("\n")
                w.flush()
            }
        } catch (t: Throwable) {
            Logs.d("Client", "发送失败: ${t.javaClass.simpleName} ${t.message}")
        }
    }

    override fun move(dx: Int, dy: Int) {
        if (dx != 0 || dy != 0) send("M $dx $dy")
    }

    override fun button(index: Int, down: Boolean) = send("B $index ${if (down) 1 else 0}")

    override fun wheel(vertical: Int, horizontal: Int) {
        if (vertical != 0) send("W $vertical")
        if (horizontal != 0) send("H $horizontal")
    }

    /** 结束会话：通知助手释放独占并退出，然后停止守护线程。 */
    override fun close() {
        runCatching { send("Q") }
        sleepQuiet(250) // 给写线程把 Q 发出去的时间
        closed = true
        stopHeartbeat()
        stopWriter()
        closeChannel()
        worker?.interrupt()
        worker = null
        connected = false
    }

    // ------------------------------------------------------------------ 心跳 / 资源

    private fun startHeartbeat() {
        stopHeartbeat()
        heartbeat = Thread({
            while (!closed && connected) {
                send("P ${SystemClock.uptimeMillis()}")
                if (SystemClock.uptimeMillis() - lastAliveAt > ALIVE_TIMEOUT_MS) {
                    Logs.d("Client", "心跳超时(${ALIVE_TIMEOUT_MS}ms 无响应)，重连")
                    closeChannel() // 让读循环立即退出，交给守护线程重连
                    return@Thread
                }
                sleepQuiet(HEARTBEAT_MS)
            }
        }, "bsm-client-heartbeat").also {
            it.isDaemon = true
            it.start()
        }
    }

    private fun stopHeartbeat() {
        heartbeat?.interrupt()
        heartbeat = null
    }

    private fun closeChannel() {
        synchronized(this) {
            runCatching { writer?.close() }
            writer = null
        }
        val ch = channel
        channel = null
        runCatching { ch?.close() }
        sendQueue.clear() // 断开后丢弃未发送的陈旧指令
    }

    private fun sleepQuiet(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
        }
    }

    companion object {
        const val PROTO_VER = 3
        private const val DEFAULT_SRC_W = 904
        private const val DEFAULT_SRC_H = 572
        private const val HEARTBEAT_MS = 3000L
        private const val ALIVE_TIMEOUT_MS = 10_000L
    }
}
