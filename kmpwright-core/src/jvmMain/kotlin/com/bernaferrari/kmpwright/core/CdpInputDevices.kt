package com.bernaferrari.kmpwright.core

import kotlinx.coroutines.delay
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal class CdpInputState {
    val pressedModifiers = linkedSetOf<String>()
    var configuredTouchPoints = 0
    fun modifierMask(): Int = pressedModifiers.fold(0) { mask, key -> mask or when (key) {
        KeyboardModifiers.ALT -> 1
        KeyboardModifiers.CONTROL -> 2
        KeyboardModifiers.META -> 4
        KeyboardModifiers.SHIFT -> 8
        else -> 0
    } }
}

internal interface CdpInputBridge {
    val inputState: CdpInputState
    suspend fun rectangleInRoot(rect: Rectangle): Rectangle = rect
    suspend fun prepareFrameScreenshot() {}
    suspend fun pointerInRoot(x: Double, y: Double, force: Boolean): Pair<Double, Double> = x to y
    suspend fun sendCdpCommand(method: String, params: Map<String, Any?> = emptyMap()): CdpResponse
}

private fun CdpResponse.throwIfError(method: String) {
    error?.let {
        throw KmpwrightException("CDP error for $method (${it.code}): ${it.message}")
    }
}

private suspend fun CdpInputBridge.sendOrThrow(
    method: String,
    params: Map<String, Any?> = emptyMap()
): CdpResponse {
    val response = sendCdpCommand(method, params)
    response.throwIfError(method)
    return response
}

private data class CdpKeyInfo(
    val key: String,
    val code: String,
    val keyCode: Int,
    val text: String? = null
)

