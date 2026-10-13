package com.bernaferrari.kmpwright.core

import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.webkit.WebView
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Native touch input. Coordinates are CSS pixels relative to the layout
 * viewport (the same space as `Locator.boundingBox`), converted to WebView
 * device pixels once per gesture.
 */
internal class AndroidTouch(
    private val controller: AndroidWebViewController,
    /** Resolves per-call timeouts; a null `timeoutMs` uses [TimeoutConfig.action]. */
    private val config: KmpwrightConfig = KmpwrightConfig()
) : Touchscreen {
    private val webView: WebView = controller.getWebView()

    override suspend fun tap(x: Double, y: Double, modifiers: Int, timeoutMs: Long?): Unit = config.inputAction(timeoutMs, "tap") {
        press(x, y, 10)
    }

    /** Touch down at ([x], [y]), hold for [holdMs], release. */
    internal suspend fun press(x: Double, y: Double, holdMs: Long) {
        val transform = webView.cssToViewTransform()
        val downTime = SystemClock.uptimeMillis()
        dispatchTouch(MotionEvent.ACTION_DOWN, transform.x(x), transform.y(y), downTime)
        delay(holdMs.coerceAtLeast(10))
        dispatchTouch(MotionEvent.ACTION_UP, transform.x(x), transform.y(y), downTime)
    }

    override suspend fun longTap(x: Double, y: Double, durationMs: Long, timeoutMs: Long?) {
        val transform = webView.cssToViewTransform()
        val downTime = SystemClock.uptimeMillis()
        dispatchTouch(MotionEvent.ACTION_DOWN, transform.x(x), transform.y(y), downTime)
        delay(durationMs)
        dispatchTouch(MotionEvent.ACTION_UP, transform.x(x), transform.y(y), downTime)
    }

    override suspend fun scroll(x: Double, y: Double, deltaX: Double, deltaY: Double, steps: Int, timeoutMs: Long?) {
        val transform = webView.cssToViewTransform()
        val downTime = SystemClock.uptimeMillis()
        val stepCount = steps.coerceAtLeast(1)
        dispatchTouch(MotionEvent.ACTION_DOWN, transform.x(x), transform.y(y), downTime)
        for (step in 1..stepCount) {
            val progress = step.toDouble() / stepCount
            dispatchTouch(
                MotionEvent.ACTION_MOVE,
                transform.x(x + (deltaX * progress)),
                transform.y(y + (deltaY * progress)),
                downTime
            )
            delay(8)
        }
        dispatchTouch(MotionEvent.ACTION_UP, transform.x(x + deltaX), transform.y(y + deltaY), downTime)
    }

    override suspend fun swipe(x1: Double, y1: Double, x2: Double, y2: Double, speed: Double, timeoutMs: Long?) {
        val transform = webView.cssToViewTransform()
        val dx = x2 - x1
        val dy = y2 - y1
        val distance = sqrt((dx * dx) + (dy * dy))
        val durationMs = ((distance / speed.coerceAtLeast(1.0)) * 1000.0).toLong().coerceAtLeast(16L)
        val steps = (durationMs / 16L).coerceAtLeast(1L).toInt()
        val downTime = SystemClock.uptimeMillis()

        dispatchTouch(MotionEvent.ACTION_DOWN, transform.x(x1), transform.y(y1), downTime)
        for (step in 1..steps) {
            val progress = step.toDouble() / steps
            dispatchTouch(
                MotionEvent.ACTION_MOVE,
                transform.x(x1 + (dx * progress)),
                transform.y(y1 + (dy * progress)),
                downTime
            )
            delay(16)
        }
        dispatchTouch(MotionEvent.ACTION_UP, transform.x(x2), transform.y(y2), downTime)
    }

    override suspend fun pinch(x: Double, y: Double, scale: Double, timeoutMs: Long?) {
        onMain {
            webView.zoomBy(scale.toFloat().coerceIn(0.01f, 100f))
        }
    }

    /** [x]/[y] are WebView-local device pixels. */
    private suspend fun dispatchTouch(action: Int, x: Float, y: Float, downTime: Long) {
        onMain {
            webView.requestFocus()
            val properties = arrayOf(MotionEvent.PointerProperties().apply {
                id = 0
                toolType = MotionEvent.TOOL_TYPE_FINGER
            })
            val coordinates = arrayOf(MotionEvent.PointerCoords().apply {
                this.x = x
                this.y = y
                pressure = 1f
                size = 1f
            })
            val event = MotionEvent.obtain(
                downTime, SystemClock.uptimeMillis(), action, 1, properties, coordinates,
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0
            )
            try {
                webView.dispatchTouchEvent(event)
            } finally {
                event.recycle()
            }
        }
    }
}

