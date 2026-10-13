@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.bernaferrari.kmpwright.core

import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import platform.CoreGraphics.CGRectMake
import platform.WebKit.WKFrameInfo
import platform.WebKit.WKNavigation
import platform.WebKit.WKNavigationAction
import platform.WebKit.WKNavigationActionPolicy
import platform.WebKit.WKNavigationDelegateProtocol
import platform.WebKit.WKUIDelegateProtocol
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.darwin.NSObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Host delegates installed on a WKWebView before KMPwright attaches keep receiving WebKit callbacks
 * (the iOS counterpart of Android's forwarding clients), while KMPwright's policy denials and
 * automation dialog handlers take precedence. Needs an app host; run through
 * `scripts/run-ios-webkit-tests.sh`.
 */
class IOSHostDelegateDocumentTest {

    /** A host app's delegate: records callbacks and cancels navigations to `host-cancel` URLs. */
    private class HostDelegate : NSObject(), WKNavigationDelegateProtocol, WKUIDelegateProtocol {
        val finished = mutableListOf<String>()
        val committed = mutableListOf<String>()
        val decided = mutableListOf<String>()
        val alerts = mutableListOf<String>()

        override fun webView(
            webView: WKWebView,
            decidePolicyForNavigationAction: WKNavigationAction,
            decisionHandler: (WKNavigationActionPolicy) -> Unit
        ) {
            val url = decidePolicyForNavigationAction.request.URL?.absoluteString.orEmpty()
            decided += url
            decisionHandler(
                if ("host-cancel" in url) WKNavigationActionPolicy.WKNavigationActionPolicyCancel
                else WKNavigationActionPolicy.WKNavigationActionPolicyAllow
            )
        }

        @ObjCSignatureOverride
        override fun webView(webView: WKWebView, didFinishNavigation: WKNavigation?) {
            finished += webView.URL?.absoluteString.orEmpty()
        }

        // Not implemented by KMPwright itself: reaches the host through message forwarding.
        @ObjCSignatureOverride
        override fun webView(webView: WKWebView, didCommitNavigation: WKNavigation?) {
            committed += webView.URL?.absoluteString.orEmpty()
        }

        override fun webView(
            webView: WKWebView,
            runJavaScriptAlertPanelWithMessage: String,
            initiatedByFrame: WKFrameInfo,
            completionHandler: () -> Unit
        ) {
            alerts += runJavaScriptAlertPanelWithMessage
            completionHandler()
        }
    }

    private suspend fun waitUntil(description: String, condition: () -> Boolean) {
        withTimeout(5_000) { while (!condition()) delay(20) }
        assertTrue(condition(), description)
    }

    @Test
    fun host_delegates_receive_callbacks_and_dialogs_unless_automation_handles_them() = runWebKitTest(30_000) {
        requireWebKitDocuments()
        val webView = WKWebView(CGRectMake(0.0, 0.0, 390.0, 844.0), WKWebViewConfiguration())
        val host = HostDelegate()
        webView.navigationDelegate = host
        webView.UIDelegate = host
        val page = IOSPage(IOSWebViewController(existingWebView = webView))
        try {
            assertFalse(webView.navigationDelegate === host, "KMPwright installs its own delegate while attached")
            page.setContent("<title>host</title><p>host delegates</p>")
            waitUntil("host saw didFinish: ${host.finished}") { host.finished.isNotEmpty() }
            assertTrue(host.committed.isNotEmpty(), "host saw didCommit through forwarding")
            assertTrue(host.decided.isNotEmpty(), "host decided the allowed navigation")

            // No automation dialog handler: the host's panel runs and page events still observe it.
            val observed = mutableListOf<String>()
            val events = CoroutineScope(Dispatchers.Main).launch(start = CoroutineStart.UNDISPATCHED) {
                page.events.dialog.collect { observed += it.message }
            }
            try {
                page.evaluate("setTimeout(function() { alert('to host'); window.hostAlertDone = true; }, 0); 1")
                waitUntil("host saw the alert: ${host.alerts}") { host.alerts == listOf("to host") }
                withTimeout(5_000) { while (page.evaluate("String(window.hostAlertDone)").value != "true") delay(20) }
                waitUntil("page.events.dialog observed the host-handled alert: $observed") { "to host" in observed }
            } finally { events.cancel() }

            // An automation handler takes precedence over the host.
            val handled = mutableListOf<String>()
            val handler = page.onDialog { event -> handled += event.message; DialogResponse.Accept }
            try {
                page.evaluate("setTimeout(function() { alert('to automation'); window.automationAlertDone = true; }, 0); 1")
                withTimeout(5_000) { while (page.evaluate("String(window.automationAlertDone)").value != "true") delay(20) }
                assertEquals(listOf("to automation"), handled)
                assertEquals(listOf("to host"), host.alerts, "the host must not see an alert automation answered")
            } finally { handler.close() }
        } finally {
            page.close()
        }
        assertSame(host, webView.navigationDelegate, "closing restores the host navigation delegate")
        assertSame(host, webView.UIDelegate, "closing restores the host UI delegate")
    }

    @Test
    fun host_cancellations_are_respected_and_kmpwright_denials_win_over_host_allows() = runWebKitTest(30_000) {
        requireWebKitDocuments()
        val webView = WKWebView(CGRectMake(0.0, 0.0, 390.0, 844.0), WKWebViewConfiguration())
        val host = HostDelegate()
        webView.navigationDelegate = host
        val page = IOSPage(IOSWebViewController(existingWebView = webView))
        try {
            page.setContent("<title>start</title><p>start</p>")
            val startUrl = page.evaluate("location.href").value

            val cancelled = page.goto("https://fixture.invalid/host-cancel", timeoutMs = 10_000)
            assertFalse(cancelled.ok, "a navigation the host cancels must fail: $cancelled")
            assertTrue(cancelled.error.orEmpty().contains("host navigation delegate"), "reported as a host cancellation: $cancelled")
            assertTrue(host.decided.any { "host-cancel" in it }, "the host decided the navigation")
            assertEquals(startUrl, page.evaluate("location.href").value)
            assertEquals("start", page.evaluate("document.title").value)

            // The host allows everything else, but KMPwright's domain allowlist denies first.
            page.setAllowedDomains(listOf("allowed.invalid"))
            val decidedBefore = host.decided.size
            val blocked = page.goto("https://blocked.invalid/page", timeoutMs = 10_000)
            assertFalse(blocked.ok, "a domain-filter denial must win over the host's allow: $blocked")
            assertTrue(blocked.error.orEmpty().contains("domain filter"), "reported as a domain-filter denial: $blocked")
            assertEquals(decidedBefore, host.decided.size, "a KMPwright denial is final; the host is not consulted")
            assertEquals("start", page.evaluate("document.title").value)
        } finally {
            page.close()
        }
    }
}
