package com.bernaferrari.kmpwright.core

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Deterministic iOS-target contracts. The controller is a small WebView seam:
 * these tests run on the simulator without a network server, while still using
 * IOSPage and IOSLocator (the production iOS backend types).
 */
class IOSBackendContractTest {

    @Test
    fun advertised_capability_levels_cover_every_shared_capability() {
        val capabilities = iosPageCapabilities()
        assertEquals(BackendKind.IOSWebView, capabilities.backend)
        assertEquals(
            mapOf(
                PageCapability.RouteInterception to SupportLevel.Partial,
                PageCapability.TouchInput to SupportLevel.Partial,
                PageCapability.MouseInput to SupportLevel.Partial,
                PageCapability.KeyboardInput to SupportLevel.Partial,
                PageCapability.Pdf to SupportLevel.Supported,
                PageCapability.BrowserContexts to SupportLevel.Partial,
                PageCapability.ServiceWorkers to SupportLevel.Unsupported,
                PageCapability.DomainFiltering to SupportLevel.Partial,
                PageCapability.NetworkEmulation to SupportLevel.Partial,
                PageCapability.HarRecording to SupportLevel.Partial,
                PageCapability.Downloads to SupportLevel.Unsupported,
                PageCapability.FileChooser to SupportLevel.Unsupported,
                PageCapability.Tracing to SupportLevel.Unsupported,
                PageCapability.Profiling to SupportLevel.Unsupported,
                PageCapability.Tabs to SupportLevel.Supported,
                PageCapability.Cdp to SupportLevel.Unsupported,
                PageCapability.InitScripts to SupportLevel.Supported,
                PageCapability.WebMcp to SupportLevel.Unsupported,
                PageCapability.AccessibilityAudit to SupportLevel.Partial,
                PageCapability.WebVitals to SupportLevel.Partial,
                PageCapability.ReactInspection to SupportLevel.Supported,
                PageCapability.HttpRead to SupportLevel.Supported,
                PageCapability.NetworkHistory to SupportLevel.Partial,
                PageCapability.VideoRecording to SupportLevel.Unsupported,
            PageCapability.Screencast to SupportLevel.Unsupported
            ),
            capabilities.supportLevels()
        )
        assertEquals(PageCapability.values().size, capabilities.supportLevels().size)
        assertTrue(capabilities.notes.isNotEmpty())
    }

    @Test
    fun navigation_locator_and_cleanup_follow_page_lifecycle() = runBlocking {
        val controller = RecordingIOSController()
        val page = testPage(controller)

        val result = page.goto("https://fixture.invalid/page")
        assertEquals("https://fixture.invalid/page", result.url)
        assertEquals("https://fixture.invalid/page", page.url.value)

        val locator = page.locator("button.submit")
        assertEquals("fixture text", locator.innerText())
        locator.click()
        assertTrue(controller.evaluations.any { it.contains("el.click()") })
        assertEquals(BackendKind.IOSWebView, page.capabilities().backend)

        page.close()
        assertTrue(controller.disposed)
        assertEquals(1, controller.disposeCount)
        assertEquals(0, controller.requestListenerCount)
    }

    @Test
    fun state_cookie_and_storage_round_trip_isolated_through_page_api() = runBlocking {
        val controller = RecordingIOSController()
        val page = testPage(controller)
        page.goto("https://fixture.invalid/state")
        page.setCookie(Cookie("session", "one", "fixture.invalid"))
        page.setLocalStorage("token", "one")
        page.setSessionStorage("tab", "one")
        page.saveState("fixture")

        controller.cookies.clear()
        controller.localStorage.clear()
        controller.sessionStorage.clear()
        assertTrue(page.loadState("fixture"))
        assertEquals("one", page.getCookie("session")?.value)
        assertEquals("one", page.getLocalStorage("token"))
        assertEquals("one", page.getSessionStorage("tab"))
        assertEquals(listOf("fixture"), page.stateNames())
        page.close()
    }

    @Test
    fun route_registration_and_network_events_preserve_async_contract() = runBlocking {
        val controller = RecordingIOSController()
        val page = testPage(controller)
        var routeCalled = false
        page.route("**/api/**") {
            routeCalled = true
            RouteHandler.Fallthrough
        }
        assertEquals(1, controller.routeHandlerCount)
        page.unroute()
        assertEquals(0, controller.routeHandlerCount)

        val request = async(start = CoroutineStart.UNDISPATCHED) {
            page.waitForRequest("**/api/**", 1_000)
        }
        controller.emitRequest(Request("https://fixture.invalid/api/items", "GET", emptyMap(), null, "r1"))
        assertEquals("https://fixture.invalid/api/items", request.await().url)
        assertFalse(routeCalled)
        page.close()
    }

