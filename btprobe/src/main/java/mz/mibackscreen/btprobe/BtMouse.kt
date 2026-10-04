package mz.mibackscreen.btprobe

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
import java.util.UUID
import java.util.concurrent.Executors

/**
 * 手机作为 BLE HID 鼠标（HOGP 外设）—— 独立测试模块里的核心。
 *
 * 注册成功后本机蓝牙变成一只「可被主机搜索/连接的鼠标」；主机连上后，
 * [move] / [button] / [wheel] 会转成 5 字节鼠标报告（按键 + X/Y + 滚轮 + AC Pan）发给对方。
 *
 * 主机主动来连手机，手机不能主动连主机；换主机时由对方在自己的蓝牙里配对。
 * 这里比正式版多一份「发送计数」诊断：用来判断报告是被接受还是被对方忽略。
 */
class BtMouse(
    private val context: Context,
    /** 状态变化（后台线程）：注册结果、连接变化、诊断计数，交给界面写日志。 */
    private val onState: (String) -> Unit,
) {

    companion object {
        private const val REPORT_ID = 1

        /** HID 服务 UUID（0x1812）：主机靠它认出「这是一只鼠标」。 */
        private const val HID_SERVICE_UUID = "00001812-0000-1000-8000-00805f9b34fb"

        /**
         * HID over GATT 的标准鼠标报告描述符：
         * 5 个按键位（补 3 位常量）+ X/Y/滚轮（相对、-127..127）+ AC Pan，报告共 5 字节。
         */
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

    /** 诊断：发送次数与失败次数。 */
    var sentCount = 0
        private set
    var failCount = 0
        private set

    val isRegistered: Boolean get() = hid != null
    val isConnected: Boolean get() = device != null
    val hostAddress: String? get() = device?.address

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            // pluggedDevice：注册时主机已经连着的情况（这时不会再有 onConnectionStateChanged）
            if (registered) {
                if (pluggedDevice != null) device = pluggedDevice
            } else {
                device = null
            }
            onState("注册状态变化 registered=$registered plug=${pluggedDevice?.address ?: "-"} 已连接=${device != null}")
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            this@BtMouse.device = if (state == BluetoothProfile.STATE_CONNECTED) device else null
            onState("主机连接状态 ${device.address} state=$state")
        }

        override fun onGetReport(device: BluetoothDevice, type: Byte, id: Byte, bufferSize: Int) {
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
                    "mz.mibackscreen.btprobe",
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
        adapter?.let { runCatching { it.closeProfileProxy(BluetoothProfile.HID_DEVICE, d) } }
        hid = null
        device = null
        buttons = 0
        onState("已注销 HID")
    }

    /** 断开当前主机（配对信息保留）。 */
    fun disconnect() {
        val d = device ?: return
        hid?.disconnect(d)
        onState("已请求断开 ${d.address}")
    }

    fun move(dx: Int, dy: Int) {
        if (dx == 0 && dy == 0) return
        send(dx, dy, 0, 0)
    }

    fun button(index: Int, down: Boolean) {
        if (index !in 1..3) return
        val bit = 1 shl (index - 1)
        buttons = if (down) buttons or bit else buttons and bit.inv()
        send(0, 0, 0, 0)
    }

    fun wheel(vertical: Int, horizontal: Int) {
        if (vertical == 0 && horizontal == 0) return
        send(0, 0, vertical, horizontal)
    }

    fun close() {
        stopAdvertise()
        unregister()
        executor.shutdown()
    }

    // ---------------------------------------------------------------- BLE 广播

    private var advertiser: android.bluetooth.le.BluetoothLeAdvertiser? = null
    private var advCallback: AdvertiseCallback? = null

    var isAdvertising = false
        private set

    /**
     * 自己广播一条可连接的 BLE 广告：HID 服务 UUID + 设备名。
     *
     * Android 的 HID 设备角色**不会自动广播**，所以新主机根本搜不到我们（实测确认）。
     * 广播出去后，主机才能在"搜索设备"里看到它，并按 BLE 鼠标来连接。
     */
    fun startAdvertise() {
        val a = adapter ?: return
        if (advCallback != null) {
            onState("广播已在运行")
            return
        }
        onState("支持多广播=${a.isMultipleAdvertisementSupported}")
        val adv = a.bluetoothLeAdvertiser ?: run {
            onState("没有 BLE 广播器（设备不支持）")
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
                onState("BLE 广播已开启：HID UUID + 设备名，可连接")
            }

            override fun onStartFailure(errorCode: Int) {
                isAdvertising = false
                onState("BLE 广播失败 errorCode=$errorCode")
            }
        }
        advertiser = adv
        advCallback = cb
        adv.startAdvertising(settings, data, cb)
        onState("已请求开启 BLE 广播（结果见上/下一行）")
    }

    fun stopAdvertise() {
        val adv = advertiser ?: return
        val cb = advCallback ?: return
        runCatching { adv.stopAdvertising(cb) }
        advertiser = null
        advCallback = null
        isAdvertising = false
        onState("BLE 广播已停止")
    }

    private fun send(dx: Int, dy: Int, wheel: Int, pan: Int) {
        val d = device ?: return
        val payload = byteArrayOf(
            buttons.toByte(),
            dx.coerceIn(-127, 127).toByte(),
            dy.coerceIn(-127, 127).toByte(),
            wheel.coerceIn(-127, 127).toByte(),
            pan.coerceIn(-127, 127).toByte(),
        )
        val ok = hid?.sendReport(d, REPORT_ID, payload) ?: false
        sentCount++
        if (!ok) failCount++
        // 每 50 次报告打一条：能区分「报告没被接受」与「对方收到却不认」
        if (sentCount % 50 == 1) onState("已发报告 $sentCount 次，失败 $failCount 次")
    }
}

