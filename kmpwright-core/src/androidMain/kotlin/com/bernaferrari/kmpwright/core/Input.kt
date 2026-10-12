package com.bernaferrari.kmpwright.core

import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.webkit.WebView
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.sqrt

class AndroidTouch(
    private val controller: AndroidWebViewController
) : Touch {
    private val webView: WebView = controller.getWebView()

    override suspend fun tap(x: Double, y: Double, options: TapOptions) {
        val downTime = SystemClock.uptimeMillis()
        dispatchTouch(MotionEvent.ACTION_DOWN, x, y, downTime)
        delay(10)
        dispatchTouch(MotionEvent.ACTION_UP, x, y, downTime)
    }

    override suspend fun longTap(x: Double, y: Double, options: LongTapOptions) {
        val downTime = SystemClock.uptimeMillis()
        dispatchTouch(MotionEvent.ACTION_DOWN, x, y, downTime)
        delay(options.durationMs)
        dispatchTouch(MotionEvent.ACTION_UP, x, y, downTime)
    }

    override suspend fun scroll(x: Double, y: Double, deltaX: Double, deltaY: Double, options: ScrollOptions) {
        val downTime = SystemClock.uptimeMillis()
        val steps = options.steps.coerceAtLeast(1)
        dispatchTouch(MotionEvent.ACTION_DOWN, x, y, downTime)
        for (step in 1..steps) {
            val progress = step.toDouble() / steps
            dispatchTouch(
                MotionEvent.ACTION_MOVE,
                x + (deltaX * progress),
                y + (deltaY * progress),
                downTime
            )
            delay(8)
        }
        dispatchTouch(MotionEvent.ACTION_UP, x + deltaX, y + deltaY, downTime)
    }

    override suspend fun swipe(x1: Double, y1: Double, x2: Double, y2: Double, options: SwipeOptions) {
        val dx = x2 - x1
        val dy = y2 - y1
        val distance = sqrt((dx * dx) + (dy * dy))
        val durationMs = ((distance / options.speed.coerceAtLeast(1.0)) * 1000.0).toLong().coerceAtLeast(16L)
        val steps = (durationMs / 16L).coerceAtLeast(1L).toInt()
        val downTime = SystemClock.uptimeMillis()

        dispatchTouch(MotionEvent.ACTION_DOWN, x1, y1, downTime)
        for (step in 1..steps) {
            val progress = step.toDouble() / steps
            dispatchTouch(
                MotionEvent.ACTION_MOVE,
                x1 + (dx * progress),
                y1 + (dy * progress),
                downTime
            )
            delay(16)
        }
        dispatchTouch(MotionEvent.ACTION_UP, x2, y2, downTime)
    }

    override suspend fun pinch(x: Double, y: Double, scale: Double, options: PinchOptions) {
        runOnUiThread(webView) {
            webView.zoomBy(scale.toFloat().coerceAtLeast(0.1f))
        }
    }

    private suspend fun dispatchTouch(action: Int, x: Double, y: Double, downTime: Long) {
        runOnUiThread(webView) {
            webView.requestFocus()
            val event = MotionEvent.obtain(
                downTime,
                SystemClock.uptimeMillis(),
                action,
                x.toFloat(),
                y.toFloat(),
                0
            )
            try {
                webView.dispatchTouchEvent(event)
            } finally {
                event.recycle()
            }
        }
    }
}