internal class CdpMouse(
    private val bridge: CdpInputBridge,
    private val config: KmpwrightConfig = KmpwrightConfig()
) : Mouse {
    private var currentX = 0.0
    private var currentY = 0.0
    private var pressedButtons = 0

    override suspend fun move(x: Double, y: Double, steps: Int, timeoutMs: Long?) {
        val stepCount = steps.coerceAtLeast(1)
        if (stepCount == 1) {
            dispatchMove(x, y)
            return
        }

        val startX = currentX
        val startY = currentY
        for (step in 1..stepCount) {
            val progress = step.toDouble() / stepCount
            dispatchMove(
                startX + ((x - startX) * progress),
                startY + ((y - startY) * progress)
            )
        }
    }

    override suspend fun click(x: Double, y: Double, button: MouseButton, clickCount: Int, delayMs: Long, timeoutMs: Long?) {
        move(x, y, timeoutMs = timeoutMs)
        // Like Playwright, a click with clickCount=N dispatches N press/release
        // pairs with increasing clickCount so the page observes N `click`
        // events followed by `dblclick` (N >= 2), instead of a single click.
        // delayMs is the time between mousedown and mouseup (and between clicks).
        val clicks = clickCount.coerceAtLeast(1)
        for (count in 1..clicks) {
            val acquired = pressedButtons and buttonMask(button) == 0
            try {
                if (acquired) down(button, count)
                if (delayMs > 0) delay(delayMs)
            } finally {
                if (acquired) release(button, count)
            }
            if (count < clicks && delayMs > 0) delay(delayMs)
        }
    }

    private suspend fun release(button: MouseButton, clickCount: Int = 1) {
        withContext(NonCancellable) {
            withTimeout(1_000) {
                pressedButtons = pressedButtons and buttonMask(button).inv()
                dispatchButtonEvent("mouseReleased", currentX, currentY, button, clickCount, pressedButtons)
            }
        }
    }

    override suspend fun dblclick(x: Double, y: Double, button: MouseButton, delayMs: Long, timeoutMs: Long?) {
        click(x, y, button, 2, delayMs, timeoutMs)
    }

    override suspend fun down(button: MouseButton, clickCount: Int) {
        pressedButtons = pressedButtons or buttonMask(button)
        dispatchButtonEvent(
            type = "mousePressed",
            x = currentX,
            y = currentY,
            button = button,
            clickCount = clickCount,
            buttons = pressedButtons
        )
    }

    override suspend fun up(button: MouseButton) {
        pressedButtons = pressedButtons and buttonMask(button).inv()
        dispatchButtonEvent(
            type = "mouseReleased",
            x = currentX,
            y = currentY,
            button = button,
            clickCount = 1,
            buttons = pressedButtons
        )
    }

    override suspend fun wheel(deltaX: Double, deltaY: Double, timeoutMs: Long?) {
        bridge.sendOrThrow(
            CdpMethods.INPUT_DISPATCH_MOUSE_EVENT,
            mapOf(
                "type" to "mouseWheel",
                "x" to currentX,
                "y" to currentY,
                "deltaX" to deltaX,
                "deltaY" to deltaY,
                "button" to "none",
                "modifiers" to bridge.inputState.modifierMask(),
                "buttons" to pressedButtons
            )
        )
    }

    override suspend fun drag(startX: Double, startY: Double, endX: Double, endY: Double, timeoutMs: Long?): Unit = config.inputAction(timeoutMs, "mouse drag") {
        move(startX, startY)
        val acquired = pressedButtons and buttonMask(MouseButton.Left) == 0
        try {
            if (acquired) down()
            val steps = max(2, max(kotlin.math.abs(endX - startX).roundToInt(),
                kotlin.math.abs(endY - startY).roundToInt()) / 24)
            for (step in 1..steps) {
                val progress = step.toDouble() / steps
                dispatchMove(startX + ((endX - startX) * progress), startY + ((endY - startY) * progress))
            }
        } finally {
            if (acquired) release(MouseButton.Left)
        }
    }

    private suspend fun dispatchMove(x: Double, y: Double) {
        currentX = x
        currentY = y
        bridge.sendOrThrow(
            CdpMethods.INPUT_DISPATCH_MOUSE_EVENT,
            mapOf(
                "type" to "mouseMoved",
                "x" to x,
                "y" to y,
                "button" to "none",
                "modifiers" to bridge.inputState.modifierMask(),
                "buttons" to pressedButtons
            )
        )
    }

    private suspend fun dispatchButtonEvent(
        type: String,
        x: Double,
        y: Double,
        button: MouseButton,
        clickCount: Int,
        buttons: Int
    ) {
        bridge.sendOrThrow(
            CdpMethods.INPUT_DISPATCH_MOUSE_EVENT,
            mapOf(
                "type" to type,
                "x" to x,
                "y" to y,
                "button" to button.cdpName(),
                "modifiers" to bridge.inputState.modifierMask(),
                "buttons" to buttons,
                "clickCount" to clickCount
            )
        )
    }

    private fun buttonMask(button: MouseButton): Int = when (button) {
        MouseButton.Left -> 1
        MouseButton.Right -> 2
        MouseButton.Middle -> 4
    }

    private fun MouseButton.cdpName(): String = when (this) {
        MouseButton.Left -> "left"
        MouseButton.Middle -> "middle"
        MouseButton.Right -> "right"
    }
}

