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

    // ---------------------------------------------------------------- 蓝牙鼠标模式

    /** 蓝牙鼠标模式开关（手机变成一只可被主机连接的 BLE 鼠标）。 */
    var btMouseMode: Boolean
        get() = sp.getBoolean(KEY_BT_MODE, false)
        set(value) = sp.edit().putBoolean(KEY_BT_MODE, value).apply()

    /** 蓝牙模式的报告节流窗口（毫秒）：越小越跟手，越大越稳（BLE 通道每秒承载有限）。 */
    var btFlushMs: Int
        get() = sp.getInt(KEY_BT_FLUSH_MS, 8)
        set(value) = sp.edit().putInt(KEY_BT_FLUSH_MS, value).apply()

    /** 蓝牙模式的位移平滑强度（0~90，0=关闭）：越大越顺，延迟略增。 */
    var btSmooth: Int
        get() = sp.getInt(KEY_BT_SMOOTH, 0)
        set(value) = sp.edit().putInt(KEY_BT_SMOOTH, value).apply()

    // ---------------------------------------------------------------- 外观

    /** 是否使用桌面壁纸做背景；关闭（默认）= 纯白底。 */
    var useWallpaper: Boolean
        get() = sp.getBoolean(KEY_USE_WALLPAPER, false)
        set(value) = sp.edit().putBoolean(KEY_USE_WALLPAPER, value).apply()

    /** 顶栏不透明度（0~100，%）。 */
    var alphaTop: Int
        get() = sp.getInt(KEY_ALPHA_TOP, 95)
        set(value) = sp.edit().putInt(KEY_ALPHA_TOP, value.coerceIn(0, 100)).apply()

    /** 底栏不透明度（0~100，%）。 */
    var alphaNav: Int
        get() = sp.getInt(KEY_ALPHA_NAV, 90)
        set(value) = sp.edit().putInt(KEY_ALPHA_NAV, value.coerceIn(0, 100)).apply()

    /** 卡片不透明度（0~100，%）。 */
    var alphaCard: Int
        get() = sp.getInt(KEY_ALPHA_CARD, 90)
        set(value) = sp.edit().putInt(KEY_ALPHA_CARD, value.coerceIn(0, 100)).apply()

    companion object {
        private const val KEY_ENABLED = "touchpad_enabled"
        private const val KEY_ON_BACK = "show_on_back_screen"
        private const val KEY_SENS = "feel_sensitivity"
        private const val KEY_NATURAL = "feel_natural_scroll"
        private const val KEY_LONG_PRESS = "feel_long_press_ms"
        private const val KEY_ACCEL = "feel_acceleration"
        private const val KEY_REVERSE_X = "feel_reverse_x"
        private const val KEY_REVERSE_Y = "feel_reverse_y"
        private const val KEY_BT_MODE = "bt_mouse_mode"
        private const val KEY_BT_FLUSH_MS = "bt_flush_ms"
        private const val KEY_BT_SMOOTH = "bt_smooth"
        private const val KEY_USE_WALLPAPER = "use_wallpaper"
        private const val KEY_ALPHA_TOP = "alpha_top"
        private const val KEY_ALPHA_NAV = "alpha_nav"
        private const val KEY_ALPHA_CARD = "alpha_card"
    }
}