class AndroidMouse(
    private val controller: AndroidWebViewController
) : Mouse {
    private val webView: WebView = controller.getWebView()
    private val touch = AndroidTouch(controller)

    override suspend fun move(x: Double, y: Double, options: MoveOptions) {
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

    override suspend fun click(x: Double, y: Double, options: MouseClickOptions) {
        repeat(options.clickCount.coerceAtLeast(1)) {
            touch.tap(x, y, TapOptions(timeoutMs = options.timeoutMs))
            if (options.delayMs > 0) {
                delay(options.delayMs)
            }
        }
    }

    override suspend fun dblclick(x: Double, y: Double, options: MouseClickOptions) {
        click(x, y, options.copy(clickCount = 2))
    }

    override suspend fun tripleclick(x: Double, y: Double, options: MouseClickOptions) {
        click(x, y, options.copy(clickCount = 3))
    }

    override suspend fun down(options: MouseDownOptions) {
        controller.evaluateJavaScript(
            """
            document.dispatchEvent(new MouseEvent('mousedown', {
                bubbles: true,
                cancelable: true,
                button: ${options.button.ordinal}
            }))
            """.trimIndent()
        ) { }
    }

    override suspend fun up(options: MouseUpOptions) {
        controller.evaluateJavaScript(
            """
            document.dispatchEvent(new MouseEvent('mouseup', {
                bubbles: true,
                cancelable: true,
                button: ${options.button.ordinal}
            }))
            """.trimIndent()
        ) { }
    }

    override suspend fun wheel(deltaX: Double, deltaY: Double, options: WheelOptions) {
        runOnUiThread(webView) {
            webView.scrollBy(deltaX.toInt(), deltaY.toInt())
        }
    }

    override suspend fun drag(startX: Double, startY: Double, endX: Double, endY: Double, options: DragOptions) {
        touch.swipe(startX, startY, endX, endY)
    }
}

class AndroidKeyboard(
    private val controller: AndroidWebViewController
) : Keyboard {
    private val webView: WebView = controller.getWebView()
    private val pressedKeys = linkedSetOf<String>()
    private val keyDownTimes = mutableMapOf<String, Long>()

    override suspend fun insertText(text: String) {
        // A WebView InputConnection can still reference the previous editor
        // after DOM focus changes. Insert atomically in the renderer instead.
        insertTextViaDom(text)
    }

    override suspend fun type(text: String, options: TypeOptions) {
        for (unit in text.toUnicodeTextUnits()) {
            insertText(unit)
            if (options.delayMs > 0) {
                delay(options.delayMs)
            }
        }
    }

    override suspend fun down(key: String) {
        val canonicalKey = canonicalModifierKey(key)
        if (canonicalKey.isModifierKey()) {
            pressedKeys.add(canonicalKey)
        }

        val keyCode = canonicalKey.toAndroidKeyCode()
        if (keyCode == KeyEvent.KEYCODE_UNKNOWN) {
            if (canonicalKey.length == 1) {
                insertText(canonicalKey)
            }
            return
        }

        val downTime = SystemClock.uptimeMillis()
        keyDownTimes[canonicalKey] = downTime
        dispatchKeyEvent(KeyEvent.ACTION_DOWN, keyCode, downTime)
    }

    override suspend fun up(key: String) {
        val canonicalKey = canonicalModifierKey(key)
        val keyCode = canonicalKey.toAndroidKeyCode()
        val downTime = keyDownTimes.remove(canonicalKey) ?: SystemClock.uptimeMillis()
        if (keyCode != KeyEvent.KEYCODE_UNKNOWN) {
            dispatchKeyEvent(KeyEvent.ACTION_UP, keyCode, downTime)
        }
        if (canonicalKey.isModifierKey()) {
            pressedKeys.remove(canonicalKey)
        }
    }

    override suspend fun press(key: String, options: PressOptions) {
        down(key)
        if (options.delayMs > 0) {
            delay(options.delayMs)
        }
        up(key)
    }

    override suspend fun downAndUp(key: String, options: TypeOptions) {
        down(key)
        if (options.delayMs > 0) {
            delay(options.delayMs)
        }
        up(key)
    }

    override fun isModifierPressed(key: String): Boolean = pressedKeys.contains(canonicalModifierKey(key))

    override fun modifiers(): Set<String> = pressedKeys.toSet()

    private suspend fun dispatchKeyEvent(action: Int, keyCode: Int, downTime: Long) {
        runOnUiThread(webView) {
            webView.requestFocus()
            val event = KeyEvent(
                downTime,
                SystemClock.uptimeMillis(),
                action,
                keyCode,
                0,
                currentMetaState()
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

private suspend fun <T> runOnUiThread(webView: WebView, block: () -> T): T =
    suspendCancellableCoroutine { continuation ->
        webView.post {
            continuation.resume(block())
        }
    }

private fun canonicalModifierKey(key: String): String = when (key.lowercase()) {
    "shift" -> KeyboardModifiers.SHIFT
    "control", "ctrl" -> KeyboardModifiers.CONTROL
    "alt", "option" -> KeyboardModifiers.ALT
    "meta", "cmd", "command" -> KeyboardModifiers.META
    else -> key
}

private fun String.isModifierKey(): Boolean = when (this) {
    KeyboardModifiers.SHIFT,
    KeyboardModifiers.CONTROL,
    KeyboardModifiers.ALT,
    KeyboardModifiers.META -> true
    else -> false
}

private fun String.toAndroidKeyCode(): Int = when (lowercase()) {
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
    "space", " " -> KeyEvent.KEYCODE_SPACE
    "shift" -> KeyEvent.KEYCODE_SHIFT_LEFT
    "control", "ctrl" -> KeyEvent.KEYCODE_CTRL_LEFT
    "alt", "option" -> KeyEvent.KEYCODE_ALT_LEFT
    "meta", "cmd", "command" -> KeyEvent.KEYCODE_META_LEFT
    else -> when {
        length == 1 -> first().toAndroidKeyCode()
        else -> KeyEvent.KEYCODE_UNKNOWN
    }
}

private fun Char.toAndroidKeyCode(): Int = when {
    this in 'a'..'z' || this in 'A'..'Z' ->
        KeyEvent.KEYCODE_A + (uppercaseChar().code - 'A'.code)
    this in '0'..'9' ->
        KeyEvent.KEYCODE_0 + (code - '0'.code)
    else -> when (this) {
        ';', ':' -> KeyEvent.KEYCODE_SEMICOLON
        '=', '+' -> KeyEvent.KEYCODE_EQUALS
        ',', '<' -> KeyEvent.KEYCODE_COMMA
        '-', '_' -> KeyEvent.KEYCODE_MINUS
        '.', '>' -> KeyEvent.KEYCODE_PERIOD
        '/', '?' -> KeyEvent.KEYCODE_SLASH
        '`', '~' -> KeyEvent.KEYCODE_GRAVE
        '[', '{' -> KeyEvent.KEYCODE_LEFT_BRACKET
        '\\', '|' -> KeyEvent.KEYCODE_BACKSLASH
        ']', '}' -> KeyEvent.KEYCODE_RIGHT_BRACKET
        '\'', '"' -> KeyEvent.KEYCODE_APOSTROPHE
        else -> KeyEvent.KEYCODE_UNKNOWN
    }
}

private fun String.toUnicodeTextUnits(): List<String> =
    codePoints().toArray().map { codePoint -> String(Character.toChars(codePoint)) }

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
