@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.bernaferrari.kmpwright.core

import kotlinx.coroutines.*
import platform.Foundation.NSDate
import platform.Foundation.NSRunLoop
import platform.Foundation.NSThread
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.Foundation.runUntilDate
import platform.CoreGraphics.CGRectMake
import platform.WebKit.*
import kotlin.test.*

/** The native executable checks SDK ownership. Document execution needs an iOS app host. */
class IOSDocumentStartTest {
    @Test fun registration_removal_and_disposal_preserve_host_scripts() = runWebKitTest {
        val configuration = WKWebViewConfiguration()
        val hostScript = WKUserScript("window.hostScript = 'host';", WKUserScriptInjectionTime.WKUserScriptInjectionTimeAtDocumentStart, false)
        configuration.userContentController.addUserScript(hostScript)
        val view = WKWebView(CGRectMake(0.0, 0.0, 640.0, 480.0), configuration)
        val controller = IOSWebViewController(existingWebView = view)
        try {
            val first = controller.addInitScript("window.early = 'registered';")
            val retained = controller.addInitScript("window.other = 7;")
            val registered = view.configuration.userContentController.userScripts.filterIsInstance<WKUserScript>()
            assertTrue(registered.any { it.source == "window.early = 'registered';" &&
                it.injectionTime == WKUserScriptInjectionTime.WKUserScriptInjectionTimeAtDocumentStart && !it.forMainFrameOnly })
            assertTrue(registered.contains(hostScript))
            controller.removeInitScript(first)
            val remaining = view.configuration.userContentController.userScripts.filterIsInstance<WKUserScript>()
            assertFalse(remaining.any { it.source == "window.early = 'registered';" })
            assertTrue(remaining.any { it.source == "window.other = 7;" })
            assertTrue(remaining.contains(hostScript))
            controller.removeInitScript(retained)
        } finally { controller.dispose() }
        val remaining = view.configuration.userContentController.userScripts.filterIsInstance<WKUserScript>()
        assertEquals(listOf(hostScript), remaining)
    }

    private fun runWebKitTest(block: suspend () -> Unit) {
        assertTrue(NSThread.isMainThread, "WebKit contracts must run on the simulator's main thread")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val completion = CompletableDeferred<Unit>()
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try { block(); completion.complete(Unit) }
            catch (error: Throwable) { completion.completeExceptionally(error) }
        }
        val started = kotlin.time.TimeSource.Monotonic.markNow()
        try {
            while (!completion.isCompleted && started.elapsedNow().inWholeMilliseconds < 20_000) {
                NSRunLoop.mainRunLoop.runUntilDate(NSDate.dateWithTimeIntervalSinceNow(0.01))
            }
            assertTrue(completion.isCompleted, "WebKit contract timed out")
            runBlocking { completion.await() }
        } finally { scope.cancel() }
    }
}
