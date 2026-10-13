package com.bernaferrari.kmpwright.core

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ConsoleErrorTest {

    private lateinit var page: TestPage

    @Before
    fun setup() {
        page = TestPage()
    }

    @Test
    fun `consoleMessages returns empty list initially`() {
        assertTrue(page.consoleMessages().isEmpty())
    }

    @Test
    fun `errors returns empty list initially`() {
        assertTrue(page.pageErrors().isEmpty())
    }

    @Test
    fun `consoleMessages accumulates emitted messages`() {
        page.reportConsoleMessage(ConsoleMessage("log", "hello world", SourceLocation("app.js", 10, 5)))
        page.reportConsoleMessage(ConsoleMessage("warning", "something happened", null))

        val messages = page.consoleMessages()
        assertEquals(2, messages.size)
        assertEquals("log", messages[0].type)
        assertEquals("hello world", messages[0].text)
        assertEquals("warning", messages[1].type)
        assertEquals("something happened", messages[1].text)
    }

    @Test
    fun `pageErrors are uncaught exceptions, not console errors`() = runBlocking {
        val events = async(start = CoroutineStart.UNDISPATCHED) { page.events.pageError.first() }
        page.reportConsoleMessage(ConsoleMessage("error", "logged, not thrown", null))
        page.reportConsoleMessage(ConsoleMessage("assert", "console.assert", null))
        assertTrue(page.pageErrors().isEmpty())

        page.reportPageError(PageError("TypeError", "x is undefined", "TypeError: x is undefined\n    at f (app.js:1:2)"))
        page.reportPageError(PageError("", "plain rejection"))
        assertEquals(listOf("TypeError: x is undefined", "plain rejection"), page.pageErrors())
        assertEquals("x is undefined", events.await().message)
    }

    @Test
    fun `consoleMessages preserves source location`() {
        val location = SourceLocation("https://example.com/script.js", 99, 7)
        page.reportConsoleMessage(ConsoleMessage("error", "TypeError: undefined", location))

        val messages = page.consoleMessages()
        assertEquals(1, messages.size)
        assertEquals("https://example.com/script.js", messages[0].location!!.url)
        assertEquals(99, messages[0].location!!.lineNumber)
        assertEquals(7, messages[0].location!!.columnNumber)
    }

    @Test
    fun `history survives navigation and the default filter starts at the last commit`() {
        page.reportConsoleMessage(ConsoleMessage("log", "old message", null))
        page.reportPageError(PageError("Error", "old error"))
        // Snapshot invalidation alone (navigation start) does not drop messages.
        page.testInvalidateNavigation()
        assertEquals(1, page.consoleMessages().size)

        page.markDocumentCommitted()
        page.reportConsoleMessage(ConsoleMessage("log", "new message", null))
        assertEquals(listOf("new message"), page.consoleMessages().map { it.text })
        assertEquals(listOf("old message", "new message"), page.consoleMessages(PageMessageFilter.All).map { it.text })
        assertTrue(page.pageErrors().isEmpty())
        assertEquals(listOf("Error: old error"), page.pageErrors(PageMessageFilter.All))
    }

    @Test
    fun `clear forgets stored messages and errors independently`() {
        page.reportConsoleMessage(ConsoleMessage("log", "a", null))
        page.reportPageError(PageError("Error", "b"))
        page.clearConsoleMessages()
        assertTrue(page.consoleMessages(PageMessageFilter.All).isEmpty())
        assertEquals(1, page.pageErrors().size)
        page.clearPageErrors()
        assertTrue(page.pageErrors(PageMessageFilter.All).isEmpty())
    }

    @Test
    fun `histories keep the last 200 entries like Playwright`() {
        repeat(250) { page.reportPageError(PageError("Error", "e$it")) }
        val errors = page.pageErrors()
        assertEquals(200, errors.size)
        assertEquals("Error: e50", errors.first())
    }

    @Test
    fun `bridged payloads require the nonce and Playwright console types`() {
        val nonce = "abc123"
        val console = parsePageDiagnosticPayload("""{"nonce":"abc123","kind":"console","type":"warning","text":"w","url":"https://a/x.js","line":3,"column":4}""", nonce)
        assertEquals(PageDiagnostic.Console(ConsoleMessage("warning", "w", SourceLocation("https://a/x.js", 3, 4))), console)
        val error = parsePageDiagnosticPayload("""{"nonce":"abc123","kind":"pageerror","name":"Error","message":"boom","stack":"Error: boom"}""", nonce)
        assertEquals(PageError("Error", "boom", "Error: boom", null), (error as PageDiagnostic.Error).error)
        assertEquals(PageDiagnostic.DocumentStarted, parsePageDiagnosticPayload("""{"nonce":"abc123","kind":"document"}""", nonce))
        assertNull(parsePageDiagnosticPayload("""{"nonce":"wrong","kind":"console","type":"log","text":"x"}""", nonce))
        assertNull(parsePageDiagnosticPayload("""{"kind":"console","type":"log","text":"x"}""", nonce))
        assertNull(parsePageDiagnosticPayload("""{"nonce":"abc123","kind":"console","type":"warn","text":"x"}""", nonce))
        assertNull(parsePageDiagnosticPayload("not json", nonce))
    }

    @Test
    fun `cdp console arguments render like Playwright previews`() {
        val params = Json.parseToJsonElement("""{"type":"warning","args":[
            {"type":"string","value":"hi"},{"type":"number","value":2},{"type":"undefined"},
            {"type":"number","unserializableValue":"NaN"},
            {"type":"object","className":"Object","description":"Object","preview":{"properties":[{"name":"a","type":"number","value":"1"}]}},
            {"type":"object","subtype":"array","description":"Array(2)","preview":{"properties":[{"name":"0","type":"number","value":"1"},{"name":"1","type":"string","value":"x"}]}},
            {"type":"object","subtype":"null","value":null}],
            "stackTrace":{"callFrames":[{"url":"https://a/app.js","lineNumber":4,"columnNumber":2,"functionName":""}]}}""").jsonObject
        assertEquals(ConsoleMessage("warning", "hi 2 undefined NaN {a: 1} [1, x] null", SourceLocation("https://a/app.js", 4, 2)), cdpConsoleMessage(params))
    }

    @Test
    fun `cdp exceptions split name, message and stack like Playwright`() {
        val thrown = Json.parseToJsonElement("""{"exceptionDetails":{"text":"Uncaught","lineNumber":1,"columnNumber":7,"url":"https://a/",
            "exception":{"type":"object","subtype":"error","description":"TypeError: bad thing\n    at f (https://a/:2:8)"}}}""").jsonObject
        val error = cdpPageError(thrown)!!
        assertEquals("TypeError", error.name)
        assertEquals("bad thing", error.message)
        assertEquals("TypeError: bad thing\n    at f (https://a/:2:8)", error.stack)
        val rejected = Json.parseToJsonElement("""{"exceptionDetails":{"text":"Uncaught (in promise)","exception":{"type":"string","value":"nope"}}}""").jsonObject
        assertEquals(PageError("", "nope", null, null), cdpPageError(rejected))
    }

    // Test helper
    private class TestPage : BasePage(
        TestWebViewController(),
        KmpwrightConfig()
    ) {
        fun testInvalidateNavigation() {
            invalidateOnNavigation()
        }

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

    private class TestWebViewController : WebViewController {
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
