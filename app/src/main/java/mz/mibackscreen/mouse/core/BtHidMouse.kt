package mz.mibackscreen.mouse.core

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.os.ParcelUuid
import android.os.SystemClock
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 手机作为 BLE HID 鼠标（HOGP 外设）：注册后本机蓝牙变成一只可被主机搜索/连接的鼠标，
 * [move]/[button]/[wheel] 会转成 5 字节鼠标报告发给已连接的主机。
 *
 * 主机主动来连手机，手机不能主动连主机；换主机时由对方在自己的蓝牙里配对。
 */
class BtHidMouse(
    private val context: Context,
    /** 状态变化回调（后台线程）：注册结果、连接变化、错误，交给调用方写日志/状态卡。 */
    private val onState: (String) -> Unit,
) : MouseSink {

    companion object {
        private const val REPORT_ID = 1

        /** 报告节流窗口：8ms（约 125 次/秒）。 */
        private const val FLUSH_INTERVAL_MS = 8L

        /** 单窗口最多发几条（快速划动时拆成多条，避免单条 ±127 溢出丢位移）。 */
        private const val MAX_REPORTS_PER_FLUSH = 3

        /** HID 服务 UUID（0x1812）。 */
        private const val HID_SERVICE_UUID = "00001812-0000-1000-8000-00805f9b34fb"

        /** HOGP 标准鼠标报告描述符：5 键 + X/Y + 滚轮 + AC Pan，报告 5 字节。 */
        private const val DESCRIPTOR =
            "05010902A10185010901A10005091901290515002501950575018102950175038103" +
                "05010930093109381581257F750895038106050C0A38021581257F750895018106C0C0"

        private fun toBytes(hex: String): ByteArray =
            hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    private val descriptor = toBytes(DESCRIPTOR)

    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private val executor = Executors.newSingleThreadExecutor()

    private var hid: BluetoothHidDevice? = null
    private var device: BluetoothDevice? = null
    private var buttons = 0

    val isRegistered: Boolean get() = hid != null
    val isConnected: Boolean get() = device != null
    val hostAddress: String? get() = device?.address

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            // pluggedDevice：注册时主机已经连着的情况（此时不会再有 onConnectionStateChanged）
            if (registered) {
                if (pluggedDevice != null) device = pluggedDevice
            } else {
                device = null
            }
            onState("注册状态变化 registered=$registered plug=${pluggedDevice?.address ?: "-"} 已连接=${device != null}")
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            this@BtHidMouse.device = if (state == BluetoothProfile.STATE_CONNECTED) device else null
            onState("主机连接状态 ${device.address} state=$state")
        }

        override fun onGetReport(device: BluetoothDevice, type: Byte, id: Byte, bufferSize: Int) {
            // 主机来读报告：回一份空报告，不回的话部分主机会超时报错
            hid?.replyReport(device, type, id, ByteArray(bufferSize))
            onState("onGetReport type=$type id=$id size=$bufferSize")
        }

        override fun onSetReport(device: BluetoothDevice, type: Byte, id: Byte, data: ByteArray) {
            hid?.reportError(device, BluetoothHidDevice.ERROR_RSP_SUCCESS)
            onState("onSetReport type=$type id=$id len=${data.size}")
        }

        override fun onSetProtocol(device: BluetoothDevice, protocol: Byte) {
            hid?.reportError(device, BluetoothHidDevice.ERROR_RSP_SUCCESS)
            onState("onSetProtocol protocol=$protocol")
        }
    }

    /** 注册 HID 设备（异步）：成功后本机蓝牙即可被主机搜索/连接。 */
    fun register(): Boolean {
        val a = adapter ?: run {
            onState("没有蓝牙适配器")
            return false
        }
        if (!a.isEnabled) {
            onState("蓝牙未开启")
            return false
        }
        if (hid != null) {
            onState("已经注册过了")
            return true
        }
        val ok = a.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                if (profile != BluetoothProfile.HID_DEVICE) return
                val d = proxy as BluetoothHidDevice
                hid = d
                val sdp = BluetoothHidDeviceAppSdpSettings(
                    "MiBackScreen Mouse",
                    "Back screen touchpad mouse",
                    "mz.mibackscreen.mouse",
                    BluetoothHidDevice.SUBCLASS1_MOUSE,
                    descriptor,
                )
                val ret = d.registerApp(sdp, null, null, executor, callback)
                onState("registerApp 调用返回=$ret")
            }

            override fun onServiceDisconnected(profile: Int) {
                if (profile != BluetoothProfile.HID_DEVICE) return
                hid = null
                device = null
                onState("HID 服务断开")
            }
        }, BluetoothProfile.HID_DEVICE)
        onState("getProfileProxy(HID_DEVICE)=$ok")
        return ok
    }

    /** 注销：手机蓝牙恢复普通状态，已配对信息保留在系统里。 */
    fun unregister() {
        val d = hid ?: return
        runCatching { d.unregisterApp() }
        val a = adapter
        if (a != null) runCatching { a.closeProfileProxy(BluetoothProfile.HID_DEVICE, d) }
        hid = null
        device = null
        buttons = 0
        clearPending()
        onState("已注销 HID")
    }

    /** 断开当前主机连接（公开 API；配对信息保留）。 */
    fun disconnect() {
        val d = device ?: return
        hid?.disconnect(d)
        onState("已请求断开 ${d.address}")
    }

    override fun move(dx: Int, dy: Int) {
        if (dx == 0 && dy == 0) return
        queue(dx, dy, 0, 0, immediate = false)
    }

    override fun button(index: Int, down: Boolean) {
        if (index !in 1..3) return
        val bit = 1 shl (index - 1)
        buttons = if (down) buttons or bit else buttons and bit.inv()
        // 按键要立刻生效，不进节流窗口（会和待发位移一起发出去）
        queue(0, 0, 0, 0, immediate = true)
    }

    override fun wheel(vertical: Int, horizontal: Int) {
        if (vertical == 0 && horizontal == 0) return
        queue(0, 0, vertical, horizontal, immediate = true)
    }

    // ---------------------------------------------------------------- BLE 广播

    private var advertiser: android.bluetooth.le.BluetoothLeAdvertiser? = null
    private var advCallback: android.bluetooth.le.AdvertiseCallback? = null

    var isAdvertising = false
        private set

    /**
     * 自己广播可连接的 BLE 广告（HID UUID + 设备名）。
     *
     * Android 的 HID 设备角色不会自动广播，不广播主机根本搜不到我们。
     */
    fun startAdvertising() {
        val a = adapter ?: return
        if (advCallback != null) {
            onState("广播已在运行")
            return
        }
        val adv = a.bluetoothLeAdvertiser ?: run {
            onState("设备不支持 BLE 广播")
            return
        }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .build()
        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(UUID.fromString(HID_SERVICE_UUID)))
            .setIncludeDeviceName(true)
            .build()
        val cb = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                isAdvertising = true
                onState("BLE 广播已开启（可被搜索/连接）")
            }

            override fun onStartFailure(errorCode: Int) {
                isAdvertising = false
                onState("BLE 广播失败 errorCode=$errorCode")
            }
        }
        advertiser = adv
        advCallback = cb
        adv.startAdvertising(settings, data, cb)
    }

    fun stopAdvertising() {
        val adv = advertiser ?: return
        val cb = advCallback ?: return
        runCatching { adv.stopAdvertising(cb) }
        advertiser = null
        advCallback = null
        isAdvertising = false
        onState("BLE 广播已停止")
    }

    /** 结束会话：先停广播再注销，顺序不能反。 */
    override fun close() {
        flushPending()
        stopAdvertising()
        unregister()
        flushExecutor.shutdown()
        executor.shutdown()
    }

    // ---------------------------------------------------------------- 报告节流

    private val flushLock = Any()
    private val flushExecutor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "bt-hid-flush").apply { isDaemon = true }
    }

    /** 节流窗口（毫秒），界面可调：越小越跟手，越大越稳。 */
    @Volatile
    var flushIntervalMs: Long = FLUSH_INTERVAL_MS

    /** 位移平滑强度（0=关，0~90）：只改"这一步发多少"，没发的留到下一步，总位移不丢。 */
    @Volatile
    var smoothPercent: Int = 0

    private var lastOutX = 0f
    private var lastOutY = 0f

    private var pendingDx = 0
    private var pendingDy = 0
    private var pendingWheel = 0
    private var pendingPan = 0
    private var flushScheduled = false

    private var sentInWindow = 0
    private var failedInWindow = 0
    private var windowStartAt = 0L

    /**
     * 把窗口内的位移合并成尽量少的报告。
     *
     * 触控板每帧都产生位移（100Hz 上下），但 BLE 每秒能发的报告有限，逐帧写会一顿一顿。
     */
    private fun queue(dx: Int, dy: Int, wheel: Int, pan: Int, immediate: Boolean) {
        synchronized(flushLock) {
            pendingDx += dx
            pendingDy += dy
            pendingWheel += wheel
            pendingPan += pan
            if (immediate || !flushScheduled) {
                flushScheduled = true
                flushExecutor.schedule(
                    { flushPending() },
                    if (immediate) 0L else flushIntervalMs,
                    TimeUnit.MILLISECONDS,
                )
            }
        }
    }

    private fun flushPending() {
        val dx: Int
        val dy: Int
        val wheel: Int
        val pan: Int
        val btn: Int
        synchronized(flushLock) {
            dx = pendingDx
            dy = pendingDy
            wheel = pendingWheel
            pan = pendingPan
            pendingDx = 0
            pendingDy = 0
            pendingWheel = 0
            pendingPan = 0
            btn = buttons
            flushScheduled = false
        }

        // 平滑：这一步只发一部分，没发的（平滑扣下的 + 超 ±127 剩下的）留到下一轮
        val a = smoothPercent.coerceIn(0, 90) / 100f
        var outX = dx
        var outY = dy
        if (a > 0f) {
            outX = (dx * (1f - a) + lastOutX * a).toInt()
            outY = (dy * (1f - a) + lastOutY * a).toInt()
            lastOutX = outX.toFloat()
            lastOutY = outY.toFloat()
        }

        var restX = outX
        var restY = outY
        var restW = wheel
        var restP = pan
        var guard = 0
        do {
            val sx = restX.coerceIn(-127, 127)
            val sy = restY.coerceIn(-127, 127)
            val sw = restW.coerceIn(-127, 127)
            val sp = restP.coerceIn(-127, 127)
            sendReportNow(btn, sx, sy, sw, sp)
            restX -= sx
            restY -= sy
            restW -= sw
            restP -= sp
            guard++
        } while (guard < MAX_REPORTS_PER_FLUSH && (restX != 0 || restY != 0 || restW != 0 || restP != 0))

        // 余量（平滑扣下的 + 超 ±127 剩下的）留到下一轮，绝不丢位移
        val holdX = (dx - outX) + restX
        val holdY = (dy - outY) + restY
        val holdW = restW
        val holdP = restP
        if (holdX != 0 || holdY != 0 || holdW != 0 || holdP != 0) {
            synchronized(flushLock) {
                pendingDx += holdX
                pendingDy += holdY
                pendingWheel += holdW
                pendingPan += holdP
                flushScheduled = true
                flushExecutor.schedule({ flushPending() }, flushIntervalMs, TimeUnit.MILLISECONDS)
            }
        }
    }

    private fun sendReportNow(btn: Int, dx: Int, dy: Int, wheel: Int, pan: Int) {
        val d = device ?: return
        val payload = byteArrayOf(
            btn.toByte(),
            dx.toByte(),
            dy.toByte(),
            wheel.toByte(),
            pan.toByte(),
        )
        val ok = hid?.sendReport(d, REPORT_ID, payload) ?: false
        sentInWindow++
        if (!ok) failedInWindow++
        logRate()
    }

    /** 每 10 秒报一次实际报告速率，用来判断瓶颈。 */
    private fun logRate() {
        val now = SystemClock.uptimeMillis()
        if (windowStartAt == 0L) {
            windowStartAt = now
            return
        }
        val elapsed = now - windowStartAt
        if (elapsed < 10_000) return
        val rate = sentInWindow * 1000L / elapsed
        onState("报告速率约 ${rate}/秒（窗口内 $sentInWindow 条，失败 $failedInWindow 条）")
        sentInWindow = 0
        failedInWindow = 0
        windowStartAt = now
    }

    private fun clearPending() {
        synchronized(flushLock) {
            pendingDx = 0
            pendingDy = 0
            pendingWheel = 0
            pendingPan = 0
            lastOutX = 0f
            lastOutY = 0f
        }
    }
}
