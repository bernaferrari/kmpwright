package com.bernaferrari.kmpwright.core

import kotlinx.coroutines.Deferred

/** Shared policy, lifetime and total deadline for every platform's cached input devices. */
internal class PageInputDevices(
    private val config: KmpwrightConfig,
    private val closed: Deferred<Unit>,
    private val checkPolicy: suspend (ActionType) -> Unit,
    createTouch: () -> Touchscreen,
    createMouse: () -> Mouse,
    createKeyboard: () -> Keyboard
) {
    val touchscreen: Touchscreen by lazy(LazyThreadSafetyMode.NONE) { guardTouch(createTouch()) }
    val mouse: Mouse by lazy(LazyThreadSafetyMode.NONE) { guardMouse(createMouse()) }
    val keyboard: Keyboard by lazy(LazyThreadSafetyMode.NONE) { guardKeyboard(createKeyboard()) }

    private suspend fun perform(action: ActionType, timeoutMs: Long?, label: String, block: suspend () -> Unit) =
        awaitPageOperation(closed) {
            config.inputAction(timeoutMs, label) {
                checkPolicy(action)
                block()
            }
        }

    private fun guardTouch(delegate: Touchscreen): Touchscreen = object : Touchscreen {
        override suspend fun tap(x: Double, y: Double, modifiers: Int, timeoutMs: Long?) = perform(ActionType.Click, timeoutMs, "tap") {
            delegate.tap(x, y, modifiers, timeoutMs)
        }
        override suspend fun longTap(x: Double, y: Double, durationMs: Long, timeoutMs: Long?) = perform(ActionType.Click, timeoutMs, "longTap") {
            delegate.longTap(x, y, durationMs, timeoutMs)
        }
        override suspend fun scroll(x: Double, y: Double, deltaX: Double, deltaY: Double, steps: Int, timeoutMs: Long?) = perform(ActionType.Scroll, timeoutMs, "scroll") {
            delegate.scroll(x, y, deltaX, deltaY, steps, timeoutMs)
        }
        override suspend fun swipe(x1: Double, y1: Double, x2: Double, y2: Double, speed: Double, timeoutMs: Long?) = perform(ActionType.Drag, timeoutMs, "swipe") {
            delegate.swipe(x1, y1, x2, y2, speed, timeoutMs)
        }
        override suspend fun pinch(x: Double, y: Double, scale: Double, timeoutMs: Long?) = perform(ActionType.Scroll, timeoutMs, "pinch") {
            delegate.pinch(x, y, scale, timeoutMs)
        }
    }

    private fun guardMouse(delegate: Mouse): Mouse = object : Mouse {
        override suspend fun move(x: Double, y: Double, steps: Int, timeoutMs: Long?) = perform(ActionType.Hover, timeoutMs, "move") {
            delegate.move(x, y, steps, timeoutMs)
        }
        override suspend fun click(x: Double, y: Double, button: MouseButton, clickCount: Int, delayMs: Long, timeoutMs: Long?) = perform(ActionType.Click, timeoutMs, "click") {
            delegate.click(x, y, button, clickCount, delayMs, timeoutMs)
        }
        override suspend fun dblclick(x: Double, y: Double, button: MouseButton, delayMs: Long, timeoutMs: Long?) = perform(ActionType.Click, timeoutMs, "dblclick") {
            delegate.dblclick(x, y, button, delayMs, timeoutMs)
        }
        override suspend fun down(button: MouseButton, clickCount: Int) = perform(ActionType.Click, null, "down") {
            delegate.down(button, clickCount)
        }
        override suspend fun up(button: MouseButton) = perform(ActionType.Click, null, "up") {
            delegate.up(button)
        }
        override suspend fun wheel(deltaX: Double, deltaY: Double, timeoutMs: Long?) = perform(ActionType.Scroll, timeoutMs, "wheel") {
            delegate.wheel(deltaX, deltaY, timeoutMs)
        }
        override suspend fun drag(startX: Double, startY: Double, endX: Double, endY: Double, timeoutMs: Long?) = perform(ActionType.Drag, timeoutMs, "drag") {
            delegate.drag(startX, startY, endX, endY, timeoutMs)
        }
    }

    private fun guardKeyboard(delegate: Keyboard): Keyboard = object : Keyboard {
        override suspend fun insertText(text: String) = perform(ActionType.Type, null, "insertText") {
            delegate.insertText(text)
        }
        override suspend fun type(text: String, delayMs: Long, timeoutMs: Long?) = perform(ActionType.Type, timeoutMs, "type") {
            delegate.type(text, delayMs, timeoutMs)
        }
        override suspend fun down(key: String) = perform(ActionType.Press, null, "down") {
            delegate.down(key)
        }
        override suspend fun up(key: String) = perform(ActionType.Press, null, "up") {
            delegate.up(key)
        }
        override suspend fun press(key: String, delayMs: Long, timeoutMs: Long?) = perform(ActionType.Press, timeoutMs, "press") {
            delegate.press(key, delayMs, timeoutMs)
        }
    }
}