internal class AndroidMouse(
    private val controller: AndroidWebViewController,
    /** Resolves per-call timeouts; a null `timeoutMs` uses [TimeoutConfig.action]. */
    private val config: KmpwrightConfig = KmpwrightConfig()
) : Mouse {
    private val webView: WebView = controller.getWebView()
    private val touch = AndroidTouch(controller, config)

    override suspend fun move(x: Double, y: Double, steps: Int, timeoutMs: Long?) {
        controller.evaluateJavaScript(
            """
            (function() {
                var el = document.elementFromPoint($x, $y);
                if (el) {
                    el.dispatchEvent(new MouseEvent('mousemove', {
                        bubbles: true,
                        cancelable: true,
                        clientX: $x,
                        clientY: $y,
                        screenX: $x,
                        screenY: $y
                    }));
                }
            })()
            """.trimIndent()
        ) { }
    }

    /** Playwright semantics: [delayMs] is the time between press and release (and between clicks). */
    override suspend fun click(x: Double, y: Double, button: MouseButton, clickCount: Int, delayMs: Long, timeoutMs: Long?): Unit =
        config.inputAction(timeoutMs, "mouse click") {
            val clicks = clickCount.coerceAtLeast(1)
            repeat(clicks) { index ->
                if (index > 0 && delayMs > 0) delay(delayMs)
                touch.press(x, y, delayMs)
            }
        }

    override suspend fun dblclick(x: Double, y: Double, button: MouseButton, delayMs: Long, timeoutMs: Long?) {
        click(x, y, button, 2, delayMs, timeoutMs)
    }

    override suspend fun down(button: MouseButton, clickCount: Int) {
        controller.evaluateJavaScript(
            """
            document.dispatchEvent(new MouseEvent('mousedown', {
                bubbles: true,
                cancelable: true,
                button: ${button.ordinal}
            }))
            """.trimIndent()
        ) { }
    }

    override suspend fun up(button: MouseButton) {
        controller.evaluateJavaScript(
            """
            document.dispatchEvent(new MouseEvent('mouseup', {
                bubbles: true,
                cancelable: true,
                button: ${button.ordinal}
            }))
            """.trimIndent()
        ) { }
    }

    /** Deltas are CSS pixels. */
    override suspend fun wheel(deltaX: Double, deltaY: Double, timeoutMs: Long?) {
        val transform = webView.cssToViewTransform()
        onMain {
            webView.scrollBy(transform.length(deltaX).roundToInt(), transform.length(deltaY).roundToInt())
        }
    }

    override suspend fun drag(startX: Double, startY: Double, endX: Double, endY: Double, timeoutMs: Long?): Unit =
        config.inputAction(timeoutMs, "mouse drag") {
        touch.swipe(startX, startY, endX, endY)
    }
}

