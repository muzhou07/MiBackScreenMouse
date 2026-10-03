package mz.mibackscreen.mouse.core

import android.content.Context
import android.content.Intent
import java.lang.ref.WeakReference

/**
 * 触控板会话控制 + 背屏状态只读诊断。
 *
 * 投放只有一步：把带 `miui.rear.policy` 的触控板直接建到背屏（Display 1）。
 * 主屏锁屏时系统会拒绝这类启动，按设计此时就不该有触控板，不做任何补救。
 */
object BackScreenController {

    /** 背屏的 display id。 */
    const val BACK_DISPLAY = 1

    /** dumpsys 匹配用的 Activity 简写。 */
    const val TOUCHPAD_CLASS = "mz.mibackscreen.mouse/.touchpad.TouchpadActivity"

    /** 启动触控板用的全限定类名（字符串，避免 core → touchpad 的包依赖）。 */
    private const val TOUCHPAD_CLASS_NAME = "mz.mibackscreen.mouse.touchpad.TouchpadActivity"

    /** 最近一次投放结果，用于状态卡显示"为什么没到背屏"。 */
    data class LaunchReport(val ok: Boolean, val reason: String, val detail: String = "") {
        fun text(): String = if (detail.isBlank()) reason else "$reason｜${detail.take(60)}"
    }

    @Volatile
    var lastLaunchReport: LaunchReport? = null
        private set

    /** 当前进程里是否有一个由我们主动开启的会话（只存内存，进程被杀即失效，防幽灵触控板）。 */
    @Volatile
    var sessionActive: Boolean = false
        private set

    /** 触控板当前是否可见可交互（由 Activity 的 onStart/onStop 维护）。 */
    @Volatile
    var touchpadVisible: Boolean = false
        private set

    /** 由 TouchpadActivity 在 onStart/onStop 调用。 */
    fun setTouchpadVisible(visible: Boolean) {
        touchpadVisible = visible
        if (!visible) touchpadDisplayId = -1
    }

    /** 触控板 Activity 当前所在的 display（由 Activity 回报；-1 = 未知/未启动）。 */
    @Volatile
    var touchpadDisplayId: Int = -1

    private val lock = Any()
    private var activityRef: WeakReference<android.app.Activity>? = null

    fun attachTouchpadActivity(activity: android.app.Activity) {
        activityRef = WeakReference(activity)
    }

    fun detachTouchpadActivity() {
        activityRef = null
    }

    /** 请求触控板 Activity 自我结束（不杀进程、不动背屏）。 */
    fun requestTouchpadFinish() {
        val activity = activityRef?.get() ?: return
        activity.runOnUiThread { activity.finish() }
    }

    // ---------------------------------------------------------------- 状态查询（只读）

    /** Display 1 的开关状态。 */
    fun backDisplayPowerState(): String =
        RootShell.run("cmd display get-displays | grep -m1 'Display id 1' | grep -o 'state [A-Z_]*'")
            .output.trim()

    fun isTouchpadRunning(): Boolean =
        touchpadVisible || RootShell.run(
            // 只认 resume 中的那条，避免历史记录被误判成运行中
            "dumpsys activity activities | grep -c 'topResumedActivity=.*$TOUCHPAD_CLASS'"
        ).output.lines().firstOrNull()?.trim()?.toIntOrNull()?.let { it > 0 } ?: false

    // ---------------------------------------------------------------- 会话启停

    /** 启动触控板会话：拉起 root 助手 + 打开触控板，返回窗口是否跑起来了。 */
    fun enableTouchpad(context: Context): Boolean = synchronized(lock) {
        val prefs = AppPrefs(context)
        touchpadDisplayId = -1
        lastLaunchReport = null
        // 先置会话标记，再拉起触控板
        sessionActive = true

        // root 助手：uinput 虚拟鼠标 + 独占触摸设备（每次会话换一个新的鉴权串）
        RootHelper.newSessionToken()
        val helperOk = RootHelper.start(context)
        Logs.d("Session", if (helperOk) "root 助手已启动" else "root 助手启动失败（退回窗口触摸模式）")

        val useBack = prefs.showOnBackScreen && RootShell.isRootAvailable()
        if (!prefs.showOnBackScreen) {
            lastLaunchReport = LaunchReport(true, "当前屏幕（未开启背屏投放）")
        } else if (!RootShell.isRootAvailable()) {
            lastLaunchReport = LaunchReport(false, "无 root，无法投放到背屏", "已退回当前屏幕")
        }

        val started = if (useBack) launchOnBackDisplay() else startTouchpadActivity(context)
        if (!started) {
            sessionActive = false
            RootHelper.stop(context) // 窗口没起来就别留着助手
        }
        prefs.touchpadEnabled = started
        Logs.d("Session", "会话启动${if (started) "成功" else "失败"}：${lastLaunchReport?.text() ?: "-"}")
        started
    }

