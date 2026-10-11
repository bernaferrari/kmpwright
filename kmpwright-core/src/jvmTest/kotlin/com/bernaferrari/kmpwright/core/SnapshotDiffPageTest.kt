package com.bernaferrari.kmpwright.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SnapshotDiffPageTest {

    private lateinit var controller: MockEvalController
    private lateinit var page: TestPage

    @Before
    fun setup() {
        controller = MockEvalController()
        page = TestPage(controller)
    }

    @Test
    fun `diffSnapshot with two snapshots returns diff`() {
        runBlocking {
            val before = A11ySnapshot(
                nodes = listOf(A11yNode("heading", "Title")),
                refMap = mapOf("@e1" to RefEntry("@e1", "heading", "Title", "h1")),
                textTree = "[1] heading \"Title\"\n",
                timestamp = 0
            )
            val after = A11ySnapshot(
                nodes = listOf(A11yNode("heading", "Title"), A11yNode("button", "Submit")),
                refMap = mapOf("@e1" to RefEntry("@e1", "heading", "Title", "h1")),
                textTree = "[1] heading \"Title\"\n[2] button \"Submit\"\n",
                timestamp = 1
            )
            val diff = page.diffSnapshot(before, after)
            assertTrue(diff.changed)
            assertEquals(1, diff.additions)
            assertEquals(0, diff.removals)
        }
    }

    @Test
    fun `diffSnapshot with identical snapshots returns unchanged`() {
        runBlocking {
            val snap = A11ySnapshot(
                nodes = listOf(A11yNode("heading", "Title")),
                refMap = emptyMap(),
                textTree = "[1] heading \"Title\"\n",
                timestamp = 0
            )
            val diff = page.diffSnapshot(snap, snap)
            assertFalse(diff.changed)
            assertEquals(0, diff.additions)
            assertEquals(0, diff.removals)
            assertTrue(diff.unchanged > 0)
        }
    }

    @Test
    fun `diffSnapshot by name throws when no snapshot saved`() {
        runBlocking {
            var thrown = false
            try {
                page.diffSnapshot("nonexistent")
            } catch (e: KmpwrightException) {
                thrown = true
                assertTrue(e.message!!.contains("nonexistent"))
            }
            assertTrue(thrown)
        }
    }

    @Test
    fun `frameLocator returns FrameLocator`() {
        val fl = page.frameLocator("iframe")
        assertTrue(fl is FrameLocator)
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
