package com.bernaferrari.kmpwright.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BrowserReuseTest {

    private lateinit var controller: ReusableController
    private lateinit var page: ReuseTestPage

    @Before
    fun setup() {
        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        Dispatchers.setMain(Dispatchers.Unconfined)
        controller = ReusableController()
        page = ReuseTestPage(controller)
    }

    @After
    fun tearDown() {
        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        Dispatchers.resetMain()
    }

    @Test
    fun `resetPageForReuse clears page state and navigates blank`() = runBlocking {
        controller.currentUrl = "https://example.com/app"
        controller.cookies += Cookie("sid", "abc", "example.com")
        controller.localStorage["token"] = "123"
        controller.sessionStorage["draft"] = "hello"

        page.route(".*") { RouteHandler.Fallthrough }
        page.setExtraHTTPHeaders(mapOf("X-Test" to "1"))
        page.setHTTPCredentials("user", "pass")

        resetPageForReuse(page)

        assertTrue(controller.cookies.isEmpty())
        assertTrue(controller.localStorage.isEmpty())
        assertTrue(controller.sessionStorage.isEmpty())
        assertEquals(emptyMap<String, String>(), controller.recordedExtraHttpHeaders)
        assertEquals(null, controller.recordedHttpCredentials)
        assertEquals("about:blank", controller.currentUrl)
        assertEquals("about:blank", page.url.value)
    }

    private class ReuseTestPage(controller: ReusableController) : BasePage(controller, KmpwrightConfig()) {
        override fun createPlatformLocator(selector: String): BaseLocator =
            object : BaseLocator(controller, selector, KmpwrightConfig()) {
                override fun createLocator(newSelector: String, options: FilterOptions?): Locator =
                    throw UnsupportedOperationException()

                override suspend fun evaluateJavaScriptAsync(script: String): String? = null
            }

        override fun createTouch(): Touch = throw UnsupportedOperationException()
        override fun createMouse(): Mouse = throw UnsupportedOperationException()
        override fun createKeyboard(): Keyboard = throw UnsupportedOperationException()
    }

    private class ReusableController : WebViewController {
        val cookies = mutableListOf<Cookie>()
        val localStorage = mutableMapOf<String, String>()
        val sessionStorage = mutableMapOf<String, String>()
        var currentUrl: String = "about:blank"
        var recordedExtraHttpHeaders: Map<String, String> = emptyMap()
        var recordedHttpCredentials: Pair<String, String>? = null
        private var navigationCallback: ((NavigationResult) -> Unit)? = null

        override fun loadUrl(url: String) {
            currentUrl = url
            navigationCallback?.invoke(NavigationResult(url, 200, true))
        }

        override fun loadHtml(content: String, baseUrl: String?) {}

        override fun getUrl(): String? = currentUrl

        override fun evaluateJavaScript(script: String, callback: (String?) -> Unit) {
            callback(executeStorageScript(script))
        }

        override fun goBack() {}
        override fun goForward() {}
        override fun reload() {}
        override fun getTitle(): String? = null
        override fun takeScreenshot(options: ScreenshotOptions, callback: (ByteArray?, Int, Int) -> Unit) {}
        override fun getViewportSize(): Pair<Int, Int> = 375 to 667
        override fun setViewportSize(width: Int, height: Int) {}
        override fun setZoomScale(scale: Double) {}
        override fun getZoomScale(): Double = 1.0
        override fun setUserAgent(userAgent: String) {}
        override fun setDeviceScaleFactor(scale: Double) {}
        override fun injectJavaScript(script: String, callback: ((String?) -> Unit)?) {}
        override fun dispose() {}
        override fun setNavigationCallback(callback: ((NavigationResult) -> Unit)?) {
            navigationCallback = callback
        }
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
        override fun setCookie(cookie: Cookie) {
            cookies.removeAll { it.name == cookie.name && it.domain == cookie.domain }
            cookies.add(cookie)
        }
        override fun getCookie(name: String, domain: String, callback: (Cookie?) -> Unit) {
            callback(cookies.find { it.name == name && it.domain == domain })
        }
        override fun getAllCookies(callback: (List<Cookie>) -> Unit) {
            callback(cookies.toList())
        }
        override fun deleteCookie(name: String, domain: String) {
            cookies.removeAll { it.name == name && it.domain == domain }
        }
        override fun deleteAllCookies() {
            cookies.clear()
        }
        override fun setExtraHttpHeaders(headers: Map<String, String>): Boolean {
            recordedExtraHttpHeaders = headers.toMap()
            return true
        }
        override fun setHttpCredentials(username: String?, password: String?): Boolean {
            recordedHttpCredentials = if (username != null && password != null) username to password else null
            return true
        }
        override fun interceptRequest(url: String, method: String, headers: Map<String, String>, onResult: (RouteAction?) -> Unit) {}

        private fun executeStorageScript(script: String): String? {
            val setItemRegex = Regex("""(localStorage|sessionStorage)\.setItem\('([^']*)',\s*'([^']*)'\)""")
            val getItemRegex = Regex("""return (localStorage|sessionStorage)\.getItem\('([^']*)'\)""")
            val removeItemRegex = Regex("""(localStorage|sessionStorage)\.removeItem\('([^']*)'\)""")
            val clearRegex = Regex("""(localStorage|sessionStorage)\.clear\(\)""")
            val getAllRegex = Regex("""JSON\.stringify\(items\)(?:\))?""")

            return when {
                setItemRegex.containsMatchIn(script) -> {
                    val match = setItemRegex.find(script)!!
                    val target = if (match.groupValues[1] == "localStorage") localStorage else sessionStorage
                    target[match.groupValues[2]] = match.groupValues[3]
                    "true"
                }
                getItemRegex.containsMatchIn(script) -> {
                    val match = getItemRegex.find(script)!!
                    val target = if (match.groupValues[1] == "localStorage") localStorage else sessionStorage
                    target[match.groupValues[2]] ?: "null"
                }
                removeItemRegex.containsMatchIn(script) -> {
                    val match = removeItemRegex.find(script)!!
                    val target = if (match.groupValues[1] == "localStorage") localStorage else sessionStorage
                    target.remove(match.groupValues[2])
                    "true"
                }
                clearRegex.containsMatchIn(script) -> {
                    val match = clearRegex.find(script)!!
                    val target = if (match.groupValues[1] == "localStorage") localStorage else sessionStorage
                    target.clear()
                    "true"
                }
                getAllRegex.containsMatchIn(script) -> {
                    val target = if (script.contains("localStorage.key")) localStorage else sessionStorage
                    target.entries.joinToString(prefix = "{", postfix = "}") { "\"${it.key}\":\"${it.value}\"" }
                }
                else -> null
            }
        }
    }
}
