package mz.mibackscreen.mouse.core

import android.content.Context
import android.os.Handler
import android.os.Looper

/**
 * 「蓝牙鼠标模式」的进程内唯一持有者：打开=注册 HID + 广播，关闭=停广播 + 注销。
 *
 * 触控板会话只通过 [sink] 取输出目标、不负责注册，避免两处注册互相顶掉。
 */
object BtMouseMode {

    private var mouse: BtHidMouse? = null
    private val listeners = mutableSetOf<(String) -> Unit>()

    val isOn: Boolean get() = mouse != null
    val isAdvertising: Boolean get() = mouse?.isAdvertising == true
    val isConnected: Boolean get() = mouse?.isConnected == true
    val hostAddress: String? get() = mouse?.hostAddress

    /** 界面用：打码后的主机地址 */
    val hostAddressMasked: String? get() = mouse?.hostAddressMasked

    fun addListener(listener: (String) -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: (String) -> Unit) {
        listeners -= listener
    }

    /** 打开模式：注册 HID，等 GATT 挂上后再广播（调用前必须已有蓝牙权限）。 */
    fun turnOn(context: Context) {
        val app = context.applicationContext
        val m = mouse ?: BtHidMouse(app) { msg -> notify(msg) }.also { mouse = it }
        applyFeelTo(m, app)
        m.register()
        Handler(Looper.getMainLooper()).postDelayed({
            if (mouse === m) m.startAdvertising()
        }, 1200)
    }

    /** 关闭模式：停止广播 → 注销，手机蓝牙恢复普通状态。 */
    fun turnOff() {
        val m = mouse ?: return
        mouse = null
        m.close()
    }

    /** 主动断开当前主机（配对信息保留）。 */
    fun disconnectHost() {
        mouse?.disconnect()
    }

    /** 触控板会话取输出目标；模式没开时返回 null（走本机 uinput 那条路）。 */
    fun sink(): MouseSink? = mouse

    /** 手感参数改动后立即生效（界面拖动滑块时调用）。 */
    fun applyFeel(context: Context) {
        val m = mouse ?: return
        applyFeelTo(m, context.applicationContext)
    }

    private fun applyFeelTo(m: BtHidMouse, app: Context) {
        val prefs = AppPrefs(app)
        m.flushIntervalMs = prefs.btFlushMs.coerceIn(2, 40).toLong()
        m.smoothPercent = prefs.btSmooth.coerceIn(0, 90)
    }

    private fun notify(msg: String) {
        Logs.d("BtMouse", msg)
        // 回到主线程再通知界面，Compose 状态更新才安全
        Handler(Looper.getMainLooper()).post {
            listeners.toList().forEach { it(msg) }
        }
    }
}
