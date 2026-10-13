package com.bernaferrari.kmpwright.core

import kotlin.coroutines.cancellation.CancellationException

/**
 * A lazy, re-resolving reference to elements matching a selector, like Playwright's `Locator`.
 *
 * Selector engines: CSS (default, pierces open shadow roots), `text=` (case-insensitive substring;
 * `text="Save"` exact; `text=/re/i` regex), `role=button[name="Save"]` (ARIA role with accessible-name
 * and state filters), `label=`, `placeholder=`, `alt=`, `title=`, `testid=`, `xpath=`, snapshot refs
 * (`@e3`, `e3` or `ref=e3`), and `>>` chaining.
 *
 * Actions wait until the element is attached, visible, enabled, stable and hit-testable (fill:
 * editable) and retry until their timeout. `force = true` skips those checks. Options are named
 * parameters (`click(button = MouseButton.Right, force = true)`); a `timeoutMs` of null uses
 * [TimeoutConfig.action].
 */
public interface Locator : ElementFinder {
    /**
     * Clicks the element [clickCount] times with [button]. [delayMs] is the time between `mousedown` and
     * `mouseup` of each click (Playwright `delay`); [position] is relative to the element's top-left
     * corner (null clicks the visible center).
     */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun click(
        button: MouseButton = MouseButton.Left,
        clickCount: Int = 1,
        delayMs: Long = 0,
        position: Position? = null,
        force: Boolean = false,
        timeoutMs: Long? = null
    )

    /** Double-clicks the element; the parameters are those of [click]. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun dblclick(
        button: MouseButton = MouseButton.Left,
        delayMs: Long = 0,
        position: Position? = null,
        force: Boolean = false,
        timeoutMs: Long? = null
    )

    /** Taps the element with a touch sequence; [modifiers] is the CDP modifier bit mask (Alt 1, Control 2, Meta 4, Shift 8; CDP only). */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun tap(force: Boolean = false, modifiers: Int = 0, timeoutMs: Long? = null)

    /** Replaces the value of an input, textarea, select or `[contenteditable]` element. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun fill(value: String, force: Boolean = false, timeoutMs: Long? = null)

    /** Clears an editable element, like `fill("")`. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun clear(force: Boolean = false, timeoutMs: Long? = null)

    /**
     * Focuses the element and presses a key or combination such as `"Enter"`, `"Control+A"` or
     * `"ControlOrMeta+A"` (Meta on Apple platforms, Control elsewhere). Key names follow Playwright's US
     * layout, including code names such as `"Space"`, `"KeyA"` and `"Digit1"`. [delayMs] waits between
     * `keydown` and `keyup`.
     */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun press(key: String, delayMs: Long = 0, timeoutMs: Long? = null)

    /**
     * Types [text] one key at a time (Playwright `pressSequentially`, formerly `type`); [delayMs] waits
     * between key presses.
     */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun pressSequentially(text: String, delayMs: Long = 0, timeoutMs: Long? = null)

    /** Moves the pointer over the element. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun hover(force: Boolean = false, timeoutMs: Long? = null)

    /** Focuses the element. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun focus(timeoutMs: Long? = null)

    /** Removes keyboard focus from the element. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun blur(timeoutMs: Long? = null)

    /** Checks a checkbox or radio button, waiting for it like every other action. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun check(force: Boolean = false, timeoutMs: Long? = null)

    /** Unchecks a checkbox. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun uncheck(force: Boolean = false, timeoutMs: Long? = null)

    /** Checks or unchecks a checkbox or radio button. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun setChecked(checked: Boolean, force: Boolean = false, timeoutMs: Long? = null)

    /** Selects the `<option>` whose value or label is [value]. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun selectOption(value: String, force: Boolean = false, timeoutMs: Long? = null)

    /** Selects every `<option>` whose value or label is in [values]; an empty list deselects all. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun selectOption(values: List<String>, force: Boolean = false, timeoutMs: Long? = null)

    /** Sets the files of an `<input type=file>`; an empty list clears the selection. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun setInputFiles(files: List<String>, force: Boolean = false, timeoutMs: Long? = null)

    /**
     * Drags this element onto [target]. [sourcePosition] and [targetPosition] are relative to the
     * top-left corner of each element (null uses its visible center).
     */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun dragTo(
        target: Locator,
        sourcePosition: Position? = null,
        targetPosition: Position? = null,
        timeoutMs: Long? = null
    )

