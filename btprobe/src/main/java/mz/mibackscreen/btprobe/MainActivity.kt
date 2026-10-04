package mz.mibackscreen.btprobe

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 蓝牙鼠标测试台（独立 APK，不参与正式 App）。
 *
 * 用途：验证「手机作为 BLE HID 鼠标」在各种主机上的表现。
 * 步骤：① 打开本页（会自动注册 HID）② 在主机（电脑/平板）蓝牙里搜索并连接
 *      ③ 主机连上后本页会自动开始"走正方形"测试 ④ 观察对方指针是否跟着动
 */
class MainActivity : Activity() {

    private lateinit var status: TextView
    private var mouse: BtMouse? = null
    private val ui = Handler(Looper.getMainLooper())
    private var moving = false
    private var step = 0

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    /** 走正方形，方便肉眼确认对方指针确实受我们控制。 */
    private val moveLoop = object : Runnable {
        override fun run() {
            if (!moving) return
            val m = mouse ?: return
            val d = 6
            when ((step / 8) % 4) {
                0 -> m.move(d, 0)
                1 -> m.move(0, d)
                2 -> m.move(-d, 0)
                else -> m.move(0, -d)
            }
            step++
            ui.postDelayed(this, 250)
        }
    }

    /** 每秒看一眼：主机连上了就自动开始测试移动，省得手点。 */
    private val poller = object : Runnable {
        override fun run() {
            val m = mouse
            if (m != null && m.isConnected && !moving) {
                moving = true
                append("主机已连接（${m.hostAddress ?: "?"}）→ 自动开始测试移动")
                ui.post(moveLoop)
            }
            ui.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 48, 24, 24)
        }

        root.addView(TextView(this).apply {
            text = "蓝牙鼠标测试台"
            textSize = 20f
        })
        root.addView(TextView(this).apply {
            text = "① 打开本页（自动注册 HID + 自动开启 BLE 广播）\n" +
                "② 在对方蓝牙里搜索本机名（或 MiBackScreen Mouse）并连接\n" +
                "③ 连上后自动走正方形　④ 看对方指针是否跟着动"
            textSize = 13f
            setPadding(0, 12, 0, 12)
        })

        root.addView(button("注册为鼠标") { startRegister() })
        root.addView(button("开启 BLE 广播（主机才搜得到）") { mouse?.startAdvertise() })
        root.addView(button("停止 BLE 广播") { mouse?.stopAdvertise() })
        root.addView(button("注销（恢复正常蓝牙）") { mouse?.unregister() })
        root.addView(button("断开当前主机") { mouse?.disconnect() })
        root.addView(button("测试移动（正方形）") { moving = true; ui.post(moveLoop) })
        root.addView(button("停止移动") { moving = false; ui.removeCallbacks(moveLoop) })
        root.addView(button("测试单击（左键）") {
            val m = mouse ?: return@button
            m.button(1, true)
            ui.postDelayed({ m.button(1, false) }, 60)
        })
        root.addView(button("测试滚轮（向下 3 格）") { mouse?.wheel(-3, 0) })
        root.addView(button("清屏") { status.text = "" })

        status = TextView(this).apply {
            textSize = 12f
            setTextIsSelectable(true)
        }
        root.addView(ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            addView(status)
        })

        setContentView(root)

        val missing = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            missing += Manifest.permission.BLUETOOTH_CONNECT
        }
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED) {
            missing += Manifest.permission.BLUETOOTH_ADVERTISE
        }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)

        ui.postDelayed({ startRegister() }, 1200)
        ui.postDelayed(poller, 2000)
    }

    private fun button(label: String, onClick: () -> Unit) =
        Button(this).apply {
            text = label
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setOnClickListener { onClick() }
        }

    private fun startRegister() {
        if (mouse == null) {
            mouse = BtMouse(applicationContext) { msg -> runOnUiThread { append(msg) } }
        }
        val bt = (getSystemService(BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)?.adapter
        append("蓝牙 state=${bt?.state} enabled=${bt?.isEnabled} name=${runCatching { bt?.name }.getOrNull()}")
        append("开始注册…")
        mouse?.register()
        // 注册后必须广播：Android 的 HID 设备角色本身不广播，主机否则搜不到
        ui.postDelayed({ mouse?.startAdvertise() }, 1500)
    }

    /** 界面日志 + logcat 双写：logcat 便于用 adb 抓证据。 */
    private fun append(msg: String) {
        Log.i("BtProbe", msg)
        status.append("${timeFormat.format(Date())}  $msg\n")
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        append("权限授权结果=${grantResults.joinToString()}")
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            startRegister()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        moving = false
        ui.removeCallbacks(moveLoop)
        ui.removeCallbacks(poller)
        mouse?.close()
        mouse = null
    }
}

