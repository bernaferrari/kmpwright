package com.bernaferrari.kmpwright.core

import kotlinx.collections.immutable.persistentSetOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HelpersInjectionTest {

    @Before
    fun setup() {
        BaseLocator.injectedControllers = persistentSetOf()
    }

    @Test
    fun `helpersJsSource is non-empty`() {
        val source = BaseLocator.helpersJsSource()
        assertNotNull(source)
        assertTrue(source.length > 100)
        assertTrue(source.contains("__kmpwright_queryOne"))
        assertTrue(source.contains("__kmpwright_resolveAll"))
        assertTrue(source.contains("__kmpwright_resolveFirst"))
    }

    @Test
    fun `helpersJsSource contains IIFE guard`() {
        val source = BaseLocator.helpersJsSource()
        assertTrue(source.contains("if (window.__kmpwright_queryOne) return;"))
    }

    @Test
    fun `helpersJsSource contains all query functions`() {
        val source = BaseLocator.helpersJsSource()
        assertTrue(source.contains("__kmpwright_roleSelector"))
        assertTrue(source.contains("__kmpwright_byText"))
        assertTrue(source.contains("__kmpwright_byLabel"))
        assertTrue(source.contains("__kmpwright_queryOne"))
        assertTrue(source.contains("__kmpwright_queryAll"))
    }

    @Test
    fun `injectedControllers starts empty`() {
        assertEquals(0, BaseLocator.injectedControllers.size)
    }

    @Test
    fun `invalidateHelpers clears from set`() {
        val controller = createMockController()
        BaseLocator.injectedControllers = BaseLocator.injectedControllers.add(controller)
        assertTrue(controller in BaseLocator.injectedControllers)

        BaseLocator.invalidateHelpers(controller)
        assertFalse(controller in BaseLocator.injectedControllers)
    }

    @Test
    fun `invalidateHelpers is no-op for non-injected controller`() {
        val controller = createMockController()
        assertFalse(controller in BaseLocator.injectedControllers)
        BaseLocator.invalidateHelpers(controller)
        assertFalse(controller in BaseLocator.injectedControllers)
    }

    @Test
    fun `multiple controllers tracked independently`() {
        val c1 = createMockController()
        val c2 = createMockController()
        BaseLocator.injectedControllers = BaseLocator.injectedControllers.adding(c1).adding(c2)
        assertEquals(2, BaseLocator.injectedControllers.size)

        BaseLocator.invalidateHelpers(c1)
        assertEquals(1, BaseLocator.injectedControllers.size)
        assertTrue(c2 in BaseLocator.injectedControllers)
    }

    private fun createMockController(): WebViewController {
        return object : WebViewController {
            override fun loadUrl(url: String) {}
            override fun loadHtml(content: String, baseUrl: String?) {}
            override fun evaluateJavaScript(script: String, callback: (String?) -> Unit) {}
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
}
