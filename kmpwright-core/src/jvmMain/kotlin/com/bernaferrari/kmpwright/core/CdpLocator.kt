package com.bernaferrari.kmpwright.core

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal class CdpLocator(
    controller: WebViewLocatorController,
    selector: String,
    config: KmpwrightConfig,
    private val options: FilterOptions? = null
) : BaseLocator(controller, selector, config, options) {

    constructor(
        controller: WebViewController,
        selector: String,
        config: KmpwrightConfig
    ) : this(controller as WebViewLocatorController, selector, config)

    private val bridge: CdpInputBridge get() = controller as CdpInputBridge
    private val mouse by lazy { CdpMouse(bridge) }
    private val keyboard by lazy { CdpKeyboard(bridge) }

    private suspend fun <T> nativeAction(timeoutMs: Long, block: suspend () -> T): T {
        require(timeoutMs > 0) { "Action timeout must be positive" }
        return try { withTimeout(timeoutMs) { block() } }
        catch (_: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            throw KmpwrightException("Timeout performing action on '$selector'")
        }
    }

    private suspend fun pointerPosition(force: Boolean, position: Position? = null): Pair<Double, Double> {
        require(position == null || (position.x.isFinite() && position.y.isFinite())) { "Pointer position must be finite" }
        val raw = evalOnElement("""
            el.scrollIntoView({block:'center', inline:'center'});
            var rect = el.getBoundingClientRect();
            if (!rect.width || !rect.height) return '__kmpwright_not_visible__';
            if (!$force && (el.disabled || el.getAttribute('aria-disabled') === 'true')) return '__kmpwright_disabled__';
            if (rect.right <= 0 || rect.bottom <= 0 || rect.left >= innerWidth || rect.top >= innerHeight) return '__kmpwright_not_visible__';
            var x = ${position?.let { "rect.left + ${it.x}" } ?: "(Math.max(0, rect.left) + Math.min(innerWidth, rect.right)) / 2"};
            var y = ${position?.let { "rect.top + ${it.y}" } ?: "(Math.max(0, rect.top) + Math.min(innerHeight, rect.bottom)) / 2"};
            if (x < rect.left || x >= rect.right || y < rect.top || y >= rect.bottom || x < 0 || y < 0 || x >= innerWidth || y >= innerHeight) return '__kmpwright_invalid_position__';
            if (!$force) {
                var root = el.getRootNode();
                var hit = root.elementFromPoint ? root.elementFromPoint(x, y) : document.elementFromPoint(x, y);
                var label = hit && hit.closest ? hit.closest('label') : null;
                var allowed = hit === el || el.contains(hit) || (label && label.control === el);
                var node = el;
                while (!allowed && node.getRootNode && node.getRootNode().host) {
                    node = node.getRootNode().host;
                    allowed = hit === node;
                }
                if (!allowed) return '__kmpwright_intercepted__:' + (hit ? hit.tagName : 'outside viewport');
            }
            return JSON.stringify({x:x,y:y});
        """.trimIndent(), requireElement = true)
            ?: throw KmpwrightException("No element found: $selector")
        if (raw.startsWith("__kmpwright_")) throw KmpwrightException("Cannot act on '$selector': $raw")
        val point = Json.parseToJsonElement(raw).jsonObject
        return bridge.pointerInRoot(point.getValue("x").jsonPrimitive.double, point.getValue("y").jsonPrimitive.double, force)
    }

    override suspend fun waitFor(state: SelectorState, timeoutMs: Long) {
        if (selector.split(">>").none { it.trim().startsWith("role=") }) return super.waitFor(state, timeoutMs)
        try {
            withTimeout(timeoutMs) {
                while (true) {
                    val ready = when (state) {
                        SelectorState.Visible -> isVisible()
                        SelectorState.Hidden -> !isVisible()
                        SelectorState.Attached -> count() > 0
                        SelectorState.Detached -> count() == 0
                    }
                    if (ready) return@withTimeout
                    kotlinx.coroutines.delay(100)
                }
            }
        } catch (_: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            throw KmpwrightException("Timeout waiting for selector '$selector' state: $state")
        }
    }

    override suspend fun click(options: ClickOptions) = nativeAction(options.timeoutMs) {
        policyChecker?.invoke(ActionType.Click)
        if (parseRef(selector) != null) evalOnElement("return true;", requireElement = true)
        if (!options.force) waitFor(SelectorState.Visible, options.timeoutMs)
        val (x, y) = pointerPosition(options.force)
        mouse.click(x, y, MouseClickOptions(timeoutMs = options.timeoutMs, button = options.button, clickCount = options.clickCount, delayMs = options.delayMs))
    }

    override suspend fun dblclick(options: ClickOptions) {
        click(options.copy(clickCount = 1))
        click(options.copy(clickCount = 2))
    }

    override suspend fun tap(options: TapOptions) = nativeAction(options.timeoutMs) {
        policyChecker?.invoke(ActionType.Click)
        if (parseRef(selector) != null) evalOnElement("return true;", requireElement = true)
        if (!options.force) waitFor(SelectorState.Visible, options.timeoutMs)
        val (x, y) = pointerPosition(options.force)
        CdpTouch(bridge).tap(x, y, options)
    }

    override suspend fun hover() = nativeAction(config.timeouts.waitForSelector) {
        policyChecker?.invoke(ActionType.Hover)
        waitFor(SelectorState.Visible, config.timeouts.waitForSelector)
        val (x, y) = pointerPosition(false)
        mouse.move(x, y, MoveOptions())
    }

    private suspend fun focusForInput(selectAll: Boolean) {
        val result = evalOnElement("""
            if (el.disabled || el.readOnly || el.getAttribute('aria-disabled') === 'true') return '__kmpwright_not_editable__';
            el.focus();
            if (el !== el.ownerDocument.activeElement && el !== el.getRootNode().activeElement) return '__kmpwright_not_focusable__';
            if ($selectAll) {
                if (el.isContentEditable) {
                    var range = document.createRange(); range.selectNodeContents(el);
                    var selection = window.getSelection(); selection.removeAllRanges(); selection.addRange(range);
                } else if (typeof el.select === 'function' && el.type !== 'file') el.select();
                else return '__kmpwright_not_editable__';
            }
            return true;
        """.trimIndent(), requireElement = true)
        if (result?.startsWith("__kmpwright_") == true) throw KmpwrightException("Cannot edit '$selector': $result")
    }

    override suspend fun fill(text: String, options: FillOptions) = nativeAction(options.timeoutMs) {
        policyChecker?.invoke(ActionType.Fill)
        if (parseRef(selector) != null) evalOnElement("return true;", requireElement = true)
        if (!options.force) waitFor(SelectorState.Visible, options.timeoutMs)
        focusForInput(selectAll = true)
        if (text.isEmpty()) keyboard.press("Backspace") else keyboard.insertText(text)
    }

    override suspend fun type(text: String, options: TypeOptions) = nativeAction(options.timeoutMs) {
        policyChecker?.invoke(ActionType.Type)
        waitFor(SelectorState.Visible, options.timeoutMs)
        focusForInput(selectAll = false)
        keyboard.type(text, options)
    }

    override suspend fun press(key: String, options: PressOptions) = nativeAction(options.timeoutMs) {
        policyChecker?.invoke(ActionType.Press)
        focusForInput(selectAll = false)
        keyboard.press(key, options)
    }

    override suspend fun dragTo(target: Locator, options: DragOptions) = nativeAction(options.timeoutMs) {
        policyChecker?.invoke(ActionType.Drag)
        require(target is CdpLocator && target.controller === controller) { "Drag target must belong to the same page" }
        val (x1, y1) = pointerPosition(false, options.sourcePosition)
        mouse.move(x1, y1, MoveOptions())
        mouse.down()
        try {
            val (x2, y2) = target.pointerPosition(false, options.targetPosition)
            mouse.move(x2, y2, MoveOptions(steps = 10))
        } finally {
            withContext(NonCancellable) { withTimeout(1_000) { mouse.up() } }
        }
    }

    override suspend fun setInputFiles(files: List<String>, options: ActionOptions) {
        if (files.isEmpty()) {
            super.clearInputFiles(options)
            return
        }
        policyChecker?.invoke(ActionType.Fill)
        val paths = files.map { path ->
            java.nio.file.Path.of(path).toAbsolutePath().also {
                require(java.nio.file.Files.isRegularFile(it)) { "Upload file does not exist: $path" }
            }.toString()
        }
        ensureHelpersInjected(controller, config.installMode)
        val bridge = controller as? CdpInputBridge ?: throw KmpwrightException("File upload requires CDP")
        val nth = this.options?.nth
        val result = bridge.sendCdpCommand(CdpMethods.RUNTIME_EVALUATE, mapOf(
            "expression" to """(function() {
                var list = window.__kmpwright_resolveAll(${selectorPartsJs()});
                if (${config.strictSelectors} && list.length > 1 && ${nth == null}) throw new Error('Strict selector matched more than one element');
                var index = ${nth ?: 0};
                return list[index < 0 ? list.length + index : index] || null;
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

    override suspend fun clearInputFiles(options: ActionOptions) = setInputFiles(emptyList(), options)

    override fun createLocator(newSelector: String, options: FilterOptions?): Locator {
        return CdpLocator(controller, newSelector, config, options)
    }

    override suspend fun evaluateJavaScriptAsync(script: String): String? {
        if (selector.split(">>").any { it.trim().startsWith("role=") }) controller.prepareAccessibilitySnapshot()
        return controller.evaluateJavaScriptAsync(script)
    }
}