internal class AndroidKeyboard(
    private val controller: AndroidWebViewController,
    /** Resolves per-call timeouts; a null `timeoutMs` uses [TimeoutConfig.action]. */
    private val config: KmpwrightConfig = KmpwrightConfig()
) : Keyboard {
    private val webView: WebView = controller.getWebView()
    private val pressedKeys = linkedSetOf<String>()
    private val keyDownTimes = mutableMapOf<String, Long>()
    private val pendingKeyUps = mutableMapOf<String, List<KeyEvent>>()
    private val virtualKeyMap: KeyCharacterMap by lazy { KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD) }

    override suspend fun insertText(text: String) {
        // A WebView InputConnection can still reference the previous editor
        // after DOM focus changes. Insert atomically in the renderer instead.
        insertTextViaDom(text)
    }

    private val focused = FocusedElementInput(controller, config)

    /**
     * Playwright `keyboard.type`: keydown, keypress, beforeinput, input and keyup for every character,
     * sent to the focused element in the renderer (an IME [insertText] has no key events).
     */
    override suspend fun type(text: String, delayMs: Long, timeoutMs: Long?): Unit = config.inputAction(timeoutMs, "keyboard type") {
        focused.type(text, delayMs)
    }

    override suspend fun down(key: String) {
        val canonicalKey = canonicalModifierKey(key)
        if (canonicalKey.isModifierKey()) {
            pressedKeys.add(canonicalKey)
        }

        val namedKeyCode = canonicalKey.toNamedAndroidKeyCode()
        if (namedKeyCode == KeyEvent.KEYCODE_UNKNOWN && canonicalKey.length == 1) {
            // Printable characters use the virtual keyboard's real key
            // sequence, including SHIFT for uppercase and shifted symbols.
            val events = virtualKeyMap.getEvents(canonicalKey.toCharArray())
            if (events == null) {
                insertText(canonicalKey)
                return
            }
            val downTime = SystemClock.uptimeMillis()
            keyDownTimes[canonicalKey] = downTime
            pendingKeyUps[canonicalKey] = events.filter { it.action == KeyEvent.ACTION_UP }
            events.filter { it.action == KeyEvent.ACTION_DOWN }
                .forEach { dispatchKeyEvent(it.action, it.keyCode, downTime, it.metaState) }
            return
        }
        if (namedKeyCode == KeyEvent.KEYCODE_UNKNOWN) return

        val downTime = SystemClock.uptimeMillis()
        keyDownTimes[canonicalKey] = downTime
        dispatchKeyEvent(KeyEvent.ACTION_DOWN, namedKeyCode, downTime)
    }

    override suspend fun up(key: String) {
        val canonicalKey = canonicalModifierKey(key)
        val downTime = keyDownTimes.remove(canonicalKey) ?: SystemClock.uptimeMillis()
        val characterUps = pendingKeyUps.remove(canonicalKey)
        if (characterUps != null) {
            characterUps.forEach { dispatchKeyEvent(it.action, it.keyCode, downTime, it.metaState) }
        } else {
            val keyCode = canonicalKey.toNamedAndroidKeyCode()
            if (keyCode != KeyEvent.KEYCODE_UNKNOWN) {
                dispatchKeyEvent(KeyEvent.ACTION_UP, keyCode, downTime)
            }
        }
        if (canonicalKey.isModifierKey()) {
            pressedKeys.remove(canonicalKey)
        }
    }

    /**
     * Playwright `keyboard.press`: modifiers of a combination go down in order, then the key,
     * [delayMs], key up and modifiers up in reverse. Named keys (Enter, Tab, Backspace,
     * arrows...) are native [KeyEvent]s; characters and shortcuts with Control/Meta/Alt (`"Control+A"`,
     * `"ControlOrMeta+A"` = Control on Android) use the page runtime's synthetic key sequence, which
     * inserts the character or applies the shortcut's editing default (select-all).
     */
    override suspend fun press(key: String, delayMs: Long, timeoutMs: Long?): Unit = config.inputAction(timeoutMs, "keyboard press") {
        val (mainKey, modifiers) = parseKeyCombination(key)
        val held = pressedKeys.filter { it.isModifierKey() }.toSet()
        val shortcut = (modifiers + held).any { it == KeyboardModifiers.CONTROL || it == KeyboardModifiers.META || it == KeyboardModifiers.ALT }
        if (shortcut || mainKey.toUnicodeTextUnits().size == 1) {
            // Injected KeyEvents neither run editing shortcuts (Ctrl+A) nor reliably insert characters into
            // an editor focused from script (the IME connection may still point at another editor): use the
            // same synthetic sequence as Locator.press, which applies the default action of the key.
            focused.press(key, delayMs, held)
            return@inputAction
        }
        val acquired = mutableListOf<String>()
        try {
            for (modifier in modifiers) {
                if (modifier !in pressedKeys) { down(modifier); acquired += modifier }
            }
            down(mainKey)
            acquired += mainKey
            if (delayMs > 0) delay(delayMs)
        } finally {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { acquired.asReversed().forEach { up(it) } }
        }
    }

    internal fun isModifierPressed(key: String): Boolean = pressedKeys.contains(canonicalModifierKey(key))

    internal fun modifiers(): Set<String> = pressedKeys.toSet()

    private suspend fun dispatchKeyEvent(action: Int, keyCode: Int, downTime: Long, extraMetaState: Int = 0) {
        onMain {
            webView.requestFocus()
            val event = KeyEvent(
                downTime,
                SystemClock.uptimeMillis(),
                action,
                keyCode,
                0,
                currentMetaState() or extraMetaState,
                KeyCharacterMap.VIRTUAL_KEYBOARD,
                0,
                0,
                InputDevice.SOURCE_KEYBOARD
            )
            webView.dispatchKeyEvent(event)
        }
    }

    private fun currentMetaState(): Int {
        var metaState = 0
        if (pressedKeys.contains(KeyboardModifiers.SHIFT)) metaState = metaState or KeyEvent.META_SHIFT_ON
        if (pressedKeys.contains(KeyboardModifiers.CONTROL)) metaState = metaState or KeyEvent.META_CTRL_ON
        if (pressedKeys.contains(KeyboardModifiers.ALT)) metaState = metaState or KeyEvent.META_ALT_ON
        if (pressedKeys.contains(KeyboardModifiers.META)) metaState = metaState or KeyEvent.META_META_ON
        return metaState
    }

    private suspend fun insertTextViaDom(text: String) {
        val result = controller.normalizeJavaScriptResult(controller.evaluateJavaScriptAsync("""
            (function() {
                var active = document.activeElement;
                if (!active || active.disabled || active.readOnly) return 'No editable focused element';
                if (active.tagName !== 'INPUT' && active.tagName !== 'TEXTAREA' && !active.isContentEditable) return 'No editable focused element';
                if (document.execCommand('insertText', false, ${text.jsQuoted()})) return 'inserted';
                if (active.isContentEditable) {
                    var selection = window.getSelection();
                    if (!selection.rangeCount) return 'Contenteditable has no selection';
                    var range = selection.getRangeAt(0);
                    if (!active.contains(range.commonAncestorContainer)) return 'Selection belongs to another editor';
                    range.deleteContents();
                    var node = document.createTextNode(${text.jsQuoted()});
                    range.insertNode(node); range.setStartAfter(node); range.collapse(true);
                    selection.removeAllRanges(); selection.addRange(range);
                } else {
                    if (active.selectionStart == null) return 'This input type does not support text insertion';
                    var start = active.selectionStart, end = active.selectionEnd;
                    active.setRangeText(${text.jsQuoted()}, start, end, 'end');
                }
                active.dispatchEvent(new InputEvent('input', { bubbles: true, inputType: 'insertText', data: ${text.jsQuoted()} }));
                return 'inserted';
            })()
        """.trimIndent()))
        if (result != "inserted") throw KmpwrightException("Text insertion failed: $result")
    }

}

