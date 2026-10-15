package com.bernaferrari.kmpwright.core

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PageWaitLifecycleTest {
    private class WaitPage(controller: WebViewController) : BasePage(
        controller, KmpwrightConfig(), dispatcher = Dispatchers.Unconfined
    ) {
        override fun createPlatformLocator(selector: String): BaseLocator = error("Not needed by wait contracts")
        private val bridge = object : CdpInputBridge {
            override val inputState = CdpInputState()
            override suspend fun sendCdpCommand(method: String, params: Map<String, Any?>): CdpResponse = CdpResponse(id = 1)
        }
        override fun createTouch(): Touchscreen = CdpTouch(bridge)
        override fun createMouse(): Mouse = CdpMouse(bridge)
        override fun createKeyboard(): Keyboard = CdpKeyboard(bridge)
    }

    @Test fun `load and URL deadlines include a bridge reply that never arrives`() = runBlocking {
        val controller = object : WebViewController by MockWebViewController() {
            override suspend fun evaluateJavaScriptAsync(script: String): String? = awaitCancellation()
        }
        for (wait in listOf<suspend (Page) -> Unit>(
            { it.waitForLoadState(LoadState.Load, 50) },
            { it.waitForLoadState(LoadState.DOMContentLoaded, 50) },
            { it.waitForURL("**", timeoutMs = 50) }
        )) {
            val page = WaitPage(controller)
            try {
                withTimeout(1_000) { assertFailsWith<KmpwrightTimeoutException> { wait(page) } }
            } finally { page.close() }
        }
    }

    @Test fun `closing the page terminates every event and load waiter`() = runBlocking {
        val waits = listOf<suspend (Page) -> Any?>(
            { it.waitForDownload(timeoutMs = 60_000) },
            { it.waitForFileChooser(timeoutMs = 60_000) },
            { it.waitForDialog(timeoutMs = 60_000) },
            { it.waitForLoadState(LoadState.Load, 60_000) },
            { it.waitForLoadState(LoadState.NetworkIdle, 60_000) },
            { it.waitForRequest("**", 60_000) },
            { it.waitForResponse("**", 60_000) },
            { it.waitForEvent("console", 60_000) }
        )
        for (wait in waits) {
            val controller = MockWebViewController().apply { evalHandler = { "loading" } }
            val page = WaitPage(controller)
            val pending = async(start = CoroutineStart.UNDISPATCHED) { runCatching { wait(page) } }
            page.close()
            val failure = withTimeout(1_000) { pending.await() }.exceptionOrNull()
            assertTrue(failure is KmpwrightException && failure.message.orEmpty().contains("closed"), "$failure")
            val afterClose = assertFailsWith<KmpwrightException> { withTimeout(1_000) { wait(page) } }
            assertTrue(afterClose.message.orEmpty().contains("closed"))
        }
    }

    @Test fun `timing out document preparation never starts a navigation afterward`() = runBlocking {
        var navigations = 0
        val controller = object : WebViewController by MockWebViewController() {
            override suspend fun evaluateJavaScriptAsync(script: String): String? = awaitCancellation()
            override fun loadUrl(url: String) { navigations++ }
        }
        val page = WaitPage(controller)
        try {
            assertFailsWith<KmpwrightTimeoutException> {
                page.goto("https://example.com", waitUntil = LoadState.DOMContentLoaded, timeoutMs = 50)
            }
            kotlin.test.assertEquals(0, navigations, "An expired operation must not issue its pending loadUrl")
        } finally { page.close() }
    }

    @Test fun `navigation and input budgets include policy confirmation`() = runBlocking {
        val page = WaitPage(MockWebViewController())
        page.setActionPolicy(ActionPolicy(confirmActions = setOf(ActionType.Navigate, ActionType.SetContent,
            ActionType.Click, ActionType.Type, ActionType.Press, ActionType.Scroll)))
        page.setPolicyConfirmCallback { _, _ -> awaitCancellation() }
        try {
            val actions = listOf<suspend () -> Unit>(
                { page.goto("https://example.com", timeoutMs = 50) },
                { page.reload(timeoutMs = 50) },
                { page.goBack(timeoutMs = 50) },
                { page.goForward(timeoutMs = 50) },
                { page.setContent("<p>content</p>", timeoutMs = 50) },
                { page.mouse.click(1.0, 1.0, timeoutMs = 50) },
                { page.mouse.wheel(0.0, 10.0, timeoutMs = 50) },
                { page.touchscreen.tap(1.0, 1.0, timeoutMs = 50) },
                { page.keyboard.type("text", timeoutMs = 50) },
                { page.keyboard.press("Enter", timeoutMs = 50) }
            )
            for (action in actions) withTimeout(1_000) { assertFailsWith<KmpwrightTimeoutException> { action() } }
        } finally { page.close() }
    }

    @Test fun `load events are observed even while the first readiness probe is stalled`() = runBlocking {
        val controller = object : WebViewController by MockWebViewController() {
            override suspend fun evaluateJavaScriptAsync(script: String): String? = awaitCancellation()
        }
        val page = WaitPage(controller)
        try {
            val wait = async(start = CoroutineStart.UNDISPATCHED) { page.waitForLoadState(LoadState.Load, 2_000) }
            (page.events as PageEventSinks).loadStateSink.emit(LoadState.Load)
            withTimeout(500) { wait.await() }
        } finally { page.close() }
    }

    @Test fun `a navigation queued behind another one still observes its own budget`() = runBlocking {
        val controller = MockWebViewController().apply { completeNavigationImmediately = false }
        val page = WaitPage(controller)
        try {
            val first = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { page.goto("https://example.com/first", timeoutMs = 60_000) }
            }
            withTimeout(1_000) { assertFailsWith<KmpwrightTimeoutException> { page.goto("https://example.com/second", timeoutMs = 50) } }
            page.close()
            assertTrue(first.await().exceptionOrNull() is KmpwrightException)
        } finally { page.close() }
    }

    @Test fun `response waits distinguish a network failure from a received zero status response`() = runBlocking {
        var registeredListener: RequestListener? = null
        val controller = object : WebViewController by MockWebViewController() {
            override fun addRequestListener(listener: RequestListener) { registeredListener = listener }
        }
        val page = WaitPage(controller)
        try {
            val response = async(start = CoroutineStart.UNDISPATCHED) { page.waitForResponse("**", 2_000) }
            val requests = requireNotNull(registeredListener)
            requests.onRequestFailed(Request("https://example.com/failed"), "refused")
            val received = Response("https://example.com/opaque", 0)
            requests.onResponseReceived(received)
            kotlin.test.assertSame(received, withTimeout(500) { response.await() })
        } finally { page.close() }
    }

    @Test fun `network idle event requires a quiet interval after a failed or finished request`() = runBlocking {
        var requests: RequestListener? = null
        var pending = 0
        val controller = object : WebViewController by MockWebViewController() {
            override fun addRequestListener(listener: RequestListener) { requests = listener }
            override fun getPendingRequestCount(): Int = pending
        }
        val page = WaitPage(controller)
        try {
            val event = async(start = CoroutineStart.UNDISPATCHED) { page.waitForEvent("networkidle", 3_000) }
            val listener = requireNotNull(requests)
            val first = Request("https://example.com/first")
            pending = 1; listener.onRequestStarted(first)
            pending = 0; listener.onRequestFinished(first)
            delay(300)
            assertTrue(!event.isCompleted, "The final request is not an immediate network-idle event")
            val failed = Request("https://example.com/failed")
            pending = 1; listener.onRequestStarted(failed)
            delay(50)
            pending = 0; listener.onRequestFailed(failed, "connection refused")
            delay(300)
            assertTrue(!event.isCompleted, "Failed requests must also reset the quiet interval")
            assertTrue(withTimeout(1_000) { event.await() } == LoadState.NetworkIdle)
        } finally { page.close() }
    }
}
