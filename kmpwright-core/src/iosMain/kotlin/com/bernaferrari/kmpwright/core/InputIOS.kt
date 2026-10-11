package com.bernaferrari.kmpwright.core

import kotlinx.coroutines.delay

class IOSTouch(
    private val controller: WebViewController
) : Touch {

    override suspend fun tap(x: Double, y: Double, options: TapOptions) {
        injectTouchEvent("touchstart", x, y)
        delay(10)
        injectTouchEvent("touchend", x, y)
    }

    override suspend fun longTap(x: Double, y: Double, options: LongTapOptions) {
        injectTouchEvent("touchstart", x, y)
        delay(options.durationMs)
        injectTouchEvent("touchend", x, y)
    }

    override suspend fun scroll(x: Double, y: Double, deltaX: Double, deltaY: Double, options: ScrollOptions) {
        injectTouchEvent("touchstart", x, y)

        val steps = options.steps.coerceAtLeast(1)
        val stepX = deltaX / steps
        val stepY = deltaY / steps

        for (i in 1..steps) {
            val newX = x + stepX * i
            val newY = y + stepY * i
            injectTouchEvent("touchmove", newX, newY)
            delay(5)
        }

        injectTouchEvent("touchend", x + deltaX, y + deltaY)
    }

    override suspend fun swipe(x1: Double, y1: Double, x2: Double, y2: Double, options: SwipeOptions) {
        val dx = x2 - x1
        val dy = y2 - y1
        val distance = kotlin.math.sqrt(dx * dx + dy * dy).toFloat()
        val duration = (distance / options.speed * 1000).toLong()
        val steps = (duration / 16).coerceAtLeast(1)

        injectTouchEvent("touchstart", x1, y1)

        for (i in 1..steps) {
            val progress = i.toFloat() / steps
            val x = x1 + (x2 - x1) * progress
            val y = y1 + (y2 - y1) * progress
            injectTouchEvent("touchmove", x, y)
            delay(16)
        }

        injectTouchEvent("touchend", x2, y2)
    }

    override suspend fun pinch(x: Double, y: Double, scale: Double, options: PinchOptions) {
        controller.evaluateJavaScript("""
            (function() {
                var el = document.elementFromPoint($x, $y);
                if (el) {
                    var event = new Event('touchstart', { bubbles: true, touches: [new Touch({ identifier: 0, target: el, clientX: $x, clientY: $y })] });
                    el.dispatchEvent(event);
                }
            })()
        """.trimIndent()) { }
    }

    private fun injectTouchEvent(type: String, x: Double, y: Double) {
        val jsType = jsStringLiteral(type)
        controller.evaluateJavaScript("""
            (function() {
                var el = document.elementFromPoint($x, $y);
                if (el) {
                    var touch = new Touch({
                        identifier: Date.now(),
                        target: el,
                        clientX: $x,
                        clientY: $y,
                        pageX: $x,
                        pageY: $y
                    });
                    var keepTouch = $jsType !== 'touchend' && $jsType !== 'touchcancel';
                    var event = new TouchEvent($jsType, {
                        bubbles: true,
                        cancelable: true,
                        touches: keepTouch ? [touch] : [],
                        targetTouches: keepTouch ? [touch] : [],
                        changedTouches: [touch]
                    });
                    el.dispatchEvent(event);
                }
            })()
        """.trimIndent()) { }
    }
}

