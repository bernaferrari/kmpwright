package com.bernaferrari.kmpwright.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HttpHeadersAuthTest {

    private lateinit var controller: MockEvalController
    private lateinit var page: TestPage

    @Before
    fun setup() {
        controller = MockEvalController()
        page = TestPage(controller)
    }

    @Test
    fun `unsupported extra headers fail without exposing values to page scripts`() {
        runBlocking {
            val error = runCatching {
                page.setExtraHTTPHeaders(mapOf("X-Api-Key" to "abc123"))
            }.exceptionOrNull()

            assertTrue(error is KmpwrightException)
            assertTrue(controller.executedScripts.isEmpty())
        }
    }

    @Test
    fun `unsupported credentials fail without exposing secrets to page scripts`() {
        runBlocking {
            val error = runCatching {
                page.setHTTPCredentials("admin", "secret")
            }.exceptionOrNull()

            assertTrue(error is KmpwrightException)
            assertTrue(controller.executedScripts.isEmpty())
        }
    }

    @Test
    fun `native request configuration never injects page scripts`() {
        val nativeController = NativeRequestConfigController()
        val nativePage = TestPage(nativeController)

        runBlocking {
            nativePage.setExtraHTTPHeaders(mapOf("X-Native" to "1"))
            nativePage.setHTTPCredentials("native", "secret")
            nativePage.clearHTTPCredentials()
        }

        assertTrue(nativeController.executedScripts.isEmpty())
        assertEquals(mapOf("X-Native" to "1"), nativeController.recordedExtraHttpHeaders)
        assertEquals(listOf("native" to "secret", null), nativeController.httpCredentialTransitions)
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

    private open class MockEvalController : WebViewController {
        val executedScripts = mutableListOf<String>()
        var recordedExtraHttpHeaders: Map<String, String> = emptyMap()
        var recordedHttpCredentials: Pair<String, String>? = null

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
        override fun setExtraHttpHeaders(headers: Map<String, String>): Boolean {
            recordedExtraHttpHeaders = headers.toMap()
            return false
        }
        override fun setHttpCredentials(username: String?, password: String?): Boolean {
            recordedHttpCredentials = if (username != null && password != null) {
                username to password
            } else {
                null
            }
            return false
        }
        override fun interceptRequest(url: String, method: String, headers: Map<String, String>, onResult: (RouteAction?) -> Unit) {}
    }

    private class NativeRequestConfigController : MockEvalController() {
        val httpCredentialTransitions = mutableListOf<Pair<String, String>?>()

        override fun setExtraHttpHeaders(headers: Map<String, String>): Boolean {
            recordedExtraHttpHeaders = headers.toMap()
            return true
        }

        override fun setHttpCredentials(username: String?, password: String?): Boolean {
            val credentials = if (username != null && password != null) {
                username to password
            } else {
                null
            }
            recordedHttpCredentials = credentials
            httpCredentialTransitions += credentials
            return true
        }
    }
}
