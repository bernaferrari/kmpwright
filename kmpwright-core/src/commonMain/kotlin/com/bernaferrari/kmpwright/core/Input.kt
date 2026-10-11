package com.bernaferrari.kmpwright.core

interface Touch {
    suspend fun tap(x: Double, y: Double, options: TapOptions = TapOptions())
    suspend fun longTap(x: Double, y: Double, options: LongTapOptions = LongTapOptions())
    suspend fun scroll(x: Double, y: Double, deltaX: Double = 0.0, deltaY: Double = -100.0, options: ScrollOptions = ScrollOptions())
    suspend fun swipe(x1: Double, y1: Double, x2: Double, y2: Double, options: SwipeOptions = SwipeOptions())
    suspend fun pinch(x: Double, y: Double, scale: Double, options: PinchOptions = PinchOptions())
}

data class TapOptions(
    val timeoutMs: Long = 30_000,
    val force: Boolean = false,
    val modifiers: Int = 0
)

data class LongTapOptions(
    val timeoutMs: Long = 30_000,
    val durationMs: Long = 750
)

data class ScrollOptions(
    val timeoutMs: Long = 30_000,
    val steps: Int = 5
)

data class SwipeOptions(
    val timeoutMs: Long = 30_000,
    val speed: Double = 500.0
)

data class PinchOptions(
    val timeoutMs: Long = 30_000
)

interface Mouse {
    suspend fun move(x: Double, y: Double, options: MoveOptions = MoveOptions())
    suspend fun click(x: Double, y: Double, options: MouseClickOptions = MouseClickOptions())
    suspend fun dblclick(x: Double, y: Double, options: MouseClickOptions = MouseClickOptions())
    suspend fun tripleclick(x: Double, y: Double, options: MouseClickOptions = MouseClickOptions())
    suspend fun down(options: MouseDownOptions = MouseDownOptions())
    suspend fun up(options: MouseUpOptions = MouseUpOptions())
    suspend fun wheel(deltaX: Double = 0.0, deltaY: Double = -100.0, options: WheelOptions = WheelOptions())
    suspend fun drag(startX: Double, startY: Double, endX: Double, endY: Double, options: DragOptions = DragOptions())
}

data class MoveOptions(
    val timeoutMs: Long = 30_000,
    val steps: Int = 1
)

data class MouseClickOptions(
    val timeoutMs: Long = 30_000,
    val button: MouseButton = MouseButton.LEFT,
    val clickCount: Int = 1,
    val delayMs: Long = 0
)

data class MouseDownOptions(
    val button: MouseButton = MouseButton.LEFT,
    val clickCount: Int = 1
)

data class MouseUpOptions(
    val button: MouseButton = MouseButton.LEFT
)

data class WheelOptions(
    val timeoutMs: Long = 30_000
)

data class DragOptions(
    val timeoutMs: Long = 30_000,
    val sourcePosition: Position? = null,
    val targetPosition: Position? = null
)

enum class MouseButton {
    LEFT,
    MIDDLE,
    RIGHT
}

interface Keyboard {
    suspend fun insertText(text: String)
    suspend fun type(text: String, options: TypeOptions = TypeOptions())
    suspend fun down(key: String)
    suspend fun up(key: String)
    suspend fun press(key: String, options: PressOptions = PressOptions())
    suspend fun downAndUp(key: String, options: TypeOptions = TypeOptions())
    fun isModifierPressed(key: String): Boolean
    fun modifiers(): Set<String>
}

data class TypeOptions(
    val delayMs: Long = 0,
    val timeoutMs: Long = 30_000
)

data class PressOptions(
    val delayMs: Long = 0,
    val timeoutMs: Long = 30_000
)

object KeyboardModifiers {
    const val SHIFT = "Shift"
    const val CONTROL = "Control"
    const val ALT = "Alt"
    const val META = "Meta"
    const val CAPS_LOCK = "CapsLock"
}
