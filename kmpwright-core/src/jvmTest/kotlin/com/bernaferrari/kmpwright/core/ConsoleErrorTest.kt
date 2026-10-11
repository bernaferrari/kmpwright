package com.bernaferrari.kmpwright.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ConsoleErrorTest {

    private lateinit var page: TestPage

    @Before
    fun setup() {
        page = TestPage()
    }

    @Test
    fun `consoleMessages returns empty list initially`() {
        assertTrue(page.consoleMessages().isEmpty())
    }

    @Test
    fun `errors returns empty list initially`() {
        assertTrue(page.errors().isEmpty())
    }

    @Test
    fun `consoleMessages accumulates emitted messages`() {
        page.reportConsoleMessage(ConsoleMessage("log", "hello world", SourceLocation("app.js", 10, 5)))
        page.reportConsoleMessage(ConsoleMessage("warn", "something happened", null))

        val messages = page.consoleMessages()
        assertEquals(2, messages.size)
        assertEquals("log", messages[0].type)
        assertEquals("hello world", messages[0].text)
        assertEquals("warn", messages[1].type)
        assertEquals("something happened", messages[1].text)
    }

    @Test
    fun `errors filters only error and assertion types`() {
        page.reportConsoleMessage(ConsoleMessage("log", "normal log", null))
        page.reportConsoleMessage(ConsoleMessage("error", "something broke", SourceLocation("app.js", 42, 1)))
        page.reportConsoleMessage(ConsoleMessage("warn", "warning", null))
        page.reportConsoleMessage(ConsoleMessage("assertion", "assert failed", null))

        val errs = page.errors()
        assertEquals(2, errs.size)
        assertEquals("something broke", errs[0])
        assertEquals("assert failed", errs[1])
    }

    @Test
    fun `consoleMessages preserves source location`() {
        val location = SourceLocation("https://example.com/script.js", 99, 7)
        page.reportConsoleMessage(ConsoleMessage("error", "TypeError: undefined", location))

        val messages = page.consoleMessages()
        assertEquals(1, messages.size)
        assertEquals("https://example.com/script.js", messages[0].location!!.url)
        assertEquals(99, messages[0].location!!.lineNumber)
        assertEquals(7, messages[0].location!!.columnNumber)
    }

    @Test
    fun `invalidateOnNavigation clears accumulated messages`() {
        page.reportConsoleMessage(ConsoleMessage("log", "before nav", null))
        assertEquals(1, page.consoleMessages().size)

        page.testInvalidateNavigation()
        assertTrue(page.consoleMessages().isEmpty())
        assertTrue(page.errors().isEmpty())
    }

    @Test
    fun `navigation invalidates console messages`() {
        page.reportConsoleMessage(ConsoleMessage("log", "old message", null))
        assertEquals(1, page.consoleMessages().size)

        // invalidateOnNavigation is called by goto/reload/back/forward
        page.testInvalidateNavigation()

        // After navigation, messages should be cleared
        assertTrue(page.consoleMessages().isEmpty())
    }

    // Test helper
    private class TestPage : BasePage(
        TestWebViewController(),
        KmpwrightConfig()
    ) {
        fun testInvalidateNavigation() {
            invalidateOnNavigation()
        }

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

    private class TestWebViewController : WebViewController {
        override fun loadUrl(url: String) {}
        override fun loadHtml(content: String, baseUrl: String?) {}
        override fun evaluateJavaScript(script: String, callback: (String?) -> Unit) { callback(null) }
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
