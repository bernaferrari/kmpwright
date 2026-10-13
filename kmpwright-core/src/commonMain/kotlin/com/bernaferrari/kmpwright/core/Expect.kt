package com.bernaferrari.kmpwright.core

import kotlinx.coroutines.delay
import kotlin.time.TimeSource
import kotlin.coroutines.cancellation.CancellationException

/**
 * Web-first assertions for [locator], like `expect(locator)` in `@playwright/test`. Each assertion
 * re-reads the element until it holds or the timeout expires.
 *
 * ```
 * expect(page.getByText("Saved")).toBeVisible()
 * expect(page.locator("#count")).not.toHaveText("0")
 * ```
 */
public fun expect(locator: Locator): LocatorAssertions =
    LocatorAssertions(locator, (locator as? BaseLocator)?.config?.timeouts?.waitForSelector ?: TimeoutConfig().waitForSelector, false)

/** Web-first assertions for [page], like `expect(page)` in `@playwright/test`. */
public fun expect(page: Page): PageAssertions =
    PageAssertions(page, (page as? BasePage)?.pageConfig?.timeouts?.waitForSelector ?: TimeoutConfig().waitForSelector, false)

/**
 * Assertions returned by [expect] for a [Locator] (Playwright-Java `LocatorAssertions`). A null
 * `timeoutMs` uses [TimeoutConfig.waitForSelector]. Every assertion polls in a single loop (no per-attempt
 * waiting) and fails with [KmpwrightAssertionError]; text and emptiness assertions never pass on a
 * missing element.
 */