    /** 把触控板直接建到背屏；建不上就在状态卡里报失败（主屏锁屏时系统会拒绝）。 */
    private fun launchOnBackDisplay(): Boolean {
        RootShell.run("input -d $BACK_DISPLAY keyevent KEYCODE_WAKEUP")
        val res = RootShell.run("am start --display $BACK_DISPLAY -n $TOUCHPAD_CLASS")
        if (awaitDisplay(BACK_DISPLAY, 2500)) {
            report(true, "投放成功")
            return true
        }
        report(false, "窗口没到背屏", amError(res.output) ?: res.output.take(80))
        return false
    }

    /** 停止触控板会话：关掉触控板 + 让助手退出（释放触摸独占，避免残留进程耗电）。 */
    fun disableTouchpad(context: Context): Boolean {
        synchronized(lock) {
            AppPrefs(context).touchpadEnabled = false
            sessionActive = false
            lastLaunchReport = null
            touchpadDisplayId = -1
        }
        // 收尾放在锁外：等助手退出时不要卡住并发的状态查询
        requestTouchpadFinish() // 触控板会把 Q 指令发给助手
        awaitHelperExit(1500)
        RootHelper.stop(context) // 兜底清理 su 的子进程并确认无残留
        Logs.d("Session", "触控板会话已停止（助手退出，触摸设备已释放）")
        return true
    }

    /** 等助手进程退出（最多 timeoutMs；收到 Q 后通常几百毫秒内就退了）。 */
    private fun awaitHelperExit(timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (RootShell.run("pidof bsm_helper").output.trim().isEmpty()) return
            try {
                Thread.sleep(150)
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    /** 无会话时清理残留的 root 助手（崩溃/被杀后可能残留，会一直占用触摸设备）。 */
    fun cleanupOrphanHelper(context: Context) {
        AppPrefs(context).touchpadEnabled = false // 顺带复位异常退出留下的陈旧标记
        val pid = RootShell.run("pidof bsm_helper").output.trim()
        if (pid.isEmpty()) return
        Logs.d("Helper", "发现残留助手 pid=$pid，清理")
        RootHelper.killAllHelpers()
        Logs.d("Helper", "清理结果 pidof bsm_helper=[${RootShell.run("pidof bsm_helper").output.trim()}]")
    }

    /** 正常启动触控板 Activity（不指定 display，只在 App 当前屏幕显示）。 */
    private fun startTouchpadActivity(context: Context): Boolean {
        val intent = Intent(Intent.ACTION_MAIN)
            .setClassName(context.packageName, TOUCHPAD_CLASS_NAME)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return try {
            context.startActivity(intent)
            true
        } catch (t: Throwable) {
            Logs.d("Session", "启动触控板失败: ${t.javaClass.simpleName} ${t.message}")
            false
        }
    }

    private fun report(ok: Boolean, reason: String, detail: String = "") {
        lastLaunchReport = LaunchReport(ok, reason, detail)
        Logs.d("BackScreen", "投放结果: ${lastLaunchReport?.text()}")
    }

    /** 轮询等待触控板落在指定屏幕上。 */
    private fun awaitDisplay(expect: Int, timeoutMs: Long): Boolean = await(timeoutMs) { touchpadDisplayId == expect }

    private inline fun await(timeoutMs: Long, predicate: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return true
            try {
                Thread.sleep(120)
            } catch (_: InterruptedException) {
                return predicate()
            }
        }
        return predicate()
    }

    /** 从 am 输出里挑出错误行（Error/Exception/denied）。 */
    private fun amError(output: String): String? =
        output.lines().map { it.trim() }.firstOrNull {
            it.startsWith("Error") || it.contains("Exception") ||
                it.contains("denied", true) || it.contains("not allowed", true)
        }
}
