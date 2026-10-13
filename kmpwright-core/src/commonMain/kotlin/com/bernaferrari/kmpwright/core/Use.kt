package com.bernaferrari.kmpwright.core

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * Runs [block] with this browser and then closes it, even when [block] throws or the coroutine is
 * cancelled, like Kotlin's `Closeable.use` and Ktor's `HttpClient.use`:
 *
 * ```kotlin
 * Browser(JvmPlatformContext(), KmpwrightConfig(cdp = CdpConfig(websocketUrl = endpoint))).use { browser ->
 *     browser.newPage().goto("https://example.com")
 * }
 * ```
 *
 * [Browser.close] runs in [NonCancellable], so a cancelled caller still releases every page. A failure
 * while closing is added as suppressed to the failure of [block], or thrown when [block] succeeded.
 */
@Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
public suspend inline fun <T> Browser.use(block: (Browser) -> T): T = useThenClose(block) { it.close() }

/**
 * Runs [block] with this context and then closes it (and its pages), even when [block] throws or the
 * coroutine is cancelled, like Kotlin's `Closeable.use`:
 *
 * ```kotlin
 * browser.newContext().use { context ->
 *     context.newPage().goto("https://example.com")
 * }
 * ```
 *
 * [BrowserContext.close] runs in [NonCancellable]; a failure while closing is added as suppressed to the
 * failure of [block], or thrown when [block] succeeded.
 */
@Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
public suspend inline fun <T> BrowserContext.use(block: (BrowserContext) -> T): T = useThenClose(block) { it.close() }

/**
 * Runs [block] with this page and then closes it, even when [block] throws or the coroutine is
 * cancelled, like Kotlin's `Closeable.use`:
 *
 * ```kotlin
 * browser.newPage().use { page ->
 *     page.goto("https://example.com")
 *     page.screenshot(fullPage = true)
 * }
 * ```
 *
 * [Page.close] runs in [NonCancellable]; a failure while closing is added as suppressed to the failure
 * of [block], or thrown when [block] succeeded.
 */
@Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
public suspend inline fun <T> Page.use(block: (Page) -> T): T = useThenClose(block) { it.close() }

/**
 * Runs [block] with this launched browser and then closes it (process included), even when [block]
 * throws or the coroutine is cancelled, like Kotlin's `Closeable.use` and Ktor's `HttpClient.use`:
 *
 * ```kotlin
 * launchBrowser(executablePath = chrome).use { launched ->
 *     launched.browser.newPage().goto("https://example.com")
 * }
 * ```
 *
 * [LaunchedBrowser.close] runs in [NonCancellable]; a failure while closing is added as suppressed to
 * the failure of [block], or thrown when [block] succeeded.
 */
@Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
public suspend inline fun <T> LaunchedBrowser.use(block: (LaunchedBrowser) -> T): T = useThenClose(block) { it.close() }

/** Runs [block] and always runs [close] afterwards, in [NonCancellable]. */
@PublishedApi
internal suspend inline fun <R, T> R.useThenClose(block: (R) -> T, crossinline close: suspend (R) -> Unit): T {
    var failure: Throwable? = null
    try {
        return block(this)
    } catch (error: Throwable) {
        failure = error
        throw error
    } finally {
        val primary = failure
        withContext(NonCancellable) {
            try {
                close(this@useThenClose)
            } catch (closeError: Throwable) {
                if (primary == null) throw closeError
                primary.addSuppressed(closeError)
            }
        }
    }
}
