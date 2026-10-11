package com.bernaferrari.kmpwright.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GeolocationTest {

    private lateinit var controller: MockEvalController
    private lateinit var page: TestPage

    @Before
    fun setup() {
        controller = MockEvalController()
        page = TestPage(controller)
    }

    @Test
    fun `emulateGeolocation overrides getCurrentPosition`() {
        runBlocking {
            page.emulateGeolocation(40.7128, -74.0060)
            val script = controller.executedScripts.last()
            assertTrue(script.contains("getCurrentPosition = function"))
            assertTrue(script.contains("latitude: 40.7128"))
            assertTrue(script.contains("longitude: -74.006"))
            assertTrue(script.contains("__kmpwright_origGeolocation"))
        }
    }

    @Test
    fun `emulateGeolocation overrides watchPosition`() {
        runBlocking {
            page.emulateGeolocation(51.5074, -0.1278)
            val script = controller.executedScripts.last()
            assertTrue(script.contains("watchPosition = function"))
            assertTrue(script.contains("latitude: 51.5074"))
            assertTrue(script.contains("longitude: -0.1278"))
        }
    }

    @Test
    fun `resetGeolocation restores original functions`() {
        runBlocking {
            page.emulateGeolocation(0.0, 0.0)
            page.resetGeolocation()
            val lastScript = controller.executedScripts.last()
            assertTrue(lastScript.contains("__kmpwright_origGeolocation"))
            assertTrue(lastScript.contains("getCurrentPosition = window.__kmpwright_origGeolocation.getCurrentPosition"))
            assertTrue(lastScript.contains("watchPosition = window.__kmpwright_origGeolocation.watchPosition"))
            assertTrue(lastScript.contains("delete window.__kmpwright_origGeolocation"))
        }
    }

    @Test
    fun `multiple geolocation overrides replace each other`() {
        runBlocking {
            page.emulateGeolocation(10.0, 20.0)
            page.emulateGeolocation(30.0, 40.0)
            val lastScript = controller.executedScripts.last()
            assertTrue(lastScript.contains("latitude: 30.0"))
            assertTrue(lastScript.contains("longitude: 40.0"))
        }
    }

    private class TestPage(controller: MockEvalController) : BasePage(
        controller, KmpwrightConfig()
    ) {
        override fun createPlatformLocator(selector: String): BaseLocator =
            object : BaseLocator(controller, selector, config) {
                override fun createLocator(newSelector: String, options: FilterOptions?): Locator =
                    throw UnsupportedOperationException()
                override suspend fun evaluateJavaScriptAsync(script: String): String? = null
            }

        override fun createTouch(): Touch = throw UnsupportedOperationException()
        override fun createMouse(): Mouse = throw UnsupportedOperationException()
        override fun createKeyboard(): Keyboard = throw UnsupportedOperationException()
    }

    private class MockEvalController : WebViewController {
        val executedScripts = mutableListOf<String>()

        override fun loadUrl(url: String) {}
        override fun loadHtml(content: String, baseUrl: String?) {}
        override fun evaluateJavaScript(script: String, callback: (String?) -> Unit) {
            executedScripts.add(script)
            callback(null)
        }
        override fun goBack() {}
        override fun goForward() {}
        override fun reload() {}
        override fun getUrl(): String? = "about:blank"
        override fun getTitle(): String? = null
        override fun takeScreenshot(options: ScreenshotOptions, callback: (ByteArray?, Int, Int) -> Unit) {}
        override fun getViewportSize(): Pair<Int, Int> = Pair(375, 667)
        override fun setViewportSize(width: Int, height: Int) {}
        override fun setZoomScale(scale: Double) {}
        override fun getZoomScale(): Double = 1.0
        override fun setUserAgent(userAgent: String) {}
        override fun setDeviceScaleFactor(scale: Double) {}
        override fun injectJavaScript(script: String, callback: ((String?) -> Unit)?) {}
        override fun dispose() {}
        override fun setNavigationCallback(callback: ((NavigationResult) -> Unit)?) {}
        override fun generatePdf(options: PdfOptions, callback: (ByteArray?) -> Unit) {}
        override fun addRequestListener(listener: RequestListener) {}
        override fun removeRequestListener(listener: RequestListener) {}
        override fun getPendingRequestCount(): Int = 0
        override fun resetRequestCount() {}
        override fun setRequestIdleTimeout(timeoutMs: Long) {}
        override fun addDialogListener(listener: DialogListener) {}
        override fun removeDialogListener(listener: DialogListener) {}
        override fun addRouteHandler(urlPattern: String, handler: suspend (Route) -> RouteHandler) {}
        override fun removeRouteHandler(urlPattern: String) {}
        override fun removeAllRouteHandlers() {}
        override fun fulfillRoute(requestId: String, options: FulfillOptions) {}
        override fun abortRoute(requestId: String, errorCode: String) {}
        override fun continueRoute(requestId: String, options: ContinueOptions) {}
        override fun setCookie(cookie: Cookie) {}
        override fun getCookie(name: String, domain: String, callback: (Cookie?) -> Unit) {}
        override fun getAllCookies(callback: (List<Cookie>) -> Unit) {}
        override fun deleteCookie(name: String, domain: String) {}
        override fun deleteAllCookies() {}
        override fun interceptRequest(url: String, method: String, headers: Map<String, String>, onResult: (RouteAction?) -> Unit) {}
    }
}
