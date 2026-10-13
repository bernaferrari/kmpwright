package com.bernaferrari.kmpwright.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
        import org.junit.Test

class ClipboardTest {

    private lateinit var controller: MockEvalController
    private lateinit var page: TestPage

    @Before
    fun setup() {
        controller = MockEvalController()
        page = TestPage(controller)
    }

    @Test
    fun `clipboardWrite uses navigator clipboard API`() {
        runBlocking {
            page.clipboard.writeText("hello world")
            val script = controller.executedScripts.last()
            assertTrue(script.contains("navigator.clipboard.writeText"))
            assertTrue(script.contains("hello world"))
        }
    }

    @Test
    fun `clipboardWrite escapes special characters`() {
        runBlocking {
            page.clipboard.writeText("it's a \"test\"")
            val script = controller.executedScripts.last()
            assertTrue(script.contains("it\\'s a \\\"test\\\""))
        }
    }

    @Test
    fun `clipboardWrite falls back to execCommand`() {
        runBlocking {
            controller.throwOnClipboardApi = true
            page.clipboard.writeText("fallback text")
            val script = controller.executedScripts.last()
            assertTrue(script.contains("execCommand"))
            assertTrue(script.contains("fallback text"))
            assertTrue(script.contains("textarea"))
        }
    }

    @Test
    fun `clipboardRead uses navigator clipboard API`() {
        runBlocking {
            controller.clipboardReturnValue = "copied text"
            val result = page.clipboard.readText()
            assertEquals("copied text", result)
            val script = controller.executedScripts.last()
            assertTrue(script.contains("navigator.clipboard.readText"))
        }
    }

    @Test
    fun `clipboardRead falls back to execCommand paste`() {
        runBlocking {
            controller.throwOnClipboardApi = true
            controller.selectionReturnValue = "pasted"
            val result = page.clipboard.readText()
            assertEquals("pasted", result)
        }
    }

    @Test
    fun `clipboardRead returns null on failure`() {
        runBlocking {
            controller.throwOnClipboardApi = true
            controller.selectionReturnValue = ""
            val result = page.clipboard.readText()
            // Empty string is falsy so the fallback returns ""
            assertEquals("", result)
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
        var clipboardReturnValue: String? = null
        var throwOnClipboardApi: Boolean = false
        var selectionReturnValue: String = ""

        override fun loadUrl(url: String) {}
        override fun loadHtml(content: String, baseUrl: String?) {}
        override fun evaluateJavaScript(script: String, callback: (String?) -> Unit) {
            executedScripts.add(script)
            val result = when {
                throwOnClipboardApi && script.contains("clipboard.readText") -> "\"$selectionReturnValue\""
                throwOnClipboardApi && script.contains("clipboard.writeText") -> null
                script.contains("clipboard.readText") -> "\"${clipboardReturnValue ?: ""}\""
                script.contains("clipboard.writeText") -> null
                else -> null
            }
            callback(result)
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