    /**
     * Scrolls the element into view when it is not already visible in the viewport, aligned by [block]
     * (vertical) and [inline] (horizontal).
     */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun scrollIntoViewIfNeeded(
        block: ScrollPosition = ScrollPosition.Center,
        inline: ScrollPosition = ScrollPosition.Nearest,
        timeoutMs: Long? = null
    )

    /** Dispatches a DOM event of [type] (for example `"click"` or `"input"`) on the element. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun dispatchEvent(type: String, timeoutMs: Long? = null)

    /**
     * Evaluates [expression], a JavaScript function that receives the element (for example
     * `"el => el.value"`), and returns its result like [Page.evaluate]. The function runs synchronously;
     * a returned Promise is not awaited.
     */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun evaluate(expression: String, timeoutMs: Long? = null): JsResult

    /**
     * Highlights the element with an overlay for visual debugging: [color] fills it, [borderColor]
     * outlines it (CSS colors), and it is removed after [durationMs] (0 keeps it until navigation).
     */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun highlight(color: String = "rgba(255, 0, 0, 0.3)", borderColor: String = "red", durationMs: Long = 3000)

    /**
     * Captures a screenshot of the element's bounding box. The parameters are those of
     * [Page.screenshot]; [timeoutMs] null uses [TimeoutConfig.default].
     */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun screenshot(
        omitBackground: Boolean = false,
        quality: Int = 90,
        format: ScreenshotFormat = ScreenshotFormat.Png,
        scale: Double = 1.0,
        timeoutMs: Long? = null
    ): ScreenshotResult

    /** Returns the element's bounding box in CSS pixels, or null when nothing matches. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun boundingBox(): Rectangle?

    /** Waits until the element reaches [state]. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun waitFor(state: SelectorState = SelectorState.Visible, timeoutMs: Long? = null)

    /** The element's `textContent`, or null when nothing matches. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun textContent(): String?

    /** The element's rendered `innerText`. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun innerText(): String

    /** The element's `innerHTML`. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun innerHTML(): String

    /** The value of attribute [name], or null when absent. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun getAttribute(name: String): String?

    /** The current value of an input, textarea or select element. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun inputValue(): String?

    /** The number of matching elements (no waiting). */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun count(): Int

    /** One locator per currently matching element. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun all(): List<Locator>

    /** `textContent` of every matching element. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun allTextContents(): List<String>

    /** `innerText` of every matching element. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun allInnerTexts(): List<String>

    /** Whether the element is visible; false when nothing matches (no waiting). */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun isVisible(): Boolean

    /** Whether the element is hidden or missing (no waiting). */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun isHidden(): Boolean

    /** Whether the element is enabled. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun isEnabled(): Boolean

    /** Whether the element is disabled. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun isDisabled(): Boolean

    /** Whether a checkbox or radio button is checked. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun isChecked(): Boolean

    /** Whether the element is editable (enabled and not read-only). */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun isEditable(): Boolean

    /** Whether the element has keyboard focus. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun isFocused(): Boolean

    /** The first matching element. */
    public fun first(): Locator

    /** The last matching element. */
    public fun last(): Locator

    /** The element at [index] (negative counts from the end). */
    public fun nth(index: Int): Locator

    /** Narrows the matches by text or by descendants, like Playwright's `locator.filter()`. */
    public fun filter(
        hasText: String? = null,
        hasNotText: String? = null,
        has: Locator? = null,
        hasNot: Locator? = null
    ): Locator

    /** Narrows the matches by a text regular expression, like Playwright's `locator.filter({ hasText: /re/ })`. */
    public fun filter(
        hasText: Regex?,
        hasNotText: Regex? = null,
        has: Locator? = null,
        hasNot: Locator? = null
    ): Locator

    /** Elements matching both this locator and [locator]. */
    public fun and(locator: Locator): Locator

    /** Elements matching this locator or [locator], in document order. */
    public fun or(locator: Locator): Locator
}