internal class CdpKeyboard(
    private val bridge: CdpInputBridge,
    private val config: KmpwrightConfig = KmpwrightConfig()
) : Keyboard {
    private val pressedKeys get() = bridge.inputState.pressedModifiers

    override suspend fun insertText(text: String) {
        bridge.sendOrThrow(CdpMethods.INPUT_INSERT_TEXT, mapOf("text" to text))
    }

    override suspend fun type(text: String, delayMs: Long, timeoutMs: Long?): Unit = config.inputAction(timeoutMs, "keyboard type") {
        for (textUnit in text.toUnicodeTextUnits()) {
            val keyInfo = textUnit.toSingleCharCdpKeyInfo()
            if (keyInfo.code.isEmpty() || keyInfo.keyCode == 0) {
                insertText(textUnit)
            } else {
                dispatchKeyEvent("keyDown", keyInfo, modifierMask(), keyInfo.text)
                dispatchKeyEvent("keyUp", keyInfo, modifierMask())
            }
            if (delayMs > 0) {
                delay(delayMs)
            }
        }
    }

    override suspend fun down(key: String) {
        val canonicalKey = canonicalModifierKey(key)
        val isModifier = canonicalKey.isModifierKey()
        if (isModifier) {
            pressedKeys.add(canonicalKey)
        }
        val keyInfo = cdpKeyInfoFor(canonicalKey)
        dispatchKeyEvent(
            type = "keyDown",
            keyInfo = keyInfo,
            modifiers = modifierMask(),
            text = keyInfo.text.takeUnless { modifierMaskExcludesText() },
            commands = macEditingCommands(keyInfo.code)
        )
    }

    /**
     * macOS Chromium runs Cmd shortcuts through menu commands, which synthetic key events never reach;
     * like Playwright, name the editing command explicitly (`ControlOrMeta+A` selects all on every OS).
     */
    private fun macEditingCommands(code: String): List<String>? {
        if (controlOrMetaModifier() != KeyboardModifiers.META || KeyboardModifiers.META !in pressedKeys) return null
        val shift = KeyboardModifiers.SHIFT in pressedKeys
        val command = when (code) {
            "KeyA" -> "selectAll"
            "KeyC" -> "copy"
            "KeyX" -> "cut"
            "KeyV" -> "paste"
            "KeyZ" -> if (shift) "redo" else "undo"
            "ArrowLeft" -> if (shift) "moveToLeftEndOfLineAndModifySelection" else "moveToLeftEndOfLine"
            "ArrowRight" -> if (shift) "moveToRightEndOfLineAndModifySelection" else "moveToRightEndOfLine"
            "ArrowUp" -> if (shift) "moveToBeginningOfDocumentAndModifySelection" else "moveToBeginningOfDocument"
            "ArrowDown" -> if (shift) "moveToEndOfDocumentAndModifySelection" else "moveToEndOfDocument"
            "Backspace" -> "deleteToBeginningOfLine"
            else -> return null
        }
        return listOf(command)
    }

    override suspend fun up(key: String) {
        val canonicalKey = canonicalModifierKey(key)
        val keyInfo = cdpKeyInfoFor(canonicalKey)
        dispatchKeyEvent("keyUp", keyInfo, modifierMask())
        if (canonicalKey.isModifierKey()) {
            pressedKeys.remove(canonicalKey)
        }
    }

    override suspend fun press(key: String, delayMs: Long, timeoutMs: Long?): Unit = config.inputAction(timeoutMs, "keyboard press") {
        // Playwright: modifiers down in order, key down, delayMs, key up, modifiers up in reverse order.
        val (mainKey, modifiers) = parseKeyCombination(key)
        val acquired = mutableListOf<String>()
        try {
            for (modifier in modifiers) {
                if (!isModifierPressed(modifier)) { down(modifier); acquired += modifier }
            }
            down(mainKey)
            acquired += mainKey
            if (delayMs > 0) delay(delayMs)
        } finally {
            withContext(NonCancellable) {
                withTimeout(1_000) { acquired.asReversed().forEach { up(it) } }
            }
        }
    }

    internal fun isModifierPressed(key: String): Boolean = pressedKeys.contains(canonicalModifierKey(key))

    internal fun modifiers(): Set<String> = pressedKeys.toSet()

    private suspend fun dispatchKeyEvent(
        type: String,
        keyInfo: CdpKeyInfo,
        modifiers: Int,
        text: String? = null,
        commands: List<String>? = null
    ) {
        bridge.sendOrThrow(
            CdpMethods.INPUT_DISPATCH_KEY_EVENT,
            mapOf(
                "type" to type,
                "key" to keyInfo.key,
                "code" to keyInfo.code,
                // Like Playwright, no nativeVirtualKeyCode: Windows key codes are different native keys on
                // macOS (49 is Space there), which made Chromium run unrelated native shortcuts.
                "windowsVirtualKeyCode" to keyInfo.keyCode,
                "modifiers" to modifiers,
                "text" to text,
                "unmodifiedText" to text,
                "commands" to commands
            ).filterValues { it != null }
        )
    }

    private fun modifierMask(): Int {
        var mask = 0
        if (pressedKeys.contains(KeyboardModifiers.ALT)) mask = mask or 1
        if (pressedKeys.contains(KeyboardModifiers.CONTROL)) mask = mask or 2
        if (pressedKeys.contains(KeyboardModifiers.META)) mask = mask or 4
        if (pressedKeys.contains(KeyboardModifiers.SHIFT)) mask = mask or 8
        return mask
    }

    private fun modifierMaskExcludesText(): Boolean {
        val active = pressedKeys - KeyboardModifiers.SHIFT
        return active.isNotEmpty()
    }

    private fun cdpKeyInfoFor(key: String): CdpKeyInfo {
        val descriptor = describeSyntheticKeyPress(key, pressedKeys)
        return CdpKeyInfo(
            key = descriptor.key,
            code = descriptor.code,
            keyCode = descriptor.keyCode,
            text = descriptor.text
        )
    }
}

