package mz.mibackscreen.mouse.touchpad

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Point
import android.graphics.drawable.ColorDrawable
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowManager
import mz.mibackscreen.mouse.core.AppPrefs
import mz.mibackscreen.mouse.core.BackScreenController
import mz.mibackscreen.mouse.core.BtMouseMode
import mz.mibackscreen.mouse.core.HelperClient
import mz.mibackscreen.mouse.core.Logs
import mz.mibackscreen.mouse.core.MouseSink

/**
 * 背屏触控板：纯黑背景 + 触点指示。
 *
 * 会话期间助手 EVIOCGRAB 独占触摸设备，系统手势收不到事件，触点与手势全部由助手数据驱动。
 * 一不可见（锁屏 / 被官方副屏顶掉 / 被切走）就结束会话——锁屏即视为关闭触控板。
 * 不使用 setShowWhenLocked / setTurnScreenOn：覆盖锁屏会干扰 HyperOS 的 SystemUI。
 */
class TouchpadActivity : Activity() {

    private lateinit var indicator: TouchIndicatorView
    private var lastLogAt = 0L
    private var client: HelperClient? = null
    private var engine: TouchpadGestureEngine? = null

    /** 本次实例是否因为“没有活动会话”而自我结束（此时不能再去停会话）。 */
    private var selfFinished = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 无活动会话（系统恢复出来的实例）直接结束，防幽灵触控板
        if (!BackScreenController.sessionActive) {
            selfFinished = true
            Logs.d("Touchpad", "无活动会话，直接结束（防止幽灵触控板）")
            BackScreenController.setTouchpadVisible(false)
            finish()
            return
        }

        // 纯黑背景：白色触点最清晰
        window.setBackgroundDrawable(ColorDrawable(Color.BLACK))
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        // 不用 setShowWhenLocked / setTurnScreenOn：覆盖锁屏会干扰 HyperOS 的 SystemUI

        indicator = TouchIndicatorView(this)
        setContentView(indicator, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        BackScreenController.attachTouchpadActivity(this)

        // 手感设置（主界面可调，进入触控板时生效）
        val prefs = AppPrefs(this)
        val engineCfg = TouchpadGestureEngine.Config(
            sensitivity = prefs.sensitivity,
            accelEnabled = prefs.accelEnabled,
            naturalScroll = prefs.naturalScroll,
            longPressMs = prefs.longPressMs.toLong(),
            reverseX = prefs.reverseX,
            reverseY = prefs.reverseY,
        )
        // 连接 root 助手：触点帧 → 触点显示 + 手势 → uinput 鼠标（自带断线重连）
        val c = HelperClient(
            this,
            onFrame = { frame ->
                engine?.onFrame(frame)
                indicator.post { syncIndicator(frame) }
            },
            onNotice = { msg -> Logs.d("Helper", msg) },
            onGeometry = { w, h -> engine?.setSource(w, h) },
        )
        client = c
        // 蓝牙鼠标模式开着时，手势输出发给远端主机（本机 uinput 不动）；否则照旧走助手
        val sink: MouseSink = BtMouseMode.sink() ?: c
        engine = TouchpadGestureEngine(sink, engineCfg, Handler(Looper.getMainLooper())).apply {
            setSource(c.srcWidth, c.srcHeight)
            // 光标要落在主屏上，所以目标尺寸取“主屏真实分辨率”，与本 Activity 所在屏幕无关
            val primary = primaryDisplaySize()
            setTarget(primary.first, primary.second)
            Logs.d(
                "Touchpad",
                "映射 触摸设备 ${c.srcWidth}x${c.srcHeight} → 主屏 ${primary.first}x${primary.second}，" +
                    "灵敏度=${engineCfg.sensitivity} 加速=${engineCfg.accelEnabled} " +
                    "自然滚动=${engineCfg.naturalScroll} 左右反向=${engineCfg.reverseX} 上下反向=${engineCfg.reverseY} " +
                    "输出=${if (sink === c) "本机" else "蓝牙"}"
            )
        }
        c.start()

        val display = this.display
        Logs.d(
            "Touchpad",
            "onCreate displayId=${display?.displayId} mode=${display?.mode?.physicalWidth}x${display?.mode?.physicalHeight} density=${resources.displayMetrics.densityDpi}"
        )
    }

    /** 把助手的触点帧同步到指示器（真实多指数据）。 */
    private fun syncIndicator(frame: HelperClient.TouchFrame) {
        val n = frame.points.size
        val ids = IntArray(n)
        val xs = FloatArray(n)
        val ys = FloatArray(n)
        // 触点坐标（触摸设备像素）→ 窗口坐标的等比映射
        val winW = if (indicator.width > 0) indicator.width else resources.displayMetrics.widthPixels
        val winH = if (indicator.height > 0) indicator.height else resources.displayMetrics.heightPixels
        val sw = (client?.srcWidth ?: 0).takeIf { it > 0 }?.toFloat() ?: winW.toFloat()
        val sh = (client?.srcHeight ?: 0).takeIf { it > 0 }?.toFloat() ?: winH.toFloat()
        val kx = if (sw > 0f) winW / sw else 1f
        val ky = if (sh > 0f) winH / sh else 1f
        for (i in 0 until n) {
            ids[i] = frame.points[i].id
            xs[i] = frame.points[i].x * kx
            ys[i] = frame.points[i].y * ky
        }
        indicator.syncFrame(ids, xs, ys)
    }