/** Non-printable named keys; printable characters go through [KeyCharacterMap]. */
private fun String.toNamedAndroidKeyCode(): Int = when (lowercase()) {
    "enter", "return" -> KeyEvent.KEYCODE_ENTER
    "tab" -> KeyEvent.KEYCODE_TAB
    "escape", "esc" -> KeyEvent.KEYCODE_ESCAPE
    "backspace" -> KeyEvent.KEYCODE_DEL
    "delete" -> KeyEvent.KEYCODE_FORWARD_DEL
    "arrowleft", "left" -> KeyEvent.KEYCODE_DPAD_LEFT
    "arrowup", "up" -> KeyEvent.KEYCODE_DPAD_UP
    "arrowright", "right" -> KeyEvent.KEYCODE_DPAD_RIGHT
    "arrowdown", "down" -> KeyEvent.KEYCODE_DPAD_DOWN
    "home" -> KeyEvent.KEYCODE_MOVE_HOME
    "end" -> KeyEvent.KEYCODE_MOVE_END
    "pageup" -> KeyEvent.KEYCODE_PAGE_UP
    "pagedown" -> KeyEvent.KEYCODE_PAGE_DOWN
    "insert" -> KeyEvent.KEYCODE_INSERT
    "capslock" -> KeyEvent.KEYCODE_CAPS_LOCK
    "f1" -> KeyEvent.KEYCODE_F1
    "f2" -> KeyEvent.KEYCODE_F2
    "f3" -> KeyEvent.KEYCODE_F3
    "f4" -> KeyEvent.KEYCODE_F4
    "f5" -> KeyEvent.KEYCODE_F5
    "f6" -> KeyEvent.KEYCODE_F6
    "f7" -> KeyEvent.KEYCODE_F7
    "f8" -> KeyEvent.KEYCODE_F8
    "f9" -> KeyEvent.KEYCODE_F9
    "f10" -> KeyEvent.KEYCODE_F10
    "f11" -> KeyEvent.KEYCODE_F11
    "f12" -> KeyEvent.KEYCODE_F12
    "space", " " -> KeyEvent.KEYCODE_SPACE
    "shift" -> KeyEvent.KEYCODE_SHIFT_LEFT
    "control", "ctrl" -> KeyEvent.KEYCODE_CTRL_LEFT
    "alt", "option" -> KeyEvent.KEYCODE_ALT_LEFT
    "meta", "cmd", "command" -> KeyEvent.KEYCODE_META_LEFT
    else -> KeyEvent.KEYCODE_UNKNOWN
}

private fun String.jsQuoted(): String =
    buildString(length + 2) {
        append('"')
        for (char in this@jsQuoted) {
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(char)
            }
        }
        append('"')
    }
