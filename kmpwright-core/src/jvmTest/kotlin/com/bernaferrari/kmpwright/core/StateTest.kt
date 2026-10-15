package com.bernaferrari.kmpwright.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class StateTest {

    private lateinit var controller: MockStateController
    private lateinit var page: TestPage

    @Before
    fun setup() {
        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        Dispatchers.setMain(Dispatchers.Unconfined)
        controller = MockStateController()
        page = TestPage(controller)
    }

    @After
    fun tearDown() {
        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        Dispatchers.resetMain()
    }

    @Test
    fun `storageState and setStorageState roundtrip`() {
        runBlocking {
            controller.currentUrl = "https://example.com/app"
            controller.cookies.add(Cookie("session", "abc", "example.com"))
            controller.localStorage["key1"] = "val1"
            controller.sessionStorage["skey1"] = "sval1"

            val saved = page.storageState()

            controller.cookies.clear()
            controller.localStorage.clear()
            controller.sessionStorage.clear()

            page.setStorageState(saved)
            assertEquals(1, controller.cookies.size)
            assertEquals("abc", controller.cookies[0].value)
            assertEquals("val1", controller.localStorage["key1"])
            assertEquals("sval1", controller.sessionStorage["skey1"])
        }
    }

    @Test
    fun `multiple states are independent`() {
        runBlocking {
            controller.currentUrl = "https://state1.kmpwright.test/app"
            controller.localStorage["a"] = "1"
            val state1 = page.storageState()

            controller.currentUrl = "https://state2.kmpwright.test/app"
            controller.localStorage.clear()
            controller.localStorage["b"] = "2"
            val state2 = page.storageState()

            controller.localStorage.clear()
            page.setStorageState(state1)
            assertEquals("1", controller.localStorage["a"])

            controller.localStorage.clear()
            page.setStorageState(state2)
            assertEquals("2", controller.localStorage["b"])
        }
    }

    @Test
    fun `setStorageState navigates back to saved url before restoring storage`() {
        runBlocking {
            controller.currentUrl = "https://saved.kmpwright.test/app"
            controller.localStorage["token"] = "abc"
            controller.sessionStorage["tab"] = "1"
            val saved = page.storageState()

            controller.currentUrl = "https://other.kmpwright.test/dashboard"
            controller.localStorage.clear()
            controller.sessionStorage.clear()

            page.setStorageState(saved)
            assertEquals("https://saved.kmpwright.test/app", controller.currentUrl)
            assertEquals("abc", controller.localStorage["token"])
            assertEquals("1", controller.sessionStorage["tab"])
            assertEquals("https://saved.kmpwright.test/app", controller.loadedUrls.last())
        }
    }

    @Test
    fun `setStorageState refuses to restore storage without a restorable url`() {
        runBlocking {
            controller.localStorage["token"] = "abc"
            val saved = page.storageState()

            controller.localStorage.clear()

            try {
                page.setStorageState(saved)
                org.junit.Assert.fail("Expected loadState to reject storage restore without a navigable URL")
            } catch (e: KmpwrightException) {
                assertTrue(e.message?.contains("localStorage/sessionStorage") == true)
            }

            assertNull(controller.localStorage["token"])
        }
    }

    private class TestPage(controller: MockStateController) : BasePage(
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

    private class MockStateController : WebViewController {
        val cookies = mutableListOf<Cookie>()
        val localStorage = mutableMapOf<String, String>()
        val sessionStorage = mutableMapOf<String, String>()
        val loadedUrls = mutableListOf<String>()
        var currentUrl: String = "about:blank"
        private var navigationCallback: ((NavigationResult) -> Unit)? = null

        override fun loadUrl(url: String) {
            currentUrl = url
            loadedUrls += url
            navigationCallback?.invoke(NavigationResult(url, 200, true))
        }
        override fun loadHtml(content: String, baseUrl: String?) {}
        override fun evaluateJavaScript(script: String, callback: (String?) -> Unit) {
            callback(executeJs(script))
        }

        fun executeJs(script: String): String? {
            if (script.contains("Saved state origin")) {
                val local = kotlinx.serialization.json.Json.decodeFromString<String>(Regex("(?m)^\\s*var localJson = (.*);$").find(script)!!.groupValues[1])
                val session = kotlinx.serialization.json.Json.decodeFromString<String>(Regex("(?m)^\\s*var sessionJson = (.*);$").find(script)!!.groupValues[1])
                localStorage.clear(); sessionStorage.clear()
                localStorage.putAll(kotlinx.serialization.json.Json.decodeFromString<Map<String, String>>(local))
                sessionStorage.putAll(kotlinx.serialization.json.Json.decodeFromString<Map<String, String>>(session))
                return "true"
            }
            val setItemRegex = Regex("""(localStorage|sessionStorage)\.setItem\('([^']*)',\s*'([^']*)'\)""")
            val getItemRegex = Regex("""return (localStorage|sessionStorage)\.getItem\('([^']*)'\)""")
            val removeItemRegex = Regex("""(localStorage|sessionStorage)\.removeItem\('([^']*)'\)""")
            val clearRegex = Regex("""(localStorage|sessionStorage)\.clear\(\)""")
            val getAllRegex = Regex("""JSON\.stringify\(items\)(?:\))?""")

            val storage = when {
                setItemRegex.containsMatchIn(script) -> {
                    val match = setItemRegex.find(script)!!
                    val target = if (match.groupValues[1] == "localStorage") localStorage else sessionStorage
                    target[match.groupValues[2]] = match.groupValues[3]
                    return "true"
                }
                getItemRegex.containsMatchIn(script) -> {
                    val match = getItemRegex.find(script)!!
                    val target = if (match.groupValues[1] == "localStorage") localStorage else sessionStorage
                    return target[match.groupValues[2]] ?: "null"
                }
                removeItemRegex.containsMatchIn(script) -> {
                    val match = removeItemRegex.find(script)!!
                    val target = if (match.groupValues[1] == "localStorage") localStorage else sessionStorage
                    target.remove(match.groupValues[2])
                    return "true"
                }
                clearRegex.containsMatchIn(script) -> {
                    val match = clearRegex.find(script)!!
                    val target = if (match.groupValues[1] == "localStorage") localStorage else sessionStorage
                    target.clear()
                    return "true"
                }
                getAllRegex.containsMatchIn(script) -> {
                    // Determine which storage from the for-loop variable
                    val target = if (script.contains("localStorage.key")) localStorage else sessionStorage
                    val items = target.entries.joinToString(",") { "\"${it.key}\":\"${it.value}\"" }
                    return "{$items}"
                }
                else -> return null
            }
        }
        override fun goBack() {}
        override fun goForward() {}
        override fun reload() {}
        override fun getUrl(): String? = currentUrl
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
        override fun addRouteHandler(urlPattern: String, handler: suspend (Route) -> Unit) {}
        override fun removeRouteHandler(urlPattern: String) {}
        override fun removeAllRouteHandlers() {}

        override fun setCookie(cookie: Cookie) {
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
    }
}
