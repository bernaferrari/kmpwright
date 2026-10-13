package com.bernaferrari.kmpwright.core

import kotlinx.coroutines.delay
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Page-keyboard input for WebView backends without a native keyboard pipeline: the same synthetic key
 * sequences as `Locator.press`/`Locator.pressSequentially`, sent to the focused element (descending
 * into open shadow roots and same-origin frames; `document.body` when nothing is focused), exactly
 * like Playwright's `page.keyboard`.
 */
internal class FocusedElementInput(
    private val controller: WebViewScriptController,
    private val config: KmpwrightConfig
) {
    /** Runs `H.perform(<focused element>, kind, args)` with the page runtime, installing it on demand. */
    suspend fun perform(kind: String, args: JsonObject): JsonElement {
        repeat(3) {
            val raw = controller.evaluateJavaScriptAsync(PageHelpers.envelope(
                "var el = H.activeElement(); return H.perform(el, ${jsStringLiteral(kind)}, $args, {x: 0, y: 0});"
            ))
            when (val outcome = PageHelpers.decode(controller.normalizeJavaScriptResult(raw))) {
                is PageHelpers.Outcome.Value -> return outcome.value
                is PageHelpers.Outcome.Error -> throw KmpwrightException("Keyboard input failed: ${outcome.message}")
                PageHelpers.Outcome.Tampered -> throw KmpwrightException(
                    "The page replaced or pre-defined the KMPwright runtime slot; refusing to automate this document"
                )
                PageHelpers.Outcome.Missing -> BaseLocator.ensureHelpersInjected(controller, config.installMode)
            }
        }
        throw KmpwrightException("Could not install the KMPwright page runtime for keyboard input")
    }

    /** Playwright `keyboard.type`: keydown, keypress, beforeinput, input and keyup for every character. */
    suspend fun type(text: String, delayMs: Long) {
        val units = text.toUnicodeTextUnits()
        if (units.isEmpty()) return
        val batches = if (delayMs > 0) units.map { listOf(it) } else listOf(units)
        for ((index, batch) in batches.withIndex()) {
            if (index > 0) delay(delayMs)
            perform("type", buildJsonObject { put("chars", typedChars(batch)) })
        }
    }

    /**
     * Playwright `keyboard.press`: modifiers down, key down (with its default action), [delayMs], key up,
     * modifiers up. [heldModifiers] (from `keyboard.down`) apply to the key without being pressed again.
     */
    suspend fun press(key: String, delayMs: Long, heldModifiers: Set<String>) {
        val args = pressArgsJson(key, heldModifiers)
        if (delayMs <= 0) {
            perform("press", args)
            return
        }
        try {
            perform("pressDown", args)
            delay(delayMs)
        } finally {
            withContext(NonCancellable) { withTimeout(1_000) { perform("pressUp", args) } }
        }
    }

    private fun typedChars(units: List<String>): JsonArray = buildJsonArray {
        units.forEach { unit ->
            add(buildJsonObject {
                put("text", unit)
                put("d", keyDescriptorJson(if (unit == "\n" || unit == "\r") "Enter" else unit, emptySet()))
            })
        }
    }
}
