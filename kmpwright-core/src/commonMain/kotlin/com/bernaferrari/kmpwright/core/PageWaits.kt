package com.bernaferrari.kmpwright.core

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlin.coroutines.cancellation.CancellationException

// Action-scoped waits, the Kotlin form of Playwright's `Promise.all([page.waitForX(), action()])`.
// The wait is subscribed (undispatched) *before* the action runs, so an event triggered by the
// action cannot be missed.
//
//   val nav = page.waitForNavigation { click("text=Next") }
//   val response = page.waitForResponse("**/api/items") { click("#load") }

private suspend fun <T> Page.subscribeThen(wait: suspend () -> T, action: suspend Page.() -> Unit): T = coroutineScope {
    val result = async(start = CoroutineStart.UNDISPATCHED) { wait() }
    try {
        action()
    } catch (error: Throwable) {
        result.cancel()
        throw error
    }
    result.await()
}

/**
 * Runs [action] and waits for the navigation it triggers.
 * @throws KmpwrightTimeoutException when no matching navigation happens in time.
 */
@Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
public suspend fun Page.waitForNavigation(
    options: WaitForNavigationOptions = WaitForNavigationOptions(),
    action: suspend Page.() -> Unit
): NavigationResult = subscribeThen({
    waitForNavigation(options) ?: throw KmpwrightTimeoutException(
        "No navigation${options.url?.let { " matching $it" } ?: ""} happened within the timeout"
    )
}, action)

/** Runs [action] and returns the first request matching [url] (Playwright URL glob, see [Page.route]). */
@Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
public suspend fun Page.waitForRequest(url: String, timeoutMs: Long? = null, action: suspend Page.() -> Unit): Request =
    subscribeThen({ waitForRequest(url, timeoutMs) }, action)

/** Runs [action] and returns the first request matching [url]. */
@Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
public suspend fun Page.waitForRequest(url: Regex, timeoutMs: Long? = null, action: suspend Page.() -> Unit): Request =
    subscribeThen({ waitForRequest(url, timeoutMs) }, action)

/** Runs [action] and returns the first response matching [url] (Playwright URL glob, see [Page.route]). */
@Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
public suspend fun Page.waitForResponse(url: String, timeoutMs: Long? = null, action: suspend Page.() -> Unit): Response =
    subscribeThen({ waitForResponse(url, timeoutMs) }, action)

/** Runs [action] and returns the first response matching [url]. */
@Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
public suspend fun Page.waitForResponse(url: Regex, timeoutMs: Long? = null, action: suspend Page.() -> Unit): Response =
    subscribeThen({ waitForResponse(url, timeoutMs) }, action)

/** Runs [action] and waits until the page URL matches [url] (Playwright URL glob, see [Page.route]). */
@Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
public suspend fun Page.waitForURL(url: String, options: WaitForURLOptions = WaitForURLOptions(), action: suspend Page.() -> Unit): Unit =
    subscribeThen({ waitForURL(url, options) }, action)

/** Runs [action] and waits until the page URL matches [url]. */
@Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
public suspend fun Page.waitForURL(url: Regex, options: WaitForURLOptions = WaitForURLOptions(), action: suspend Page.() -> Unit): Unit =
    subscribeThen({ waitForURL(url, options) }, action)

/** Runs [action] and waits until [predicate] accepts the page URL. */
@Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
public suspend fun Page.waitForURL(
    predicate: (String) -> Boolean,
    options: WaitForURLOptions = WaitForURLOptions(),
    action: suspend Page.() -> Unit
): Unit = subscribeThen({ waitForURL(options, predicate) }, action)

/** Runs [action] and returns the download it starts. */
@Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
public suspend fun Page.waitForDownload(url: String? = null, timeoutMs: Long? = null, action: suspend Page.() -> Unit): Download =
    subscribeThen({ waitForDownload(url, timeoutMs) }, action)

/** Runs [action] and returns the file chooser it opens (requires file chooser interception on CDP). */
@Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
public suspend fun Page.waitForFileChooser(timeoutMs: Long? = null, action: suspend Page.() -> Unit): FileChooser =
    subscribeThen({ waitForFileChooser(timeoutMs) }, action)

/**
 * Runs [action] and returns the dialog it opens. While waiting this counts as a dialog listener
 * (see [waitForDialog]), so the dialog reaches automation on every backend. It is answered by the
 * newest [Page.onDialog] handler (dismissed when there is none); register one before calling this
 * to accept it.
 */
@Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
public suspend fun Page.waitForDialog(timeoutMs: Long? = null, action: suspend Page.() -> Unit): DialogEvent =
    subscribeThen({ waitForDialog(timeoutMs) }, action)
