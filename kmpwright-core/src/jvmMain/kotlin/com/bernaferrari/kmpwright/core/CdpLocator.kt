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

    override suspend fun click(options: ClickOptions) {
        if (config.inputMode == InputMode.JsOnly) return super.click(options)
        policyChecker?.invoke(ActionType.Click)
        nativeClick(options, options.clickCount.coerceAtLeast(1), "click")
    }

    /** Playwright `dblclick`: one press/release pair per click (`click` detail 1, `click` detail 2, `dblclick`). */
    override suspend fun dblclick(options: ClickOptions) {
        if (config.inputMode == InputMode.JsOnly) return super.dblclick(options)
        policyChecker?.invoke(ActionType.Click)
        nativeClick(options, 2, "double-click")
    }

    private suspend fun nativeClick(options: ClickOptions, clickCount: Int, label: String) {
        val timeoutMs = timeoutOf(options.timeoutMs)
        requireLiveRef()
        val (x, y) = actionPoint(label, timeoutMs, options.force, options.position)
        if (config.inputMode == InputMode.TouchOnly) {
            val touch = CdpTouch(bridge, config)
            repeat(clickCount) { touch.tap(x, y, TapOptions(timeoutMs = timeoutMs, force = options.force)) }
            return
        }
        // delayMs is the time between mousedown and mouseup of each click (Playwright semantics).
        mouse.click(x, y, MouseClickOptions(timeoutMs = timeoutMs, button = options.button, clickCount = clickCount, delayMs = options.delayMs))
    }

    override suspend fun tap(options: TapOptions) {
        if (config.inputMode == InputMode.JsOnly) return super.tap(options)
        policyChecker?.invoke(ActionType.Click)
        val timeoutMs = timeoutOf(options.timeoutMs)
        requireLiveRef()
        val (x, y) = actionPoint("tap", timeoutMs, options.force)
        CdpTouch(bridge, config).tap(x, y, options.copy(timeoutMs = timeoutMs))
    }

    override suspend fun hover(options: HoverOptions) {
        if (config.inputMode != InputMode.Hybrid) return super.hover(options)
        policyChecker?.invoke(ActionType.Hover)
        val (x, y) = actionPoint("hover", timeoutOf(options.timeoutMs), options.force, checks = Checks(enabled = false))
        mouse.move(x, y, MoveOptions())
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

    override suspend fun fill(value: String, options: FillOptions) {
        if (config.inputMode != InputMode.Hybrid) return super.fill(value, options)
        policyChecker?.invoke(ActionType.Fill)
        requireLiveRef()
        // The same editability checks and errors as the WebView backends.
        performAction("noop", JsonObject(emptyMap()), timeoutOf(options.timeoutMs), options.force,
            Checks(editable = true, stable = false, hit = false), actionLabel = "fill")
        focusForInput(selectAll = true, label = "fill")
        if (value.isEmpty()) keyboard.press("Backspace") else keyboard.insertText(value)
    }

    override suspend fun pressSequentially(text: String, options: PressSequentiallyOptions) {
        if (config.inputMode != InputMode.Hybrid) return super.pressSequentially(text, options)
        policyChecker?.invoke(ActionType.Type)
        if (text.isEmpty()) return
        val timeoutMs = timeoutOf(options.timeoutMs)
        performAction("focus", JsonObject(emptyMap()), timeoutMs, force = false,
            Checks(stable = false, hit = false), actionLabel = "type into")
        keyboard.type(text, TypeOptions(options.delayMs, timeoutMs))
    }

    override suspend fun press(key: String, options: PressOptions) {
        if (config.inputMode != InputMode.Hybrid) return super.press(key, options)
        policyChecker?.invoke(ActionType.Press)
        val timeoutMs = timeoutOf(options.timeoutMs)
        // Like Playwright: focus the element (no visibility/enabled checks), then press natively.
        performAction("focus", JsonObject(emptyMap()), timeoutMs, force = false,
            Checks(visible = false, enabled = false, stable = false, hit = false, scroll = false), actionLabel = "press $key on")
        keyboard.press(key, options.copy(timeoutMs = timeoutMs))
    }

    override suspend fun dragTo(target: Locator, options: DragOptions) {
        if (config.inputMode != InputMode.Hybrid) return super.dragTo(target, options)
        policyChecker?.invoke(ActionType.Drag)
        require(target is CdpLocator && target.controller === controller) { "Drag target must belong to the same page" }
        val timeoutMs = timeoutOf(options.timeoutMs)
        val started = TimeSource.Monotonic.markNow()
        val (x1, y1) = actionPoint("drag", timeoutMs, force = false, position = options.sourcePosition, checks = Checks(enabled = false))
        mouse.move(x1, y1, MoveOptions())
        mouse.down()
        try {
            val remaining = (timeoutMs - started.elapsedNow().inWholeMilliseconds).coerceAtLeast(FINAL_PROBE_MARGIN_MS + 1)
            // The pressed source may cover the target's hit point while dragging; only require visibility.
            val (x2, y2) = target.actionPoint("drop", remaining, force = false, position = options.targetPosition,
                checks = Checks(enabled = false, stable = false, hit = false))
            mouse.move(x2, y2, MoveOptions(steps = 10))
        } finally {
            withContext(NonCancellable) { withTimeout(1_000) { mouse.up() } }
        }
    }

    override suspend fun setInputFiles(files: List<String>, options: ActionOptions) {
        if (files.isEmpty()) return super.setInputFiles(files, options)
        policyChecker?.invoke(ActionType.Fill)
        val paths = cdpUploadPaths(files, config)
        // Waits for the input like every other action (honours timeoutMs and force).
        awaitFileInput(files.size, options)
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
