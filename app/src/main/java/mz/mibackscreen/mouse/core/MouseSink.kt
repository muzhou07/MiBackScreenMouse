package mz.mibackscreen.mouse.core

/**
 * 手势引擎的输出目标：本机 [HelperClient]（uinput）或蓝牙 [BtHidMouse]（BLE HID 报告）。
 */
interface MouseSink {

    fun move(dx: Int, dy: Int)

    /** index：1=左 2=右 3=中。 */
    fun button(index: Int, down: Boolean)

    fun wheel(vertical: Int, horizontal: Int)

    fun close()
}
