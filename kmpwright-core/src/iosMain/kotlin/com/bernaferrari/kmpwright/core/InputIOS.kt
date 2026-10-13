package com.bernaferrari.kmpwright.core

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.withLock

internal class IOSTouch(
    private val controller: WebViewController,
    private val config: KmpwrightConfig = KmpwrightConfig()
) : Touchscreen {
    private val mutex = kotlinx.coroutines.sync.Mutex()
    private val slot = "__kmpwright_touch_" + kotlin.random.Random.nextLong().toULong().toString(16)
    private var lastX = 0.0
    private var lastY = 0.0

    private suspend fun gesture(x: Double, y: Double, block: suspend () -> Unit) = mutex.withLock {
        try {
            injectTouchEvent("touchstart", x, y)
            block()
            injectTouchEvent("touchend", lastX, lastY)
        } catch (error: Throwable) {
            try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    kotlinx.coroutines.withTimeout(1_000) { injectTouchEvent("touchcancel", lastX, lastY) }
                }
            } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
            throw error
        }
    }

    override suspend fun tap(x: Double, y: Double, options: TapOptions): Unit = config.inputAction(options.timeoutMs, "tap") {
        gesture(x, y) { delay(10) }
    }

    override suspend fun longTap(x: Double, y: Double, options: LongTapOptions): Unit = config.inputAction(options.timeoutMs, "long tap") {
        gesture(x, y) { delay(options.durationMs) }
    }

    override suspend fun scroll(x: Double, y: Double, deltaX: Double, deltaY: Double, options: ScrollOptions): Unit = config.inputAction(options.timeoutMs, "touch scroll") {
        gesture(x, y) {
            val steps = options.steps.coerceAtLeast(1)
            for (i in 1..steps) {
                injectTouchEvent("touchmove", x + deltaX * i / steps, y + deltaY * i / steps)
                delay(5)
            }
        }
    }

    override suspend fun swipe(x1: Double, y1: Double, x2: Double, y2: Double, options: SwipeOptions): Unit = config.inputAction(options.timeoutMs, "swipe") {
        require(options.speed.isFinite() && options.speed > 0) { "Swipe speed must be finite and positive" }
        val distance = kotlin.math.hypot(x2 - x1, y2 - y1)
        val steps = (distance / options.speed * 1000 / 16).toInt().coerceAtLeast(1)
        gesture(x1, y1) {
            for (i in 1..steps) {
                val fraction = i.toDouble() / steps
                injectTouchEvent("touchmove", x1 + (x2 - x1) * fraction, y1 + (y2 - y1) * fraction)
                delay(16)
            }
        }
    }

    override suspend fun pinch(x: Double, y: Double, scale: Double, options: PinchOptions): Unit =
        throw KmpwrightUnsupportedException("WKWebView cannot synthesize a trusted native pinch gesture")

    private suspend fun injectTouchEvent(type: String, x: Double, y: Double) {
        lastX = x
        lastY = y
        controller.evaluateJavaScriptAsync("""
            (function() {
                var slot = ${jsStringLiteral(slot)}, kind = ${jsStringLiteral(type)};
                var state = window[slot];
                if (kind === 'touchstart') {
                    var target = document.elementFromPoint($x, $y);
                    if (!target) throw new Error('No element at touch coordinates');
                    state = window[slot] = {target: target, identifier: Date.now()};
                }
                if (!state) return;
                var ended = kind === 'touchend' || kind === 'touchcancel';
                var touch = new Touch({identifier: state.identifier, target: state.target,
                    clientX: $x, clientY: $y, pageX: $x + window.scrollX, pageY: $y + window.scrollY});
                // Clear before dispatch so a throwing listener cannot retain a finished gesture.
                if (ended) delete window[slot];
                state.target.dispatchEvent(new TouchEvent(kind, {bubbles: true, cancelable: true,
                    touches: ended ? [] : [touch], targetTouches: ended ? [] : [touch], changedTouches: [touch]}));
            })()
        """.trimIndent())
    }
}

