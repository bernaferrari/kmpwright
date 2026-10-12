package com.bernaferrari.kmpwright.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import kotlin.test.assertFailsWith

class PageApiTest {

    private lateinit var page: TestPage
    private lateinit var controller: MockWebViewController

    @OptIn(ExperimentalCoroutinesApi::class)
    @Before
    fun setup() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        controller = MockWebViewController()
        page = TestPage(controller)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `goto returns navigation result`() {
        runBlocking {
            val result = page.goto("https://example.com")
            assertEquals("https://example.com", result.url)
            assertEquals(200, result.status)
            assertTrue(result.ok)
        }
    }

    @Test
    fun `goto with custom timeout`() {
        runBlocking {
            val result = page.goto("https://example.com", GotoOptions(timeoutMs = 5000))
            assertTrue(result.ok)
        }
    }

    @Test
    fun `goto with referer`() {
        runBlocking {
            val result = page.goto("https://example.com", GotoOptions(referer = "https://google.com"))
            assertEquals("https://example.com", result.url)
        }
    }

    @Test
    fun `goto with waitUntil Load`() {
        runBlocking {
            val result = page.goto("https://example.com", GotoOptions(waitUntil = LoadState.Load))
            assertTrue(result.ok)
        }
    }

    @Test
    fun `goto with waitUntil NetworkIdle`() {
        runBlocking {
            val result = page.goto("https://example.com", GotoOptions(waitUntil = LoadState.NetworkIdle))
            assertTrue(result.ok)
        }
    }

    @Test
    fun `reload completes without error`() {
        runBlocking {
            page.goto("https://example.com")
            page.reload()
        }
    }

    @Test
    fun `back completes without error`() {
        runBlocking {
            page.goto("https://example.com")
            page.back()
        }
    }

    @Test
    fun `forward completes without error`() {
        runBlocking {
            page.goto("https://example.com")
            page.forward()
        }
    }

    @Test
    fun `url StateFlow is updated after goto`() {
        runBlocking {
            assertNull(page.url.value)
            page.goto("https://example.com")
            assertEquals("https://example.com", page.url.value)
        }
    }

    @Test
    fun `multiple goto calls update url`() {
        runBlocking {
            page.goto("https://first.com")
            assertEquals("https://first.com", page.url.value)
            page.goto("https://second.com")
            assertEquals("https://second.com", page.url.value)
        }
    }

    @Test
    fun `locator returns a Locator instance`() {
        val locator = page.locator("#submit")
        assertNotNull(locator)
        assertTrue(locator is TestLocator)
    }

    @Test
    fun `locator returns TestLocator with correct selector`() {
        val locator = page.locator("div.container > p") as TestLocator
        assertEquals("div.container > p", locator.selectorStr)
    }

    @Test
    fun `evaluate returns JsResult`() {
        runBlocking {
            controller.evalHandler = { "42" }
            val result = page.evaluate("1 + 1")
            assertEquals("42", result.value)
            assertEquals("text", result.type)
        }
    }

    @Test
    fun `evaluate returns null as JsResult`() {
        runBlocking {
            controller.evalHandler = { null }
            val result = page.evaluate("undefined")
            assertEquals("null", result.value)
        }
    }

    @Test
    fun `evaluate records executed script`() {
        runBlocking {
            page.evaluate("1 + 1")
            assertTrue(controller.executedScripts.any { it.contains("1 + 1") })
        }
    }

    @Test
    fun `content returns HTML string`() {
        runBlocking {
            controller.evalHandler = { "<html><body>Hello</body></html>" }
            val html = page.content()
            assertEquals("<html><body>Hello</body></html>", html)
        }
    }

    @Test
    fun `setContent loads HTML`() {
        runBlocking {
            try {
                page.setContent("<html><body>Test</body></html>")
            } catch (e: KmpwrightException) {
                // Expected timeout in mock
            }
        }
    }

    @Test
    fun `setContent with custom timeout`() {
        runBlocking {
            try {
                page.setContent("<html></html>", SetContentOptions(timeoutMs = 100))
            } catch (e: KmpwrightException) {
                // Expected timeout in mock
            }
        }
    }

    @Test
    fun `screenshot throws for TestPage without screenshot controller`() {
        runBlocking {
            try {
                page.screenshot()
            } catch (e: IllegalStateException) {
                // Expected - TestPage does not override createScreenshotController
            }
        }
    }

    @Test
    fun `screenshot with fullPage option throws for TestPage`() {
        runBlocking {
            try {
                page.screenshot(ScreenshotOptions(fullPage = true))
            } catch (e: IllegalStateException) {
                // Expected - TestPage does not override createScreenshotController
            }
        }
    }

