package com.bernaferrari.kmpwright.core

import platform.Foundation.NSLog
import platform.Foundation.NSThread
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_sync

/**
 * WebKit objects (WKWebView, WKHTTPCookieStore, WKWebsiteDataStore) must only
 * be touched on the main thread. Synchronous controller APIs run their body
 * inline when already on main and otherwise hop to the main queue and wait.
 *
 * Callers must not block the main thread while waiting for a background
 * caller of these APIs (for example `runBlocking` on main around work that
 * itself runs on a background dispatcher); suspend APIs avoid that by using
 * `withContext(Dispatchers.Main)` instead.
 */
internal fun <T> onMainThread(block: () -> T): T {
    if (NSThread.isMainThread) return block()
    var outcome: Result<T>? = null
    dispatch_sync(dispatch_get_main_queue()) { outcome = runCatching(block) }
    return outcome!!.getOrThrow()
}

/**
 * Reports a failure that has no caller to propagate to. The message is passed
 * as the format string with `%` escaped: Kotlin/Native cannot safely forward
 * a Kotlin String through NSLog's C varargs.
 */
internal fun iosLog(message: String) {
    NSLog("[kmpwright] " + message.replace("%", "%%"))
}
