package com.bernaferrari.kmpwright.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking

/**
 * Blocking test helper that wraps [runBlocking].
 *
 * Usage:
 * ```kotlin
 * @Test fun `my test`() = testBlocking {
 *     page.goto("https://example.com")
 *     page.click("button")
 * }
 * ```
 */
fun testBlocking(block: suspend () -> Unit): Unit = runBlocking {
    try {
        block()
    } catch (e: CancellationException) {
        throw CancellationException("Test coroutine cancelled: ${e.message}", e)
    }
}
