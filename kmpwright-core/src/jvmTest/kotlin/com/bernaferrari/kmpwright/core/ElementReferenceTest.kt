package com.bernaferrari.kmpwright.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream

class ElementReferenceTest {

    private lateinit var basePage: TestBasePage

    @Before
    fun setup() {
        basePage = TestBasePage()
    }

    @Test
    fun `parseRef with @e1 format`() {
        assertEquals("@e1", basePage.parseRef("@e1"))
    }

    @Test
    fun `parseRef with e1 format`() {
        assertEquals("@e1", basePage.parseRef("e1"))
    }

    @Test
    fun `parseRef with e10 format`() {
        assertEquals("@e10", basePage.parseRef("e10"))
    }

    @Test
    fun `parseRef with ref=e1 format`() {
        assertEquals("@e1", basePage.parseRef("ref=e1"))
    }

    @Test
    fun `parseRef with ref=@e5 format`() {
        assertEquals("@e5", basePage.parseRef("ref=@e5"))
    }

    @Test
    fun `parseRef with invalid format returns null`() {
        assertNull(basePage.parseRef("invalid"))
    }

    @Test
    fun `parseRef with empty string returns null`() {
        assertNull(basePage.parseRef(""))
    }

    @Test
    fun `parseRef with e prefix but no digits returns null`() {
        assertNull(basePage.parseRef("e"))
    }

    @Test
    fun `parseRef with random text returns null`() {
        assertNull(basePage.parseRef("hello"))
    }

    @Test
    fun `parseRef trims whitespace`() {
        assertEquals("@e1", basePage.parseRef("  @e1  "))
    }

    @Test(expected = KmpwrightException::class)
    fun `locatorByRef throws for invalid ref format`() {
        runBlocking {
            basePage.currentSnapshotRefMap = mapOf("@e1" to RefEntry("@e1", "button", "Click", "#btn"))
            basePage.locatorByRef("invalid-ref")
        }
    }

    @Test(expected = KmpwrightException::class)
    fun `locatorByRef throws for ref not in snapshot`() {
        runBlocking {
            basePage.currentSnapshotRefMap = emptyMap()
            basePage.locatorByRef("@e1")
        }
    }

    @Test
    fun `locatorByRef uses node identity when ref has no CSS selector`() {
        runBlocking {
            basePage.currentSnapshotRefMap = mapOf("@e1" to RefEntry("@e1", "button", "Click", null))
            assertEquals("ref=@e1", (basePage.locatorByRef("@e1") as TestLocator).selectorStr)
        }
    }

    @Test
    fun `locatorByRef delegates to node identity`() {
        runBlocking {
            basePage.currentSnapshotRefMap = mapOf(
                "@e1" to RefEntry("@e1", "button", "Click", "#my-button"),
                "@e2" to RefEntry("@e2", "link", "Home", "nav > a:nth-of-type(1)")
            )
            val locator1 = basePage.locatorByRef("@e1") as TestLocator
            assertEquals("ref=@e1", locator1.selectorStr)

            val locator2 = basePage.locatorByRef("e2") as TestLocator
            assertEquals("ref=@e2", locator2.selectorStr)
        }
    }

    @Test
    fun `navigation invalidates refMap`() {
        basePage.currentSnapshotRefMap = mapOf("@e1" to RefEntry("@e1", "button", "Click", "#btn"))
        assertEquals(1, basePage.currentSnapshotRefMap.size)

        basePage.testInvalidateNavigation()
        assertEquals(0, basePage.currentSnapshotRefMap.size)
    }

    // Test helpers
    private class TestBasePage : BasePage(
        object : WebViewController {
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
        },
        KmpwrightConfig()
    ) {
        fun testInvalidateNavigation() {
            invalidateOnNavigation()
        }

        override fun createPlatformLocator(selector: String): BaseLocator = TestLocator(selector)

        override fun createTouch(): Touch = throw UnsupportedOperationException()
        override fun createMouse(): Mouse = throw UnsupportedOperationException()
        override fun createKeyboard(): Keyboard = throw UnsupportedOperationException()
    }

    private class TestLocator(selector: String) : BaseLocator(
        object : WebViewController {
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
        },
        selector,
        KmpwrightConfig()
    ) {
        val selectorStr: String get() = this.selector
        override fun createLocator(newSelector: String, options: FilterOptions?): Locator = TestLocator(newSelector)
        override suspend fun evaluateJavaScriptAsync(script: String): String? = null
    }
}
