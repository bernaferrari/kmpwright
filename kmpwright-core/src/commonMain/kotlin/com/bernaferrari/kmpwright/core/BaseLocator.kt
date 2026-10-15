package com.bernaferrari.kmpwright.core

import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.time.TimeSource

/**
 * Shared locator implementation for every backend.
 *
 * Actions run Playwright-style actionability checks in the page and retry until the action timeout:
 * the element must be attached, visible, enabled (editable for fill/type), stable (same bounding box
 * across two consecutive probes ~2 animation frames apart) and must receive pointer events at the
 * action point. Pointer and keyboard input is then synthesized as a full, realistic event sequence.
 * Backends with native input (CDP) override the pointer/keyboard actions.
 */
internal abstract class BaseLocator(
    protected val controller: WebViewLocatorController,
    protected val selector: String,
    internal val config: KmpwrightConfig,
    private val filterOptions: FilterOptions? = null
) : Locator {

    /** Policy checker invoked before actions. Set by BasePage.locator(). */
    internal var policyChecker: (suspend (ActionType) -> Unit)? = null
    internal var lifetime: kotlinx.coroutines.Deferred<Unit>? = null

    private fun selectorParts(): List<String> = splitSelectorChain(selector)

    // A query is an immutable sequence: selection, filtering and indexing keep
    // their position when another selector is appended.
    private var querySteps: List<String> = selectorStepsOf(selector) + listOfNotNull(filterOptions?.let { filterStep(it) })

    private fun filterStep(options: FilterOptions): String {
        fun field(name: String, value: String?) = value?.let { "$name:${jsStringLiteral(it)}" }
        fun steps(name: String, value: String?) = value?.let { "$name:$it" }
        return listOfNotNull(
            field("hasText", options.hasText), field("hasNotText", options.hasNotText),
            steps("has", options.has), steps("hasNot", options.hasNot),
            steps("and", options.and), steps("or", options.or),
            options.nth?.let { "nth:$it" }
        ).joinToString(",", "{", "}")
    }

    /** The query as a JavaScript array of steps for the page runtime's `resolveAll`. */
    internal fun selectorPartsJs(): String = querySteps.joinToString(",", "[", "]")

    private fun stepsOf(other: Locator, operation: String): String {
        val base = other as? BaseLocator
            ?: throw KmpwrightException("$operation needs a locator created by the same page (got ${other::class.simpleName})")
        if (base.controller !== controller) throw KmpwrightException("$operation needs a locator created by the same page")
        return base.selectorPartsJs()
    }

    /** Human-readable target used in error messages (`@e3` for snapshot refs). */
    protected val displayName: String
        get() = selectorParts().singleOrNull()?.let { parseRef(it) } ?: selector

    private fun derived(newSelector: String, steps: List<String>): Locator =
        createLocator(newSelector).also {
            (it as? BaseLocator)?.apply {
                querySteps = steps
                policyChecker = this@BaseLocator.policyChecker
                lifetime = this@BaseLocator.lifetime
            }
        }

    // ---------------------------------------------------------------- runtime plumbing

    private suspend fun evalRaw(script: String, timeoutMs: Long = config.timeouts.default): String? =
        withKmpwrightTimeout(timeoutMs.coerceAtLeast(1), { "Timed out evaluating a script for '$displayName' after ${timeoutMs}ms" }) {
            evaluateJavaScriptAsync(script)
        }

    /** Runs [body] with the page runtime bound to `H`, installing the runtime on demand. */
    internal suspend fun runWithRuntime(body: String, timeoutMs: Long = config.timeouts.default): JsonElement =
        withKmpwrightTimeout(timeoutMs, { "Timed out evaluating a script for '$displayName' after ${timeoutMs}ms" }) {
            runWithInstalledRuntime(body, timeoutMs)
        }

    private suspend fun runWithInstalledRuntime(body: String, timeoutMs: Long): JsonElement {
        repeat(3) {
            when (val outcome = PageHelpers.decode(evalRaw(PageHelpers.envelope(body), timeoutMs))) {
                is PageHelpers.Outcome.Value -> return outcome.value
                is PageHelpers.Outcome.Error -> throw KmpwrightException(
                    if (outcome.message.startsWith("Invalid selector") || isRefError(outcome.message)) outcome.message else "$displayName: ${outcome.message}"
                )
                PageHelpers.Outcome.Tampered -> throw tamperedRuntime()
                PageHelpers.Outcome.Missing -> installRuntime(timeoutMs)
            }
        }
        throw KmpwrightException("Could not install the KMPwright page runtime for '$displayName'")
    }

    private suspend fun installRuntime(timeoutMs: Long) {
        if (config.installMode == InstallMode.Disabled) {
            throw KmpwrightException("Selector helpers are disabled by KmpwrightConfig.installMode")
        }
        val result = controller.normalizeJavaScriptResult(evalRaw(PageHelpers.installScript(), timeoutMs))
        if (result == PageHelpers.TAMPERED) throw tamperedRuntime()
    }

    private fun tamperedRuntime() = KmpwrightException(
        "The page replaced or pre-defined the KMPwright runtime slot; refusing to automate this document"
    )

    /** Script fragment resolving the locator's elements into `list`, honouring strict mode. */
    private fun resolveJs(strict: Boolean = config.strictSelectors): String = """
        var list = H.resolveAll(${selectorPartsJs()});
        if ($strict && list.length > 1) return {strict: list.length, d: H.describe(list[0]) + ', ' + H.describe(list[1])};
        var el = list[0] || null;
    """.trimIndent()

    private fun strictViolation(value: JsonElement): Nothing? {
        val obj = value as? JsonObject ?: return null
        val count = obj["strict"]?.jsonPrimitive?.content ?: return null
        throw KmpwrightException(
            "Strict mode violation: '$displayName' resolved to $count elements (${obj["d"]?.jsonPrimitive?.content}). " +
                "Use first(), last(), nth() or a more specific selector."
        )
    }

    /**
     * Evaluates [body] with `el` bound to the resolved element. Returns the body's value as a string
     * (JSON for objects), or null when no element matches and [requireElement] is false.
     */
    protected suspend fun evalOnElement(body: String, requireElement: Boolean = false): String? = awaitPageOperation(lifetime) {
        val value = runWithRuntime("""
            ${resolveJs()}
            if (!el) return {missing: 1};
            return {v: (function(el) { $body
            })(el)};
        """.trimIndent())
        strictViolation(value)
        val obj = value as? JsonObject
        if (obj == null) {
            // Custom bridges without envelope support return the body's value directly.
            return@awaitPageOperation PageHelpers.valueAsString(value)
        }
        if (obj.containsKey("missing")) {
            if (requireElement) throw KmpwrightException("No element found for '$displayName'. Refresh the snapshot if this is a stale reference.")
            return@awaitPageOperation null
        }
        PageHelpers.valueAsString(obj["v"] ?: JsonNull)
    }

    /** Evaluates [body] once with `el` bound (strict mode applies); null when nothing matches. Never waits. */
    internal suspend fun queryElement(body: String): String? = evalOnElement(body, requireElement = false)

    // ---------------------------------------------------------------- actionability

    internal data class Checks(
        val visible: Boolean = true,
        val enabled: Boolean = true,
        val editable: Boolean = false,
        val stable: Boolean = true,
        val hit: Boolean = true,
        val scroll: Boolean = true
    )

    /**
     * Waits until the element passes [checks], then runs `H.perform(el, kind, args, probe)`.
     * Retries until [timeoutMs]; the error names the last failing check and the element.
     */
    internal suspend fun performAction(
        kind: String,
        args: JsonObject,
        timeoutMs: Long,
        force: Boolean,
        checks: Checks,
        position: Position? = null,
        actionLabel: String = kind
    ): JsonElement = awaitPageOperation(lifetime) {
        require(position == null || (position.x.isFinite() && position.y.isFinite())) { "Position must be finite" }
        val started = TimeSource.Monotonic.markNow()
        var previousRect: JsonElement? = null
        var lastState = "missing"
        var lastDetail: JsonObject? = null
        var attempt = 0
        withKmpwrightTimeout(timeoutMs, { actionTimeout(actionLabel, timeoutMs, lastState, lastDetail).message.orEmpty() }) {
            while (true) {
                val opts = buildJsonObject {
                    put("visible", checks.visible && !force)
                    put("enabled", checks.enabled && !force)
                    put("editable", checks.editable)
                    put("force", force)
                    put("stable", checks.stable && !force)
                    put("hit", checks.hit && !force)
                    put("scroll", checks.scroll)
                    previousRect?.let { put("prev", it) }
                    position?.let { putJsonObject("position") { put("x", it.x); put("y", it.y) } }
                }
                val value = runWithRuntime("""
                    ${resolveJs()}
                    if (!el) return {state: 'missing'};
                    var p = H.probe(el, $opts);
                    if (p.state !== 'ok') return p;
                    return {state: 'done', result: H.perform(el, ${jsStringLiteral(kind)}, $args, p)};
                """.trimIndent(), (timeoutMs - started.elapsedNow().inWholeMilliseconds).coerceAtLeast(1))
                strictViolation(value)
                val obj = value as? JsonObject
                    // A bridge that cannot report results (custom controller) is treated as completed.
                    ?: return@withKmpwrightTimeout value
                val state = obj["state"]?.jsonPrimitive?.content ?: "done"
                when (state) {
                    "done" -> return@withKmpwrightTimeout obj["result"] ?: JsonNull
                    "fatal" -> throw KmpwrightException("Cannot $actionLabel '$displayName': ${obj["reason"]?.jsonPrimitive?.content}: ${obj["d"]?.jsonPrimitive?.content}")
                }
                lastState = state
                lastDetail = obj
                if (state == "unstable") previousRect = obj["rect"] else if (state != "intercepted") previousRect = null
                // Leave room for one final probe inside the budget, so the detailed error is reported
                // before any caller-level timeout with the same budget fires.
                val budget = timeoutMs - started.elapsedNow().inWholeMilliseconds - FINAL_PROBE_MARGIN_MS
                if (budget <= 0) throw actionTimeout(actionLabel, timeoutMs, lastState, lastDetail)
                val wait = when (state) {
                    "unstable" -> 34L // two animation frames
                    else -> RETRY_BACKOFF_MS[minOf(attempt, RETRY_BACKOFF_MS.lastIndex)]
                }
                attempt++
                delay(minOf(wait, budget))
            }
            @Suppress("UNREACHABLE_CODE") JsonNull
        }
    }

    private fun actionTimeout(action: String, timeoutMs: Long, state: String, detail: JsonObject?): KmpwrightTimeoutException {
        val element = detail?.get("d")?.jsonPrimitive?.content?.let { ": $it" }.orEmpty()
        val reason = when (state) {
            "missing" -> "was not found"
            "detached" -> "was detached from the DOM"
            "notvisible" -> "is not visible"
            "disabled" -> "is not enabled"
            "noteditable" -> "is not editable (${detail?.get("reason")?.jsonPrimitive?.content ?: "read-only"})"
            "unstable" -> "is not stable (still moving or animating)"
            "intercepted" -> "would not receive the $action: it is covered by ${detail?.get("hit")?.jsonPrimitive?.content}" +
                " (pass force = true only when that is intended)"
            "nooption" -> "has no option matching ${detail?.get("value")?.jsonPrimitive?.content}"
            else -> "is not ready ($state)"
        }
        return KmpwrightTimeoutException("Element $displayName $reason after ${timeoutMs}ms while trying to $action$element")
    }

    private suspend fun checkPolicy(type: ActionType) { policyChecker?.invoke(type) }

    protected fun timeoutOf(requested: Long?): Long = config.actionTimeout(requested).also {
        if (it <= 0) throw KmpwrightException("Action timeout must be positive, was ${it}ms")
    }

    // ---------------------------------------------------------------- actions

    override suspend fun click(button: MouseButton, clickCount: Int, delayMs: Long, position: Position?, force: Boolean, timeoutMs: Long?): Unit = config.inputAction(timeoutMs, "locator click") {
        checkPolicy(ActionType.Click)
        syntheticClick(button, clickCount.coerceAtLeast(1), delayMs, position, force, timeoutMs, "click")
    }

    override suspend fun dblclick(button: MouseButton, delayMs: Long, position: Position?, force: Boolean, timeoutMs: Long?): Unit = config.inputAction(timeoutMs, "locator dblclick") {
        checkPolicy(ActionType.Click)
        syntheticClick(button, 2, delayMs, position, force, timeoutMs, "double-click")
    }

    /**
     * Playwright click semantics: [delayMs] is the time between `mousedown` and `mouseup`
     * of every click (and between consecutive clicks of a multi-click).
     */
    private suspend fun syntheticClick(
        mouseButton: MouseButton,
        clickCount: Int,
        delayMs: Long,
        position: Position?,
        force: Boolean,
        timeoutMs: Long?,
        label: String
    ): Unit = config.inputAction(timeoutMs, label) {
        val timeout = timeoutOf(timeoutMs)
        val button = mouseButtonCode(mouseButton)
        if (delayMs <= 0) {
            performAction("click", buildJsonObject {
                put("button", button)
                put("clickCount", clickCount)
            }, timeout, force, Checks(), position = position, actionLabel = label)
            return@inputAction
        }
        val point = performAction("clickDown", buildJsonObject { put("button", button); put("i", 1) },
            timeout, force, Checks(), position = position, actionLabel = label) as? JsonObject
        val x = point?.get("x")?.jsonPrimitive?.doubleOrNull ?: return@inputAction
        val y = point["y"]?.jsonPrimitive?.doubleOrNull ?: return@inputAction
        for (i in 1..clickCount) {
            if (i > 1) {
                delay(delayMs)
                performOnElement("clickDown", buildJsonObject { put("button", button); put("i", i) }, x, y, label)
            }
            Unit.useThenClose({ delay(delayMs) }) {
                withKmpwrightTimeout(1_000, { "Timed out releasing the pointer" }) {
                    performOnElement("clickUp", buildJsonObject { put("button", button); put("i", i) }, x, y, label)
                }
            }
        }
    }

    /** Runs one step of an action that already passed its actionability checks (no re-probing). */
    internal suspend fun performOnElement(kind: String, args: JsonObject, x: Double, y: Double, label: String): JsonElement =
        awaitPageOperation(lifetime) {
            val value = runWithRuntime("""
                ${resolveJs()}
                if (!el) return {missing: 1};
                return {v: H.perform(el, ${jsStringLiteral(kind)}, $args, {x: $x, y: $y})};
            """.trimIndent())
            strictViolation(value)
            val obj = value as? JsonObject ?: return@awaitPageOperation value
            if (obj.containsKey("missing")) throw KmpwrightException("Cannot $label '$displayName': the element was detached during the action")
            obj["v"] ?: JsonNull
        }

    override suspend fun tap(force: Boolean, modifiers: Int, timeoutMs: Long?): Unit = config.inputAction(timeoutMs, "locator tap") {
        checkPolicy(ActionType.Click)
        performAction("tap", JsonObject(emptyMap()), timeoutOf(timeoutMs), force, Checks(), actionLabel = "tap")
    }

    override suspend fun fill(value: String, force: Boolean, timeoutMs: Long?): Unit = config.inputAction(timeoutMs, "locator fill") {
        checkPolicy(ActionType.Fill)
        performAction("fill", buildJsonObject { put("value", value) }, timeoutOf(timeoutMs), force,
            Checks(editable = true, stable = false, hit = false), actionLabel = "fill")
    }

    override suspend fun clear(force: Boolean, timeoutMs: Long?) = fill("", force, timeoutMs)

    override suspend fun pressSequentially(text: String, delayMs: Long, timeoutMs: Long?): Unit = config.inputAction(timeoutMs, "locator keyboard input") {
        checkPolicy(ActionType.Type)
        if (text.isEmpty()) return@inputAction
        val timeout = timeoutOf(timeoutMs)
        val units = text.toUnicodeTextUnits()
        val batches = if (delayMs > 0) units.map { listOf(it) } else listOf(units)
        for ((index, batch) in batches.withIndex()) {
            if (index > 0) delay(delayMs)
            performAction("type", buildJsonObject {
                put("chars", buildJsonArray {
                    batch.forEach { unit ->
                        add(buildJsonObject {
                            put("text", unit)
                            put("d", keyDescriptorJson(if (unit == "\n") "Enter" else unit, emptySet()))
                        })
                    }
                })
            }, timeout, force = false, Checks(stable = false, hit = false), actionLabel = "type into")
        }
    }

    override suspend fun press(key: String, delayMs: Long, timeoutMs: Long?): Unit = config.inputAction(timeoutMs, "locator keyboard input") {
        checkPolicy(ActionType.Press)
        val args = pressArgsJson(key)
        val checks = Checks(visible = false, enabled = false, stable = false, hit = false, scroll = false)
        if (delayMs <= 0) {
            performAction("press", args, timeoutOf(timeoutMs), force = false, checks, actionLabel = "press $key on")
            return@inputAction
        }
        // Playwright: delayMs is the time between keydown and keyup.
        performAction("pressDown", args, timeoutOf(timeoutMs), force = false, checks, actionLabel = "press $key on")
        Unit.useThenClose({ delay(delayMs) }) {
            withKmpwrightTimeout(1_000, { "Timed out releasing $key" }) {
                performOnElement("pressUp", args, 0.0, 0.0, "press $key on")
            }
        }
    }

    override suspend fun hover(force: Boolean, timeoutMs: Long?): Unit = config.inputAction(timeoutMs, "locator hover") {
        checkPolicy(ActionType.Hover)
        performAction("hover", JsonObject(emptyMap()), timeoutOf(timeoutMs), force, Checks(enabled = false), actionLabel = "hover")
    }

    override suspend fun scrollIntoViewIfNeeded(block: ScrollPosition, inline: ScrollPosition, timeoutMs: Long?): Unit = config.inputAction(timeoutMs, "locator scrollIntoViewIfNeeded") {
        checkPolicy(ActionType.Scroll)
        fun position(value: ScrollPosition) = when (value) {
            ScrollPosition.Start -> "start"; ScrollPosition.Center -> "center"; ScrollPosition.End -> "end"; ScrollPosition.Nearest -> "nearest"
        }
        performAction("scrollIfNeeded", buildJsonObject {
            put("block", position(block)); put("inline", position(inline))
        }, timeoutOf(timeoutMs), force = false, Checks(enabled = false, stable = true, hit = false, scroll = false),
            actionLabel = "scroll into view")
    }

    override suspend fun focus(timeoutMs: Long?): Unit = config.inputAction(timeoutMs, "locator focus") {
        checkPolicy(ActionType.Focus)
        performAction("focus", JsonObject(emptyMap()), timeoutOf(timeoutMs), force = false,
            Checks(visible = false, enabled = false, stable = false, hit = false, scroll = false), actionLabel = "focus")
    }

    override suspend fun blur(timeoutMs: Long?): Unit = config.inputAction(timeoutMs, "locator blur") {
        checkPolicy(ActionType.Focus)
        waitFor(SelectorState.Attached, timeoutOf(timeoutMs))
        evalOnElement("if (typeof el.blur === 'function') el.blur(); return true;", requireElement = true)
    }

    override suspend fun dispatchEvent(type: String, timeoutMs: Long?): Unit = config.inputAction(timeoutMs, "locator dispatchEvent") {
        checkPolicy(ActionType.Click)
        waitFor(SelectorState.Attached, timeoutOf(timeoutMs))
        evalOnElement("""
            var type = ${jsStringLiteral(type)}, init = {bubbles: true, cancelable: true, composed: true}, ev;
            var C = /^(click|dblclick|mouse|contextmenu|auxclick)/.test(type) ? window.MouseEvent
              : /^pointer/.test(type) ? window.PointerEvent
              : /^key/.test(type) ? window.KeyboardEvent
              : /^(focus|blur)/.test(type) ? window.FocusEvent
              : /^(drag|drop)/.test(type) ? window.DragEvent
              : /^touch/.test(type) ? window.TouchEvent
              : /^(input|beforeinput)$/.test(type) ? window.InputEvent : window.Event;
            try { ev = new (C || window.Event)(type, init); } catch (e) { ev = new Event(type, init); }
            el.dispatchEvent(ev);
            return true;
        """.trimIndent(), requireElement = true)
    }

    override suspend fun evaluate(expression: String, timeoutMs: Long?): JsResult = config.inputAction(timeoutMs, "locator evaluate") {
        checkPolicy(ActionType.Evaluate)
        if (!config.allowEval) throw KmpwrightException("JavaScript evaluation is disabled by KmpwrightConfig.allowEval")
        waitFor(SelectorState.Attached, timeoutOf(timeoutMs))
        val raw = evalOnElement("""
            var v = (${expression.trim().removeSuffix(";")})(el);
            var t = v === null ? 'null' : Array.isArray(v) ? 'array' : typeof v, s;
            if (t === 'string') s = v;
            else if (t === 'undefined') s = 'null';
            else if (t === 'function' || t === 'symbol') s = String(v);
            else { try { s = JSON.stringify(v); } catch (e) { s = String(v); } if (s === undefined) s = String(v); }
            return JSON.stringify({t: t, v: s});
        """.trimIndent(), requireElement = true) ?: return@inputAction JsResult("null", "undefined")
        val envelope = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
        val type = envelope?.get("t")?.jsonPrimitive?.content
        val value = envelope?.get("v")?.jsonPrimitive?.content
        if (type == null || value == null) JsResult(raw, "unknown") else JsResult(value, type)
    }

    override suspend fun check(force: Boolean, timeoutMs: Long?) = setChecked(true, force, timeoutMs)

    override suspend fun uncheck(force: Boolean, timeoutMs: Long?) = setChecked(false, force, timeoutMs)

    override suspend fun setChecked(checked: Boolean, force: Boolean, timeoutMs: Long?): Unit = config.inputAction(timeoutMs, "locator setChecked") {
        checkPolicy(ActionType.Click)
        val timeout = timeoutOf(timeoutMs)
        val started = TimeSource.Monotonic.markNow()
        // Like every other action, wait for the element to be attached before inspecting it.
        waitFor(SelectorState.Attached, timeout)
        val current = checkedState() ?: throw KmpwrightException("Element $displayName is not a checkbox or radio button")
        if (current == checked) return@inputAction
        if (!checked && evalOnElement("return el.tagName === 'INPUT' && String(el.type).toLowerCase() === 'radio';", true) == "true") {
            throw KmpwrightException("Cannot uncheck radio button $displayName; check another option in its group instead")
        }
        click(force = force, timeoutMs = (timeout - started.elapsedNow().inWholeMilliseconds).coerceAtLeast(1))
        if (checkedState() != checked) {
            throw KmpwrightException("Clicking $displayName did not ${if (checked) "check" else "uncheck"} it")
        }
    }

    /** True/false for checkbox-like elements, null when the element is not checkable. */
    private suspend fun checkedState(): Boolean? =
        when (evalOnElement("var c = H.checkedState(el); return c === null ? 'none' : String(c);", requireElement = true)) {
            "true", "mixed" -> true
            "false" -> false
            else -> null
        }

    override suspend fun selectOption(value: String, force: Boolean, timeoutMs: Long?) = selectOption(listOf(value), force, timeoutMs)

    override suspend fun selectOption(values: List<String>, force: Boolean, timeoutMs: Long?): Unit = config.inputAction(timeoutMs, "locator selectOption") {
        checkPolicy(ActionType.Select)
        val timeout = timeoutOf(timeoutMs)
        val started = TimeSource.Monotonic.markNow()
        while (true) {
            val result = performAction("select", buildJsonObject {
                put("values", JsonArray(values.map { JsonPrimitive(it) }))
            }, (timeout - started.elapsedNow().inWholeMilliseconds).coerceAtLeast(FINAL_PROBE_MARGIN_MS + 1), force,
                Checks(stable = false, hit = false), actionLabel = "select an option in")
            val missing = (result as? JsonObject)?.get("missing")?.jsonPrimitive?.content ?: return@inputAction
            val budget = timeout - started.elapsedNow().inWholeMilliseconds - FINAL_PROBE_MARGIN_MS
            if (budget <= 0) {
                throw KmpwrightTimeoutException("Element $displayName has no option matching \"$missing\" after ${timeout}ms. " +
                    "Available: ${result["available"]?.jsonPrimitive?.content}")
            }
            delay(minOf(100, budget))
        }
    }

    override suspend fun setInputFiles(files: List<String>, force: Boolean, timeoutMs: Long?): Unit = config.inputAction(timeoutMs, "locator setInputFiles") {
        if (files.isNotEmpty()) throw KmpwrightException("setInputFiles is not supported in mobile WebView")
        checkPolicy(ActionType.Fill)
        awaitFileInput(files.size, force, timeoutMs)
        clearInputFiles()
    }

    /**
     * Waits (within [timeoutMs]) until the element is attached and, unless [force], enabled; then
     * requires an `<input type=file>` that accepts [fileCount] files.
     * File inputs are usually hidden, so (as in Playwright) visibility is not required.
     */
    protected suspend fun awaitFileInput(fileCount: Int, force: Boolean, timeoutMs: Long?) {
        val result = performAction("fileInput", buildJsonObject { put("count", fileCount) }, timeoutOf(timeoutMs), force,
            Checks(visible = false, enabled = true, stable = false, hit = false, scroll = false), actionLabel = "set input files on")
        val error = (result as? JsonObject)?.get("error")?.jsonPrimitive?.content ?: return
        throw KmpwrightException("Cannot set input files on '$displayName': $error")
    }

    /** Clears a file input's selection (`setInputFiles(emptyList())`). */
    protected suspend fun clearInputFiles() {
        evalOnElement("el.value = ''; el.dispatchEvent(new Event('input', { bubbles: true })); el.dispatchEvent(new Event('change', { bubbles: true })); return true;", requireElement = true)
    }

    override suspend fun dragTo(target: Locator, sourcePosition: Position?, targetPosition: Position?, timeoutMs: Long?): Unit = config.inputAction(timeoutMs, "locator dragTo") {
        checkPolicy(ActionType.Drag)
        val targetBase = target as? BaseLocator
            ?: throw KmpwrightException("dragTo target must be a locator created by the same page (got ${target::class.simpleName})")
        if (targetBase.controller !== controller) throw KmpwrightException("dragTo target must belong to the same page")
        val timeout = timeoutOf(timeoutMs)
        // The source must be actionable; the target only needs to be visible.
        performAction("noop", JsonObject(emptyMap()), timeout, force = false,
            Checks(enabled = false, scroll = true), position = sourcePosition, actionLabel = "drag")
        targetBase.waitFor(SelectorState.Visible, timeout)
        val result = runWithRuntime("""
            ${resolveJs()}
            if (!el) return {e: 'source missing'};
            var targets = H.resolveAll(${targetBase.selectorPartsJs()});
            if (!targets.length) return {e: 'target missing'};
            var tgt = targets[0];
            var sp = H.probe(el, ${pointJson(sourcePosition)});
            var tp = H.probe(tgt, ${pointJson(targetPosition)});
            return H.drag(el, tgt, {x: sp.x, y: sp.y}, {x: tp.x, y: tp.y}, 8);
        """.trimIndent())
        strictViolation(result)
        (result as? JsonObject)?.get("e")?.let { throw KmpwrightException("Cannot drag $displayName: ${it.jsonPrimitive.content}") }
    }

    private fun pointJson(position: Position?): String = buildJsonObject {
        put("scroll", false)
        position?.let { putJsonObject("position") { put("x", it.x); put("y", it.y) } }
    }.toString()

    // ---------------------------------------------------------------- queries

    private fun requireDomExtraction() {
        if (!config.allowDomExtraction) throw KmpwrightException("DOM extraction is disabled by KmpwrightConfig.allowDomExtraction")
    }

    // Playwright: element reads wait for the element to be attached (no other actionability checks)
    // within the action timeout, and fail when it never appears.

    override suspend fun textContent(): String? {
        requireDomExtraction()
        return evalWhenAttached("return el.textContent;", "textContent")
    }

    override suspend fun innerText(): String {
        requireDomExtraction()
        return evalWhenAttached("return el.innerText == null ? (el.textContent || '') : el.innerText;", "innerText") ?: ""
    }

    override suspend fun innerHTML(): String {
        requireDomExtraction()
        return evalWhenAttached("return el.innerHTML;", "innerHTML") ?: ""
    }

    override suspend fun getAttribute(name: String): String? {
        requireDomExtraction()
        return evalWhenAttached("return el.getAttribute(${jsStringLiteral(name)});", "getAttribute")
    }

    /** Playwright `inputValue`: `<input>`, `<textarea>` or `<select>` (or a `<label>` of one); throws otherwise. */
    override suspend fun inputValue(): String? {
        requireDomExtraction()
        val value = evalWhenAttached(INPUT_VALUE_JS, "inputValue")
        if (value == NOT_AN_INPUT) throw KmpwrightException("Cannot read the input value of '$displayName': not an <input>, <textarea> or <select> element")
        return value
    }

    /**
     * Like [evalOnElement], but waits (polling, within [TimeoutConfig.action]) until the element is
     * attached; throws [KmpwrightTimeoutException] when it never is.
     */
    private suspend fun evalWhenAttached(body: String, label: String, timeoutMs: Long = timeoutOf(null)): String? = awaitPageOperation(lifetime) {
        withKmpwrightTimeout(timeoutMs, { "Timeout ${timeoutMs}ms exceeded waiting for '$displayName' to be attached (reading $label)" }) {
            val started = TimeSource.Monotonic.markNow()
            var attempt = 0
            while (true) {
                val value = runWithRuntime("""
                    ${resolveJs()}
                    if (!el) return {missing: 1};
                    return {v: (function(el) { $body
                    })(el)};
                """.trimIndent())
                strictViolation(value)
                val obj = value as? JsonObject
                    // Custom bridges without envelope support return the body's value directly.
                    ?: return@withKmpwrightTimeout PageHelpers.valueAsString(value)
                if (!obj.containsKey("missing")) return@withKmpwrightTimeout PageHelpers.valueAsString(obj["v"] ?: JsonNull)
                val elapsed = started.elapsedNow().inWholeMilliseconds
                if (elapsed >= timeoutMs) {
                    throw KmpwrightTimeoutException("Timeout ${timeoutMs}ms exceeded waiting for '$displayName' to be attached (reading $label)")
                }
                delay(minOf(WAIT_BACKOFF_MS[minOf(attempt++, WAIT_BACKOFF_MS.lastIndex)], (timeoutMs - elapsed).coerceAtLeast(1)))
            }
            @Suppress("UNREACHABLE_CODE") null
        }
    }

    override suspend fun count(): Int = awaitPageOperation(lifetime) {
        // Structural query: allowed even when allowDomExtraction is false (it reveals no content).
        val value = runWithRuntime("return H.resolveAll(${selectorPartsJs()}).length;")
        PageHelpers.valueAsString(value)?.toDoubleOrNull()?.toInt() ?: 0
    }

    override suspend fun all(): List<Locator> = List(count()) { nth(it) }

    override suspend fun allTextContents(): List<String> = mapAll("el.textContent || ''")

    override suspend fun allInnerTexts(): List<String> = mapAll("el.innerText == null ? (el.textContent || '') : el.innerText")

    /** Evaluates [expression] (with `el` bound) for every match, without strict mode. */
    private suspend fun mapAll(expression: String): List<String> = awaitPageOperation(lifetime) {
        requireDomExtraction()
        val value = runWithRuntime("return JSON.stringify(H.resolveAll(${selectorPartsJs()}).map(function(el) { return String($expression); }));")
        val raw = PageHelpers.valueAsString(value) ?: return@awaitPageOperation emptyList()
        try {
            Json.decodeFromString<List<String>>(raw)
        } catch (e: Exception) {
            throw KmpwrightException("Could not decode element texts for '$displayName'", e)
        }
    }

    /** Playwright semantics: a missing element is not visible (no waiting). */
    override suspend fun isVisible(): Boolean = evalOnElement("return H.isVisible(el);") == "true"

    override suspend fun isHidden(): Boolean = !isVisible()

    override suspend fun isEnabled(): Boolean = evalOnElement("return !H.isDisabled(el);", requireElement = true) == "true"

    override suspend fun isDisabled(): Boolean = !isEnabled()

    override suspend fun isChecked(): Boolean {
        val state = evalOnElement("var c = H.checkedState(el); return c === null ? 'none' : String(c);", requireElement = true)
        if (state == "none") throw KmpwrightException("Element $displayName is not a checkbox or radio button")
        return state == "true" || state == "mixed"
    }

    override suspend fun isFocused(): Boolean =
        evalOnElement("var r = el.getRootNode ? el.getRootNode() : document; return el === (r.activeElement || document.activeElement);") == "true"

    override suspend fun isEditable(): Boolean =
        evalOnElement("return !H.isDisabled(el) && H.editability(el) === null;", requireElement = true) == "true"

    @Serializable
    data class BoundingBoxJson(
        val x: Double,
        val y: Double,
        val width: Double,
        val height: Double
    )

    override suspend fun boundingBox(): Rectangle? {
        // Structural query: allowed even when allowDomExtraction is false (it reveals no content).
        val result = evalOnElement("""
            var rect = el.getBoundingClientRect();
            return JSON.stringify({x:rect.x, y:rect.y, width:rect.width, height:rect.height});
        """.trimIndent()) ?: return null
        val parsed = Json.decodeFromString<BoundingBoxJson>(result)
        return Rectangle(parsed.x, parsed.y, parsed.width, parsed.height)
    }

    override suspend fun screenshot(omitBackground: Boolean, quality: Int, format: ScreenshotFormat, scale: Double, timeoutMs: Long?): ScreenshotResult =
        screenshot(ScreenshotOptions(omitBackground = omitBackground, quality = quality, format = format, scale = scale, timeoutMs = timeoutMs))

    /** Captures the element's bounding box with the page capture path ([ScreenshotOptions.clip] is replaced). */
    protected open suspend fun screenshot(options: ScreenshotOptions): ScreenshotResult {
        val timeout = config.defaultTimeout(options.timeoutMs)
        return awaitPageOperation(lifetime) {
            withKmpwrightTimeout(timeout, { "Element screenshot of $displayName timed out after ${timeout}ms" }) {
                checkPolicy(ActionType.Screenshot)
                scrollIntoViewIfNeeded(timeoutMs = timeout)
                val box = boundingBox() ?: throw KmpwrightException("No element found for screenshot: $displayName")
                // Same capture path as page screenshots: format, quality and omitBackground behave identically.
                controller.captureScreenshot(options.copy(fullPage = false, clip = box, captureBeyondViewport = true))
            }
        }
    }

    override suspend fun highlight(color: String, borderColor: String, durationMs: Long) {
        evalOnElement("""
            var rect = el.getBoundingClientRect();
            var div = document.createElement('div');
            div.setAttribute('data-kmpwright-highlight', 'true');
            div.style.position = 'fixed';
            div.style.left = rect.x + 'px';
            div.style.top = rect.y + 'px';
            div.style.width = rect.width + 'px';
            div.style.height = rect.height + 'px';
            div.style.backgroundColor = ${jsStringLiteral(color)};
            div.style.border = '2px solid ' + ${jsStringLiteral(borderColor)};
            div.style.borderRadius = '2px';
            div.style.pointerEvents = 'none';
            div.style.zIndex = '2147483647';
            document.body.appendChild(div);
            if ($durationMs > 0) setTimeout(function() { div.remove(); }, $durationMs);
            return true;
        """.trimIndent(), requireElement = true)
    }

    override suspend fun waitFor(state: SelectorState, timeoutMs: Long?) = awaitWaitFor(state, timeoutMs ?: config.timeouts.waitForSelector)

    private suspend fun awaitWaitFor(state: SelectorState, timeoutMs: Long) = awaitPageOperation(lifetime) {
        if (timeoutMs <= 0) throw KmpwrightException("Wait timeout must be positive")
        withKmpwrightTimeout(timeoutMs, { "Timeout ${timeoutMs}ms exceeded waiting for '$displayName' to be ${state.name.lowercase()}" }) {
            val condition = when (state) {
                SelectorState.Attached -> "return list.length > 0;"
                SelectorState.Detached -> "return list.length === 0;"
                SelectorState.Visible -> "return list.length > 0 && H.isVisible(list[0]);"
                SelectorState.Hidden -> "return list.length === 0 || !H.isVisible(list[0]);"
            }
            // A ref that is gone already satisfies hidden/detached; for other states it fails fast.
            val resolve = if (state == SelectorState.Detached || state == SelectorState.Hidden) {
                "var list; try { list = H.resolveAll(${selectorPartsJs()}); } catch (e) { if (/^(Unknown ref|Ref @)/.test(String(e && e.message))) list = []; else throw e; }"
            } else {
                "var list = H.resolveAll(${selectorPartsJs()});"
            }
            val started = TimeSource.Monotonic.markNow()
            var attempt = 0
            while (true) {
                val value = runWithRuntime("/*kmpwright:waitFor*/ $resolve $condition")
                if (PageHelpers.valueAsString(value) == "true") return@withKmpwrightTimeout
                val elapsed = started.elapsedNow().inWholeMilliseconds
                if (elapsed >= timeoutMs) {
                    throw KmpwrightTimeoutException("Timeout ${timeoutMs}ms exceeded waiting for '$displayName' to be ${state.name.lowercase()}")
                }
                delay(minOf(WAIT_BACKOFF_MS[minOf(attempt++, WAIT_BACKOFF_MS.lastIndex)], (timeoutMs - elapsed).coerceAtLeast(1)))
            }
        }
    }

    override fun first(): Locator = nth(0)
    override fun nth(index: Int): Locator = derived(selector, querySteps + filterStep(FilterOptions(nth = index)))
    override fun last(): Locator = nth(-1)
    override fun locator(selector: String): Locator = derived("${this.selector} >> $selector",
        querySteps + selectorStepsOf(selector))
    override fun filter(hasText: String?, hasNotText: String?, has: Locator?, hasNot: Locator?): Locator =
        derived(selector, querySteps + filterStep(FilterOptions(
            hasText = hasText, hasNotText = hasNotText,
            has = has?.let { stepsOf(it, "filter(has)") }, hasNot = hasNot?.let { stepsOf(it, "filter(hasNot)") }
        )))

    override fun filter(hasText: Regex?, hasNotText: Regex?, has: Locator?, hasNot: Locator?): Locator =
        derived(selector, querySteps + filterStep(FilterOptions(
            hasText = hasText?.let(::jsRegexLiteral), hasNotText = hasNotText?.let(::jsRegexLiteral),
            has = has?.let { stepsOf(it, "filter(has)") }, hasNot = hasNot?.let { stepsOf(it, "filter(hasNot)") }
        )))

    override fun and(locator: Locator): Locator =
        derived("$selector and $locator", querySteps + filterStep(FilterOptions(and = stepsOf(locator, "and()"))))

    override fun or(locator: Locator): Locator =
        derived("$selector or $locator", querySteps + filterStep(FilterOptions(or = stepsOf(locator, "or()"))))

    /** The frame owner is looked up inside this locator's elements, like Playwright's `locator.frameLocator()`. */
    override fun frameLocator(selector: String): FrameLocator {
        return BaseFrameLocator(controller, selector, config, scopeSteps = selectorPartsJs())
            .also { it.policyChecker = policyChecker; it.lifetime = lifetime }
    }

    override fun toString(): String = "Locator($displayName)"

    protected abstract fun createLocator(newSelector: String, options: FilterOptions? = null): Locator
    protected abstract suspend fun evaluateJavaScriptAsync(script: String): String?

    companion object {
        /** Errors raised by the runtime for unknown or detached snapshot refs (see `PageHelpers` `ref` engine). */
        internal fun isRefError(message: String): Boolean =
            message.startsWith("Unknown ref @") || (message.startsWith("Ref @") && message.contains("no longer attached"))

        private val RETRY_BACKOFF_MS = longArrayOf(0, 20, 50, 100, 100, 250, 500)
        internal const val FINAL_PROBE_MARGIN_MS = 50L
        private val WAIT_BACKOFF_MS = longArrayOf(20, 50, 100, 100, 250)
        private const val NOT_AN_INPUT = "__kmpwright_not_an_input__"
        /** `el` body returning an input value (Playwright `inputValue` rules) or [NOT_AN_INPUT]. */
        internal const val INPUT_VALUE_JS = "var t = el; if (t.tagName === 'LABEL' && t.control) t = t.control; " +
            "if (t.tagName !== 'INPUT' && t.tagName !== 'TEXTAREA' && t.tagName !== 'SELECT') return '$NOT_AN_INPUT'; return t.value;"

        /** Source of the in-page runtime (installed under a private, non-writable slot). */
        internal fun helpersJsSource(): String = PageHelpers.installScript()

        /** Installs the page runtime (idempotent); callers then reach it through [PageHelpers.runtimeExpression]. */
        internal suspend fun ensureHelpersInjected(
            controller: WebViewScriptController,
            installMode: InstallMode = InstallMode.Automatic
        ) {
            if (installMode == InstallMode.Disabled) {
                throw KmpwrightException("Selector helpers are disabled by KmpwrightConfig.installMode")
            }
            val result = withKmpwrightTimeout(30_000, { "Timed out installing the KMPwright page runtime" }) {
                controller.normalizeJavaScriptResult(controller.evaluateJavaScriptAsync(PageHelpers.installScript()))
            }
            if (result == PageHelpers.TAMPERED) throw KmpwrightException("The page tampered with the KMPwright runtime slot")
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

/**
 * A query step that narrows a locator. Text values are runtime matcher specs (plain substring,
 * `"quoted"` exact, `/re/flags`); [has], [hasNot], [and] and [or] are JavaScript step arrays of
 * another locator.
 */
internal data class FilterOptions(
    val hasText: String? = null,
    val hasNotText: String? = null,
    val has: String? = null,
    val hasNot: String? = null,
    val and: String? = null,
    val or: String? = null,
    val nth: Int? = null
)

/**
 * Splits `Control+Shift+A` into the main key and canonical modifier names. `ControlOrMeta` resolves to
 * `Meta` on Apple platforms and `Control` elsewhere (Playwright semantics).
 */
internal fun parseKeyCombination(spec: String): Pair<String, Set<String>> {
    if (spec.isEmpty()) throw KmpwrightException("Key must not be empty")
    if (spec.length <= 1 || !spec.contains('+')) return canonicalModifierKey(spec) to emptySet()
    val trailingPlus = spec.endsWith("++")
    val parts = (if (trailingPlus) spec.dropLast(2) else spec).split('+').filter { it.isNotEmpty() }
    val main = if (trailingPlus) "+" else canonicalModifierKey(parts.lastOrNull() ?: spec)
    val modifierParts = if (trailingPlus) parts else parts.dropLast(1)
    val modifiers = modifierParts.map { canonicalModifierKey(it) }
    modifiers.firstOrNull { !it.isModifierKey() }?.let { throw KmpwrightException("Unknown modifier '$it' in key '$spec'") }
    return main to modifiers.toSet()
}

/** Splits a `>>` selector chain into its trimmed, non-empty parts. */
internal fun splitSelectorChain(selector: String): List<String> =
    if (selector.contains(">>")) selector.split(">>").map { it.trim() }.filter { it.isNotEmpty() } else listOf(selector)

/** JavaScript string literals of a selector chain's steps; snapshot refs (`@e3`, `e3`, `ref=e3`) become `ref=@e3`. */
internal fun selectorStepsOf(selector: String): List<String> =
    splitSelectorChain(selector).map { jsStringLiteral(parseRef(it)?.let { ref -> "ref=$ref" } ?: it) }

/** Runtime arguments for a synthetic key press of [key] (a key or combination such as `Control+A`). */
internal fun pressArgsJson(key: String, heldModifiers: Set<String> = emptySet()): JsonObject {
    val (mainKey, comboModifiers) = parseKeyCombination(key)
    val modifiers = heldModifiers + comboModifiers
    return buildJsonObject {
        put("key", keyDescriptorJson(mainKey, modifiers))
        putJsonObject("modifiers") {
            put("ctrl", KeyboardModifiers.CONTROL in modifiers)
            put("shift", KeyboardModifiers.SHIFT in modifiers)
            put("alt", KeyboardModifiers.ALT in modifiers)
            put("meta", KeyboardModifiers.META in modifiers)
        }
        // Modifiers already held down (keyboard.down) are not pressed or released again.
        put("held", buildJsonArray { heldModifiers.forEach { add(JsonPrimitive(it)) } })
    }
}

internal fun keyDescriptorJson(key: String, modifiers: Set<String>): JsonObject {
    val descriptor = describeSyntheticKeyPress(key, modifiers)
    val text = descriptor.text
    return buildJsonObject {
        put("key", descriptor.key)
        put("code", descriptor.code)
        put("keyCode", descriptor.keyCode)
        put("charCode", text?.firstOrNull()?.code ?: if (descriptor.key == "Enter") 13 else 0)
        if (text != null) put("text", text) else put("text", JsonNull)
    }
}

/**
 * Same-origin frames execute selector helpers against the frame document. `>>` in [selector] separates
 * nested frames (`"#outer >> #inner"`). With [scopeSteps] (a `Locator.frameLocator` call) the first
 * frame is looked up inside that locator's elements.
 */
internal class BaseFrameLocator(
    private val controller: WebViewLocatorController,
    private val selector: String,
    private val config: KmpwrightConfig,
    private val scopeSteps: String? = null
) : FrameLocator {

    internal var policyChecker: (suspend (ActionType) -> Unit)? = null
    internal var lifetime: kotlinx.coroutines.Deferred<Unit>? = null

    private fun frameSetupJs(): String {
        val selectors = selector.split(">>").map(String::trim).filter(String::isNotEmpty)
        return buildString {
            append("var frame = null; var frameDocument = document; var frameWindow = window;\n")
            selectors.forEachIndexed { index, frameSelector ->
                if (index == 0 && scopeSteps != null) {
                    // Scoped lookup: resolve the owner with the page runtime inside the parent locator.
                    append("var __H = ${PageHelpers.runtimeExpression};\n")
                    append("if (!__H) { ${PageHelpers.installScript()}; __H = ${PageHelpers.runtimeExpression}; }\n")
                    append("if (!__H) return '__kmpwright_frame_error__:KMPwright page runtime is unavailable';\n")
                    append("var frameMatches = __H.resolveAll(${scopeSteps.removeSuffix("]")}${if (scopeSteps == "[]") "" else ","}${jsStringLiteral(frameSelector)}]);\n")
                } else {
                    append("var frameMatches = frameDocument.querySelectorAll(${jsStringLiteral(frameSelector)});\n")
                }
                append("if (${config.strictSelectors} && frameMatches.length > 1) return '__kmpwright_frame_error__:Strict frame selector matched multiple elements';\n")
                append("frame = frameMatches[0] || null;\n")
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
        override val supportsPromiseEvaluation get() = controller.supportsPromiseEvaluation
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
        object : BaseLocator(frameController, selector, config, options) {
            init { policyChecker = this@BaseFrameLocator.policyChecker; lifetime = this@BaseFrameLocator.lifetime }
            override fun createLocator(newSelector: String, options: FilterOptions?): Locator =
                this@BaseFrameLocator.createLocator(newSelector, options)
            override suspend fun evaluateJavaScriptAsync(script: String): String? =
                frameController.evaluateJavaScriptAsync(script)
        }

    override fun frameLocator(selector: String): FrameLocator =
        BaseFrameLocator(controller, listOf(this.selector, selector).filter(String::isNotEmpty).joinToString(" >> "), config, scopeSteps)
            .also { it.policyChecker = policyChecker; it.lifetime = lifetime }

    private suspend fun inspectFrame(body: String): String? = checkedResult(controller.evaluateJavaScriptAsync("""
        (function() { ${frameSetupJs()} $body })()
    """.trimIndent()))

    override suspend fun name(): String? = inspectFrame("return frame ? frame.getAttribute('name') || frame.id || null : null;")
    override suspend fun url(): String? = inspectFrame("return frame ? frameDocument.location.href : document.location.href;")
    override suspend fun boundingBox(): Rectangle? {
        if (!config.allowDomExtraction) throw KmpwrightException("DOM extraction is disabled by KmpwrightConfig.allowDomExtraction")
        val value = inspectFrame("if (!frame) return null; var r = frame.getBoundingClientRect(); return JSON.stringify({x:r.x,y:r.y,width:r.width,height:r.height});") ?: return null
        val box = Json.decodeFromString<BaseLocator.BoundingBoxJson>(value)
        return Rectangle(box.x, box.y, box.width, box.height)
    }
}
