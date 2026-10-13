package com.bernaferrari.kmpwright.core

import kotlin.coroutines.cancellation.CancellationException

/**
 * Touch input in viewport coordinates, like Playwright's `page.touchscreen`. Besides [tap] it offers
 * the mobile gestures [longTap], [scroll], [swipe] and [pinch].
 */
public interface Touchscreen {
    /** Taps at ([x], [y]). */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun tap(x: Double, y: Double, options: TapOptions = TapOptions())

    /** Presses at ([x], [y]) for [LongTapOptions.durationMs]. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun longTap(x: Double, y: Double, options: LongTapOptions = LongTapOptions())

    /** Scrolls with a touch drag starting at ([x], [y]) by ([deltaX], [deltaY]). */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun scroll(x: Double, y: Double, deltaX: Double = 0.0, deltaY: Double = -100.0, options: ScrollOptions = ScrollOptions())

    /** Swipes from ([x1], [y1]) to ([x2], [y2]). */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun swipe(x1: Double, y1: Double, x2: Double, y2: Double, options: SwipeOptions = SwipeOptions())

    /** Pinches around ([x], [y]); [scale] above 1 zooms in. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun pinch(x: Double, y: Double, scale: Double, options: PinchOptions = PinchOptions())
}

/** Options for [Locator.tap] and [Touchscreen.tap]. [timeoutMs] null uses [TimeoutConfig.action]. */
public data class TapOptions(
    val force: Boolean = false,
    val modifiers: Int = 0,
    val timeoutMs: Long? = null
)

/** Options for [Touchscreen.longTap]. */
public data class LongTapOptions(
    val durationMs: Long = 750,
    val timeoutMs: Long? = null
)

/** Options for [Touchscreen.scroll]. */
public data class ScrollOptions(
    val steps: Int = 5,
    val timeoutMs: Long? = null
)

/** Options for [Touchscreen.swipe]; [speed] is in CSS pixels per second. */
public data class SwipeOptions(
    val speed: Double = 500.0,
    val timeoutMs: Long? = null
)

/** Options for [Touchscreen.pinch]. */
public data class PinchOptions(
    val timeoutMs: Long? = null
)

/** Mouse input in viewport coordinates, like Playwright's `page.mouse`. */
public interface Mouse {
    /** Moves the pointer to ([x], [y]). */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun move(x: Double, y: Double, options: MoveOptions = MoveOptions())

    /** Moves to ([x], [y]) and clicks; use [MouseClickOptions.clickCount] for double or triple clicks. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun click(x: Double, y: Double, options: MouseClickOptions = MouseClickOptions())

    /** Moves to ([x], [y]) and double-clicks. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun dblclick(x: Double, y: Double, options: MouseClickOptions = MouseClickOptions())

    /** Presses a mouse button at the current position. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun down(options: MouseDownOptions = MouseDownOptions())

    /** Releases a mouse button at the current position. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun up(options: MouseUpOptions = MouseUpOptions())

    /** Dispatches a wheel event; positive [deltaY] scrolls down. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun wheel(deltaX: Double, deltaY: Double, options: WheelOptions = WheelOptions())

    /** Presses at the start point, moves to the end point and releases. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun drag(startX: Double, startY: Double, endX: Double, endY: Double, options: DragOptions = DragOptions())
}

/** Options for [Mouse.move]. */
public data class MoveOptions(
    val steps: Int = 1,
    val timeoutMs: Long? = null
)

/** Options for [Mouse.click] and [Mouse.dblclick]; [delayMs] is the time between press and release. */
public data class MouseClickOptions(
    val button: MouseButton = MouseButton.LEFT,
    val clickCount: Int = 1,
    val delayMs: Long = 0,
    val timeoutMs: Long? = null
)

/** Options for [Mouse.down]. */
public data class MouseDownOptions(
    val button: MouseButton = MouseButton.LEFT,
    val clickCount: Int = 1
)

/** Options for [Mouse.up]. */
public data class MouseUpOptions(
    val button: MouseButton = MouseButton.LEFT
)

/** Options for [Mouse.wheel]. */
public data class WheelOptions(
    val timeoutMs: Long? = null
)

/** Options for [Locator.dragTo] and [Mouse.drag]. [timeoutMs] null uses [TimeoutConfig.action]. */
public data class DragOptions(
    val sourcePosition: Position? = null,
    val targetPosition: Position? = null,
    val timeoutMs: Long? = null
)

/** Mouse buttons. */
public enum class MouseButton {
    LEFT,
    MIDDLE,
    RIGHT
}

/** Keyboard input to the focused element, like Playwright's `page.keyboard`. */
public interface Keyboard {
    /** Inserts [text] without key events (like an IME commit). */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun insertText(text: String)

    /**
     * Types [text] character by character, sending `keydown`, `keypress`, `beforeinput`, `input` and `keyup`
     * for each (Playwright `keyboard.type`); use [insertText] for input events only.
     */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun type(text: String, options: TypeOptions = TypeOptions())

    /** Dispatches `keydown` for [key]. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun down(key: String)

    /** Dispatches `keyup` for [key]. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun up(key: String)

    /**
     * Presses a key or combination such as `"Control+A"` or `"ControlOrMeta+A"` (Meta on Apple platforms,
     * Control elsewhere): modifiers down, key down, [PressOptions.delayMs], key up, modifiers up.
     */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun press(key: String, options: PressOptions = PressOptions())
}

/** Options for [Keyboard.type] and the `Page.type` convenience; [delayMs] waits between key presses. */
public data class TypeOptions(
    val delayMs: Long = 0,
    val timeoutMs: Long? = null
)

/** Options for [Keyboard.press] and [Locator.press]; [delayMs] waits between `keydown` and `keyup`. */
public data class PressOptions(
    val delayMs: Long = 0,
    val timeoutMs: Long? = null
)

/** Canonical modifier key names accepted in key combinations such as `"Shift+Tab"`. */
public object KeyboardModifiers {
    /** The Shift key. */
    public const val SHIFT: String = "Shift"
    /** The Control key. */
    public const val CONTROL: String = "Control"
    /** The Alt (Option) key. */
    public const val ALT: String = "Alt"
    /** The Meta (Command/Windows) key. */
    public const val META: String = "Meta"
    /** The Caps Lock key. */
    public const val CAPS_LOCK: String = "CapsLock"
}
