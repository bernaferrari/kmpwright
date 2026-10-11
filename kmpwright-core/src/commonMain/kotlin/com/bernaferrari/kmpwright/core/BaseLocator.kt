package com.bernaferrari.kmpwright.core

import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentSet
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.coroutines.resume

abstract class BaseLocator(
    protected val controller: WebViewLocatorController,
    protected val selector: String,
    protected val config: KmpwrightConfig,
    private val filterOptions: FilterOptions? = null
) : Locator {

    /** Compatibility constructor for callers that still hold the aggregate facade. */
    constructor(
        controller: WebViewController,
        selector: String,
        config: KmpwrightConfig,
        filterOptions: FilterOptions? = null
    ) : this(controller as WebViewLocatorController, selector, config, filterOptions)

    protected val parsedSelector: ParsedSelector by lazy { SelectorParser.parse(selector) }

    /**
     * Policy checker invoked before actions. Set by BasePage.locator().
     */
    internal var policyChecker: (suspend (ActionType) -> Unit)? = null

    private fun selectorParts(): List<String> {
        return if (selector.contains(">>")) {
            selector.split(">>").map { it.trim() }.filter { it.isNotEmpty() }
        } else {
            listOf(selector)
        }
    }

    protected fun selectorPartsJs(): String {
        val parts = selectorParts().map { part -> parseRef(part)?.let { "ref=$it" } ?: part }
        val encoded = parts.joinToString(
            separator = ",",
            prefix = "[",
            postfix = "]"
        ) { "'${escapeJsString(it)}'" }
        return encoded
    }

    protected suspend fun evalOnElement(body: String, requireElement: Boolean = false): String? {
        ensureHelpersInjected(controller, config.installMode)
        val parts = selectorPartsJs()
        val nthIndex = filterOptions?.nth
        val nthExpr = nthIndex?.toString() ?: "null"
        val script = """
            (function() {
                var list = window.__kmpwright_resolveAll($parts);
                if (!list || list.length === 0) return ${if (requireElement) "'__kmpwright_missing_element__'" else "null"};
                if (${config.strictSelectors} && list.length > 1 && $nthExpr === null) return '__kmpwright_strict_violation__';
                var el = (function() {
                    if ($nthExpr === null) return list[0];
                    var idx = $nthExpr;
                    if (idx < 0) idx = list.length + idx;
                    return list[idx] || null;
                })();
                if (!el) return ${if (requireElement) "'__kmpwright_missing_element__'" else "null"};
                $body
            })()
        """.trimIndent()
        val result = evaluateJavaScriptAsync(script)
        if (result == "__kmpwright_missing_element__") {
            throw KmpwrightException("No element found: $selector. Refresh the snapshot if this is a stale reference.")
        }
        if (result == "__kmpwright_strict_violation__") {
            throw KmpwrightException("Strict selector matched more than one element: $selector")
        }
        return result
    }

    private suspend fun evalActionOnElement(body: String): String? = evalOnElement(body, requireElement = true)

    override suspend fun click(options: ClickOptions) {
        policyChecker?.invoke(ActionType.Click)
        val force = options.force
        if (options.delayMs > 0) delay(options.delayMs)
        val result = evalActionOnElement("""
            el.scrollIntoView({ block: 'center', inline: 'center' });
            if (!$force) {
                var rect = el.getBoundingClientRect();
                var hit = document.elementFromPoint(rect.left + rect.width / 2, rect.top + rect.height / 2);
                if (hit && hit !== el && !el.contains(hit)) {
                    return '__kmpwright_click_intercepted__:' + (hit.tagName || 'element');
                }
            }
            el.click();
            return true;
        """.trimIndent())
        if (result?.startsWith("__kmpwright_click_intercepted__:") == true) {
            throw KmpwrightException(
                "Click was intercepted by ${result.substringAfter(':')}; pass force=true only when intentional"
            )
        }
    }

    override suspend fun dblclick(options: ClickOptions) {
        policyChecker?.invoke(ActionType.Click)
        evalActionOnElement("el.dispatchEvent(new MouseEvent('dblclick', { bubbles: true })); return true;")
    }

    override suspend fun tap(options: TapOptions) {
        policyChecker?.invoke(ActionType.Click)
        evalActionOnElement("el.click(); return true;")
    }

    override suspend fun fill(text: String, options: FillOptions) {
        policyChecker?.invoke(ActionType.Fill)
        val escapedText = escapeJsString(text)
        evalActionOnElement("el.value = '$escapedText'; el.dispatchEvent(new Event('input', { bubbles: true })); el.dispatchEvent(new Event('change', { bubbles: true })); return true;")
    }

    override suspend fun type(text: String, options: TypeOptions) {
        policyChecker?.invoke(ActionType.Type)
        if (text.isEmpty()) return
        if (options.delayMs == 0L) {
            val escapedText = escapeJsString(text)
            evalActionOnElement("""
                var value = el.value || '';
                var text = '$escapedText';
                for (var i = 0; i < text.length; i++) {
                    value += text.charAt(i);
                    el.value = value;
                    el.dispatchEvent(new InputEvent('input', {
                        bubbles: true,
                        data: text.charAt(i),
                        inputType: 'insertText'
                    }));
                }
                el.dispatchEvent(new Event('change', { bubbles: true }));
                return el.value;
            """.trimIndent())
            return
        }
        for (char in text) {
            val escapedChar = escapeJsString(char.toString())
            evalActionOnElement("var event = new InputEvent('input', { bubbles: true }); el.value += '$escapedChar'; el.dispatchEvent(event); return el.value;")
            delay(options.delayMs)
        }
    }

    override suspend fun press(key: String, options: PressOptions) {
        policyChecker?.invoke(ActionType.Press)
        val escapedKey = escapeJsString(key)
        evalActionOnElement("el.dispatchEvent(new KeyboardEvent('keydown', { key: '$escapedKey', bubbles: true })); return true;")
    }

    override suspend fun hover() {
        policyChecker?.invoke(ActionType.Hover)
        evalActionOnElement("el.dispatchEvent(new MouseEvent('mouseover', { bubbles: true })); return true;")
    }

    override suspend fun scrollIntoViewIfNeeded(options: ScrollIntoViewOptions) {
        policyChecker?.invoke(ActionType.Scroll)
        evalActionOnElement("if (el.scrollIntoViewIfNeeded) { el.scrollIntoViewIfNeeded(); } else { el.scrollIntoView({ block: 'center', inline: 'center' }); } return true;")
    }

    override suspend fun scrollDown(distance: Double) {
        policyChecker?.invoke(ActionType.Scroll)
        val dist = if (distance > 0) distance else 0.0
        evalActionOnElement("el.scrollIntoView({ block: 'end' }); return true;")
        val script = "window.scrollBy(0, $dist + window.innerHeight * 0.8); return true;"
        evaluateJavaScriptAsync(script)
    }

    override suspend fun scrollUp(distance: Double) {
        policyChecker?.invoke(ActionType.Scroll)
        val dist = if (distance > 0) distance else 0.0
        evalActionOnElement("el.scrollIntoView({ block: 'start' }); return true;")
        val script = "window.scrollBy(0, -($dist + window.innerHeight * 0.8)); return true;"
        evaluateJavaScriptAsync(script)
    }

    override suspend fun focus() {
        policyChecker?.invoke(ActionType.Click)
        evalActionOnElement("el.focus(); return true;")
    }

    override suspend fun check(options: CheckOptions) {
        val isCurrentlyChecked = isChecked()
        if (!isCurrentlyChecked) {
            click(ClickOptions(timeoutMs = options.timeoutMs, force = options.force))
        }
    }

    override suspend fun uncheck(options: CheckOptions) {
        val isCurrentlyChecked = isChecked()
        if (isCurrentlyChecked) {
            click(ClickOptions(timeoutMs = options.timeoutMs, force = options.force))
        }
    }

    override suspend fun selectOption(value: String, options: ActionOptions) {
        selectOption(listOf(value), options)
    }

    override suspend fun selectOption(values: List<String>, options: ActionOptions) {
        policyChecker?.invoke(ActionType.Click)
        val encodedValues = values.joinToString(",", "[", "]") { jsStringLiteral(it) }
        val result = evalActionOnElement("""
            if (el.tagName !== 'SELECT') return '__kmpwright_select_error__:Target is not a select element';
            var values = $encodedValues;
            if (!el.multiple && values.length > 1) return '__kmpwright_select_error__:Select does not allow multiple values';
            var all = Array.from(el.options);
            function normalized(text) { return (text || '').replace(/\s+/g, ' ').trim(); }
            var selected = [];
            for (var i = 0; i < values.length; i++) {
                var option = all.find(function(o) { return o.value === values[i]; }) ||
                    all.find(function(o) { return normalized(o.label) === normalized(values[i]); });
                if (!option) return '__kmpwright_select_error__:No option matches "' + values[i] +
                    '". Available: ' + all.map(function(o) { return o.value + ' (' + normalized(o.label) + ')'; }).join(', ');
                if (option.disabled || (option.parentElement.tagName === 'OPTGROUP' && option.parentElement.disabled))
                    return '__kmpwright_select_error__:Option is disabled: ' + values[i];
                selected.push(option);
            }
            all.forEach(function(o) { o.selected = selected.indexOf(o) !== -1; });
            if (!selected.length) el.selectedIndex = -1;
            el.dispatchEvent(new Event('input', { bubbles: true }));
            el.dispatchEvent(new Event('change', { bubbles: true }));
            return true;
        """.trimIndent())
        if (result?.startsWith("__kmpwright_select_error__:") == true) {
            throw KmpwrightException("$selector: ${result.substringAfter(':')}")
        }
    }

    override suspend fun setInputFiles(files: List<String>, options: ActionOptions) {
        throw KmpwrightException("setInputFiles is not supported in mobile WebView")
    }

    override suspend fun clearInputFiles(options: ActionOptions) {
        policyChecker?.invoke(ActionType.Fill)
        evalActionOnElement("el.value = ''; el.dispatchEvent(new Event('change', { bubbles: true })); return true;")
    }

    override suspend fun dragTo(target: Locator, options: DragOptions) {
        policyChecker?.invoke(ActionType.Drag)
        val targetBase = target as? BaseLocator ?: return
        val sourceBox = boundingBox()
        val targetBox = target.boundingBox()

        if (sourceBox != null && targetBox != null) {
            val startX = sourceBox.x + sourceBox.width / 2
            val startY = sourceBox.y + sourceBox.height / 2
            val endX = targetBox.x + targetBox.width / 2
            val endY = targetBox.y + targetBox.height / 2

            evalActionOnElement("el.dispatchEvent(new MouseEvent('mousedown', { bubbles: true })); return true;")

            delay(50)

            targetBase.evalActionOnElement("""
                var rect = el.getBoundingClientRect();
                var x = rect.x + rect.width / 2;
                var y = rect.y + rect.height / 2;
                var elem = document.elementFromPoint(x, y);
                if (elem) {
                    elem.dispatchEvent(new MouseEvent('mouseup', { bubbles: true }));
                    elem.dispatchEvent(new MouseEvent('click', { bubbles: true }));
                }
                return true;
            """.trimIndent())
        }
    }

    override suspend fun textContent(): String? {
        return evalOnElement("return el.textContent;")
    }

    override suspend fun innerText(): String {
        return evalOnElement("return el.innerText;") ?: ""
    }

    override suspend fun innerHTML(): String {
        return evalOnElement("return el.innerHTML;") ?: ""
    }

    override suspend fun getAttribute(name: String): String? {
        val escapedName = escapeJsString(name)
        return evalOnElement("return el.getAttribute('$escapedName');")
    }

    override suspend fun inputValue(): String? {
        return evalOnElement("return el.value;")
    }

    override suspend fun computedStyles(): Map<String, String> {
        val raw = evalOnElement("""
            var s = window.getComputedStyle(el);
            var result = {};
            for (var i = 0; i < s.length; i++) {
                var name = s[i];
                result[name] = s.getPropertyValue(name);
            }
            return JSON.stringify(result);
        """) ?: return emptyMap()
        return try {
            kotlinx.serialization.json.Json.decodeFromString<Map<String, String>>(raw)
        } catch (e: Exception) {
            emptyMap()
        }
    }

    override suspend fun count(): Int {
        ensureHelpersInjected(controller, config.installMode)
        val parts = selectorPartsJs()
        val script = """
            (function() {
                var list = window.__kmpwright_resolveAll($parts);
                return list.length;
            })()
        """.trimIndent()
        return evaluateJavaScriptAsync(script)?.toIntOrNull() ?: 0
    }

    override suspend fun isVisible(): Boolean {
        return evalOnElement("""
            var rect = el.getBoundingClientRect();
            return rect.width > 0 && rect.height > 0 && window.getComputedStyle(el).visibility !== 'hidden';
        """.trimIndent()) == "true"
    }

    override suspend fun isEnabled(): Boolean {
        return evalOnElement("return !el.disabled;") != "false"
    }

    override suspend fun isChecked(): Boolean {
        return evalOnElement("return !!el.checked;") == "true"
    }

    override suspend fun isFocused(): Boolean {
        return evalOnElement("return (el === document.activeElement);") == "true"
    }

    override suspend fun isEditable(): Boolean {
        return evalOnElement("""
            var isInput = el.tagName === 'INPUT' || el.tagName === 'TEXTAREA';
            var isDisabled = !!el.disabled;
            var isReadOnly = !!el.readOnly;
            return isInput && !isDisabled && !isReadOnly;
        """.trimIndent()) == "true"
    }

    @Serializable
    data class BoundingBoxJson(
        val x: Double,
        val y: Double,
        val width: Double,
        val height: Double
    )

    override suspend fun boundingBox(): Rectangle? {
        ensureHelpersInjected(controller, config.installMode)
        val script = """
            (function() {
                var el = window.__kmpwright_resolveFirst(${selectorPartsJs()});
                if (!el) return null;
                var rect = el.getBoundingClientRect();
                return JSON.stringify({ "x": rect.x, "y": rect.y, "width": rect.width, "height": rect.height });
            })()
        """.trimIndent()

        return suspendCancellableCoroutine { cont ->
            controller.evaluateJavaScript(script) { result ->
                try {
                    val normalized = controller.normalizeJavaScriptResult(result)
                    if (normalized == null || !normalized.startsWith("{")) {
                        cont.resume(null)
                        return@evaluateJavaScript
                    }

                    val parsed = Json.decodeFromString<BoundingBoxJson>(normalized)
                    cont.resume(Rectangle(parsed.x, parsed.y, parsed.width, parsed.height))
                } catch (e: Exception) {
                    cont.resume(null)
                }
            }
        }
    }

    override suspend fun screenshot(options: ScreenshotOptions): ScreenshotResult {
        policyChecker?.invoke(ActionType.Screenshot)
        scrollIntoViewIfNeeded()
        val box = boundingBox() ?: return ScreenshotResult(ByteArray(0), 0, 0)
        val clip = Rectangle(box.x, box.y, box.width, box.height)

        return suspendCancellableCoroutine { cont ->
            controller.takeScreenshot(options.copy(fullPage = false, clip = clip)) { bytes, width, height ->
                cont.resume(ScreenshotResult(bytes ?: ByteArray(0), width, height))
            }
        }
    }

    override suspend fun highlight(options: HighlightOptions) {
        val color = options.color
        val borderColor = options.borderColor
        val duration = options.durationMs
        evalOnElement("""
            var rect = el.getBoundingClientRect();
            var div = document.createElement('div');
            div.setAttribute('data-kmpwright-highlight', 'true');
            div.style.position = 'fixed';
            div.style.left = rect.x + 'px';
            div.style.top = rect.y + 'px';
            div.style.width = rect.width + 'px';
            div.style.height = rect.height + 'px';
            div.style.backgroundColor = '${escapeJsString(color)}';
            div.style.border = '2px solid ${escapeJsString(borderColor)}';
            div.style.borderRadius = '2px';
            div.style.pointerEvents = 'none';
            div.style.zIndex = '2147483647';
            document.body.appendChild(div);
            if ($duration > 0) {
                setTimeout(function() { div.remove(); }, $duration);
            }
        """.trimIndent())
    }

    override suspend fun waitFor(state: SelectorState, timeoutMs: Long) {
        ensureHelpersInjected(controller, config.installMode)
        val conditionJs = when (state) {
            SelectorState.Visible -> {
                val parts = selectorPartsJs()
                "(function() { var list = window.__kmpwright_resolveAll($parts); if (!list || list.length === 0) return false; var el = list[0]; var rect = el.getBoundingClientRect(); return rect.width > 0 && rect.height > 0 && window.getComputedStyle(el).visibility !== 'hidden'; })()"
            }
            SelectorState.Hidden -> {
                val parts = selectorPartsJs()
                "(function() { var list = window.__kmpwright_resolveAll($parts); if (!list || list.length === 0) return true; var el = list[0]; var rect = el.getBoundingClientRect(); return rect.width === 0 || rect.height === 0 || window.getComputedStyle(el).visibility === 'hidden'; })()"
            }
            SelectorState.Attached -> {
                val parts = selectorPartsJs()
                "(function() { var list = window.__kmpwright_resolveAll($parts); return list && list.length > 0; })()"
            }
            SelectorState.Detached -> {
                val parts = selectorPartsJs()
                "(function() { var list = window.__kmpwright_resolveAll($parts); return !list || list.length === 0; })()"
            }
        }

        if (!awaitPageCondition(controller, conditionJs, timeoutMs)) {
            throw KmpwrightException("Timeout waiting for selector '$selector' state: $state")
        }
    }

    /** Builds a selector-scoped condition for event-driven expectations. */
    internal fun expectationConditionJs(body: String): String {
        return """
            (function() {
                var list = window.__kmpwright_resolveAll(${selectorPartsJs()});
                var el = list && list.length > 0 ? list[0] : null;
                $body
            })()
        """.trimIndent()
    }

    /**
     * Waits in the page's MutationObserver instead of polling the bridge.
     * The existing polling path remains available for custom Locator
     * implementations which are not backed by [BaseLocator].
     */
    internal suspend fun waitForExpectation(conditionJs: String, timeoutMs: Long): Boolean? {
        if (controller.nativeAccessibility && selectorParts().any { it.startsWith("role=") }) return null
        ensureHelpersInjected(controller, config.installMode)
        val observerScript = waitForConditionJs(conditionJs, timeoutMs)
        val result = suspendCancellableCoroutine<String?> { cont ->
            controller.evaluateJavaScript(observerScript) { value ->
                if (cont.isActive) cont.resume(controller.normalizeJavaScriptResult(value))
            }
        }
        return when (result) {
            "true" -> true
            "__kmpwright_timeout__" -> false
            // A custom controller may not support evaluating Promise-returning
            // scripts. Let the legacy bridge polling path handle that case.
            else -> null
        }
    }

    override fun first(): Locator = createLocator(selector, FilterOptions(nth = 0)).apply { (this as? BaseLocator)?.policyChecker = this@BaseLocator.policyChecker }
    override fun nth(index: Int): Locator = createLocator(selector, FilterOptions(nth = index)).apply { (this as? BaseLocator)?.policyChecker = this@BaseLocator.policyChecker }
    override fun last(): Locator = createLocator(selector, FilterOptions(nth = -1)).apply { (this as? BaseLocator)?.policyChecker = this@BaseLocator.policyChecker }
    override fun locator(selector: String): Locator = createLocator("${this.selector} >> $selector").apply { (this as? BaseLocator)?.policyChecker = this@BaseLocator.policyChecker }
    override fun filter(options: FilterOptions): Locator = createLocator(selector, options).apply { (this as? BaseLocator)?.policyChecker = this@BaseLocator.policyChecker }

    override fun frameLocator(selector: String): FrameLocator {
        return BaseFrameLocator(controller, selector, config)
    }

    override fun expect(): KmpwrightExpect = KmpwrightExpect(this, config)

    protected abstract fun createLocator(newSelector: String, options: FilterOptions? = null): Locator
    protected abstract suspend fun evaluateJavaScriptAsync(script: String): String?

    companion object {
        internal var injectedControllers = persistentSetOf<WebViewScriptController>()

        internal fun helpersJsSource(): String {
            return """
            (function() {
                if (window.__kmpwright_queryOne) return;
                window.__kmpwright_roleSelector = function(role) {
                    var r = (role || '').toLowerCase().replace('_', '-');
                    var map = {
                        'button': 'button,[role="button"],input[type="button"],input[type="submit"],input[type="reset"]',
                        'link': 'a,[role="link"]',
                        'heading': 'h1,h2,h3,h4,h5,h6,[role="heading"]',
                        'img': 'img,[role="img"]',
                        'input': 'input,textarea,select,[role="textbox"],[role="combobox"]',
                        'checkbox': 'input[type="checkbox"],[role="checkbox"]',
                        'radio': 'input[type="radio"],[role="radio"]',
                        'dialog': 'dialog,[role="dialog"]',
                        'list': 'ul,ol,dl,[role="list"]',
                        'listitem': 'li,[role="listitem"]',
                        'navigation': 'nav,[role="navigation"]',
                        'table': 'table,[role="table"]',
                        'textbox': 'input[type="text"],textarea,[role="textbox"]'
                    };
                    return map[r] || ('[role="' + r + '"]');
                };
                window.__kmpwright_byRole = function(root, role) {
                    var base = root || document, map = window.__kmpwright_browserAX;
                    if (!map) return Array.from(base.querySelectorAll(window.__kmpwright_roleSelector(role)));
                    var wanted = (role || '').toLowerCase().replace('_', '-');
                    var pending = Array.from(base.children || []).reverse(), result = [];
                    while (pending.length) {
                        var node = pending.pop(), metadata = map.get(node);
                        if (metadata && (metadata.role === wanted ||
                            (wanted === 'input' && ['textbox','combobox'].includes(metadata.role)))) result.push(node);
                        var children = Array.from(node.children || []);
                        if (node.shadowRoot) children = children.concat(Array.from(node.shadowRoot.children));
                        for (var i = children.length - 1; i >= 0; i--) pending.push(children[i]);
                    }
                    return result;
                };
                window.__kmpwright_byText = function(root, pattern) {
                    var text = pattern || '';
                    var isRegex = text.startsWith('/') && text.endsWith('/') && text.length > 1;
                    var matcher = null;
                    if (isRegex) {
                        try { matcher = new RegExp(text.slice(1, -1)); } catch (e) { matcher = null; }
                    }
                    var contains = text.startsWith('*') || text.endsWith('*');
                    var needle = text.replace(/^\*+|\*+$/g, '');
                    var nodes = (root || document).querySelectorAll('*');
                    for (var i = 0; i < nodes.length; i++) {
                        var n = nodes[i];
                        var t = (n.innerText || n.textContent || '').trim();
                        if (!t) continue;
                        if (matcher && matcher.test(t)) return n;
                        if ((contains || !isRegex) && t.indexOf(needle) !== -1) return n;
                    }
                    return null;
                };
                window.__kmpwright_byAttribute = function(root, attribute, value, tag) {
                    var selector = tag ? tag + '[' + attribute + ']' : '[' + attribute + ']';
                    var nodes = (root || document).querySelectorAll(selector);
                    var out = [];
                    for (var i = 0; i < nodes.length; i++) {
                        if (nodes[i].getAttribute(attribute) === value) out.push(nodes[i]);
                    }
                    return out;
                };
                window.__kmpwright_byAlt = function(root, value) {
                    var out = window.__kmpwright_byAttribute(root, 'alt', value, 'img');
                    var labelled = window.__kmpwright_byAttribute(root, 'aria-label', value, null);
                    for (var i = 0; i < labelled.length; i++) {
                        if ((labelled[i].getAttribute('role') || '').toLowerCase() === 'img') out.push(labelled[i]);
                    }
                    return out;
                };
                window.__kmpwright_byLabel = function(root, label) {
                    var labels = (root || document).querySelectorAll('label');
                    for (var i = 0; i < labels.length; i++) {
                        var l = labels[i];
                        var t = (l.innerText || l.textContent || '').trim();
                        if (t === label) {
                            if (l.control) return l.control;
                            var forId = l.getAttribute('for');
                            if (forId) return document.getElementById(forId);
                        }
                    }
                    var aria = window.__kmpwright_byAttribute(root, 'aria-label', label)[0];
                    if (aria) return aria;
                    var labelled = (root || document).querySelectorAll('[aria-labelledby]');
                    for (var j = 0; j < labelled.length; j++) {
                        var el = labelled[j];
                        var ids = (el.getAttribute('aria-labelledby') || '').split(/\s+/);
                        var name = ids.map(function(id) {
                            var ref = el.ownerDocument.getElementById(id);
                            return ref ? (ref.innerText || ref.textContent || '').trim() : '';
                        }).filter(Boolean).join(' ').replace(/\s+/g, ' ').trim();
                        if (name === label) return el;
                    }
                    return null;
                };
                window.__kmpwright_queryOne = function(selector, root) {
                    var base = root || document;
                    if (selector.startsWith('ref=')) {
                        var referenced = (window.__kmpwright_snapshotNodes || {})[selector.slice(4)];
                        var found = referenced && referenced.isConnected && (base === document || base.contains(referenced));
                        return found ? referenced : null;
                    }
                    if (selector.startsWith('role=')) {
                        return window.__kmpwright_byRole(base, selector.slice(5))[0] || null;
                    }
                    if (selector.startsWith('label=')) {
                        return window.__kmpwright_byLabel(base, selector.slice(6));
                    }
                    if (selector.startsWith('text=') || selector.startsWith('text:')) {
                        var text = selector.replace(/^text[=:]/, '');
                        return window.__kmpwright_byText(base, text);
                    }
                    if (selector.startsWith('exact_text=')) {
                        return window.__kmpwright_byText(base, selector.slice(11));
                    }
                    if (selector.startsWith('placeholder=')) {
                        return window.__kmpwright_byAttribute(base, 'placeholder', selector.slice(12))[0] || null;
                    }
                    if (selector.startsWith('alt=')) {
                        return window.__kmpwright_byAlt(base, selector.slice(4))[0] || null;
                    }
                    if (selector.startsWith('title=')) {
                        return window.__kmpwright_byAttribute(base, 'title', selector.slice(6))[0] || null;
                    }
                    if (selector.startsWith('testid=')) {
                        return window.__kmpwright_byAttribute(base, 'data-testid', selector.slice(7))[0] || null;
                    }
                    if (selector.startsWith('xpath=')) {
                        var xp = selector.slice(6);
                        try {
                            var res = document.evaluate(xp, base, null, XPathResult.FIRST_ORDERED_NODE_TYPE, null);
                            return res.singleNodeValue;
                        } catch (e) {
                            return null;
                        }
                    }
                    try {
                        return base.querySelector(selector);
                    } catch (e) {
                        return null;
                    }
                };
                window.__kmpwright_queryAll = function(selector, root) {
                    var base = root || document;
                    if (selector.startsWith('ref=')) {
                        var referenced = (window.__kmpwright_snapshotNodes || {})[selector.slice(4)];
                        var found = referenced && referenced.isConnected && (base === document || base.contains(referenced));
                        return found ? [referenced] : [];
                    }
                    if (selector.startsWith('role=')) {
                        return window.__kmpwright_byRole(base, selector.slice(5));
                    }
                    if (selector.startsWith('label=')) {
                        var one = window.__kmpwright_byLabel(base, selector.slice(6));
                        return one ? [one] : [];
                    }
                    if (selector.startsWith('text=') || selector.startsWith('text:')) {
                        var text = selector.replace(/^text[=:]/, '');
                        var nodes = Array.from(base.querySelectorAll('*'));
                        var out = [];
                        var isRegex = text.startsWith('/') && text.endsWith('/') && text.length > 1;
                        var matcher = null;
                        if (isRegex) { try { matcher = new RegExp(text.slice(1, -1)); } catch (e) { matcher = null; } }
                        var contains = true;
                        var needle = text.replace(/^\*+|\*+$/g, '');
                        for (var i = 0; i < nodes.length; i++) {
                            var t = (nodes[i].innerText || nodes[i].textContent || '').trim();
                            if (!t) continue;
                            if (matcher && matcher.test(t)) out.push(nodes[i]);
                            else if (t.indexOf(needle) !== -1) out.push(nodes[i]);
                        }
                        return out;
                    }
                    if (selector.startsWith('exact_text=')) {
                        var exactText = selector.slice(11);
                        var exactNodes = Array.from(base.querySelectorAll('*'));
                        var exactOut = [];
                        for (var e = 0; e < exactNodes.length; e++) {
                            var exactValue = (exactNodes[e].innerText || exactNodes[e].textContent || '').trim();
                            if (exactValue === exactText) exactOut.push(exactNodes[e]);
                        }
                        return exactOut;
                    }
                    if (selector.startsWith('placeholder=')) {
                        return window.__kmpwright_byAttribute(base, 'placeholder', selector.slice(12));
                    }
                    if (selector.startsWith('alt=')) {
                        return window.__kmpwright_byAlt(base, selector.slice(4));
                    }
                    if (selector.startsWith('title=')) {
                        return window.__kmpwright_byAttribute(base, 'title', selector.slice(6));
                    }
                    if (selector.startsWith('testid=')) {
                        return window.__kmpwright_byAttribute(base, 'data-testid', selector.slice(7));
                    }
                    if (selector.startsWith('xpath=')) {
                        var xp = selector.slice(6);
                        var snapshot = document.evaluate(xp, base, null, XPathResult.ORDERED_NODE_SNAPSHOT_TYPE, null);
                        var arr = [];
                        for (var i = 0; i < snapshot.snapshotLength; i++) {
                            arr.push(snapshot.snapshotItem(i));
                        }
                        return arr;
                    }
                    try {
                        return Array.from(base.querySelectorAll(selector));
                    } catch (e) {
                        return [];
                    }
                };
                window.__kmpwright_resolveFirst = function(parts) {
                    var root = document;
                    var el = null;
                    for (var i = 0; i < parts.length; i++) {
                        el = window.__kmpwright_queryOne(parts[i], root);
                        if (!el) return null;
                        root = el;
                    }
                    return el;
                };
                window.__kmpwright_resolveAll = function(parts) {
                    var root = document;
                    var current = [];
                    for (var i = 0; i < parts.length; i++) {
                        var next = [];
                        var baseList = current.length ? current : [root];
                        for (var j = 0; j < baseList.length; j++) {
                            var list = window.__kmpwright_queryAll(parts[i], baseList[j]);
                            next = next.concat(list);
                        }
                        current = Array.from(new Set(next));
                        if (current.length === 0) return [];
                    }
                    return current;
                };
            })();
            """.trimIndent()
        }

        internal suspend fun ensureHelpersInjected(
            controller: WebViewScriptController,
            installMode: InstallMode = InstallMode.DocumentStartPreferred
        ) {
            if (installMode == InstallMode.Disabled) {
                throw KmpwrightException("Selector helpers are disabled by KmpwrightConfig.installMode")
            }
            if (installMode != InstallMode.AlwaysEvaluate && controller in injectedControllers) return
            suspendCancellableCoroutine<Unit> { cont ->
                controller.evaluateJavaScript(helpersJsSource()) { _ ->
                    if (installMode != InstallMode.AlwaysEvaluate) injectedControllers = injectedControllers.adding(controller)
                    if (cont.isActive) cont.resume(Unit)
                }
            }
        }

        internal fun invalidateHelpers(controller: WebViewScriptController) {
            injectedControllers = injectedControllers.removing(controller)
        }

        internal fun waitForConditionJs(conditionJs: String, timeoutMs: Long): String {
            val sanitizedCondition = conditionJs.replace("\n", " ")
            return """
            (function() {
                var __ww_check = function() { return $sanitizedCondition; };
                if (typeof MutationObserver === 'undefined') {
                    var start = Date.now();
                    return new Promise(function(resolve, reject) {
                        function check() {
                            try {
                                if (__ww_check()) { resolve(true); return; }
                            } catch(e) {}
                            if (Date.now() - start >= $timeoutMs) {
                                resolve('__kmpwright_timeout__');
                                return;
                            }
                            setTimeout(check, 50);
                        }
                        check();
                    });
                }
                return new Promise(function(resolve, reject) {
                    try {
                        if (__ww_check()) { resolve(true); return; }
                    } catch(e) {}

                    var observer = new MutationObserver(function() {
                        try {
                            if (__ww_check()) {
                                observer.disconnect();
                                clearInterval(recheckId);
                                if (timeoutId !== null) clearTimeout(timeoutId);
                                resolve(true);
                            }
                        } catch(e) {}
                    });
                    observer.observe(document, { childList: true, attributes: true, characterData: true, subtree: true });

                    var timeoutId = null;
                    var recheckId = setInterval(function() {
                        try {
                            if (__ww_check()) {
                                clearInterval(recheckId);
                                if (timeoutId !== null) clearTimeout(timeoutId);
                                observer.disconnect();
                                resolve(true);
                            }
                        } catch(e) {}
                    }, 100);
                    timeoutId = setTimeout(function() {
                        clearInterval(recheckId);
                        observer.disconnect();
                        try {
                            if (__ww_check()) {
                                resolve(true);
                                return;
                            }
                        } catch(e) {}
                        resolve('__kmpwright_timeout__');
                    }, $timeoutMs);
                });
            })()
            """.trimIndent()
        }
    }
}