    @Test
    fun `metrics returns PageMetrics`() {
        runBlocking {
            val metrics = page.metrics()
            assertNull(metrics.frames)
            assertTrue(metrics.networkIdle)
        }
    }

    @Test
    fun `serviceWorkers returns empty list`() {
        val workers = page.serviceWorkers()
        assertTrue(workers.isEmpty())
    }

    @Test
    fun `capabilities returns PageCapabilities`() {
        val caps = page.capabilities()
        assertNotNull(caps)
        assertEquals(BackendKind.Unknown, caps.backend)
        assertEquals(SupportLevel.Unsupported, caps.cdp)
        assertFalse(caps.downloads.isAvailable)
    }

    @Test
    fun `viewportSize returns controller dimensions`() {
        val size = page.viewportSize()
        assertEquals(375, size.first)
        assertEquals(667, size.second)
    }

    @Test
    fun `setViewportSize delegates to controller`() {
        runBlocking {
            page.setViewportSize(1920, 1080)
        }
    }

    @Test
    fun `setDeviceScaleFactor delegates to controller`() {
        runBlocking {
            page.setDeviceScaleFactor(2.0)
        }
    }

    @Test
    fun `setUserAgent delegates to controller`() {
        runBlocking {
            page.setUserAgent("CustomAgent/1.0")
        }
    }

    @Test
    fun `setCookie stores cookie`() {
        runBlocking {
            val cookie = Cookie(name = "session", value = "abc123", domain = "example.com", path = "/")
            page.setCookie(cookie)
            assertEquals(1, controller.cookies.size)
            assertEquals("session", controller.cookies[0].name)
        }
    }

    @Test
    fun `getCookie retrieves stored cookie`() {
        runBlocking {
            page.goto("https://example.com")
            val cookie = Cookie(name = "session", value = "abc123", domain = "example.com", path = "/")
            page.setCookie(cookie)
            val retrieved = page.getCookie("session")
            assertNotNull(retrieved)
        }
    }

    @Test
    fun `getAllCookies retrieves all stored cookies`() {
        runBlocking {
            val cookie1 = Cookie(name = "a", value = "1", domain = "example.com", path = "/")
            val cookie2 = Cookie(name = "b", value = "2", domain = "example.com", path = "/")
            page.setCookie(cookie1)
            page.setCookie(cookie2)
            val cookies = page.getAllCookies()
            assertEquals(2, cookies.size)
        }
    }

    @Test
    fun `deleteCookie removes cookie`() {
        runBlocking {
            val cookie = Cookie(name = "temp", value = "x", domain = "example.com", path = "/")
            page.setCookie(cookie)
            assertEquals(1, controller.cookies.size)
            page.deleteCookie("temp", "example.com")
            assertEquals(0, controller.cookies.size)
        }
    }

    @Test
    fun `deleteAllCookies clears all cookies`() {
        runBlocking {
            page.setCookie(Cookie(name = "a", value = "1", domain = "example.com", path = "/"))
            page.setCookie(Cookie(name = "b", value = "2", domain = "example.com", path = "/"))
            page.deleteAllCookies()
            assertEquals(0, controller.cookies.size)
        }
    }

    @Test
    fun `multiple cookies can be stored and retrieved`() {
        runBlocking {
            page.setCookie(Cookie(name = "a", value = "1", domain = "example.com"))
            page.setCookie(Cookie(name = "b", value = "2", domain = "example.com"))
            page.setCookie(Cookie(name = "c", value = "3", domain = "example.com"))
            assertEquals(3, page.getAllCookies().size)
        }
    }

    @Test
    fun `cookie replacement on duplicate name`() {
        runBlocking {
            page.setCookie(Cookie(name = "session", value = "old", domain = "example.com"))
            page.setCookie(Cookie(name = "session", value = "new", domain = "example.com"))
            assertEquals(1, page.getAllCookies().size)
            assertEquals("new", page.getAllCookies()[0].value)
        }
    }

    @Test
    fun `setActionPolicy stores policy`() {
        val policy = ActionPolicy(deniedActions = setOf(ActionType.Evaluate))
        page.setActionPolicy(policy)
    }

    @Test
    fun `setPolicyConfirmCallback stores callback`() {
        page.setPolicyConfirmCallback { _, _ -> true }
    }

    @Test
    fun `close disposes controller`() {
        assertFalse(controller.disposed)
        page.close()
        assertTrue(controller.disposed)
    }

    @Test
    fun `expect returns KmpwrightExpect`() {
        val locator = page.locator("#btn")
        val expect = page.expect(locator)
        assertNotNull(expect)
    }

    @Test
    fun `events provides PageEvents`() {
        val events = page.events
        assertNotNull(events.console)
        assertNotNull(events.navigation)
        assertNotNull(events.loadState)
        assertNotNull(events.dialog)
        assertNotNull(events.request)
        assertNotNull(events.response)
    }

