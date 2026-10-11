package com.bernaferrari.kmpwright.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LocalStorageTest {

    private lateinit var page: TestPage

    @Before
    fun setup() {
        page = TestPage()
    }

    @Test
    fun `setLocalStorage and getLocalStorage roundtrip`() {
        runBlocking {
            page.setLocalStorage("key1", "value1")
            val result = page.getLocalStorage("key1")
            assertEquals("value1", result)
        }
    }

    @Test
    fun `getLocalStorage returns null for missing key`() {
        runBlocking {
            val result = page.getLocalStorage("nonexistent")
            assertNull(result)
        }
    }

    @Test
    fun `setLocalStorage handles keys and values with special characters`() {
        runBlocking {
            page.setLocalStorage("key_with-dash.dot", "value123")
            val result = page.getLocalStorage("key_with-dash.dot")
            assertEquals("value123", result)
        }
    }

    @Test
    fun `getAllLocalStorage returns all items`() {
        runBlocking {
            page.setLocalStorage("a", "1")
            page.setLocalStorage("b", "2")
            page.setLocalStorage("c", "3")
            val all = page.getAllLocalStorage()
            assertEquals(3, all.size)
            assertEquals("1", all["a"])
            assertEquals("2", all["b"])
            assertEquals("3", all["c"])
        }
    }

    @Test
    fun `getAllLocalStorage returns empty map when empty`() {
        runBlocking {
            val all = page.getAllLocalStorage()
            assertTrue(all.isEmpty())
        }
    }

    @Test
    fun `removeLocalStorage removes item`() {
        runBlocking {
            page.setLocalStorage("toRemove", "bye")
            assertEquals("bye", page.getLocalStorage("toRemove"))
            page.removeLocalStorage("toRemove")
            assertNull(page.getLocalStorage("toRemove"))
        }
    }

    @Test
    fun `clearLocalStorage removes all items`() {
        runBlocking {
            page.setLocalStorage("x", "1")
            page.setLocalStorage("y", "2")
            page.clearLocalStorage()
            assertNull(page.getLocalStorage("x"))
            assertNull(page.getLocalStorage("y"))
            assertTrue(page.getAllLocalStorage().isEmpty())
        }
    }

    @Test
    fun `localStorage handles empty string value`() {
        runBlocking {
            page.setLocalStorage("empty", "")
            assertEquals("", page.getLocalStorage("empty"))
        }
    }

    // Test helper with a mock controller that simulates localStorage via a HashMap
    private class TestPage : BasePage(
        MockLocalStorageController(),
        KmpwrightConfig()
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

    private class MockLocalStorageController : WebViewController {
        val storage = mutableMapOf<String, String>()

        override fun loadUrl(url: String) {}
        override fun loadHtml(content: String, baseUrl: String?) {}
        override fun evaluateJavaScript(script: String, callback: (String?) -> Unit) {
            val result = executeLocalStorageJs(script)
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

        fun executeLocalStorageJs(script: String): String? {
            val setItemRegex = Regex("""localStorage\.setItem\('([^']*)',\s*'([^']*)'\)""")
            val getItemRegex = Regex("""return localStorage\.getItem\('([^']*)'\)""")
            val removeItemRegex = Regex("""localStorage\.removeItem\('([^']*)'\)""")
            val clearRegex = Regex("""localStorage\.clear\(\)""")
            val getAllRegex = Regex("""JSON\.stringify\(items\)(?:\))?""")

            when {
                setItemRegex.containsMatchIn(script) -> {
                    val match = setItemRegex.find(script)!!
                    storage[match.groupValues[1]] = match.groupValues[2]
                    return "true"
                }
                getItemRegex.containsMatchIn(script) -> {
                    val match = getItemRegex.find(script)!!
                    return storage[match.groupValues[1]] ?: "null"
                }
                removeItemRegex.containsMatchIn(script) -> {
                    val match = removeItemRegex.find(script)!!
                    storage.remove(match.groupValues[1])
                    return "true"
                }
                clearRegex.containsMatchIn(script) -> {
                    storage.clear()
                    return "true"
                }
                getAllRegex.containsMatchIn(script) -> {
                    val items = storage.entries.joinToString(",") { "\"${it.key}\":\"${it.value}\"" }
                    return "{$items}"
                }
            }
            return null
        }
    }
}