internal class CdpTouch(
    private val bridge: CdpInputBridge,
    private val config: KmpwrightConfig = KmpwrightConfig()
) : Touchscreen {

    override suspend fun tap(x: Double, y: Double, modifiers: Int, timeoutMs: Long?): Unit = config.inputAction(timeoutMs, "tap") {
        ensureTouchEmulation(maxTouchPoints = 1)
        sendTouchEvent("touchStart", listOf(touchPoint(0, x, y)), modifiers)
        try {
        delay(10)
        } finally {
            withContext(NonCancellable) { withTimeout(1_000) { sendTouchEvent("touchEnd", emptyList(), modifiers) } }
        }
    }

    override suspend fun longTap(x: Double, y: Double, durationMs: Long, timeoutMs: Long?) {
        ensureTouchEmulation(maxTouchPoints = 1)
        sendTouchEvent("touchStart", listOf(touchPoint(0, x, y)))
        try {
        delay(durationMs)
        } finally {
            withContext(NonCancellable) { withTimeout(1_000) { sendTouchEvent("touchEnd", emptyList(), 0) } }
        }
    }

    override suspend fun scroll(x: Double, y: Double, deltaX: Double, deltaY: Double, steps: Int, timeoutMs: Long?) {
        ensureTouchEmulation(maxTouchPoints = 1)
        val stepCount = steps.coerceAtLeast(1)
        sendTouchEvent("touchStart", listOf(touchPoint(0, x, y)))
        try {
        for (step in 1..stepCount) {
            val progress = step.toDouble() / stepCount
            sendTouchEvent(
                "touchMove",
                listOf(
                    touchPoint(
                        0,
                        x + (deltaX * progress),
                        y + (deltaY * progress)
                    )
                )
            )
            delay(8)
        }
        } finally {
            withContext(NonCancellable) { withTimeout(1_000) { sendTouchEvent("touchEnd", emptyList(), 0) } }
        }
    }

    override suspend fun swipe(x1: Double, y1: Double, x2: Double, y2: Double, speed: Double, timeoutMs: Long?) {
        ensureTouchEmulation(maxTouchPoints = 1)
        val dx = x2 - x1
        val dy = y2 - y1
        val distance = sqrt((dx * dx) + (dy * dy))
        val durationMs = ((distance / speed.coerceAtLeast(1.0)) * 1000.0).toLong().coerceAtLeast(16L)
        val steps = (durationMs / 16L).coerceAtLeast(1L).toInt()
        sendTouchEvent("touchStart", listOf(touchPoint(0, x1, y1)))
        try {
        for (step in 1..steps) {
            val progress = step.toDouble() / steps
            sendTouchEvent(
                "touchMove",
                listOf(touchPoint(0, x1 + (dx * progress), y1 + (dy * progress)))
            )
            delay(16)
        }
        } finally {
            withContext(NonCancellable) { withTimeout(1_000) { sendTouchEvent("touchEnd", emptyList(), 0) } }
        }
    }

    override suspend fun pinch(x: Double, y: Double, scale: Double, timeoutMs: Long?) {
        ensureTouchEmulation(maxTouchPoints = 2)
        val startOffset = 40.0
        val endOffset = (startOffset * scale.coerceAtLeast(0.1))
        val steps = 8
        sendTouchEvent(
            "touchStart",
            listOf(
                touchPoint(0, x - startOffset, y),
                touchPoint(1, x + startOffset, y)
            )
        )
        try {
        for (step in 1..steps) {
            val progress = step.toDouble() / steps
            val currentOffset = startOffset + ((endOffset - startOffset) * progress)
            sendTouchEvent(
                "touchMove",
                listOf(
                    touchPoint(0, x - currentOffset, y),
                    touchPoint(1, x + currentOffset, y)
                )
            )
            delay(16)
        }
        } finally {
            withContext(NonCancellable) { withTimeout(1_000) { sendTouchEvent("touchEnd", emptyList(), 0) } }
        }
    }

    private suspend fun ensureTouchEmulation(maxTouchPoints: Int) {
        if (bridge.inputState.configuredTouchPoints >= maxTouchPoints) return
        bridge.sendOrThrow(
            CdpMethods.EMULATION_SET_TOUCH_EMULATION_ENABLED,
            mapOf("enabled" to true, "maxTouchPoints" to maxTouchPoints)
        )
        bridge.inputState.configuredTouchPoints = maxTouchPoints
    }

    private suspend fun sendTouchEvent(type: String, touchPoints: List<Map<String, Any>>, modifiers: Int = 0) {
        bridge.sendOrThrow(
            CdpMethods.INPUT_DISPATCH_TOUCH_EVENT,
            mapOf(
                "type" to type,
                "touchPoints" to touchPoints,
                "modifiers" to modifiers
            )
        )
    }

    private fun touchPoint(id: Int, x: Double, y: Double): Map<String, Any> = mapOf(
        "x" to x,
        "y" to y,
        "radiusX" to 1,
        "radiusY" to 1,
        "force" to 1,
        "id" to id
    )
}