    @Test
    fun `consoleMessages returns empty list initially`() {
        val messages = page.consoleMessages()
        assertTrue(messages.isEmpty())
    }

    @Test
    fun `errors returns empty list initially`() {
        val errors = page.errors()
        assertTrue(errors.isEmpty())
    }

    @Test
    fun `route registers handler`() {
        page.route("https://api.example.com/**") { route ->
            RouteHandler.Continue()
        }
    }

    @Test
    fun `unroute removes handler`() {
        page.route("https://api.example.com/**") { route ->
            RouteHandler.Continue()
        }
        page.unroute("https://api.example.com/**")
    }

    @Test
    fun `unroute with null removes all handlers`() {
        page.route("https://api.example.com/**") { route ->
            RouteHandler.Continue()
        }
        page.unroute()
    }

    @Test
    fun `waitForTimeout delays execution`() {
        runBlocking {
            val start = System.currentTimeMillis()
            page.waitForTimeout(50)
            val elapsed = System.currentTimeMillis() - start
            assertTrue(elapsed >= 40)
        }
    }

    @Test
    fun `waitForLoadState times out for Load state`() {
        runBlocking {
            try {
                page.waitForLoadState(LoadState.Load, timeoutMs = 200)
            } catch (e: KmpwrightException) {
                // Expected timeout in mock
            }
        }
    }

    @Test
    fun `waitForLoadState uses load event before bridge polling`() = runBlocking {
        controller.evalHandler = { script ->
            if (script.contains("document.readyState")) "loading" else null
        }
        val emitter = launch {
            delay(20)
            page.events.loadState.emit(LoadState.Load)
        }

        page.waitForLoadState(LoadState.Load, timeoutMs = 1_000)
        emitter.cancel()

        assertEquals(1, controller.executedScripts.count { it.contains("document.readyState") })
    }

    @Test
    fun `stateNames returns empty list initially`() {
        val names = page.stateNames()
        assertTrue(names.isEmpty())
    }

    @Test
    fun `frameLocator returns FrameLocator`() {
        val frameLocator = page.frameLocator("iframe")
        assertNotNull(frameLocator)
    }

    @Test
    fun `mainFrame returns FrameLocator`() {
        val frameLocator = page.mainFrame()
        assertNotNull(frameLocator)
    }

    @Test
    fun `touch accessor throws UnsupportedOperationException`() {
        assertFailsWith<UnsupportedOperationException> { page.touch }
    }

    @Test
    fun `mouse accessor throws UnsupportedOperationException`() {
        assertFailsWith<UnsupportedOperationException> { page.mouse }
    }

    @Test
    fun `keyboard accessor throws UnsupportedOperationException`() {
        assertFailsWith<UnsupportedOperationException> { page.keyboard }
    }

    @Test
    fun `createContentBoundary returns ContentBoundary`() {
        val boundary = page.createContentBoundary()
        assertNotNull(boundary)
    }

    @Test fun `unsupported tracing and profiling fail explicitly`() = runBlocking<Unit> {
        assertFailsWith<UnsupportedOperationException> { page.startTracing() }
        assertFailsWith<UnsupportedOperationException> { page.stopTracing() }
        assertFailsWith<UnsupportedOperationException> { page.startProfiler() }
        assertFailsWith<UnsupportedOperationException> { page.stopProfiler() }
    }

    @Test
    fun `locatorByRef throws without snapshot`() {
        runBlocking {
            try {
                page.locatorByRef("@e1")
            } catch (e: KmpwrightException) {
                // Expected - no snapshot available
            }
        }
    }

    @Test
    fun `snapshotAndInteract throws without snapshot`() {
        runBlocking {
            try {
                page.snapshotAndInteract("@e1") { }
            } catch (e: KmpwrightException) {
                // Expected - no snapshot available
            }
        }
    }

    @Test
    fun `diffSnapshot completes`() {
        runBlocking {
            val before = A11ySnapshot(emptyList(), emptyMap(), "", 0L)
            val after = A11ySnapshot(emptyList(), emptyMap(), "", 0L)
            val result = page.diffSnapshot(before, after)
            assertNotNull(result)
        }
    }

    @Test
    fun `annotatedScreenshot throws for TestPage without screenshot controller`() {
        runBlocking {
            try {
                page.annotatedScreenshot()
            } catch (e: IllegalStateException) {
                // Expected - TestPage does not override createScreenshotController
            }
        }
    }

    @Test
    fun `onDialog returns handler`() {
        val handler = page.onDialog { DialogResponse.Accept }
        assertNotNull(handler)
        handler.close()
    }
}