    @Test
    fun bridge_limits_and_async_mutations_fail_closed_on_ios() = runBlocking {
        val xhr = assertNotNull(parseWebKitNetworkBridgeMessage(
            """{"type":"xhr","id":"r1","url":"https://fixture.invalid/api","headers":{},"nonce":"n"}""",
            expectedNonce = "n"
        ))
        assertEquals("xhr", (xhr as WebKitNetworkBridgeMessage.RequestStarted).kind)
        val fetch = assertNotNull(parseWebKitNetworkBridgeMessage(
            """{"type":"fetch","id":"r2","url":"https://fixture.invalid/api","method":"POST","headers":{},"nonce":"n"}""",
            expectedNonce = "n"
        ))
        assertEquals("fetch", (fetch as WebKitNetworkBridgeMessage.RequestStarted).kind)
        assertEquals(null, parseWebKitNetworkBridgeMessage(
            """{"type":"xhr","id":"r1","url":"https://fixture.invalid/api","headers":{},"nonce":"wrong"}""",
            expectedNonce = "n"
        ))
        val limiter = WebKitNetworkBridgeRateLimiter(1)
        assertTrue(limiter.allow(2))
        assertFalse(limiter.allow(3))

        val policy = MobileHttpRequestPolicy()
        policy.setCredentials("user", "secret", "https://fixture.invalid/login")
        assertEquals("Basic encoded(user:secret)", policy.requestHeaders("https://fixture.invalid/api") { "encoded($it)" }["Authorization"])
        assertTrue(policy.requestHeaders("https://other.invalid/api") { "encoded($it)" }.isEmpty())

        val page = testPage(RecordingIOSController())
        assertFails { page.setExtraHTTPHeaders(mapOf("X-Secret" to "not-exposed")) }
        assertFails { page.setHTTPCredentials("user", "secret") }
        page.close()
    }

    @Test
    fun website_store_selection_never_claims_external_store_ownership() {
        val provided = selectBackingStore(
            embeddedStore = null,
            providedStore = "context-store",
            createStore = { "owned-store" }
        )
        assertEquals("context-store", provided.store)
        assertFalse(provided.owned)

        val owned = selectBackingStore(
            embeddedStore = null,
            providedStore = null,
            createStore = { "owned-store" }
        )
        assertEquals("owned-store", owned.store)
        assertTrue(owned.owned)
    }

    @Test
    fun screenshot_pdf_and_async_close_use_completion_callbacks() = runBlocking {
        val controller = RecordingIOSController()
        val screenshotController = RecordingScreenshotController()
        val page = IOSPage(controller, screenshotControllerFactory = { screenshotController })

        val screenshot = page.screenshot(ScreenshotOptions(fullPage = true))
        assertEquals(320, screenshot.width)
        assertTrue(screenshotController.fullPageCalls == 1)
        assertEquals("pdf", page.pdf().bytes.decodeToString())

        page.close()
        assertTrue(controller.disposed)
    }

    private fun testPage(controller: RecordingIOSController): IOSPage = IOSPage(controller)

    private suspend fun assertFails(block: suspend () -> Unit) {
        try {
            block()
            error("Expected fail-closed mutation")
        } catch (_: KmpwrightException) {
            // expected
        }
    }

    private class RecordingScreenshotController : ScreenshotController {
        var fullPageCalls = 0
        override suspend fun captureViewport(scale: Double) = ScreenshotResult(byteArrayOf(1), 320, 200)
        override suspend fun captureFullPage(scale: Double): ScreenshotResult {
            fullPageCalls += 1
            return ScreenshotResult(byteArrayOf(2), 320, 640)
        }
        override suspend fun captureClip(x: Double, y: Double, width: Double, height: Double, scale: Double) = ScreenshotResult(byteArrayOf(3), width.toInt(), height.toInt())
        override fun getPageDimensions() = PageDimensions(320, 640, 320, 200)
    }

    private class RecordingIOSController : WebViewController {
        var url: String? = null
        var disposed = false
        var disposeCount = 0
        val evaluations = mutableListOf<String>()
        val cookies = mutableListOf<Cookie>()
        val localStorage = linkedMapOf<String, String>()
        val sessionStorage = linkedMapOf<String, String>()
        private var navigationCallback: ((NavigationResult) -> Unit)? = null
        private val requestListeners = mutableListOf<RequestListener>()
        var routeHandlerCount = 0
        val requestListenerCount: Int get() = requestListeners.size

