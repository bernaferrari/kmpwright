package com.bernaferrari.kmpwright.core

import kotlin.coroutines.cancellation.CancellationException

/**
 * Touch input in viewport coordinates, like Playwright's `page.touchscreen`. Besides [tap] it offers
 * the mobile gestures [longTap], [scroll], [swipe] and [pinch].
 */
public interface Touchscreen {
    /**
     * Taps at ([x], [y]); [modifiers] is the CDP modifier bit mask (Alt 1, Control 2, Meta 4, Shift 8; CDP only).
     * [timeoutMs] null uses [TimeoutConfig.action].
     */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun tap(x: Double, y: Double, modifiers: Int = 0, timeoutMs: Long? = null)

    /** Presses at ([x], [y]) for [durationMs]. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun longTap(x: Double, y: Double, durationMs: Long = 750, timeoutMs: Long? = null)

    /** Scrolls with a touch drag in [steps] moves starting at ([x], [y]) by ([deltaX], [deltaY]). */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun scroll(x: Double, y: Double, deltaX: Double = 0.0, deltaY: Double = -100.0, steps: Int = 5, timeoutMs: Long? = null)

    /** Swipes from ([x1], [y1]) to ([x2], [y2]) at [speed] CSS pixels per second. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun swipe(x1: Double, y1: Double, x2: Double, y2: Double, speed: Double = 500.0, timeoutMs: Long? = null)

    /** Pinches around ([x], [y]); [scale] above 1 zooms in. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun pinch(x: Double, y: Double, scale: Double, timeoutMs: Long? = null)
}

/** Mouse input in viewport coordinates, like Playwright's `page.mouse`. */
public interface Mouse {
    /** Moves the pointer to ([x], [y]) in [steps] intermediate moves. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun move(x: Double, y: Double, steps: Int = 1, timeoutMs: Long? = null)

    /**
     * Moves to ([x], [y]) and clicks [clickCount] times (2 or 3 for double or triple clicks); [delayMs]
     * is the time between press and release.
     */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun click(x: Double, y: Double, button: MouseButton = MouseButton.Left, clickCount: Int = 1, delayMs: Long = 0, timeoutMs: Long? = null)

    /** Moves to ([x], [y]) and double-clicks; [delayMs] is the time between press and release. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun dblclick(x: Double, y: Double, button: MouseButton = MouseButton.Left, delayMs: Long = 0, timeoutMs: Long? = null)

    /** Presses [button] at the current position; [clickCount] is the event's `detail`. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun down(button: MouseButton = MouseButton.Left, clickCount: Int = 1)

    /** Releases [button] at the current position. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun up(button: MouseButton = MouseButton.Left)

    /** Dispatches a wheel event; positive [deltaY] scrolls down. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun wheel(deltaX: Double, deltaY: Double, timeoutMs: Long? = null)

    /** Presses at the start point, moves to the end point and releases. [timeoutMs] null uses [TimeoutConfig.action]. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun drag(startX: Double, startY: Double, endX: Double, endY: Double, timeoutMs: Long? = null)
}

/** Mouse buttons. */
public enum class MouseButton {
    Left,
    Middle,
    Right
}

/** Keyboard input to the focused element, like Playwright's `page.keyboard`. */
public interface Keyboard {
    /** Inserts [text] without key events (like an IME commit). */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun insertText(text: String)

    /**
     * Types [text] character by character, sending `keydown`, `keypress`, `beforeinput`, `input` and `keyup`
     * for each (Playwright `keyboard.type`), waiting [delayMs] between keys; use [insertText] for input events only.
     */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun type(text: String, delayMs: Long = 0, timeoutMs: Long? = null)

    /** Dispatches `keydown` for [key]. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun down(key: String)

    /** Dispatches `keyup` for [key]. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun up(key: String)

    /**
     * Presses a key or combination such as `"Control+A"` or `"ControlOrMeta+A"` (Meta on Apple platforms,
     * Control elsewhere): modifiers down, key down, [delayMs], key up, modifiers up.
     */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun press(key: String, delayMs: Long = 0, timeoutMs: Long? = null)
}

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
