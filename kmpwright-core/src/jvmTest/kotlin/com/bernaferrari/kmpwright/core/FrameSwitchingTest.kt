package com.bernaferrari.kmpwright.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FrameSwitchingTest {

    private lateinit var page: TestPage

    @Before
    fun setup() {
        page = TestPage()
    }

    @Test
    fun `frameLocator returns FrameLocator for iframe selector`() {
        val fl = page.frameLocator("iframe#content")
        assertNotNull(fl)
    }

    @Test
    fun `frameLocator returns FrameLocator for named frame`() {
        val fl = page.frameLocator("frame[name='main']")
        assertNotNull(fl)
    }

    @Test
    fun `mainFrame returns FrameLocator`() {
        val fl = page.mainFrame()
        assertNotNull(fl)
    }

    @Test
    fun `frameLocator can chain locators`() {
        val fl = page.frameLocator("iframe.embed")
        val locator = fl.locator("button.submit")
        assertNotNull(locator)
    }

    @Test
    fun `frameLocator can chain nested frames`() {
        val fl = page.frameLocator("iframe.embed")
        val nested = fl.frameLocator("iframe.nested")
        assertNotNull(nested)
    }

    private class TestPage : BasePage(
        MockController(), KmpwrightConfig()
    ) {
        override fun createTouch(): Touch = throw UnsupportedOperationException()
        override fun createMouse(): Mouse = throw UnsupportedOperationException()
        override fun createKeyboard(): Keyboard = throw UnsupportedOperationException()
        override fun createPlatformLocator(selector: String): BaseLocator =
            object : BaseLocator(controller, selector, config) {
                override fun createLocator(newSelector: String, options: FilterOptions?): Locator =
                    throw UnsupportedOperationException()
                override suspend fun evaluateJavaScriptAsync(script: String): String? = null
            }
    }

    private class MockController : WebViewController {
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