    /** 主屏（Display.DEFAULT_DISPLAY）真实分辨率：uinput 光标最终落在这里。 */
    private fun primaryDisplaySize(): Pair<Int, Int> {
        return try {
            val dm = getSystemService(DisplayManager::class.java)
            val display = dm?.getDisplay(Display.DEFAULT_DISPLAY)
            val p = Point()
            @Suppress("DEPRECATION")
            display?.getRealSize(p)
            if (p.x > 0 && p.y > 0) p.x to p.y else 1220 to 2656
        } catch (t: Throwable) {
            Logs.d("Touchpad", "取主屏尺寸失败: ${t.message}")
            1220 to 2656
        }
    }

    override fun onResume() {
        super.onResume()
        BackScreenController.setTouchpadVisible(true)
        BackScreenController.touchpadDisplayId = display?.displayId ?: -1
        Logs.d(
            "Touchpad",
            "onResume displayId=${display?.displayId} " +
                "screen=${resources.displayMetrics.widthPixels}x${resources.displayMetrics.heightPixels}"
        )
    }

    override fun onStop() {
        super.onStop()
        // 不可见（锁屏 / 被官方副屏顶掉 / 被切走）就算结束了：状态归位并直接收尾
        BackScreenController.setTouchpadVisible(false)
        if (!isFinishing) {
            Logs.d("Touchpad", "触控板不可见，结束会话")
            Thread { BackScreenController.disableTouchpad(applicationContext) }.start()
        }
    }

    override fun onStart() {
        super.onStart()
        BackScreenController.setTouchpadVisible(true)
        BackScreenController.touchpadDisplayId = display?.displayId ?: -1
    }

    /** 屏幕/尺寸变化时不一定走 onResume，这里补报一次所在屏。 */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        BackScreenController.touchpadDisplayId = display?.displayId ?: -1
        Logs.d("Touchpad", "onConfigurationChanged displayId=${display?.displayId}")
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        handleTouch(ev)
        return true
    }

    private fun handleTouch(ev: MotionEvent) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val id = ev.getPointerId(0)
                indicator.onPointerDown(id, ev.getX(0), ev.getY(0))
                Logs.d("Touchpad", "DOWN id=$id (${ev.getX(0).toInt()}, ${ev.getY(0).toInt()})".takeIf { AppPrefs(this).diagMode } ?: "DOWN id=$id")
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                val index = ev.actionIndex
                val id = ev.getPointerId(index)
                indicator.onPointerDown(id, ev.getX(index), ev.getY(index))
                Logs.d("Touchpad", "POINTER_DOWN id=$id count=${ev.pointerCount}")
            }

            MotionEvent.ACTION_MOVE -> {
                for (index in 0 until ev.pointerCount) {
                    indicator.onPointerMove(ev.getPointerId(index), ev.getX(index), ev.getY(index))
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                val index = ev.actionIndex
                val id = ev.getPointerId(index)
                indicator.onPointerUp(id, ev.getX(index), ev.getY(index))
                Logs.d("Touchpad", "POINTER_UP id=$id count=${ev.pointerCount}")
            }

            MotionEvent.ACTION_UP -> {
                val id = ev.getPointerId(0)
                indicator.onPointerUp(id, ev.getX(0), ev.getY(0))
                Logs.d("Touchpad", "UP (${ev.getX(0).toInt()}, ${ev.getY(0).toInt()})".takeIf { AppPrefs(this).diagMode } ?: "UP")
            }

            MotionEvent.ACTION_CANCEL -> {
                indicator.reset()
                Logs.d("Touchpad", "CANCEL")
            }

            else -> {
                for (index in 0 until ev.pointerCount) {
                    indicator.onPointerMove(ev.getPointerId(index), ev.getX(index), ev.getY(index))
                }
            }
        }

        // 移动事件的日志节流，避免刷屏
        val now = SystemClock.uptimeMillis()
        if (ev.actionMasked == MotionEvent.ACTION_MOVE && now - lastLogAt > 2000) {
            lastLogAt = now
            if (AppPrefs(this).diagMode) {
                val sb = StringBuilder()
                for (index in 0 until ev.pointerCount) {
                    sb.append("p$index=(${ev.getX(index).toInt()},${ev.getY(index).toInt()}) ")
                }
                Logs.d("Touchpad", "MOVE $sb")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        engine?.reset()
        client?.close()
        engine = null
        client = null
        // 幽灵实例会在 onCreate 里提前 finish（那时 indicator 还没建），这里必须判空，
        // 否则 onDestroy 抛 UninitializedPropertyAccessException 把 App 崩掉
        if (this::indicator.isInitialized) indicator.reset()
        BackScreenController.setTouchpadVisible(false)
        BackScreenController.detachTouchpadActivity()
        Logs.d("Touchpad", "onDestroy isFinishing=$isFinishing")

        // 用户主动离开（返回键/上滑）：结束会话，释放助手与触摸独占
        if (isFinishing && !selfFinished && AppPrefs(this).touchpadEnabled) {
            Thread { BackScreenController.disableTouchpad(applicationContext) }.start()
        }
    }
}
