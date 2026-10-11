package com.bernaferrari.kmpwright.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ColorSchemeTest {

    private lateinit var controller: MockEvalController
    private lateinit var page: TestPage

    @Before
    fun setup() {
        controller = MockEvalController()
        page = TestPage(controller)
    }

    @Test
    fun `emulateColorScheme dark injects meta tag and style`() {
        runBlocking {
            page.emulateColorScheme(ColorScheme.Dark)
            val scripts = controller.executedScripts
            assertTrue(scripts.any { it.contains("meta[name=\"color-scheme\"]") })
            assertTrue(scripts.any { it.contains("meta.content = 'dark'") })
            assertTrue(scripts.any { it.contains("__kmpwright_color_scheme_style") })
            assertTrue(scripts.any { it.contains("prefers-color-scheme: dark") && it.contains("color-scheme: dark") })
        }
    }

    @Test
    fun `emulateColorScheme light injects light scheme`() {
        runBlocking {
            page.emulateColorScheme(ColorScheme.Light)
            assertTrue(controller.executedScripts.any { it.contains("meta.content = 'light'") })
            assertTrue(controller.executedScripts.any { it.contains("prefers-color-scheme: light") })
        }
    }

    @Test
    fun `emulateColorScheme NoPreference injects no-preference`() {
        runBlocking {
            page.emulateColorScheme(ColorScheme.NoPreference)
            assertTrue(controller.executedScripts.any { it.contains("meta.content = 'no-preference'") })
        }
    }

    @Test
    fun `resetColorScheme removes meta and style`() {
        runBlocking {
            page.emulateColorScheme(ColorScheme.Dark)
            page.resetColorScheme()
            val lastScript = controller.executedScripts.last()
            assertTrue(lastScript.contains("meta.remove()"))
            assertTrue(lastScript.contains("__kmpwright_color_scheme_style"))
            assertTrue(lastScript.contains("style.remove()"))
        }
    }

    @Test
    fun `double emulation replaces previous style element`() {
        runBlocking {
            page.emulateColorScheme(ColorScheme.Dark)
            page.emulateColorScheme(ColorScheme.Light)
            // Second call should remove existing style before adding new one
            val secondCall = controller.executedScripts.last()
            assertTrue(secondCall.contains("existing.remove()"))
            assertTrue(secondCall.contains("meta.content = 'light'"))
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
