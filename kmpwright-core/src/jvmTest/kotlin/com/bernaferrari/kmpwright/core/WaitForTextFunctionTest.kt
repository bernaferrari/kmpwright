package com.bernaferrari.kmpwright.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WaitForTextFunctionTest {

    private lateinit var controller: MockEvalController
    private lateinit var page: TestPage

    @Before
    fun setup() {
        controller = MockEvalController()
        page = TestPage(controller)
    }

    @Test
    fun `waitForText returns when text is found`() {
        runBlocking {
            controller.forceResult = "\"true\""
            page.waitForText("Welcome")
            val script = controller.executedScripts.last()
            assertTrue(script.contains("innerText.indexOf"))
            assertTrue(script.contains("Welcome"))
        }
    }

    @Test
    fun `waitForText throws a timeout when text is not found`() {
        runBlocking {
            controller.forceResult = "\"false\""
            assertThrows(KmpwrightTimeoutException::class.java) { runBlocking { page.waitForText("NotFound", timeoutMs = 100) } }
        }
    }

    @Test
    fun `waitForText escapes special characters`() {
        runBlocking {
            controller.forceResult = "\"true\""
            page.waitForText("it's a test")
            val script = controller.executedScripts.last()
            assertTrue(script.contains("it\\'s a test"))
        }
    }

    @Test
    fun `waitForFunction returns JsResult on success`() {
        runBlocking {
            controller.forceResult = "\"42\""
            val result = page.waitForFunction("document.querySelectorAll('.item').length")
            assertEquals("42", result.value)
        }
    }

    @Test
    fun `waitForFunction times out instead of succeeding for an unresolved value`() {
        runBlocking {
            controller.forceResult = "null"
            org.junit.Assert.assertThrows(KmpwrightException::class.java) {
                runBlocking { page.waitForFunction("false", timeoutMs = 50) }
            }
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

        override fun createTouch(): Touchscreen = throw UnsupportedOperationException()
        override fun createMouse(): Mouse = throw UnsupportedOperationException()
        override fun createKeyboard(): Keyboard = throw UnsupportedOperationException()
    }

    private class MockEvalController : WebViewController {
        val executedScripts = mutableListOf<String>()
        var forceResult: String? = null

        override fun loadUrl(url: String) {}
        override fun loadHtml(content: String, baseUrl: String?) {}
        override fun evaluateJavaScript(script: String, callback: (String?) -> Unit) {
            executedScripts.add(script)
            callback(if (script.contains("new Promise") && forceResult == "\"42\"") "true" else forceResult)
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