class IOSMouse(
    private val controller: WebViewController
) : Mouse {
    private val touch = IOSTouch(controller)
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

    override suspend fun tripleclick(x: Double, y: Double, options: MouseClickOptions) {
        click(x, y, options.copy(clickCount = 3))
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
        controller.evaluateJavaScript("""
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
                if (!accepted || !event.defaultPrevented) {
                    if (target === document.body || target === document.documentElement || target === document.scrollingElement) {
                        window.scrollBy($deltaX, $deltaY);
                    } else {
                        target.scrollLeft = (target.scrollLeft || 0) + $deltaX;
                        target.scrollTop = (target.scrollTop || 0) + $deltaY;
                    }
                }
            })()
        """.trimIndent()) { }
    }

    override suspend fun drag(startX: Double, startY: Double, endX: Double, endY: Double, options: DragOptions) {
        move(startX, startY, MoveOptions(timeoutMs = options.timeoutMs))
        down(MouseDownOptions())
        dispatchMouseEvent("dragstart", startX, startY, MouseButton.LEFT, mouseButtonsBitfield(pressedButtons), 0)
        touch.swipe(
            x1 = startX,
            y1 = startY,
            x2 = endX,
            y2 = endY,
            options = SwipeOptions(timeoutMs = options.timeoutMs)
        )
        move(endX, endY, MoveOptions(timeoutMs = options.timeoutMs))
        dispatchMouseEvent("drop", endX, endY, MouseButton.LEFT, mouseButtonsBitfield(pressedButtons), 0)
        up(MouseUpOptions())
        dispatchMouseEvent("dragend", endX, endY, MouseButton.LEFT, mouseButtonsBitfield(pressedButtons), 0)
    }

    private fun dispatchMoveEvent(x: Double, y: Double) {
        val buttons = mouseButtonsBitfield(pressedButtons)
        controller.evaluateJavaScript("""
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
        """.trimIndent()) { }
    }

    private fun dispatchMouseEvent(type: String, x: Double, y: Double, button: MouseButton, buttons: Int, detail: Int) {
        val jsType = jsStringLiteral(type)
        controller.evaluateJavaScript("""
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
        """.trimIndent()) { }
    }
}