private fun Char.toCdpKeyInfo(): CdpKeyInfo = when (this) {
    '\n', '\r' -> CdpKeyInfo("Enter", "Enter", 13)
    '\t' -> CdpKeyInfo("Tab", "Tab", 9)
    ' ' -> CdpKeyInfo(" ", "Space", 32, " ")
    else -> {
        val key = toString()
        when {
            isAsciiLetter() -> CdpKeyInfo(key, "Key${uppercaseChar()}", uppercaseChar().code, key)
            isDigit() -> CdpKeyInfo(key, "Digit$this", code, key)
            else -> {
                val (code, keyCode) = punctuationKeyInfo(this)
                CdpKeyInfo(key, code, keyCode, key.takeUnless { code.isEmpty() })
            }
        }
    }
}

private fun punctuationKeyInfo(char: Char): Pair<String, Int> = when (char) {
    ';', ':' -> "Semicolon" to 186
    '=', '+' -> "Equal" to 187
    ',', '<' -> "Comma" to 188
    '-', '_' -> "Minus" to 189
    '.', '>' -> "Period" to 190
    '/', '?' -> "Slash" to 191
    '`', '~' -> "Backquote" to 192
    '[', '{' -> "BracketLeft" to 219
    '\\', '|' -> "Backslash" to 220
    ']', '}' -> "BracketRight" to 221
    '\'', '"' -> "Quote" to 222
    else -> "" to 0
}

private fun Char.isAsciiLetter(): Boolean = this in 'a'..'z' || this in 'A'..'Z'

private fun String.toUnicodeTextUnits(): List<String> =
    codePoints().toArray().map { codePoint -> String(Character.toChars(codePoint)) }

private fun String.toSingleCharCdpKeyInfo(): CdpKeyInfo {
    if (length == 1) {
        return first().toCdpKeyInfo()
    }
    return CdpKeyInfo(this, "", 0)
}
