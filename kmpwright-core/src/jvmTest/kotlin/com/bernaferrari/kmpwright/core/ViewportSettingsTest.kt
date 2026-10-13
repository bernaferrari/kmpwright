package com.bernaferrari.kmpwright.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ViewportSettingsTest {

    private lateinit var controller: MockViewportController
    private lateinit var page: TestPage

    @Before
    fun setup() {
        controller = MockViewportController()
        page = TestPage(controller)
    }

    @Test
    fun `setUserAgent delegates to controller`() {
        runBlocking {
            page.setUserAgent("Mozilla/5.0 TestBot")
            assertEquals("Mozilla/5.0 TestBot", controller.lastUserAgent)
        }
    }

    @Test
    fun `setViewportSize delegates to controller`() {
        runBlocking {
            page.setViewportSize(1024, 768)
            assertEquals(1024 to 768, controller.lastViewport)
        }
    }

    @Test
    fun `viewportSize returns controller value`() {
        val size = page.viewportSize()
        assertEquals(ViewportSize(375, 667), size)
    }

    @Test
    fun `setDeviceScaleFactor delegates to controller`() {
        runBlocking {
            page.setDeviceScaleFactor(2.5)
            assertEquals(2.5, controller.lastDeviceScaleFactor, 0.001)
        }
    }

    private class TestPage(controller: MockViewportController) : BasePage(
        controller, KmpwrightConfig()
    ) {
        override fun createPlatformLocator(selector: String): BaseLocator =
            object : BaseLocator(controller, selector, config) {
                override fun createLocator(newSelector: String, options: FilterOptions?): Locator =
                    throw UnsupportedOperationException()
                override suspend fun evaluateJavaScriptAsync(script: String): String? = null
            }

        override fun createTouch(): Touchscreen = throw UnsupportedOperationException()
        override fun createMouse(): Mouse = throw UnsupportedOperationException()
        override fun createKeyboard(): Keyboard = throw UnsupportedOperationException()
    }

    private class MockViewportController : WebViewController {
        var lastUserAgent: String? = null
        var lastViewport: Pair<Int, Int>? = null
        var lastDeviceScaleFactor: Double = 1.0

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
        override fun setViewportSize(width: Int, height: Int) { lastViewport = Pair(width, height) }
        override fun setZoomScale(scale: Double) {}
        override fun getZoomScale(): Double = 1.0
        override fun setUserAgent(userAgent: String) { lastUserAgent = userAgent }
        override fun setDeviceScaleFactor(scale: Double) { lastDeviceScaleFactor = scale }
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
        override fun addRouteHandler(urlPattern: String, handler: suspend (Route) -> Unit) {}
        override fun removeRouteHandler(urlPattern: String) {}
        override fun removeAllRouteHandlers() {}
        override fun setCookie(cookie: Cookie) {}
        override fun getCookie(name: String, domain: String, callback: (Cookie?) -> Unit) {}
        override fun getAllCookies(callback: (List<Cookie>) -> Unit) {}
        override fun deleteCookie(name: String, domain: String) {}
        override fun deleteAllCookies() {}
    }
}