public class LocatorAssertions internal constructor(
    private val locator: Locator,
    private val defaultTimeoutMs: Long,
    private val negated: Boolean
) {
    /** The same assertions with the expectation inverted. */
    public val not: LocatorAssertions get() = LocatorAssertions(locator, defaultTimeoutMs, !negated)

    private suspend fun check(timeoutMs: Long?, expectation: String, condition: suspend () -> Boolean) =
        retryUntil(timeoutMs ?: defaultTimeoutMs, "Expected $locator", negated, expectation, condition)

    /** The element is attached and visible. */
    @Throws(KmpwrightAssertionError::class, KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun toBeVisible(timeoutMs: Long? = null): Unit = check(timeoutMs, "to be visible") { locator.isVisible() }

    /** The element is missing or not visible. */
    @Throws(KmpwrightAssertionError::class, KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun toBeHidden(timeoutMs: Long? = null): Unit = check(timeoutMs, "to be hidden") { locator.isHidden() }

    /** At least one element matches. */
    @Throws(KmpwrightAssertionError::class, KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun toBeAttached(timeoutMs: Long? = null): Unit = check(timeoutMs, "to be attached") { locator.count() > 0 }

    /** The element is enabled. */
    @Throws(KmpwrightAssertionError::class, KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun toBeEnabled(timeoutMs: Long? = null): Unit = check(timeoutMs, "to be enabled") { locator.isEnabled() }

    /** The element is disabled. */
    @Throws(KmpwrightAssertionError::class, KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun toBeDisabled(timeoutMs: Long? = null): Unit = check(timeoutMs, "to be disabled") { locator.isDisabled() }

    /** The checkbox or radio button is checked. */
    @Throws(KmpwrightAssertionError::class, KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun toBeChecked(timeoutMs: Long? = null): Unit = check(timeoutMs, "to be checked") { locator.isChecked() }

    /** The element has keyboard focus. */
    @Throws(KmpwrightAssertionError::class, KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun toBeFocused(timeoutMs: Long? = null): Unit = check(timeoutMs, "to be focused") { locator.isFocused() }

    /** The element is editable. */
    @Throws(KmpwrightAssertionError::class, KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun toBeEditable(timeoutMs: Long? = null): Unit = check(timeoutMs, "to be editable") { locator.isEditable() }

    /** An editable element has an empty value, or any other element has no text. A missing element is not empty. */
    @Throws(KmpwrightAssertionError::class, KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun toBeEmpty(timeoutMs: Long? = null): Unit = check(timeoutMs, "to be empty") {
        queryElement("return (('value' in el && el.tagName !== 'BUTTON') ? el.value === '' : (el.textContent || '').trim() === '') ? 'true' : 'false';") == "true"
    }

    /** The element's `textContent` (or `innerText`), or null when nothing matches (no waiting). */
    private suspend fun currentText(useInnerText: Boolean): String? =
        if (useInnerText) queryElement("return el.innerText == null ? String(el.textContent || '') : String(el.innerText);", Read.InnerText)
        else queryElement("return String(el.textContent || '');", Read.TextContent)

    private enum class Read { TextContent, InnerText, Value, Attribute, Other }

    /**
     * Evaluates [body] (with `el` bound) once, without waiting; null when nothing matches. Locators of
     * other implementations fall back to [Locator.count] plus the matching [Locator] read ([read]).
     */
    private suspend fun queryElement(body: String, read: Read = Read.Other, attribute: String? = null): String? {
        (locator as? BaseLocator)?.let { return it.queryElement(body) }
        if (locator.count() == 0) return null
        return when (read) {
            Read.TextContent -> locator.textContent().orEmpty()
            Read.InnerText -> locator.innerText()
            Read.Value -> locator.inputValue()
            Read.Attribute -> locator.getAttribute(attribute!!)
            Read.Other -> null
        }
    }

    private suspend fun currentValue(): String? {
        val value = queryElement(BaseLocator.INPUT_VALUE_JS, Read.Value)
        if (value != null && value.startsWith("__kmpwright_not_an_input__")) {
            throw KmpwrightException("Not an <input>, <textarea> or <select> element: $locator")
        }
        return value
    }

    private suspend fun currentAttribute(name: String): String? =
        queryElement("return el.getAttribute(${jsStringLiteral(name)});", Read.Attribute, name)

    /** The `class` attribute ("" when absent), or null when nothing matches. */
    private suspend fun currentClass(): String? =
        queryElement("return String(el.getAttribute('class') || '');", Read.Attribute, "class")?.let { it }
            ?: if (locator !is BaseLocator && locator.count() > 0) "" else null

    private suspend fun hasAttribute(name: String): Boolean =
        queryElement("return el.hasAttribute(${jsStringLiteral(name)}) ? 'true' : 'false';", Read.Attribute, name)
            .let { if (locator is BaseLocator) it == "true" else it != null }

    /**
     * Whitespace-normalised full-text match against `textContent` (Playwright's default; [useInnerText]
     * uses `innerText`). A missing element never matches.
     */
    @Throws(KmpwrightAssertionError::class, KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun toHaveText(text: String, timeoutMs: Long? = null, useInnerText: Boolean = false) {
        val expected = normalizeWhitespace(text)
        check(timeoutMs, "to have text \"$text\"") { currentText(useInnerText)?.let { normalizeWhitespace(it) == expected } == true }
    }

    /** Regular-expression search in the whitespace-normalised `textContent` (or `innerText`); a missing element never matches. */
    @Throws(KmpwrightAssertionError::class, KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun toHaveText(text: Regex, timeoutMs: Long? = null, useInnerText: Boolean = false): Unit =
        check(timeoutMs, "to have text matching /${text.pattern}/") { currentText(useInnerText)?.let { text.containsMatchIn(normalizeWhitespace(it)) } == true }

    /** Whitespace-normalised substring match against `textContent` (or `innerText`); a missing element never matches. */
    @Throws(KmpwrightAssertionError::class, KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun toContainText(text: String, timeoutMs: Long? = null, useInnerText: Boolean = false) {
        val expected = normalizeWhitespace(text)
        check(timeoutMs, "to contain text \"$text\"") { currentText(useInnerText)?.let { normalizeWhitespace(it).contains(expected) } == true }
    }

    /** Regular-expression search in the whitespace-normalised `textContent` (or `innerText`); a missing element never matches. */
    @Throws(KmpwrightAssertionError::class, KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun toContainText(text: Regex, timeoutMs: Long? = null, useInnerText: Boolean = false): Unit =
        check(timeoutMs, "to contain text matching /${text.pattern}/") { currentText(useInnerText)?.let { text.containsMatchIn(normalizeWhitespace(it)) } == true }

    /** The input value equals [value]; a missing element never matches. */
    @Throws(KmpwrightAssertionError::class, KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun toHaveValue(value: String, timeoutMs: Long? = null): Unit =
        check(timeoutMs, "to have value \"$value\"") { currentValue() == value }

    /** The input value matches [value]; a missing element never matches. */
    @Throws(KmpwrightAssertionError::class, KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun toHaveValue(value: Regex, timeoutMs: Long? = null): Unit =
        check(timeoutMs, "to have value matching /${value.pattern}/") { currentValue()?.let(value::containsMatchIn) == true }

    /** The element has attribute [name]. */
    @Throws(KmpwrightAssertionError::class, KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun toHaveAttribute(name: String, timeoutMs: Long? = null): Unit =
        check(timeoutMs, "to have attribute $name") { hasAttribute(name) }

    /** Attribute [name] equals [value]. */
    @Throws(KmpwrightAssertionError::class, KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun toHaveAttribute(name: String, value: String, timeoutMs: Long? = null): Unit =
        check(timeoutMs, "to have attribute $name=\"$value\"") { currentAttribute(name) == value }

    /** Attribute [name] matches [value]. */
    @Throws(KmpwrightAssertionError::class, KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun toHaveAttribute(name: String, value: Regex, timeoutMs: Long? = null): Unit =
        check(timeoutMs, "to have attribute $name matching /${value.pattern}/") { currentAttribute(name)?.let(value::containsMatchIn) == true }

    /** The whitespace-normalised `class` attribute equals [className]; a missing element never matches. */
    @Throws(KmpwrightAssertionError::class, KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun toHaveClass(className: String, timeoutMs: Long? = null) {
        val expected = normalizeWhitespace(className)
        check(timeoutMs, "to have class \"$className\"") { currentClass()?.let { normalizeWhitespace(it) == expected } == true }
    }

    /** The `class` attribute matches [className]; a missing element never matches. */
    @Throws(KmpwrightAssertionError::class, KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun toHaveClass(className: Regex, timeoutMs: Long? = null): Unit =
        check(timeoutMs, "to have class matching /${className.pattern}/") { currentClass()?.let(className::containsMatchIn) == true }

    /** Exactly [count] elements match. */
    @Throws(KmpwrightAssertionError::class, KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun toHaveCount(count: Int, timeoutMs: Long? = null): Unit =
        check(timeoutMs, "to have count $count") { locator.count() == count }
}

/**
 * Assertions returned by [expect] for a [Page] (Playwright-Java `PageAssertions`). A null `timeoutMs`
 * uses [TimeoutConfig.waitForSelector].
 */
public class PageAssertions internal constructor(
    private val page: Page,
    private val defaultTimeoutMs: Long,
    private val negated: Boolean
) {
    /** The same assertions with the expectation inverted. */
    public val not: PageAssertions get() = PageAssertions(page, defaultTimeoutMs, !negated)

    private suspend fun check(timeoutMs: Long?, expectation: String, condition: suspend () -> Boolean) =
        retryUntil(timeoutMs ?: defaultTimeoutMs, "Expected page", negated, expectation, condition)

    private suspend fun currentUrl(): String = (page as? BasePage)?.liveUrl() ?: page.url.value.orEmpty()
    private suspend fun currentTitle(): String = (page as? BasePage)?.liveTitle() ?: page.title.value.orEmpty()

    /**
     * The page URL matches [url] with Playwright's URL-pattern rules (the same as [Page.waitForURL] and
     * [Page.route]): a string without glob characters must equal the URL; `*`, `**` and `{a,b}` are globs.
     */
    @Throws(KmpwrightAssertionError::class, KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun toHaveURL(url: String, timeoutMs: Long? = null): Unit =
        check(timeoutMs, "to have URL \"$url\"") { urlMatches(url, currentUrl()) }

    /** [predicate] accepts the page URL. */
    @Throws(KmpwrightAssertionError::class, KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun toHaveURL(timeoutMs: Long? = null, predicate: (String) -> Boolean): Unit =
        check(timeoutMs, "to have a URL accepted by the predicate") { predicate(currentUrl()) }

    /** The page URL matches [url]. */
    @Throws(KmpwrightAssertionError::class, KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun toHaveURL(url: Regex, timeoutMs: Long? = null): Unit =
        check(timeoutMs, "to have URL matching /${url.pattern}/") { url.containsMatchIn(currentUrl()) }

    /** The whitespace-normalised title equals [title]. */
    @Throws(KmpwrightAssertionError::class, KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun toHaveTitle(title: String, timeoutMs: Long? = null) {
        val expected = normalizeWhitespace(title)
        check(timeoutMs, "to have title \"$title\"") { normalizeWhitespace(currentTitle()) == expected }
    }

    /** The title matches [title]. */
    @Throws(KmpwrightAssertionError::class, KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun toHaveTitle(title: Regex, timeoutMs: Long? = null): Unit =
        check(timeoutMs, "to have title matching /${title.pattern}/") { title.containsMatchIn(currentTitle()) }
}

private val POLL_MS = longArrayOf(20, 50, 100, 100, 250)

private fun normalizeWhitespace(text: String) = text.replace(Regex("\\s+"), " ").trim()

/**
 * Retries [condition] until it equals `!negated`; conditions that throw [KmpwrightException] are retried.
 * @throws KmpwrightAssertionError when the expectation does not hold within [timeoutMs].
 */
private suspend fun retryUntil(timeoutMs: Long, subject: String, negated: Boolean, expectation: String, condition: suspend () -> Boolean) {
    val started = TimeSource.Monotonic.markNow()
    var lastError: Throwable? = null
    var attempt = 0
    while (true) {
        val outcome = try {
            condition().also { lastError = null }
        } catch (error: KmpwrightException) {
            lastError = error
            null
        }
        // A negated assertion holds when the condition is false; a throwing condition holds neither way.
        if (outcome != null && outcome != negated) return
        val elapsed = started.elapsedNow().inWholeMilliseconds
        if (elapsed >= timeoutMs) {
            val detail = lastError?.message?.let { " (last error: $it)" }.orEmpty()
            throw KmpwrightAssertionError(
                "$subject ${if (negated) "not " else ""}$expectation, but it did not happen within ${timeoutMs}ms$detail",
                lastError
            )
        }
        delay(minOf(POLL_MS[minOf(attempt++, POLL_MS.lastIndex)], (timeoutMs - elapsed).coerceAtLeast(1)))
    }
}