        override fun loadUrl(url: String) {
            this.url = url
            navigationCallback?.invoke(NavigationResult(url, 200, true))
        }
        override fun loadHtml(content: String, baseUrl: String?) {
            url = baseUrl ?: "about:blank"
            navigationCallback?.invoke(NavigationResult(url!!, 200, true))
        }
        override fun evaluateJavaScript(script: String, callback: (String?) -> Unit) {
            evaluations += script
            val result = when {
                script.contains("document.readyState") -> "complete"
                script.contains("return el.textContent") || script.contains("return el.innerText") -> "fixture text"
                script.contains("JSON.stringify(items)") && script.contains("localStorage") -> "{\"token\":\"${localStorage["token"] ?: ""}\"}"
                script.contains("JSON.stringify(items)") && script.contains("sessionStorage") -> "{\"tab\":\"${sessionStorage["tab"] ?: ""}\"}"
                script.contains("__kmpwright_resolveAll") -> "1"
                script.contains("return !el.disabled") -> "true"
                script.contains("return !!el.checked") -> "false"
                script.contains("return el.getAttribute") -> "fixture-attribute"
                script.contains("localStorage.setItem") -> { localStorage["token"] = "one"; "true" }
                script.contains("sessionStorage.setItem") -> { sessionStorage["tab"] = "one"; "true" }
                script.contains("localStorage.getItem") -> "\"${localStorage["token"] ?: ""}\""
                script.contains("sessionStorage.getItem") -> "\"${sessionStorage["tab"] ?: ""}\""
                else -> "true"
            }
            callback(result)
        }
        override fun goBack() {}
        override fun goForward() {}
        override fun reload() {}
        override fun getUrl() = url
        override fun getTitle() = "Fixture"
        override fun takeScreenshot(options: ScreenshotOptions, callback: (ByteArray?, Int, Int) -> Unit) = callback(byteArrayOf(1), 320, 200)
        override fun getViewportSize() = 320 to 200
        override fun setViewportSize(width: Int, height: Int) {}
        override fun setZoomScale(scale: Double) {}
        override fun getZoomScale() = 1.0
        override fun setUserAgent(userAgent: String) {}
        override fun setDeviceScaleFactor(scale: Double) {}
        override fun injectJavaScript(script: String, callback: ((String?) -> Unit)?) = callback?.invoke("true") ?: Unit
        override fun dispose() { disposed = true; disposeCount += 1 }
        override fun setNavigationCallback(callback: ((NavigationResult) -> Unit)?) { navigationCallback = callback }
        override fun generatePdf(options: PdfOptions, callback: (ByteArray?) -> Unit) = callback("pdf".encodeToByteArray())
        override fun addRequestListener(listener: RequestListener) { requestListeners += listener }
        override fun removeRequestListener(listener: RequestListener) { requestListeners -= listener }
        override fun getPendingRequestCount() = 0
        override fun resetRequestCount() {}
        override fun setRequestIdleTimeout(timeoutMs: Long) {}
        override fun addDialogListener(listener: DialogListener) {}
        override fun removeDialogListener(listener: DialogListener) {}
        override fun addRouteHandler(urlPattern: String, handler: suspend (Route) -> RouteHandler) { routeHandlerCount += 1 }
        override fun removeRouteHandler(urlPattern: String) { routeHandlerCount = (routeHandlerCount - 1).coerceAtLeast(0) }
        override fun removeAllRouteHandlers() { routeHandlerCount = 0 }
        override fun fulfillRoute(requestId: String, options: FulfillOptions) {}
        override fun abortRoute(requestId: String, errorCode: String) {}
        override fun continueRoute(requestId: String, options: ContinueOptions) {}
        override fun setCookie(cookie: Cookie) { cookies.removeAll { it.name == cookie.name }; cookies += cookie }
        override fun getCookie(name: String, domain: String, callback: (Cookie?) -> Unit) { callback(cookies.firstOrNull { it.name == name }) }
        override fun getAllCookies(callback: (List<Cookie>) -> Unit) { callback(cookies.toList()) }
        override fun deleteCookie(name: String, domain: String) { cookies.removeAll { it.name == name } }
        override fun deleteAllCookies() { cookies.clear() }
        override fun interceptRequest(url: String, method: String, headers: Map<String, String>, onResult: (RouteAction?) -> Unit) { onResult(RouteAction.Fallthrough) }

        fun emitRequest(request: Request) {
            requestListeners.forEach { it.onRequestStarted(request) }
        }
    }
}
