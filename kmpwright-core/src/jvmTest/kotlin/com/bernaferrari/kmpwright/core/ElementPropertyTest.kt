package com.bernaferrari.kmpwright.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ElementPropertyTest {

    @Test
    fun `innerHTML returns element inner HTML`() {
        runBlocking {
            val locator = TestLocator()
            val html = locator.innerHTML()
            assertEquals("<span class=\"highlight\">Hello</span>", html)
        }
    }

    @Test
    fun `innerHTML returns empty string when element has no children`() {
        runBlocking {
            val locator = TestLocator(returnHtml = "")
            val html = locator.innerHTML()
            assertEquals("", html)
        }
    }

    private class TestLocator(
        private val returnHtml: String = "<span class=\"highlight\">Hello</span>",
        private val returnJson: String? = null,
        private val elementExists: Boolean = true
    ) : BaseLocator(
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
        "div.test",
        KmpwrightConfig()
    ) {
        override fun createLocator(newSelector: String, options: FilterOptions?): Locator = TestLocator(newSelector)
        override suspend fun evaluateJavaScriptAsync(script: String): String? {
            if (!elementExists) return null
            if (script.contains("el.innerHTML")) return returnHtml
            if (script.contains("getComputedStyle")) {
                return returnJson ?: """{"display":"block","padding":"10px"}"""
            }
            return null
        }
    }
}
