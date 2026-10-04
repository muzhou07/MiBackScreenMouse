package mz.mibackscreen.mouse.touchpad

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View

/** 触点指示器：复刻录屏「显示点按操作反馈」，支持多指、拖尾与长按扩散环。 */
class TouchIndicatorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val baseRadius = 22f * density
    private val dotRadius = 4.5f * density

    private val trailLifetimeMs = 260L
    private val idleFadeMs = 170L
    private val longPressDelayMs = 500L
    private val ringPeriodMs = 900L
    private val trailIntervalMs = 14L
    private val maxTrail = 24

    private data class Sample(val x: Float, val y: Float, val at: Long)

    private class Pointer(val id: Int) {
        val trail = ArrayDeque<Sample>()
        var pressed = false
        var pressSince = 0L
        var lastSeen = 0L
        var x = 0f
        var y = 0f
        var lastSampleAt = 0L
    }

    private val pointers = LinkedHashMap<Int, Pointer>()

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    fun onPointerDown(id: Int, x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        val p = pointers.getOrPut(id) { Pointer(id) }
        p.pressed = true
        p.pressSince = now
        p.lastSeen = now
        p.x = x
        p.y = y
        p.trail.clear()
        pushSample(p, x, y, now, force = true)
        invalidate()
    }

    fun onPointerMove(id: Int, x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        val p = pointers.getOrPut(id) { Pointer(id) }
        p.x = x
        p.y = y
        p.lastSeen = now
        pushSample(p, x, y, now, force = false)
        invalidate()
    }

    fun onPointerUp(id: Int, x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        val p = pointers.getOrPut(id) { Pointer(id) }
        p.pressed = false
        p.x = x
        p.y = y
        p.lastSeen = now
        invalidate()
    }

    fun reset() {
        pointers.clear()
        invalidate()
    }

    /** 由助手触点帧直接驱动（真实多指数据）。 */
    fun syncFrame(ids: IntArray, xs: FloatArray, ys: FloatArray) {
        val now = SystemClock.uptimeMillis()
        val present = HashSet<Int>(ids.size)
        for (i in ids.indices) {
            val id = ids[i]
            present.add(id)
            val p = pointers.getOrPut(id) { Pointer(id) }
            if (!p.pressed) {
                p.pressed = true
                p.pressSince = now
                p.trail.clear()
                pushSample(p, xs[i], ys[i], now, force = true)
            } else {
                pushSample(p, xs[i], ys[i], now, force = false)
            }
            p.x = xs[i]
            p.y = ys[i]
            p.lastSeen = now
        }

        val keepUntil = idleFadeMs + trailLifetimeMs + 60
        val it = pointers.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            if (!present.contains(entry.key)) {
                entry.value.pressed = false
                if (now - entry.value.lastSeen > keepUntil) it.remove()
            }
        }
        invalidate()
    }

    private fun pushSample(p: Pointer, x: Float, y: Float, at: Long, force: Boolean) {
        if (!force && at - p.lastSampleAt < trailIntervalMs) return
        p.lastSampleAt = at
        p.trail.addLast(Sample(x, y, at))
        while (p.trail.size > maxTrail) p.trail.removeFirst()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = SystemClock.uptimeMillis()
        var needMoreFrames = false

        for (p in pointers.values) {
            while (p.trail.isNotEmpty() && now - p.trail.first().at > trailLifetimeMs) {
                p.trail.removeFirst()
            }

            // 拖尾：越旧越淡越小
            for (sample in p.trail) {
                val age = (now - sample.at).toFloat() / trailLifetimeMs
                if (age >= 1f) continue
                val radius = baseRadius * (0.45f + 0.45f * (1f - age))
                fill.color = Color.argb((26 * (1f - age)).toInt().coerceAtLeast(0), 255, 255, 255)
                canvas.drawCircle(sample.x, sample.y, radius, fill)
            }

            val idle = now - p.lastSeen
            val visible = p.pressed || idle < idleFadeMs
            if (visible) {
                val fade = if (p.pressed) 1f else (1f - idle.toFloat() / idleFadeMs).coerceIn(0f, 1f)

                // 长按：周期性扩散环
                if (p.pressed && now - p.pressSince > longPressDelayMs) {
                    val phase = ((now - p.pressSince - longPressDelayMs) % ringPeriodMs).toFloat() / ringPeriodMs
                    stroke.color = Color.argb((110 * (1f - phase)).toInt().coerceAtLeast(0), 255, 255, 255)
                    stroke.strokeWidth = 2f * density
                    canvas.drawCircle(p.x, p.y, baseRadius * (1f + 1.1f * phase), stroke)
                }

                fill.color = Color.argb((70 * fade).toInt().coerceAtLeast(0), 255, 255, 255)
                canvas.drawCircle(p.x, p.y, baseRadius, fill)

                stroke.color = Color.argb((120 * fade).toInt().coerceAtLeast(0), 255, 255, 255)
                stroke.strokeWidth = 1.5f * density
                canvas.drawCircle(p.x, p.y, baseRadius, stroke)

                dot.color = Color.argb((220 * fade).toInt().coerceAtLeast(0), 255, 255, 255)
                canvas.drawCircle(p.x, p.y, dotRadius, dot)
            }

            if (p.pressed || p.trail.isNotEmpty() || idle < idleFadeMs) needMoreFrames = true
        }

        if (needMoreFrames) postInvalidateOnAnimation()
    }
}
