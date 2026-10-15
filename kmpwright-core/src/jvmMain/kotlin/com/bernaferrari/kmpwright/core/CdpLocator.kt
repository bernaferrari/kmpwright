package com.bernaferrari.kmpwright.core

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.TimeSource

/**
 * CDP locator: the actionability checks are the shared [BaseLocator] loop (attached, visible, enabled,
 * stable, receives events; retried until the timeout with the same error messages as the WebView
 * backends); only the final input is dispatched natively through `Input.*`.
 *
 * [frameFactory] builds a cross-origin capable frame locator scoped to this locator's elements
 * (`Locator.frameLocator`).
 */
internal class CdpLocator(
    controller: WebViewLocatorController,
    selector: String,
    config: KmpwrightConfig,
    private val options: FilterOptions? = null,
    private val frameFactory: ((selector: String, scopeSteps: String) -> FrameLocator)? = null
) : BaseLocator(controller, selector, config, options) {

    private val bridge: CdpInputBridge get() = controller as CdpInputBridge
    private val mouse by lazy { CdpMouse(bridge, config) }
    private val keyboard by lazy { CdpKeyboard(bridge, config) }

    /** Snapshot refs fail fast when stale instead of waiting for an element that cannot come back. */
    private suspend fun requireLiveRef() {
        if (parseRef(selector) != null) evalOnElement("return true;", requireElement = true)
    }

    /**
     * Waits until the element is actionable for [label] and returns the action point in root-frame
     * viewport coordinates. A frame owner that cannot receive the input is retried like any other check.
     */
    private suspend fun actionPoint(
        label: String,
        timeoutMs: Long,
        force: Boolean,
        position: Position? = null,
        checks: Checks = Checks()
    ): Pair<Double, Double> {
        val started = TimeSource.Monotonic.markNow()
        var frameFailure: KmpwrightException? = null
        fun frameTimeout(cause: KmpwrightException): KmpwrightTimeoutException =
            KmpwrightTimeoutException("Element $displayName would not receive the $label after ${timeoutMs}ms: ${cause.message}", cause)
        while (true) {
            val remaining = timeoutMs - started.elapsedNow().inWholeMilliseconds
            // Do not invent a fresh probe budget after the final frame check. A new
            // stability probe needs two frames and would obscure the known blocker.
            if (remaining <= FINAL_PROBE_MARGIN_MS) frameFailure?.let { throw frameTimeout(it) }
            val point = try {
                performAction("point", JsonObject(emptyMap()), remaining.coerceAtLeast(1), force, checks, position, actionLabel = label)
            } catch (error: KmpwrightTimeoutException) {
                frameFailure?.let { throw KmpwrightTimeoutException("${error.message}; last frame check: ${it.message}", error) }
                throw error
            } as? JsonObject
                ?: throw KmpwrightException("Cannot $label '$displayName': the page did not report an action point")
            val x = point["x"]?.jsonPrimitive?.doubleOrNull
            val y = point["y"]?.jsonPrimitive?.doubleOrNull
            if (x == null || y == null) throw KmpwrightException("Cannot $label '$displayName': the page did not report an action point")
            try {
                return bridge.pointerInRoot(x, y, force)
            } catch (error: KmpwrightException) {
                frameFailure = error
                val budget = timeoutMs - started.elapsedNow().inWholeMilliseconds - FINAL_PROBE_MARGIN_MS
                if (budget <= 0) {
                    throw frameTimeout(error)
                }
                delay(minOf(100L, budget))
            }
        }
    }

    override suspend fun click(button: MouseButton, clickCount: Int, delayMs: Long, position: Position?, force: Boolean, timeoutMs: Long?) {
        if (config.inputMode == InputMode.JsOnly) return super.click(button, clickCount, delayMs, position, force, timeoutMs)
        policyChecker?.invoke(ActionType.Click)
        nativeClick(button, clickCount.coerceAtLeast(1), delayMs, position, force, timeoutMs, "click")
    }

    /** Playwright `dblclick`: one press/release pair per click (`click` detail 1, `click` detail 2, `dblclick`). */
    override suspend fun dblclick(button: MouseButton, delayMs: Long, position: Position?, force: Boolean, timeoutMs: Long?) {
        if (config.inputMode == InputMode.JsOnly) return super.dblclick(button, delayMs, position, force, timeoutMs)
        policyChecker?.invoke(ActionType.Click)
        nativeClick(button, 2, delayMs, position, force, timeoutMs, "double-click")
    }

    private suspend fun nativeClick(
        button: MouseButton,
        clickCount: Int,
        delayMs: Long,
        position: Position?,
        force: Boolean,
        timeoutMs: Long?,
        label: String
    ): Unit = config.inputAction(timeoutMs, label) {
        val timeout = timeoutOf(timeoutMs)
        requireLiveRef()
        val (x, y) = actionPoint(label, timeout, force, position)
        if (config.inputMode == InputMode.TouchOnly) {
            val touch = CdpTouch(bridge, config)
            repeat(clickCount) { touch.tap(x, y, timeoutMs = timeout) }
            return@inputAction
        }
        // delayMs is the time between mousedown and mouseup of each click (Playwright semantics).
        mouse.click(x, y, button, clickCount, delayMs, timeout)
    }

    override suspend fun tap(force: Boolean, modifiers: Int, timeoutMs: Long?): Unit = config.inputAction(timeoutMs, "locator tap") {
        if (config.inputMode == InputMode.JsOnly) return@inputAction super.tap(force, modifiers, timeoutMs)
        policyChecker?.invoke(ActionType.Click)
        val timeout = timeoutOf(timeoutMs)
        requireLiveRef()
        val (x, y) = actionPoint("tap", timeout, force)
        CdpTouch(bridge, config).tap(x, y, modifiers, timeout)
    }

    override suspend fun hover(force: Boolean, timeoutMs: Long?): Unit = config.inputAction(timeoutMs, "locator hover") {
        if (config.inputMode != InputMode.Hybrid) return@inputAction super.hover(force, timeoutMs)
        policyChecker?.invoke(ActionType.Hover)
        val (x, y) = actionPoint("hover", timeoutOf(timeoutMs), force, checks = Checks(enabled = false))
        mouse.move(x, y)
    }

    /** Focuses an element that already passed the editable checks; selects its content for [fill]. */
    private suspend fun focusForInput(selectAll: Boolean, label: String) {
        val result = evalOnElement("""
            el.focus();
            if (el !== el.ownerDocument.activeElement && el !== el.getRootNode().activeElement) return 'not focusable';
            if ($selectAll) {
                if (el.isContentEditable) {
                    var range = document.createRange(); range.selectNodeContents(el);
                    var selection = window.getSelection(); selection.removeAllRanges(); selection.addRange(range);
                } else if (typeof el.select === 'function' && el.type !== 'file') el.select();
                else return 'not an <input>, <textarea> or [contenteditable] element';
            }
            return true;
        """.trimIndent(), requireElement = true)
        if (result != null && result != "true") throw KmpwrightException("Cannot $label '$displayName': element is $result")
    }

    override suspend fun fill(value: String, force: Boolean, timeoutMs: Long?): Unit = config.inputAction(timeoutMs, "locator fill") {
        if (config.inputMode != InputMode.Hybrid) return@inputAction super.fill(value, force, timeoutMs)
        policyChecker?.invoke(ActionType.Fill)
        requireLiveRef()
        // The same editability checks and errors as the WebView backends.
        performAction("noop", JsonObject(emptyMap()), timeoutOf(timeoutMs), force,
            Checks(editable = true, stable = false, hit = false), actionLabel = "fill")
        focusForInput(selectAll = true, label = "fill")
        if (value.isEmpty()) keyboard.press("Backspace") else keyboard.insertText(value)
    }

    override suspend fun pressSequentially(text: String, delayMs: Long, timeoutMs: Long?): Unit = config.inputAction(timeoutMs, "locator pressSequentially") {
        if (config.inputMode != InputMode.Hybrid) return@inputAction super.pressSequentially(text, delayMs, timeoutMs)
        policyChecker?.invoke(ActionType.Type)
        if (text.isEmpty()) return@inputAction
        val timeout = timeoutOf(timeoutMs)
        performAction("focus", JsonObject(emptyMap()), timeout, force = false,
            Checks(stable = false, hit = false), actionLabel = "type into")
        keyboard.type(text, delayMs, timeout)
    }

    override suspend fun press(key: String, delayMs: Long, timeoutMs: Long?): Unit = config.inputAction(timeoutMs, "locator press") {
        if (config.inputMode != InputMode.Hybrid) return@inputAction super.press(key, delayMs, timeoutMs)
        policyChecker?.invoke(ActionType.Press)
        val timeout = timeoutOf(timeoutMs)
        // Like Playwright: focus the element (no visibility/enabled checks), then press natively.
        performAction("focus", JsonObject(emptyMap()), timeout, force = false,
            Checks(visible = false, enabled = false, stable = false, hit = false, scroll = false), actionLabel = "press $key on")
        keyboard.press(key, delayMs, timeout)
    }

    override suspend fun dragTo(target: Locator, sourcePosition: Position?, targetPosition: Position?, timeoutMs: Long?): Unit = config.inputAction(timeoutMs, "locator dragTo") {
        if (config.inputMode != InputMode.Hybrid) return@inputAction super.dragTo(target, sourcePosition, targetPosition, timeoutMs)
        policyChecker?.invoke(ActionType.Drag)
        require(target is CdpLocator && target.controller === controller) { "Drag target must belong to the same page" }
        val timeout = timeoutOf(timeoutMs)
        val started = TimeSource.Monotonic.markNow()
        val (x1, y1) = actionPoint("drag", timeout, force = false, position = sourcePosition, checks = Checks(enabled = false))
        mouse.move(x1, y1)
        mouse.down()
        try {
            val remaining = (timeout - started.elapsedNow().inWholeMilliseconds).coerceAtLeast(FINAL_PROBE_MARGIN_MS + 1)
            // The pressed source may cover the target's hit point while dragging; only require visibility.
            val (x2, y2) = target.actionPoint("drop", remaining, force = false, position = targetPosition,
                checks = Checks(enabled = false, stable = false, hit = false))
            mouse.move(x2, y2, steps = 10)
        } finally {
            withContext(NonCancellable) { withTimeout(1_000) { mouse.up() } }
        }
    }

    override suspend fun setInputFiles(files: List<String>, force: Boolean, timeoutMs: Long?): Unit = config.inputAction(timeoutMs, "locator setInputFiles") {
        if (files.isEmpty()) return@inputAction super.setInputFiles(files, force, timeoutMs)
        policyChecker?.invoke(ActionType.Fill)
        val paths = cdpUploadPaths(files, config)
        // Waits for the input like every other action (honours timeoutMs and force).
        awaitFileInput(files.size, force, timeoutMs)
        ensureHelpersInjected(controller, config.installMode)
        val bridge = controller as? CdpInputBridge ?: throw KmpwrightException("File upload requires CDP")
        val result = bridge.sendCdpCommand(CdpMethods.RUNTIME_EVALUATE, mapOf(
            "expression" to """(function() {
                var H = ${PageHelpers.runtimeExpression};
                if (!H) throw new Error('KMPwright page runtime is not installed');
                var list = H.resolveAll(${selectorPartsJs()});
                if (${config.strictSelectors} && list.length > 1) throw new Error('Strict selector matched more than one element');
                return list[0] || null;
            })()""",
            "returnByValue" to false
        ))
        result.error?.let { throw KmpwrightException("$selector: ${it.message}") }
        if (result.result?.get("exceptionDetails") != null) throw KmpwrightException("Could not resolve upload selector: $selector")
        val objectId = result.result?.get("result")?.jsonObject?.get("objectId")?.jsonPrimitive?.content
            ?: throw KmpwrightException("No file input found: $selector")
        try {
            val uploaded = bridge.sendCdpCommand("DOM.setFileInputFiles", mapOf("files" to paths, "objectId" to objectId))
            uploaded.error?.let { throw KmpwrightException("File upload failed for $selector: ${it.message}") }
        } finally {
            bridge.sendCdpCommand("Runtime.releaseObject", mapOf("objectId" to objectId))
        }
    }

    override suspend fun boundingBox(): Rectangle? = super.boundingBox()?.let { bridge.rectangleInRoot(it) }

    override suspend fun screenshot(options: ScreenshotOptions): ScreenshotResult {
        bridge.prepareFrameScreenshot()
        return super.screenshot(options)
    }

    override fun frameLocator(selector: String): FrameLocator =
        frameFactory?.invoke(selector, selectorPartsJs()) ?: super.frameLocator(selector)

    override fun createLocator(newSelector: String, options: FilterOptions?): Locator {
        return CdpLocator(controller, newSelector, config, options, frameFactory)
    }

    override suspend fun evaluateJavaScriptAsync(script: String): String? {
        if (selector.split(">>").any { it.trim().startsWith("role=") }) controller.prepareAccessibilitySnapshot()
        return controller.evaluateJavaScriptAsync(script)
    }
}
