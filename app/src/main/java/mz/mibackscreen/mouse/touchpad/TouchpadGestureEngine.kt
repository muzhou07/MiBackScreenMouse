package mz.mibackscreen.mouse.touchpad

import kotlin.math.hypot
import kotlin.math.min
import mz.mibackscreen.mouse.core.HelperClient
import mz.mibackscreen.mouse.core.Logs
import mz.mibackscreen.mouse.core.MouseSink

/**
 * 触点帧 → 鼠标指令。
 *
 * 手势：单指滑动=移动，单指轻点=左键，单指长按=按住右键，双击后拖动=左键拖拽，
 * 双指滑动=滚轮，双指轻点=右键，三指轻点=中键。
 */
class TouchpadGestureEngine(
    private val output: MouseSink,
    val config: Config = Config(),
    /** 静止长按用定时器判定：不依赖帧到达（手指按住不动时帧率很低） */
    private val handler: android.os.Handler? = null,
) {

    data class Config(
        /** 灵敏度倍率 */
        var sensitivity: Float = 1.3f,
        /** 是否开启速度加速（快速滑动 → 更大位移） */
        var accelEnabled: Boolean = true,
        var accelGain: Float = 1.5f,
        var accelMax: Float = 3.0f,
        /** true = 自然滚动（双指下滑 → 页面向下） */
        var naturalScroll: Boolean = true,
        var longPressMs: Long = 500L,
        var tapMaxMs: Long = 260L,
        /** 左右反向：默认开（符合背屏持握习惯） */
        var reverseX: Boolean = true,
        /** 上下反向：默认关 */
        var reverseY: Boolean = false,
        /** 轻点判定：起点漂移超过这个像素就算拖动 */
        var slopPx: Float = 10f,
        var doubleTapGapMs: Long = 320L,
        var swapButtons: Boolean = false,
        /** 双指移动多少像素 → 一格滚轮 */
        var wheelStepPx: Float = 16f,
    )

    /* 触摸设备像素范围（背屏 904x572） */
    private var srcW = 904f
    private var srcH = 572f

    /* 目标屏幕像素范围（主屏，Activity 传入） */
    private var dstW = 1220f
    private var dstH = 2656f

    private var prev = HashMap<Int, HelperClient.Point>()

    private var seqStartMs = 0L
    private var lastFrameMs = 0L
    private var maxPointers = 0
    private var moved = false
    private var longPressFired = false
    private var rightHolding = false
    private var dragHolding = false
    private var lastTapAt = 0L
    private var tapEmittedForSeq = false

    /* 起点（用于轻点/拖动的漂移判定） */
    private var startX = 0f
    private var startY = 0f
    private var centroid0X = Float.NaN
    private var centroid0Y = Float.NaN

    /* 位移/滚轮余数累加器 */
    private var moveAccX = 0f
    private var moveAccY = 0f
    /** 已换算到“输出(缩放后)坐标”的待发送余数 */
    private var moveOutX = 0f
    private var moveOutY = 0f
    private var scrollAccX = 0f
    private var scrollAccY = 0f

    /** 是否存在进行中的触点序列（定时器线程可见） */
    @Volatile
    private var sequenceActive = false

    private val lock = Any()

    private val longPressRunnable = Runnable { maybeLongPress() }

    fun setSource(w: Int, h: Int) {
        if (w > 0 && h > 0) {
            srcW = w.toFloat()
            srcH = h.toFloat()
        }
    }

    fun setTarget(w: Int, h: Int) {
        if (w > 0 && h > 0) {
            dstW = w.toFloat()
            dstH = h.toFloat()
        }
    }

    fun reset() {
        synchronized(lock) {
            handler?.removeCallbacks(longPressRunnable)
            sequenceActive = false
            releaseButtons()
            prev = HashMap()
            maxPointers = 0
            moved = false
            longPressFired = false
            tapEmittedForSeq = false
            centroid0X = Float.NaN
            centroid0Y = Float.NaN
            moveAccX = 0f
            moveAccY = 0f
            moveOutX = 0f
            moveOutY = 0f
            scrollAccX = 0f
            scrollAccY = 0f
        }
    }

    fun onFrame(frame: HelperClient.TouchFrame) {
        synchronized(lock) { handleFrame(frame) }
    }

    private fun handleFrame(frame: HelperClient.TouchFrame) {
        val cur = HashMap<Int, HelperClient.Point>(frame.points.size)
        for (p in frame.points) cur[p.id] = p
        val n = cur.size
        val now = frame.timeMs

        if (prev.isEmpty() && n > 0) beginSequence(now, frame)
        if (n > maxPointers) maxPointers = n

        val dtMs = (now - lastFrameMs).coerceIn(1L, 50L).toFloat()
        lastFrameMs = now

        if (n == 1) {
            val p = frame.points[0]
            val q = prev[p.id]
            if (q != null) {
                val dx = (p.x - q.x).toFloat()
                val dy = (p.y - q.y).toFloat()
                moveAccX += dx
                moveAccY += dy
                if (hypot(p.x - startX, p.y - startY) > config.slopPx) moved = true
            }
            if (moved || dragHolding || rightHolding) emitMove(dtMs)
        } else if (n >= 2) {
            val c = centroid(frame)
            if (centroid0X.isNaN()) {
                centroid0X = c.first
                centroid0Y = c.second
            }
            var dx = 0f
            var dy = 0f
            var cnt = 0
            for (p in frame.points) {
                val q = prev[p.id] ?: continue
                dx += (p.x - q.x).toFloat()
                dy += (p.y - q.y).toFloat()
                cnt++
            }
            if (cnt > 0) {
                if (hypot(c.first - centroid0X, c.second - centroid0Y) > config.slopPx) moved = true
                scrollAccX += dx / cnt
                scrollAccY += dy / cnt
                emitWheel()
            }
        }

        // 帧到达兜底的长按判定（主路径是定时器）
        if (n == 1 && now - seqStartMs >= config.longPressMs) maybeLongPress()

        if (n == 0 && prev.isNotEmpty()) finishSequence(now)

        prev = cur
    }

    private fun beginSequence(now: Long, frame: HelperClient.TouchFrame) {
        seqStartMs = now
        lastFrameMs = now
        maxPointers = frame.points.size
        moved = false
        longPressFired = false
        tapEmittedForSeq = false
        moveAccX = 0f
        moveAccY = 0f
        moveOutX = 0f
        moveOutY = 0f
        scrollAccX = 0f
        scrollAccY = 0f
        centroid0X = Float.NaN
        centroid0Y = Float.NaN
        startX = frame.points[0].x.toFloat()
        startY = frame.points[0].y.toFloat()

        // 静止长按用定时器判定，帧率低也不会漏
        sequenceActive = true
        handler?.removeCallbacks(longPressRunnable)
        if (handler != null && config.longPressMs > 0) {
            handler.postDelayed(longPressRunnable, config.longPressMs)
        }

        // 双击的第二下：立刻按住左键，之后拖动即拖拽
        if (frame.points.size == 1 && !dragHolding && now - lastTapAt in 1..config.doubleTapGapMs) {
            output.button(leftIndex(), true)
            dragHolding = true
            Logs.d("Gesture", "双击拖拽：左键按下")
        }
    }

    private fun centroid(frame: HelperClient.TouchFrame): Pair<Float, Float> {
        var sx = 0f
        var sy = 0f
        for (p in frame.points) {
            sx += p.x
            sy += p.y
        }
        val n = frame.points.size.coerceAtLeast(1)
        return sx / n to sy / n
    }

    // ------------------------------------------------------------------ 输出

    /**
     * 指针移动：等比映射（取宽高比的小值，避免纵向拉伸）+ 速度加速。
     *
     * 余数必须在输出域里扣减，否则每帧残差会指数放大，表现为光标数值爆炸、疯狂抖动。
     */
    private fun emitMove(dtMs: Float) {
        val base = min(dstW / srcW, dstH / srcH)
        val gain = if (config.accelEnabled) {
            val speed = hypot(moveAccX, moveAccY) / dtMs
            (1f + config.accelGain * speed).coerceAtMost(config.accelMax)
        } else {
            1f
        }
        val k = base * config.sensitivity * gain

        val rawX = if (config.reverseX) -moveAccX else moveAccX
        val rawY = if (config.reverseY) -moveAccY else moveAccY
        moveOutX += rawX * k
        moveOutY += rawY * k
        moveAccX = 0f
        moveAccY = 0f

        // 单帧最大步长（助手侧再拆成 ±200 的 REL 事件），防止异常时把光标甩飞
        val ix = moveOutX.toInt().coerceIn(-MAX_STEP, MAX_STEP)
        val iy = moveOutY.toInt().coerceIn(-MAX_STEP, MAX_STEP)
        if (ix != 0 || iy != 0) {
            output.move(ix, iy)
            moveOutX -= ix
            moveOutY -= iy
        }
    }

    /** 滚轮：垂直 + 水平，方向由 naturalScroll 决定。 */
    private fun emitWheel() {
        val step = (scrollAccY / config.wheelStepPx).toInt()
        if (step != 0) {
            output.wheel(if (config.naturalScroll) step else -step, 0)
            scrollAccY -= step * config.wheelStepPx
        }
        val stepX = (scrollAccX / config.wheelStepPx).toInt()
        if (stepX != 0) {
            output.wheel(0, if (config.naturalScroll) stepX else -stepX)
            scrollAccX -= stepX * config.wheelStepPx
        }
    }

    /** 长按判定：满足“单指、没移动、还没触发过”才按右键（定时器/帧到达都会调用）。 */
    private fun maybeLongPress() {
        synchronized(lock) {
            if (!sequenceActive || moved || longPressFired || dragHolding || tapEmittedForSeq) return
            if (maxPointers != 1) return
            longPressFired = true
            output.button(rightIndex(), true)
            rightHolding = true
            Logs.d("Gesture", "长按：右键按下")
        }
    }

    private fun releaseButtons() {
        if (rightHolding) {
            output.button(rightIndex(), false)
            rightHolding = false
        }
        if (dragHolding) {
            output.button(leftIndex(), false)
            dragHolding = false
        }
    }

    companion object {
        /** 单帧最大输出步长（像素） */
        private const val MAX_STEP = 240
    }

    private fun finishSequence(now: Long) {
        sequenceActive = false
        handler?.removeCallbacks(longPressRunnable)
        releaseButtons()

        val duration = now - seqStartMs
        val isTap = !moved && !longPressFired && !tapEmittedForSeq && duration <= config.tapMaxMs + 120
        if (isTap) {
            when (maxPointers) {
                1 -> {
                    output.button(leftIndex(), true)
                    output.button(leftIndex(), false)
                    lastTapAt = now
                    tapEmittedForSeq = true
                    Logs.d("Gesture", "单击：左键")
                }
                2 -> {
                    output.button(rightIndex(), true)
                    output.button(rightIndex(), false)
                    Logs.d("Gesture", "双指单击：右键")
                }
                3 -> {
                    output.button(3, true)
                    output.button(3, false)
                    Logs.d("Gesture", "三指单击：中键")
                }
                else -> Logs.d("Gesture", "${maxPointers} 指轻点：忽略")
            }
        } else if (duration > config.tapMaxMs + 120 && moved) {
            Logs.d("Gesture", "滑动结束（${duration}ms，${maxPointers} 指）")
        }
        maxPointers = 0
        prev = HashMap()
    }

    private fun rightIndex(): Int = if (config.swapButtons) 1 else 2

    private fun leftIndex(): Int = if (config.swapButtons) 2 else 1
}
