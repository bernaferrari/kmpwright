package com.bernaferrari.kmpwright.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test

class PolicyWiringTest {

    private lateinit var testPage: TestPage

    @Before
    fun setup() {
        testPage = TestPage()
    }

    @Test
    fun `locator receives policyChecker when policy is set`() {
        testPage.setActionPolicy(ActionPolicy(deniedActions = setOf(ActionType.Click)))
        val locator = testPage.locator(".btn") as TestLocator
        assertNotNull(locator.policyChecker)
    }

    @Test
    fun `locator has a live policyChecker before policy is set`() {
        val locator = testPage.locator(".btn") as TestLocator
        assertNotNull(locator.policyChecker)
    }

    @Test
    fun `child locator inherits policyChecker via first`() {
        testPage.setActionPolicy(ActionPolicy(deniedActions = setOf(ActionType.Click)))
        val parent = testPage.locator(".btn") as TestLocator
        val child = parent.first() as TestLocator
        assertNotNull(child.policyChecker)
        assertSame(parent.policyChecker, child.policyChecker)
    }

    @Test
    fun `child locator inherits policyChecker via nth`() {
        testPage.setActionPolicy(ActionPolicy(deniedActions = setOf(ActionType.Click)))
        val parent = testPage.locator(".btn") as TestLocator
        val child = parent.nth(2) as TestLocator
        assertNotNull(child.policyChecker)
        assertSame(parent.policyChecker, child.policyChecker)
    }

    @Test
    fun `child locator inherits policyChecker via last`() {
        testPage.setActionPolicy(ActionPolicy(deniedActions = setOf(ActionType.Click)))
        val parent = testPage.locator(".btn") as TestLocator
        val child = parent.last() as TestLocator
        assertNotNull(child.policyChecker)
        assertSame(parent.policyChecker, child.policyChecker)
    }

    @Test
    fun `chained locator inherits policyChecker`() {
        testPage.setActionPolicy(ActionPolicy(deniedActions = setOf(ActionType.Click)))
        val parent = testPage.locator(".container") as TestLocator
        val child = parent.locator(".btn") as TestLocator
        assertNotNull(child.policyChecker)
        assertSame(parent.policyChecker, child.policyChecker)
    }

    @Test
    fun `filtered locator inherits policyChecker`() {
        testPage.setActionPolicy(ActionPolicy(deniedActions = setOf(ActionType.Click)))
        val parent = testPage.locator(".btn") as TestLocator
        val child = parent.filter(hasText = "Submit") as TestLocator
        assertNotNull(child.policyChecker)
        assertSame(parent.policyChecker, child.policyChecker)
    }

    @Test(expected = KmpwrightException::class)
    fun `policy denial propagates through locator click`() {
        runBlocking {
            testPage.setActionPolicy(ActionPolicy(deniedActions = setOf(ActionType.Click)))
            val locator = testPage.locator(".btn") as TestLocator
            locator.click()
        }
    }

    private class TestPage : BasePage(
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
            override fun addRouteHandler(urlPattern: String, handler: suspend (Route) -> Unit) {}
            override fun removeRouteHandler(urlPattern: String) {}
            override fun removeAllRouteHandlers() {}
            override fun setCookie(cookie: Cookie) {}
            override fun getCookie(name: String, domain: String, callback: (Cookie?) -> Unit) {}
            override fun getAllCookies(callback: (List<Cookie>) -> Unit) {}
            override fun deleteCookie(name: String, domain: String) {}
            override fun deleteAllCookies() {}
        },
        KmpwrightConfig()
    ) {
        override fun createPlatformLocator(selector: String): BaseLocator = TestLocator(selector)
        override fun createTouch(): Touchscreen = throw UnsupportedOperationException()
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
            override fun addRouteHandler(urlPattern: String, handler: suspend (Route) -> Unit) {}
            override fun removeRouteHandler(urlPattern: String) {}
            override fun removeAllRouteHandlers() {}
            override fun setCookie(cookie: Cookie) {}
            override fun getCookie(name: String, domain: String, callback: (Cookie?) -> Unit) {}
            override fun getAllCookies(callback: (List<Cookie>) -> Unit) {}
            override fun deleteCookie(name: String, domain: String) {}
            override fun deleteAllCookies() {}
        },
        selector,
        KmpwrightConfig()
    ) {
        val selectorStr: String get() = this.selector
        override fun createLocator(newSelector: String, options: FilterOptions?): Locator = TestLocator(newSelector)
        override suspend fun evaluateJavaScriptAsync(script: String): String? = null
    }
}