internal class IOSMouse(
    private val controller: WebViewController,
    /** Resolves per-call timeouts; a null `timeoutMs` uses [TimeoutConfig.action]. */
    private val config: KmpwrightConfig = KmpwrightConfig()
) : Mouse {
    private val touch = IOSTouch(controller, config)
    private var lastX = 0.0
    private var lastY = 0.0
    private val pressedButtons = linkedSetOf<MouseButton>()

    override suspend fun move(x: Double, y: Double, options: MoveOptions) {
        val steps = options.steps.coerceAtLeast(1)
        val startX = lastX
        val startY = lastY
        for (step in 1..steps) {
            val progress = step.toDouble() / steps.toDouble()
            val currentX = startX + ((x - startX) * progress)
            val currentY = startY + ((y - startY) * progress)
            dispatchMoveEvent(currentX, currentY)
            lastX = currentX
            lastY = currentY
        }
    }

    override suspend fun click(x: Double, y: Double, options: MouseClickOptions) {
        repeat(options.clickCount.coerceAtLeast(1)) { index ->
            move(x, y, MoveOptions(timeoutMs = options.timeoutMs))
            touch.tap(x, y, TapOptions(timeoutMs = options.timeoutMs))
            down(MouseDownOptions(button = options.button, clickCount = index + 1))
            if (options.delayMs > 0) {
                delay(options.delayMs)
            }
            up(MouseUpOptions(button = options.button))
            dispatchMouseEvent("click", x, y, options.button, mouseButtonsBitfield(pressedButtons), index + 1)
        }
        if (options.clickCount == 2) {
            dispatchMouseEvent("dblclick", x, y, options.button, mouseButtonsBitfield(pressedButtons), 2)
        }
    }

    override suspend fun dblclick(x: Double, y: Double, options: MouseClickOptions) {
        click(x, y, options.copy(clickCount = 2))
    }

    override suspend fun down(options: MouseDownOptions) {
        pressedButtons.add(options.button)
        dispatchMouseEvent(
            type = "mousedown",
            x = lastX,
            y = lastY,
            button = options.button,
            buttons = mouseButtonsBitfield(pressedButtons),
            detail = options.clickCount
        )
    }

    override suspend fun up(options: MouseUpOptions) {
        val remainingButtons = pressedButtons - options.button
        dispatchMouseEvent(
            type = "mouseup",
            x = lastX,
            y = lastY,
            button = options.button,
            buttons = mouseButtonsBitfield(remainingButtons),
            detail = 0
        )
        pressedButtons.remove(options.button)
    }

    override suspend fun wheel(deltaX: Double, deltaY: Double, options: WheelOptions) {
        controller.evaluateJavaScriptAsync("""
            (function() {
                var target = document.elementFromPoint($lastX, $lastY) || document.scrollingElement || document.documentElement || document.body;
                if (!target) return;
                var event = new WheelEvent('wheel', {
                    bubbles: true,
                    cancelable: true,
                    clientX: $lastX,
                    clientY: $lastY,
                    screenX: $lastX,
                    screenY: $lastY,
                    deltaX: $deltaX,
                    deltaY: $deltaY,
                    deltaMode: 0
                });
                var accepted = target.dispatchEvent(event);
                if (accepted && !event.defaultPrevented) {
                    if (target === document.body || target === document.documentElement || target === document.scrollingElement) {
                        window.scrollBy($deltaX, $deltaY);
                    } else {
                        target.scrollLeft = (target.scrollLeft || 0) + $deltaX;
                        target.scrollTop = (target.scrollTop || 0) + $deltaY;
                    }
                }
            })()
        """.trimIndent())
    }

    override suspend fun drag(startX: Double, startY: Double, endX: Double, endY: Double, options: DragOptions): Unit =
        config.inputAction(options.timeoutMs, "mouse drag") {
        val timeoutMs = config.actionTimeout(options.timeoutMs)
        move(startX, startY, MoveOptions(timeoutMs = timeoutMs))
        down(MouseDownOptions())
        dispatchMouseEvent("dragstart", startX, startY, MouseButton.LEFT, mouseButtonsBitfield(pressedButtons), 0)
        touch.swipe(
            x1 = startX,
            y1 = startY,
            x2 = endX,
            y2 = endY,
            options = SwipeOptions(timeoutMs = timeoutMs)
        )
        move(endX, endY, MoveOptions(timeoutMs = timeoutMs))
        dispatchMouseEvent("drop", endX, endY, MouseButton.LEFT, mouseButtonsBitfield(pressedButtons), 0)
        up(MouseUpOptions())
        dispatchMouseEvent("dragend", endX, endY, MouseButton.LEFT, mouseButtonsBitfield(pressedButtons), 0)
    }

    private suspend fun dispatchMoveEvent(x: Double, y: Double) {
        val buttons = mouseButtonsBitfield(pressedButtons)
        controller.evaluateJavaScriptAsync("""
            (function() {
                var previous = window.__kmpwrightLastMouseTarget || null;
                var next = document.elementFromPoint($x, $y);
                if (previous && previous !== next) {
                    previous.dispatchEvent(new MouseEvent('mouseout', {
                        bubbles: true,
                        cancelable: true,
                        clientX: $x,
                        clientY: $y,
                        screenX: $x,
                        screenY: $y,
                        buttons: $buttons
                    }));
                }
                if (next && previous !== next) {
                    next.dispatchEvent(new MouseEvent('mouseover', {
                        bubbles: true,
                        cancelable: true,
                        clientX: $x,
                        clientY: $y,
                        screenX: $x,
                        screenY: $y,
                        buttons: $buttons
                    }));
                }
                window.__kmpwrightLastMouseTarget = next || null;
                if (!next) return;
                next.dispatchEvent(new MouseEvent('mousemove', {
                    bubbles: true,
                    cancelable: true,
                    clientX: $x,
                    clientY: $y,
                    screenX: $x,
                    screenY: $y,
                    buttons: $buttons
                }));
            })()
        """.trimIndent())
    }

    private suspend fun dispatchMouseEvent(type: String, x: Double, y: Double, button: MouseButton, buttons: Int, detail: Int) {
        val jsType = jsStringLiteral(type)
        controller.evaluateJavaScriptAsync("""
            (function() {
                var el = document.elementFromPoint($x, $y) || window.__kmpwrightLastMouseTarget || document;
                if (!el) return;
                el.dispatchEvent(new MouseEvent($jsType, {
                    bubbles: true,
                    cancelable: true,
                    clientX: $x,
                    clientY: $y,
                    screenX: $x,
                    screenY: $y,
                    button: ${mouseButtonCode(button)},
                    buttons: $buttons,
                    detail: $detail
                }));
            })()
        """.trimIndent())
    }
}

