package com.bernaferrari.kmpwright.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionStorageTest {

    private val controller = MockStorageController()

    private val page = TestPage(controller)

    @Test
    fun `set and get roundtrip`() {
        runBlocking {
            page.sessionStorage.set("key1", "value1")
            val result = page.sessionStorage.get("key1")
            assertEquals("value1", result)
        }
    }

    @Test
    fun `returns null for missing key`() {
        runBlocking {
            val result = page.sessionStorage.get("nonexistent")
            assertNull(result)
        }
    }

    @Test
    fun `getAll returns all items`() {
        runBlocking {
            page.sessionStorage.set("a", "1")
            page.sessionStorage.set("b", "2")
            val all = page.sessionStorage.entries()
            assertEquals(2, all.size)
            assertEquals("1", all["a"])
            assertEquals("2", all["b"])
        }
    }

    @Test
    fun `getAll returns empty when empty`() {
        runBlocking {
            val all = page.sessionStorage.entries()
            assertTrue(all.isEmpty())
        }
    }

    @Test
    fun `remove deletes item`() {
        runBlocking {
            page.sessionStorage.set("x", "bye")
            assertEquals("bye", page.sessionStorage.get("x"))
            page.sessionStorage.remove("x")
            assertNull(page.sessionStorage.get("x"))
        }
    }

    @Test
    fun `clear removes all items`() {
        runBlocking {
            page.sessionStorage.set("a", "1")
            page.sessionStorage.set("b", "2")
            page.sessionStorage.clear()
            assertTrue(page.sessionStorage.entries().isEmpty())
        }
    }

    // Reuse same mock as localStorage but for sessionStorage JS
    private class TestPage(controller: MockStorageController) : BasePage(
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

    private class MockStorageController : WebViewController {
        val storage = mutableMapOf<String, String>()

        override fun loadUrl(url: String) {}
        override fun loadHtml(content: String, baseUrl: String?) {}
        override fun evaluateJavaScript(script: String, callback: (String?) -> Unit) {
            callback(executeJs(script))
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

        fun executeJs(script: String): String? {
            val setItemRegex = Regex("""(localStorage|sessionStorage)\.setItem\('([^']*)',\s*'([^']*)'\)""")
            val getItemRegex = Regex("""(localStorage|sessionStorage)\.getItem\('([^']*)'\)""")
            val removeItemRegex = Regex("""(localStorage|sessionStorage)\.removeItem\('([^']*)'\)""")
            val clearRegex = Regex("""(localStorage|sessionStorage)\.clear\(\)""")
            val getAllRegex = Regex("""JSON\.stringify\(items\)(?:\))?""")

            when {
                setItemRegex.containsMatchIn(script) -> {
                    val match = setItemRegex.find(script)!!
                    storage[match.groupValues[2]] = match.groupValues[3]
                    return "true"
                }
                getItemRegex.containsMatchIn(script) -> {
                    val match = getItemRegex.find(script)!!
                    return storage[match.groupValues[2]] ?: "null"
                }
                removeItemRegex.containsMatchIn(script) -> {
                    val match = removeItemRegex.find(script)!!
                    storage.remove(match.groupValues[2])
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
