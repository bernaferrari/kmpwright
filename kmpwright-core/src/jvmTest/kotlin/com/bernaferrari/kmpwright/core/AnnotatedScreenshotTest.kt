package com.bernaferrari.kmpwright.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AnnotatedScreenshotTest {

    private lateinit var controller: MockAnnotatedController
    private lateinit var page: TestPage

    @Before
    fun setup() {
        controller = MockAnnotatedController()
        page = TestPage(controller)
    }

    @Test
    fun `annotated screenshot injects numbered labels and captures screenshot`() {
        runBlocking {
            controller.snapshotResult = "{\"flatNodes\":[],\"refMap\":{\"@e1\":{\"refId\":\"@e1\",\"role\":\"button\",\"name\":\"Submit\",\"cssSelector\":\"button.submit\"}},\"textTree\":\"[1] button \\\"Submit\\\"\\n\",\"timestamp\":0}"
            controller.bboxResult = "[{\"i\":0,\"x\":10.0,\"y\":20.0,\"w\":100.0,\"h\":40.0}]"
            val result = page.screenshot(ScreenshotOptions(annotate = true))
            assertNotNull(result.bytes)
            assertEquals(1, result.annotations.size)
            assertEquals("@e1", result.annotations[0].ref)
            assertEquals("button", result.annotations[0].role)
            assertEquals("Submit", result.annotations[0].name)
            assertEquals(10.0, result.annotations[0].box.x, 0.01)
            assertEquals(20.0, result.annotations[0].box.y, 0.01)
            assertEquals(100.0, result.annotations[0].box.width, 0.01)
            assertEquals(40.0, result.annotations[0].box.height, 0.01)
        }
    }

    @Test
    fun `annotated screenshot cleans up DOM labels after screenshot`() {
        runBlocking {
            controller.snapshotResult = "{\"flatNodes\":[],\"refMap\":{\"@e1\":{\"refId\":\"@e1\",\"role\":\"link\",\"name\":\"Home\",\"cssSelector\":\"a.home\"}},\"textTree\":\"[1] link \\\"Home\\\"\\n\",\"timestamp\":0}"
            controller.bboxResult = """[{"i":0,"x":0.0,"y":0.0,"w":50.0,"h":20.0}]"""
            page.screenshot(ScreenshotOptions(annotate = true))
            val scripts = controller.executedScripts
            // Should have inject script, screenshot, and cleanup script
            assertTrue(scripts.any { it.contains("data-ww-anno") })
            assertTrue(scripts.any { it.contains("querySelectorAll") && it.contains("remove") })
        }
    }

    @Test
    fun `annotated screenshot with empty refMap returns no labels`() {
        runBlocking {
            controller.snapshotResult = """{"nodes":[],"refMap":{},"textTree":"","timestamp":0}"""
            controller.bboxResult = """[]"""
            val result = page.screenshot(ScreenshotOptions(annotate = true))
            assertTrue(result.annotations.isEmpty())
        }
    }

    @Test
    fun `annotated screenshot respects format option`() {
        runBlocking {
            controller.snapshotResult = """{"nodes":[],"refMap":{},"textTree":"","timestamp":0}"""
            controller.bboxResult = """[]"""
            val result = page.screenshot(ScreenshotOptions(annotate = true, format = ScreenshotFormat.JPEG, quality = 85))
            assertNotNull(result)
            assertEquals(ScreenshotFormat.JPEG, page.capturedOptions!!.format)
            assertEquals(85, page.capturedOptions!!.quality)
        }
    }

    private class TestPage(controller: MockAnnotatedController) : BasePage(
        controller, KmpwrightConfig()
    ) {
        var capturedOptions: ScreenshotOptions? = null
        override fun createTouch(): Touchscreen = throw UnsupportedOperationException()
        override fun createMouse(): Mouse = throw UnsupportedOperationException()
        override fun createKeyboard(): Keyboard = throw UnsupportedOperationException()
        override fun createPlatformLocator(selector: String): BaseLocator =
            object : BaseLocator(controller, selector, config) {
                override fun createLocator(newSelector: String, options: FilterOptions?): Locator =
                    throw UnsupportedOperationException()
                override suspend fun evaluateJavaScriptAsync(script: String): String? = null
            }
        override fun createScreenshotController(): ScreenshotController = object : ScreenshotController {
            override suspend fun capture(options: ScreenshotOptions): ScreenshotResult {
                capturedOptions = options
                return ScreenshotResult(ByteArray(0), 375, 667)
            }
            override suspend fun captureViewport(scale: Double): ScreenshotResult = ScreenshotResult(ByteArray(0), 375, 667)
            override suspend fun captureFullPage(scale: Double): ScreenshotResult = ScreenshotResult(ByteArray(0), 375, 667)
            override suspend fun captureClip(x: Double, y: Double, width: Double, height: Double, scale: Double): ScreenshotResult = ScreenshotResult(ByteArray(0), 375, 667)
            override fun getPageDimensions(): PageDimensions = PageDimensions(375, 667, 375, 667)
        }
    }

    private class MockAnnotatedController : WebViewController {
        val executedScripts = mutableListOf<String>()
        var snapshotResult: String = "{\"flatNodes\":[],\"refMap\":{},\"textTree\":\"\",\"timestamp\":0}"
        var bboxResult: String = """[]"""

        override fun loadUrl(url: String) {}
        override fun loadHtml(content: String, baseUrl: String?) {}
        override fun evaluateJavaScript(script: String, callback: (String?) -> Unit) {
            executedScripts.add(script)
            val result = when {
                script.contains("var refMap") -> {
                    // Return as JS-quoted string: normalizeJsResult will unescape it
                    val jsEscaped = snapshotResult.replace("\\", "\\\\").replace("\"", "\\\"")
                    "\"$jsEscaped\""
                }
                script.contains("labels.push") -> kotlinx.serialization.json.JsonPrimitive(bboxResult).toString()
                script.contains("querySelectorAll") -> null
                else -> null
            }
            callback(result)
        }
        override fun goBack() {}
        override fun goForward() {}
        override fun reload() {}
        override fun getUrl(): String? = "about:blank"
        override fun getTitle(): String? = null
        override fun takeScreenshot(options: ScreenshotOptions, callback: (ByteArray?, Int, Int) -> Unit) {
            callback(ByteArray(0), 375, 667)
        }
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
