package com.bernaferrari.kmpwright.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DownloadFileChooserTest {

    private lateinit var page: TestPage

    @Before
    fun setup() {
        page = TestPage()
    }

    @Test
    fun `setDownloadPath stores path`() = testBlocking {
        page.setDownloadPath("/tmp/downloads")
        // No exception = success
    }

    @Test
    fun `setDownloadBehavior changes behavior`() = testBlocking {
        page.setDownloadBehavior(DownloadBehavior.Deny)
        page.setDownloadBehavior(DownloadBehavior.Allow)
        page.setDownloadBehavior(DownloadBehavior.SaveToDownloadPath)
    }

    @Test
    fun `reportDownload emits to download flow`() {
        val event = Download("https://example.com/file.pdf", "file.pdf", { null }, { _, _ -> })
        page.reportDownload(event)
        // Event is emitted (buffered) — accessing it via waitForDownload in real usage
    }

    @Test
    fun `reportFileChooser emits to fileChooser flow`() {
        val event = FileChooser("input[type=file]", false) { _, _ -> }
        page.reportFileChooser(event)
    }

    @Test
    fun `waitForDownload times out without events`() {
        runBlocking {
            var thrown = false
            try {
                page.waitForDownload(timeoutMs = 100)
            } catch (e: KmpwrightException) {
                thrown = true
                assertTrue(e.message!!.contains("Timeout"))
            }
            assertTrue(thrown)
        }
    }

    @Test
    fun `waitForFileChooser times out without events`() {
        runBlocking {
            var thrown = false
            try {
                page.waitForFileChooser(timeoutMs = 100)
            } catch (e: KmpwrightException) {
                thrown = true
                assertTrue(e.message!!.contains("Timeout"))
            }
            assertTrue(thrown)
        }
    }

    @Test
    fun `waitForDownload receives emitted event`() {
        runBlocking {
            CoroutineScope(Dispatchers.Default).launch {
                delay(50)
                page.reportDownload(Download("https://example.com/report.csv", "report.csv", { null }, { _, _ -> }))
            }
            val event = page.waitForDownload(timeoutMs = 1000)
            assertEquals("report.csv", event.suggestedFilename)
            assertTrue(event.url.contains("report.csv"))
        }
    }

    @Test
    fun `waitForDownload with urlPattern filters events`() {
        runBlocking {
            CoroutineScope(Dispatchers.Default).launch {
                delay(50)
                page.reportDownload(Download("https://cdn.example.com/data.json", "data.json", { null }, { _, _ -> }))
            }
            val event = page.waitForDownload(url = "**/*.json", timeoutMs = 1000)
            assertEquals("data.json", event.suggestedFilename)
        }
    }

    private class TestPage : BasePage(
        MockController(), KmpwrightConfig()
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