class IOSKeyboard(
    private val controller: WebViewController
) : Keyboard {

    private val pressedKeys = mutableSetOf<String>()

    override suspend fun insertText(text: String) {
        val jsText = jsStringLiteral(text)
        controller.evaluateJavaScript("""
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
        """.trimIndent()) { }
    }

    override suspend fun type(text: String, options: TypeOptions) {
        for (unit in text.toUnicodeTextUnits()) {
            insertText(unit)
            delay(options.delayMs)
        }
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

    override suspend fun press(key: String, options: PressOptions) {
        val canonicalKey = canonicalModifierKey(key)
        val eventKey = keyValueForPhysicalKey(canonicalKey, pressedKeys)
        val printableText = textForKeyPress(canonicalKey, pressedKeys)
        when {
            printableText != null && !canonicalKey.isModifierKey() -> {
                dispatchKeyEvent("keydown", eventKey, canonicalKey)
                if (options.delayMs > 0) delay(options.delayMs)
                dispatchKeyEvent("keypress", eventKey, canonicalKey)
                insertText(printableText)
                dispatchKeyEvent("keyup", eventKey, canonicalKey)
            }
            isSelectAllShortcut(canonicalKey, pressedKeys) -> {
                dispatchKeyEvent("keydown", eventKey, canonicalKey)
                if (options.delayMs > 0) delay(options.delayMs)
                applyShortcutKey(canonicalKey)
                dispatchKeyEvent("keyup", eventKey, canonicalKey)
            }
            canonicalKey.shouldApplyDomEditing() -> {
                dispatchKeyEvent("keydown", canonicalKey)
                if (options.delayMs > 0) delay(options.delayMs)
                applyEditingKey(canonicalKey)
                dispatchKeyEvent("keyup", canonicalKey)
            }
            else -> {
                down(canonicalKey)
                if (options.delayMs > 0) delay(options.delayMs)
                up(canonicalKey)
            }
        }
    }

    override suspend fun downAndUp(key: String, options: TypeOptions) {
        press(key, PressOptions(delayMs = options.delayMs, timeoutMs = options.timeoutMs))
    }

    override fun isModifierPressed(key: String): Boolean {
        return pressedKeys.contains(canonicalModifierKey(key))
    }

    override fun modifiers(): Set<String> {
        return pressedKeys.toSet()
    }

    private fun dispatchKeyEvent(type: String, key: String, physicalKey: String = key) {
        val keyCode = domKeyCodeForPhysicalKey(physicalKey)
        val code = domCodeForPhysicalKey(physicalKey)
        val jsKey = jsStringLiteral(key)
        val jsCode = jsStringLiteral(code)

        controller.evaluateJavaScript("""
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
                target.dispatchEvent(event);
            })()
        """.trimIndent()) { }
    }

    private fun applyShortcutKey(key: String) {
        controller.evaluateJavaScript("""
            (function() {
                var active = document.activeElement;
                var key = ${jsStringLiteral(key)};
                if (!active) return false;

                if (key.toLowerCase() === 'a') {
                    if (active.isContentEditable) {
                        var selection = window.getSelection();
                        if (!selection) return false;
                        var range = document.createRange();
                        range.selectNodeContents(active);
                        selection.removeAllRanges();
                        selection.addRange(range);
                        return true;
                    }
                    if (active.tagName === 'INPUT' || active.tagName === 'TEXTAREA') {
                        var value = active.value || '';
                        active.selectionStart = 0;
                        active.selectionEnd = value.length;
                        return true;
                    }
                }

                return false;
            })()
        """.trimIndent()) { }
    }

    private fun applyEditingKey(key: String) {
        controller.evaluateJavaScript("""
            (function() {
                var active = document.activeElement;
                if (!active) return false;
                var key = ${jsStringLiteral(key)};
                var isTextControl = active.tagName === 'INPUT' || active.tagName === 'TEXTAREA';
                var isEditable = isTextControl || active.isContentEditable;
                var shiftHeld = ${pressedKeys.contains(KeyboardModifiers.SHIFT)};

                if (key === 'Tab') {
                    var tabbables = Array.from(document.querySelectorAll('a[href], button, input, textarea, select, [tabindex]:not([tabindex="-1"])'))
                        .filter(function(el) {
                            return !el.disabled && el.tabIndex >= 0 && el.offsetParent !== null;
                        });
                    if (tabbables.length === 0) return false;
                    var index = tabbables.indexOf(active);
                    var next = shiftHeld
                        ? (index <= 0 ? tabbables.length - 1 : index - 1)
                        : (index + 1) % tabbables.length;
                    tabbables[next].focus();
                    return true;
                }

                if (key === 'Escape') {
                    if (active.blur) active.blur();
                    return true;
                }

                if (!isEditable) {
                    if (key === 'Enter' && active.form && active.form.requestSubmit) {
                        active.form.requestSubmit();
                        return true;
                    }
                    return false;
                }

                if (active.isContentEditable) {
                    if (key === 'Enter') {
                        document.execCommand('insertLineBreak');
                        active.dispatchEvent(new Event('input', { bubbles: true }));
                        return true;
                    }
                    if (key === 'Backspace') {
                        document.execCommand('delete');
                        active.dispatchEvent(new Event('input', { bubbles: true }));
                        return true;
                    }
                    if (key === 'Delete') {
                        document.execCommand('forwardDelete');
                        active.dispatchEvent(new Event('input', { bubbles: true }));
                        return true;
                    }
                    return false;
                }

                var value = active.value || '';
                var start = active.selectionStart || 0;
                var end = active.selectionEnd || start;

                function updateValue(nextValue, nextStart, nextEnd) {
                    active.value = nextValue;
                    active.selectionStart = nextStart;
                    active.selectionEnd = nextEnd;
                    active.dispatchEvent(new Event('input', { bubbles: true }));
                }

                switch (key) {
                    case 'Backspace':
                        if (start !== end) {
                            updateValue(value.substring(0, start) + value.substring(end), start, start);
                        } else if (start > 0) {
                            updateValue(value.substring(0, start - 1) + value.substring(end), start - 1, start - 1);
                        }
                        return true;
                    case 'Delete':
                        if (start !== end) {
                            updateValue(value.substring(0, start) + value.substring(end), start, start);
                        } else if (start < value.length) {
                            updateValue(value.substring(0, start) + value.substring(start + 1), start, start);
                        }
                        return true;
                    case 'Enter':
                        if (active.tagName === 'TEXTAREA') {
                            updateValue(value.substring(0, start) + '\\n' + value.substring(end), start + 1, start + 1);
                        } else if (active.form && active.form.requestSubmit) {
                            active.form.requestSubmit();
                        } else {
                            active.dispatchEvent(new Event('change', { bubbles: true }));
                        }
                        return true;
                    case 'ArrowLeft':
                        active.selectionStart = active.selectionEnd = Math.max(0, start - 1);
                        return true;
                    case 'ArrowRight':
                        active.selectionStart = active.selectionEnd = Math.min(value.length, end + 1);
                        return true;
                    case 'Home':
                        active.selectionStart = active.selectionEnd = 0;
                        return true;
                    case 'End':
                        active.selectionStart = active.selectionEnd = value.length;
                        return true;
                    default:
                        return false;
                }
            })()
        """.trimIndent()) { }
    }

}
