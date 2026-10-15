package com.bernaferrari.kmpwright.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout

/** Base type for every failure reported by KMPwright. Wrapping failures keep their [cause]. */
public open class KmpwrightException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * An operation did not finish within its timeout.
 *
 * KMPwright never lets `kotlinx.coroutines.TimeoutCancellationException` escape a public API:
 * that type is a `CancellationException`, so leaking it would silently cancel the caller's
 * coroutine instead of failing the operation.
 */
public open class KmpwrightTimeoutException(message: String, cause: Throwable? = null) : KmpwrightException(message, cause)

/**
 * The operation needs a capability this backend (or this page's configuration) does not provide,
 * for example PDF on Android or network throttling on a WebView. Every missing-capability failure,
 * including [PageCapabilities.require], is reported with this type; check [Page.capabilities] first
 * to avoid it.
 */
public open class KmpwrightUnsupportedException(message: String, cause: Throwable? = null) : KmpwrightException(message, cause)

/**
 * Runs [block] with a timeout and converts an expiry into [KmpwrightTimeoutException].
 * Cancellation of the caller (including an *outer* timeout) is propagated unchanged.
 */
internal suspend fun <T> withKmpwrightTimeout(
    timeoutMs: Long,
    message: () -> String,
    block: suspend CoroutineScope.() -> T
): T {
    if (timeoutMs <= 0) throw KmpwrightException("Timeout must be positive, was ${timeoutMs}ms")
    return try {
        withTimeout(timeoutMs, block)
    } catch (timeout: TimeoutCancellationException) {
        // An outer cancellation must stay a cancellation; only our own expiry becomes an error.
        currentCoroutineContext().ensureActive()
        throw KmpwrightTimeoutException(message(), timeout)
    }
}

/** Resolves an optional per-call timeout against [KmpwrightConfig.timeouts]. */
internal fun KmpwrightConfig.actionTimeout(requested: Long?): Long = requested ?: timeouts.action
internal fun KmpwrightConfig.navigationTimeout(requested: Long?): Long = requested ?: timeouts.navigation
internal fun KmpwrightConfig.defaultTimeout(requested: Long?): Long = requested ?: timeouts.default

/**
 * Runs an input-device ([Touch], [Mouse], [Keyboard]) or locator action within its per-call timeout;
 * a null request uses [TimeoutConfig.action].
 */
internal suspend fun <T> KmpwrightConfig.inputAction(requested: Long?, action: String, block: suspend CoroutineScope.() -> T): T {
    val timeoutMs = actionTimeout(requested)
    return withKmpwrightTimeout(timeoutMs, { "Timeout ${timeoutMs}ms exceeded while performing $action" }, block)
}

/**
 * A web-first assertion ([expect]) did not hold within its timeout. Like Playwright's `expect` failures
 * this is an [AssertionError], so test runners report it as a failed assertion rather than an error.
 * Waits that are not assertions keep throwing [KmpwrightTimeoutException].
 */
public class KmpwrightAssertionError(message: String, override val cause: Throwable? = null) : AssertionError(message)