internal class IOSKeyboard(
    private val controller: WebViewController,
    /** Resolves per-call timeouts; a null `timeoutMs` uses [TimeoutConfig.action]. */
    private val config: KmpwrightConfig = KmpwrightConfig()
) : Keyboard {

    private val pressedKeys = mutableSetOf<String>()

    override suspend fun insertText(text: String) {
        val jsText = jsStringLiteral(text)
        controller.evaluateJavaScriptAsync("""
            (function() {
                var active = document.activeElement;
                var text = $jsText;
                if (!active) return false;
                function dispatchTextInputEvent(type, inputType, data, cancelable) {
                    try {
                        return active.dispatchEvent(new InputEvent(type, {
                            bubbles: true,
                            cancelable: !!cancelable,
                            inputType: inputType,
                            data: data
                        }));
                    } catch (e) {
                        var event = new Event(type, { bubbles: true, cancelable: !!cancelable });
                        try {
                            event.inputType = inputType;
                            event.data = data;
                        } catch (_) {}
                        return active.dispatchEvent(event);
                    }
                }
                if (active.isContentEditable) {
                    if (!dispatchTextInputEvent('beforeinput', 'insertText', text, true)) {
                        return false;
                    }
                    var selection = window.getSelection();
                    if (selection && selection.rangeCount > 0) {
                        var range = selection.getRangeAt(0);
                        range.deleteContents();
                        range.insertNode(document.createTextNode(text));
                        range.collapse(false);
                        selection.removeAllRanges();
                        selection.addRange(range);
                    } else {
                        active.textContent = (active.textContent || '') + text;
                    }
                    dispatchTextInputEvent('input', 'insertText', text, false);
                    return true;
                }
                if (active.tagName === 'INPUT' || active.tagName === 'TEXTAREA') {
                    if (!dispatchTextInputEvent('beforeinput', 'insertText', text, true)) {
                        return false;
                    }
                    var start = active.selectionStart || 0;
                    var end = active.selectionEnd || 0;
                    if (typeof active.setRangeText === 'function') {
                        active.setRangeText(text, start, end, 'end');
                    } else {
                        var value = active.value || '';
                        active.value = value.substring(0, start) + text + value.substring(end);
                        active.selectionStart = active.selectionEnd = start + text.length;
                    }
                    dispatchTextInputEvent('input', 'insertText', text, false);
                    return true;
                }
                return false;
            })()
        """.trimIndent())
    }

    private val focused = FocusedElementInput(controller, config)

    /** Playwright `keyboard.type`: a full key event sequence (keydown, keypress, input, keyup) per character. */
    override suspend fun type(text: String, options: TypeOptions): Unit = config.inputAction(options.timeoutMs, "keyboard type") {
        focused.type(text, options.delayMs)
    }

    override suspend fun down(key: String) {
        val canonicalKey = canonicalModifierKey(key)
        pressedKeys.add(canonicalKey)
        dispatchKeyEvent("keydown", canonicalKey)
    }

    override suspend fun up(key: String) {
        val canonicalKey = canonicalModifierKey(key)
        pressedKeys.remove(canonicalKey)
        dispatchKeyEvent("keyup", canonicalKey)
    }

    /**
     * Playwright `keyboard.press`: combinations such as `"Control+A"`/`"ControlOrMeta+A"` (Meta on iOS),
     * default actions (text insertion, select-all, Enter, Tab, Backspace, arrows) and [PressOptions.delayMs]
     * between keydown and keyup. Modifiers held with [down] apply to the key.
     */
    override suspend fun press(key: String, options: PressOptions): Unit = config.inputAction(options.timeoutMs, "keyboard press") {
        focused.press(key, options.delayMs, pressedKeys.filter { it.isModifierKey() }.toSet())
    }

    internal fun isModifierPressed(key: String): Boolean = pressedKeys.contains(canonicalModifierKey(key))

    internal fun modifiers(): Set<String> = pressedKeys.toSet()

    /** Returns false when a listener called preventDefault(). */
    private suspend fun dispatchKeyEvent(type: String, key: String, physicalKey: String = key): Boolean {
        val keyCode = domKeyCodeForPhysicalKey(physicalKey)
        val code = domCodeForPhysicalKey(physicalKey)
        val jsKey = jsStringLiteral(key)
        val jsCode = jsStringLiteral(code)

        return controller.evaluateJavaScriptAsync("""
            (function() {
                var target = document.activeElement || document;
                var event = new KeyboardEvent('${escapeJsString(type)}', {
                    bubbles: true,
                    cancelable: true,
                    key: $jsKey,
                    code: $jsCode,
                    keyCode: $keyCode,
                    which: $keyCode,
                    shiftKey: ${pressedKeys.contains(KeyboardModifiers.SHIFT)},
                    ctrlKey: ${pressedKeys.contains(KeyboardModifiers.CONTROL)},
                    altKey: ${pressedKeys.contains(KeyboardModifiers.ALT)},
                    metaKey: ${pressedKeys.contains(KeyboardModifiers.META)}
                });
                return target.dispatchEvent(event);
            })()
        """.trimIndent()) != "false"
    }

}
