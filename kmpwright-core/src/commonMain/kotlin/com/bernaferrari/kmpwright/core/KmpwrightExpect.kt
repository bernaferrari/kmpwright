package com.bernaferrari.kmpwright.core

import kotlinx.coroutines.delay

class KmpwrightExpect(
    private val locator: Locator,
    private val config: KmpwrightConfig
) {
    suspend fun toBeVisible(timeoutMs: Long = config.timeouts.waitForSelector) {
        retryUntil(timeoutMs, "Element to be visible", """
            if (!el) return false;
            var rect = el.getBoundingClientRect();
            return rect.width > 0 && rect.height > 0 && window.getComputedStyle(el).visibility !== 'hidden';
        """) { locator.isVisible() }
    }

    suspend fun toBeHidden(timeoutMs: Long = config.timeouts.waitForSelector) {
        retryUntil(timeoutMs, "Element to be hidden", """
            if (!el) return true;
            var rect = el.getBoundingClientRect();
            return rect.width === 0 || rect.height === 0 || window.getComputedStyle(el).visibility === 'hidden';
        """) { !locator.isVisible() }
    }

    suspend fun toHaveText(text: String, timeoutMs: Long = config.timeouts.waitForSelector) {
        val expected = escapeJsString(text)
        retryUntil(timeoutMs, "Element to have text: $text", """
            return !!el && el.innerText === '$expected';
        """) { locator.innerText() == text }
    }

    suspend fun toContainText(text: String, timeoutMs: Long = config.timeouts.waitForSelector) {
        val expected = escapeJsString(text)
        retryUntil(timeoutMs, "Element to contain text: $text", """
            return !!el && (el.innerText || '').indexOf('$expected') !== -1;
        """) { locator.innerText().contains(text) }
    }

    suspend fun toHaveValue(value: String, timeoutMs: Long = config.timeouts.waitForSelector) {
        val expected = escapeJsString(value)
        retryUntil(timeoutMs, "Element to have value: $value", """
            return !!el && el.value === '$expected';
        """) { locator.inputValue() == value }
    }

    suspend fun toBeEnabled(timeoutMs: Long = config.timeouts.waitForSelector) {
        retryUntil(timeoutMs, "Element to be enabled", "return !!el && !el.disabled;") { locator.isEnabled() }
    }

    suspend fun toBeDisabled(timeoutMs: Long = config.timeouts.waitForSelector) {
        retryUntil(timeoutMs, "Element to be disabled", "return !!el && !!el.disabled;") { !locator.isEnabled() }
    }

    suspend fun toBeChecked(timeoutMs: Long = config.timeouts.waitForSelector) {
        retryUntil(timeoutMs, "Element to be checked", "return !!el && !!el.checked;") { locator.isChecked() }
    }

    suspend fun toBeFocused(timeoutMs: Long = config.timeouts.waitForSelector) {
        retryUntil(timeoutMs, "Element to be focused", "return !!el && el === document.activeElement;") { locator.isFocused() }
    }

    suspend fun toHaveAttribute(name: String, value: String?, timeoutMs: Long = config.timeouts.waitForSelector) {
        val expectedName = escapeJsString(name)
        val condition = if (value == null) {
            "return !!el && !el.hasAttribute('$expectedName');"
        } else {
            "return !!el && el.getAttribute('$expectedName') === '${escapeJsString(value)}';"
        }
        retryUntil(timeoutMs, "Element to have attribute $name=$value", condition) {
            val actual = locator.getAttribute(name)
            if (value == null) actual == null else actual == value
        }
    }

    suspend fun toHaveCount(count: Int, timeoutMs: Long = config.timeouts.waitForSelector) {
        retryUntil(timeoutMs, "Element count to be $count", "return list.length === $count;") { locator.count() == count }
    }

    private suspend fun retryUntil(
        timeoutMs: Long,
        message: String,
        conditionJsBody: String? = null,
        condition: suspend () -> Boolean
    ) {
        val baseLocator = locator as? BaseLocator
        if (baseLocator != null && conditionJsBody != null) {
            val observerResult = baseLocator.waitForExpectation(
                baseLocator.expectationConditionJs(conditionJsBody), timeoutMs
            )
            if (observerResult == true) {
                return
            }
            if (observerResult == false) {
                throw KmpwrightException("Timeout: $message (waited ${timeoutMs}ms)")
            }
        }
        val startTime = currentTimeMillis()
        val checkInterval = 50L

        while (true) {
            if (condition()) {
                return
            }

            val elapsed = currentTimeMillis() - startTime
            if (elapsed >= timeoutMs) {
                throw KmpwrightException("Timeout: $message (waited ${elapsed}ms)")
            }

            delay(checkInterval)
        }
    }
}

class KmpwrightException(message: String) : Exception(message)
