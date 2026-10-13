package com.bernaferrari.kmpwright.core

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer

class DiffUrlsTest {

    private lateinit var page: TestPage
    private lateinit var server: HttpServer
    private lateinit var baseUrl: String

    @Before
    fun setup() {
        page = TestPage(MockEvalController())
        server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/a", StaticHandler("<html><body><h1>Alpha</h1><p>One</p></body></html>"))
        server.createContext("/b", StaticHandler("<html><body><h1>Beta</h1><p>Two</p></body></html>"))
        server.start()
        baseUrl = "http://127.0.0.1:${server.address.port}"
    }

    @After
    fun tearDown() {
        server.stop(0)
    }

    @Test
    fun `diffUrls compares both URLs`() {
        runBlocking {
            val result = page.diffUrls("$baseUrl/a", "$baseUrl/b", timeoutMs = 5_000)
            assertNotNull(result)
            assertTrue(result.changed || result.additions > 0 || result.removals > 0)
        }
    }

    @Test
    fun `htmlToSnapshotText extracts stable text lines`() {
        runBlocking {
            val extracted = htmlToSnapshotText("<html><body><h1>Hello</h1><script>ignored()</script><p>World</p></body></html>")
            assertTrue(extracted.contains("Hello"))
            assertTrue(extracted.contains("World"))
            assertTrue(!extracted.contains("ignored()"))
        }
    }

    @Test
    fun `diffUrls returns a SnapshotDiffResult`() {
        runBlocking {
            val result = page.diffUrls("$baseUrl/a", "$baseUrl/b")
            assertNotNull(result.unifiedDiff)
            assertNotNull(result.additions)
            assertNotNull(result.removals)
            assertNotNull(result.unchanged)
            assertNotNull(result.changed)
        }
    }

    @Test
    fun `different URLs produce different extracted texts`() {
        val textA = htmlToSnapshotText("<html><body><h1>Alpha</h1></body></html>")
        val textB = htmlToSnapshotText("<html><body><h1>Beta</h1></body></html>")
        assertNotEquals(textA, textB)
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

        override fun loadUrl(url: String) {}
        override fun loadHtml(content: String, baseUrl: String?) {}
        override fun evaluateJavaScript(script: String, callback: (String?) -> Unit) {
            executedScripts.add(script)
            callback("\"text content\"")
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

    private class StaticHandler(private val html: String) : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            val bytes = html.toByteArray()
            exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { body ->
                body.write(bytes)
            }
        }
    }
}