/** Same-origin frames execute selector helpers against the frame document. */
class BaseFrameLocator(
    private val controller: WebViewLocatorController,
    private val selector: String,
    private val config: KmpwrightConfig
) : FrameLocator {
    constructor(controller: WebViewController, selector: String, config: KmpwrightConfig) :
        this(controller as WebViewLocatorController, selector, config)

    internal var policyChecker: (suspend (ActionType) -> Unit)? = null

    private fun frameSetupJs(): String {
        val selectors = selector.split(">>").map(String::trim).filter(String::isNotEmpty)
        return buildString {
            append("var frame = null; var frameDocument = document; var frameWindow = window;\n")
            selectors.forEach { frameSelector ->
                append("frame = frameDocument.querySelector(${jsStringLiteral(frameSelector)});\n")
                append("if (!frame) return '__kmpwright_frame_error__:Frame not found';\n")
                append("frameDocument = frame.contentDocument; frameWindow = frame.contentWindow;\n")
                append("if (!frameDocument) return '__kmpwright_frame_error__:Cross-origin frames require a backend frame session';\n")
            }
        }
    }

    private fun scopedScript(script: String): String = """
        (function() {
            ${frameSetupJs()}
            return (function(window, document) { return $script; })(frameWindow, frameDocument);
        })()
    """.trimIndent()

    private fun checkedResult(raw: String?): String? {
        val result = controller.normalizeJavaScriptResult(raw)
        if (result?.startsWith("__kmpwright_frame_error__:") == true) {
            throw KmpwrightException("$selector: ${result.substringAfter(':')}")
        }
        return result
    }

    private val frameController = object : WebViewLocatorController, WebViewCaptureController by controller {
        override fun normalizeJavaScriptResult(raw: String?): String? = raw
        override fun evaluateJavaScript(script: String, callback: (String?) -> Unit) {
            controller.evaluateJavaScript(scopedScript(script), callback)
        }
        override suspend fun evaluateJavaScriptAsync(script: String): String? =
            checkedResult(controller.evaluateJavaScriptAsync(scopedScript(script)))
        override fun injectJavaScript(script: String, callback: ((String?) -> Unit)?) {
            controller.evaluateJavaScript(scopedScript(script)) { callback?.invoke(it) }
        }
    }

    override fun locator(selector: String): Locator = createLocator(selector, null)

    private fun createLocator(selector: String, options: FilterOptions?): Locator =
        object : BaseLocator(frameController, selector, config.copy(
            installMode = if (config.installMode == InstallMode.Disabled) InstallMode.Disabled else InstallMode.AlwaysEvaluate
        ), options) {
            init { policyChecker = this@BaseFrameLocator.policyChecker }
            override fun createLocator(newSelector: String, options: FilterOptions?): Locator =
                this@BaseFrameLocator.createLocator(newSelector, options)
            override suspend fun evaluateJavaScriptAsync(script: String): String? =
                frameController.evaluateJavaScriptAsync(script)
        }

    override fun frameLocator(selector: String): FrameLocator =
        BaseFrameLocator(controller, listOf(this.selector, selector).filter(String::isNotEmpty).joinToString(" >> "), config)
            .also { it.policyChecker = policyChecker }

    private suspend fun inspectFrame(body: String): String? = checkedResult(controller.evaluateJavaScriptAsync("""
        (function() { ${frameSetupJs()} $body })()
    """.trimIndent()))

    override suspend fun name(): String? = inspectFrame("return frame ? frame.getAttribute('name') || frame.id || null : null;")
    override suspend fun url(): String? = inspectFrame("return frame ? frameDocument.location.href : document.location.href;")
    override suspend fun boundingBox(): Rectangle? {
        val value = inspectFrame("if (!frame) return null; var r = frame.getBoundingClientRect(); return JSON.stringify({x:r.x,y:r.y,width:r.width,height:r.height});") ?: return null
        val box = Json.decodeFromString<BaseLocator.BoundingBoxJson>(value)
        return Rectangle(box.x, box.y, box.width, box.height)
    }
}
