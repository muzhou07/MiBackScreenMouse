package mz.mibackscreen.mouse.core

import android.content.Context

/** 轻量持久化：只保存操控本机所需的最小状态。 */
class AppPrefs(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences("backscreen_mouse", Context.MODE_PRIVATE)

    /** 触控板是否处于由我们启动的状态。 */
    var touchpadEnabled: Boolean
        get() = sp.getBoolean(KEY_ENABLED, false)
        set(value) = sp.edit().putBoolean(KEY_ENABLED, value).apply()

    /** true = 把触控板投放到背屏(Display 1)；false = 就在本应用所在屏幕显示。 */
    var showOnBackScreen: Boolean
        get() = sp.getBoolean(KEY_ON_BACK, true)
        set(value) = sp.edit().putBoolean(KEY_ON_BACK, value).apply()

    // ---------------------------------------------------------------- 手感设置

    /** 灵敏度倍率 */
    var sensitivity: Float
        get() = sp.getFloat(KEY_SENS, 1.3f)
        set(value) = sp.edit().putFloat(KEY_SENS, value.coerceIn(0.3f, 4f)).apply()

    /** true = 自然滚动（双指下滑 → 页面向下） */
    var naturalScroll: Boolean
        get() = sp.getBoolean(KEY_NATURAL, true)
        set(value) = sp.edit().putBoolean(KEY_NATURAL, value).apply()

    /** 长按判定时长(ms) → 按住右键 */
    var longPressMs: Int
        get() = sp.getInt(KEY_LONG_PRESS, 500)
        set(value) = sp.edit().putInt(KEY_LONG_PRESS, value.coerceIn(250, 1500)).apply()

    /** 指针速度加速开关 */
    var accelEnabled: Boolean
        get() = sp.getBoolean(KEY_ACCEL, true)
        set(value) = sp.edit().putBoolean(KEY_ACCEL, value).apply()

    /** 左右反向（默认开启） */
    var reverseX: Boolean
        get() = sp.getBoolean(KEY_REVERSE_X, true)
        set(value) = sp.edit().putBoolean(KEY_REVERSE_X, value).apply()

    /** 上下反向：默认关闭 */
    var reverseY: Boolean
        get() = sp.getBoolean(KEY_REVERSE_Y, false)
        set(value) = sp.edit().putBoolean(KEY_REVERSE_Y, value).apply()

    companion object {
        private const val KEY_ENABLED = "touchpad_enabled"
        private const val KEY_ON_BACK = "show_on_back_screen"
        private const val KEY_SENS = "feel_sensitivity"
        private const val KEY_NATURAL = "feel_natural_scroll"
        private const val KEY_LONG_PRESS = "feel_long_press_ms"
        private const val KEY_ACCEL = "feel_acceleration"
        private const val KEY_REVERSE_X = "feel_reverse_x"
        private const val KEY_REVERSE_Y = "feel_reverse_y"
    }
}