/**
 * Element finders shared by [Page], [Locator] and [FrameLocator], mirroring Playwright's `locator()` and
 * `getBy*()` methods. Text arguments match case-insensitively as substrings unless `exact = true`.
 */
public interface ElementFinder {
    /** Elements matching [selector] (any selector engine, including snapshot refs such as `@e3`). */
    public fun locator(selector: String): Locator

    /**
     * Elements inside the iframe matched by [selector] (CSS). On a [Locator] the iframe is looked up
     * inside that locator's elements; `>>` separates nested frames (`"#outer >> #inner"`).
     */
    public fun frameLocator(selector: String): FrameLocator

    /** Elements by ARIA role and accessible name, like Playwright's `getByRole`. */
    public fun getByRole(
        role: String,
        name: String? = null,
        exact: Boolean = false,
        checked: Boolean? = null,
        disabled: Boolean? = null,
        expanded: Boolean? = null,
        selected: Boolean? = null,
        pressed: Boolean? = null,
        level: Int? = null,
        includeHidden: Boolean? = null
    ): Locator = locator(GetBy.role(role, name, null, exact, checked, disabled, expanded, selected, pressed, level, includeHidden))

    /** Elements by ARIA role whose accessible name matches [name]. */
    public fun getByRole(
        role: String,
        name: Regex,
        checked: Boolean? = null,
        disabled: Boolean? = null,
        expanded: Boolean? = null,
        selected: Boolean? = null,
        pressed: Boolean? = null,
        level: Int? = null,
        includeHidden: Boolean? = null
    ): Locator = locator(GetBy.role(role, null, name, false, checked, disabled, expanded, selected, pressed, level, includeHidden))

    /** Elements whose text contains [text]. */
    public fun getByText(text: String, exact: Boolean = false): Locator = locator(GetBy.text("text", text, exact))

    /** Elements whose text matches [text]. */
    public fun getByText(text: Regex): Locator = locator(GetBy.text("text", text))

    /** Form controls by their `<label>`, `aria-label` or `aria-labelledby` text. */
    public fun getByLabel(text: String, exact: Boolean = false): Locator = locator(GetBy.text("label", text, exact))

    /** Form controls whose label matches [text]. */
    public fun getByLabel(text: Regex): Locator = locator(GetBy.text("label", text))

    /** Inputs by their `placeholder` attribute. */
    public fun getByPlaceholder(text: String, exact: Boolean = false): Locator = locator(GetBy.text("placeholder", text, exact))

    /** Inputs whose `placeholder` matches [text]. */
    public fun getByPlaceholder(text: Regex): Locator = locator(GetBy.text("placeholder", text))

    /** Images and areas by their `alt` text. */
    public fun getByAltText(text: String, exact: Boolean = false): Locator = locator(GetBy.text("alt", text, exact))

    /** Images and areas whose `alt` text matches [text]. */
    public fun getByAltText(text: Regex): Locator = locator(GetBy.text("alt", text))

    /** Elements by their `title` attribute. */
    public fun getByTitle(text: String, exact: Boolean = false): Locator = locator(GetBy.text("title", text, exact))

    /** Elements whose `title` attribute matches [text]. */
    public fun getByTitle(text: Regex): Locator = locator(GetBy.text("title", text))

    /** Elements whose `data-testid` equals [testId]. */
    public fun getByTestId(testId: String): Locator = locator("testid=$testId")

    /** Elements whose `data-testid` matches [testId]. */
    public fun getByTestId(testId: Regex): Locator = locator(GetBy.text("testid", testId))
}

/** Locates elements inside an iframe, like Playwright's `FrameLocator`. */
public interface FrameLocator : ElementFinder {
    /** The iframe's `name` (or `id`) attribute, or null. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun name(): String?

    /** The URL of the iframe's document, or null. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun url(): String?

    /** The iframe element's bounding box. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun boundingBox(): Rectangle?
}

/** A point in CSS pixels. */
public data class Position(
    val x: Double,
    val y: Double
)

/** Alignment used by [Locator.scrollIntoViewIfNeeded]. */
public enum class ScrollPosition {
    Start,
    Center,
    End,
    Nearest
}

/** States for [Locator.waitFor] and [Page.waitForSelector]. */
public enum class SelectorState {
    Attached,
    Detached,
    Visible,
    Hidden
}
