@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.bernaferrari.kmpwright.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSDate
import platform.Foundation.NSRunLoop
import platform.Foundation.NSThread
import platform.Foundation.NSURL
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.Foundation.runUntilDate
import platform.Foundation.timeIntervalSinceNow
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import kotlin.test.assertTrue

/**
 * Runs [block] on the main dispatcher while pumping the main run loop, so
 * WebKit callbacks and `Dispatchers.Main` continuations can be delivered.
 */
internal fun runWebKitTest(timeoutMs: Long = 20_000, block: suspend () -> Unit) {
    assertTrue(NSThread.isMainThread, "WebKit contracts must run on the simulator's main thread")
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val completion = CompletableDeferred<Unit>()
    scope.launch(start = CoroutineStart.UNDISPATCHED) {
        try { block(); completion.complete(Unit) }
        catch (error: Throwable) { completion.completeExceptionally(error) }
    }
    val started = kotlin.time.TimeSource.Monotonic.markNow()
    try {
        while (!completion.isCompleted && started.elapsedNow().inWholeMilliseconds < timeoutMs) {
            pumpMainRunLoop(0.01)
        }
        assertTrue(completion.isCompleted, "WebKit contract timed out")
        runBlocking { completion.await() }
    } finally { scope.cancel() }
}

internal fun pumpMainRunLoop(seconds: Double) {
    NSRunLoop.mainRunLoop.runUntilDate(NSDate.dateWithTimeIntervalSinceNow(seconds))
}

/**
 * WKWebView only executes documents when the test binary runs inside an app
 * bundle (it needs a WebContent process). `./gradlew iosSimTest` spawns a bare
 * executable, so document-level contracts live in classes named `*DocumentTest`,
 * which that task excludes; `scripts/run-ios-webkit-tests.sh` runs them by
 * hosting the test binary in an app.
 */
internal val webKitDocumentsAvailable: Boolean by lazy {
    val view = WKWebView(CGRectMake(0.0, 0.0, 10.0, 10.0), WKWebViewConfiguration())
    var finished = false
    view.loadHTMLString("<p>probe</p>", baseURL = NSURL.URLWithString("https://probe.invalid/"))
    view.evaluateJavaScript("1") { result, _ -> finished = result != null }
    val deadline = NSDate.dateWithTimeIntervalSinceNow(5.0)
    while (!finished && deadline.timeIntervalSinceNow > 0) pumpMainRunLoop(0.02)
    finished
}

/** Fails (never silently passes) when WKWebView cannot execute documents. */
internal fun requireWebKitDocuments() {
    assertTrue(webKitDocumentsAvailable,
        "WKWebView documents need an app host (no WebContent process); run scripts/run-ios-webkit-tests.sh")
}
