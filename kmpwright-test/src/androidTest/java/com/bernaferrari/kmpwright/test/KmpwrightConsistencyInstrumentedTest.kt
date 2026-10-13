package com.bernaferrari.kmpwright.test

import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bernaferrari.kmpwright.core.AndroidPlatformContext
import com.bernaferrari.kmpwright.core.Browser
import com.bernaferrari.kmpwright.core.Cookie
import com.bernaferrari.kmpwright.core.Devices
import com.bernaferrari.kmpwright.core.DialogType
import com.bernaferrari.kmpwright.core.KmpwrightConfig
import com.bernaferrari.kmpwright.core.KmpwrightException
import com.bernaferrari.kmpwright.core.KmpwrightUnsupportedException
import com.bernaferrari.kmpwright.core.NetworkRequestState
import com.bernaferrari.kmpwright.core.Page
import com.bernaferrari.kmpwright.core.Rectangle
import com.bernaferrari.kmpwright.core.ScreenshotFormat
import com.bernaferrari.kmpwright.core.ScreenshotOptions
import com.bernaferrari.kmpwright.core.TimeoutConfig
import com.bernaferrari.kmpwright.core.waitForDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.ClassRule
import org.junit.Test
import org.junit.runner.RunWith

/** Cross-backend consistency contracts on a real Android WebView (device RQCY104BG8X in CI runs). */
@RunWith(AndroidJUnit4::class)
class KmpwrightConsistencyInstrumentedTest {

    companion object {
        @ClassRule
        @JvmField
        val host = ActivityScenarioRule(WebViewHarnessActivity::class.java)
    }

    private lateinit var browser: Browser
    private lateinit var page: Page

    private fun open(config: KmpwrightConfig = KmpwrightConfig(), javaScriptEnabled: Boolean = true) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            if (this::browser.isInitialized) browser.dispose()
        }
        host.scenario.onActivity { activity ->
            val webView = activity.resetWebView()
            webView.settings.javaScriptEnabled = javaScriptEnabled
            browser = Browser(AndroidPlatformContext(activity, webView), config)
            page = browser.page()
        }
    }

    @Test fun frameAmbiguityAndClosedBrowserAreRejected() = runPageTest {
        page.setContent("<div id=frames><iframe srcdoc='<button>First</button>'></iframe><iframe srcdoc='<button>Second</button>'></iframe></div>")
        for (frame in listOf(page.frameLocator("iframe"), page.locator("#frames").frameLocator("iframe"))) {
            val failure = expect<KmpwrightException> { frame.locator("button").click(com.bernaferrari.kmpwright.core.ClickOptions(timeoutMs = 300)) }
            assertTrue(failure.message!!, failure.message!!.contains("Strict", ignoreCase = true))
        }
        browser.close()
        expect<IllegalStateException> { browser.newPage() }
        assertFalse(browser.isConnected.value)
    }

    @Test fun routeHeaderOverrideRemovesOmittedHeaders() = runPageTest {
        LocalHttpServer { request ->
            LocalHttpServer.FixtureResponse(200, "OK", if (request.path == "/api/header") request.header("X-Test") ?: "absent" else "<p>Fixture</p>")
        }.use { server ->
            page.goto(server.origin)
            page.route(Regex(".*/api/header")) { route ->
                route.resume(headers = route.request.headers.filterKeys { !it.equals("X-Test", ignoreCase = true) })
            }
            page.evaluate("window.answer=null; fetch('/api/header', {headers:{'X-Test':'secret'}}).then(function(r){return r.text();}).then(function(v){window.answer=v;}); 1")
            page.waitForFunction("window.answer !== null", timeoutMs = 3_000)
            assertEquals("absent", page.evaluate("window.answer").value)
        }
    }

    @Test fun cancelledKeyboardPressReleasesModifiers() = runPageTest {
        page.setContent("<input id=editor><script>window.keys=[]; ['keydown','keyup'].forEach(function(k){document.addEventListener(k,function(e){keys.push(k+':'+e.key);});});</script>")
        page.locator("#editor").focus()
        val press = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            page.keyboard.press("Shift+A", com.bernaferrari.kmpwright.core.PressOptions(delayMs = 60_000))
        }
        page.waitForFunction("keys.includes('keydown:A')", timeoutMs = 2_000)
        press.cancel()
        press.join()
        assertEquals("true", page.evaluate("keys.includes('keyup:A') && keys.includes('keyup:Shift')").value)
    }

    @Before
    fun setUp() = open()

    @After
    fun tearDown() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            if (this::browser.isInitialized) browser.dispose()
        }
    }

    private fun runPageTest(block: suspend CoroutineScope.() -> Unit) = runBlocking(Dispatchers.Main) { block() }

    private suspend inline fun <reified T : Throwable> expect(noinline block: suspend () -> Unit): T {
        try {
            block()
        } catch (error: Throwable) {
            if (error is T) return error
            throw AssertionError("Expected ${T::class.simpleName} but got $error", error)
        }
        fail("Expected ${T::class.simpleName}")
        throw IllegalStateException()
    }

    @Test
    fun waitForDialogReceivesDialogsWithoutAHandler() = runPageTest {
        page.setContent("<p>dialogs</p>")
        val dialog = page.waitForDialog { evaluate("setTimeout(function() { window.answer = confirm('Sure?'); }, 0); 1") }
        assertEquals(DialogType.Confirm, dialog.type)
        assertEquals("Sure?", dialog.message)
        var answer = "undefined"
        withTimeout(5_000) { while (answer == "undefined") { delay(20); answer = page.evaluate("String(window.answer)").value } }
        assertEquals("dismissed like Playwright when nobody accepts it", "false", answer)
    }

    @Test
    fun clearCookiesRemovesHostOnlyPathScopedAndDomainCookies() = runPageTest {
        page.route(Regex(".*cookies\\.kmpwright\\.test.*")) { it.fulfill(body = "<p>cookies</p>", contentType = "text/html") }
        assertTrue(page.goto("https://cookies.kmpwright.test/a/b/index.html").ok)
        // A host-only cookie scoped to /a/b written by the page, plus a domain cookie and a root cookie.
        page.evaluate("document.cookie = 'pathy=1; path=/a/b'; 1")
        page.addCookies(listOf(
            Cookie("dom", "2", ".kmpwright.test", hostOnly = false),
            Cookie("root", "3", "cookies.kmpwright.test")
        ))
        val before = page.cookies().associateBy { it.name }
        assertEquals(before.toString(), setOf("pathy", "dom", "root"), before.keys)
        assertTrue(before.getValue("pathy").hostOnly)
        assertEquals("/a/b", before.getValue("pathy").path)
        assertFalse(before.getValue("dom").hostOnly)

        page.clearCookies(name = "pathy")
        assertEquals(setOf("dom", "root"), page.cookies().map { it.name }.toSet())
        assertEquals("pathy is gone from the document too", "", page.evaluate("document.cookie.split('; ').filter(function(c) { return c.indexOf('pathy=') === 0; }).join()").value)
        page.clearCookies(name = "dom")
        assertEquals(setOf("root"), page.cookies().map { it.name }.toSet())
        page.clearCookies()
        assertTrue(page.cookies().isEmpty())
    }

    @Test
    fun routeFulfillContentTypeFollowsPlaywrightPrecedence() = runPageTest {
        page.route(Regex(".*net\\.kmpwright\\.test/index.*")) { it.fulfill(body = "<p>net</p>", contentType = "text/html") }
        page.route(Regex(".*/api/override")) { it.fulfill(headers = mapOf("Content-Type" to "text/plain"), body = "x", contentType = "text/html") }
        page.route(Regex(".*/api/json")) { it.fulfill(json = """{"ok":true}""") }
        page.route(Regex(".*/api/header")) { it.fulfill(headers = mapOf("Content-Type" to "text/csv"), body = "a,b") }
        page.route(Regex(".*/api/none")) { it.fulfill(body = "plain") }
        assertTrue(page.goto("https://net.kmpwright.test/index.html").ok)
        suspend fun contentType(path: String): String {
            page.evaluate("window.ct = undefined; fetch('$path').then(function(r) { window.ct = String(r.headers.get('content-type')); }, function(e) { window.ct = 'error:' + e; }); 1")
            var value = "undefined"
            withTimeout(5_000) { while (value == "undefined") { delay(20); value = page.evaluate("String(window.ct)").value } }
            return value
        }
        assertEquals("text/html", contentType("/api/override"))
        assertEquals("application/json", contentType("/api/json"))
        assertEquals("text/csv", contentType("/api/header"))
        assertFalse(contentType("/api/none").contains("json"))
        val document = page.network.requests("index.html").first()
        assertEquals("document", document.request.resourceType)
    }

    @Test
    fun passThroughRequestsGetBestEffortResponses() = runPageTest {
        LocalHttpServer { request ->
            if (request.path.startsWith("/missing")) LocalHttpServer.FixtureResponse(404, "Not Found", "nope", contentType = "text/plain")
            else LocalHttpServer.FixtureResponse(200, "OK", "<title>Pass</title><p>pass-through</p>")
        }.use { server ->
            assertTrue(page.goto("${server.origin}/index.html").ok)
            page.evaluate("fetch('/missing').catch(function() {}); 1")
            withTimeout(10_000) {
                while (page.network.requests("missing").firstOrNull()?.response == null) delay(50)
            }
            val document = page.network.requests("index.html").first()
            assertEquals("document", document.request.resourceType)
            assertEquals(200, document.response?.status)
            assertEquals(NetworkRequestState.Finished, document.state)
            val missing = page.network.requests("missing").first()
            assertEquals(404, missing.response?.status)
            assertEquals("other", missing.request.resourceType)
        }
    }

    @Test
    fun screenshotsHonorFormatsAndFailLoudlyOnUnsupportedOptions() = runPageTest {
        page.setContent("<body style='margin:0'><div id=box style='width:100px;height:50px;background:#ff0000'></div></body>")
        val jpeg = page.screenshot(ScreenshotOptions(format = ScreenshotFormat.JPEG, quality = 60))
        assertEquals(0xFF, jpeg.bytes[0].toInt() and 0xFF)
        assertEquals(0xD8, jpeg.bytes[1].toInt() and 0xFF)
        val webp = page.locator("#box").screenshot(ScreenshotOptions(format = ScreenshotFormat.WEBP))
        assertEquals("RIFF", webp.bytes.copyOfRange(0, 4).decodeToString())
        assertEquals("WEBP", webp.bytes.copyOfRange(8, 12).decodeToString())
        expect<KmpwrightUnsupportedException> { page.screenshot(ScreenshotOptions(omitBackground = true)) }
        expect<KmpwrightUnsupportedException> { page.locator("#box").screenshot(ScreenshotOptions(omitBackground = true)) }
        expect<KmpwrightException> { page.screenshot(ScreenshotOptions(clip = Rectangle(50_000.0, 50_000.0, 10.0, 10.0))) }
        expect<IllegalArgumentException> { ScreenshotOptions(fullPage = true, clip = Rectangle(0.0, 0.0, 1.0, 1.0)) }
    }

    @Test
    fun emulateDeviceAppliesWhatWebViewSupportsWithoutThrowing() = runPageTest {
        page.setContent("<p>device</p>")
        page.emulateDevice(Devices.iPhone15)
        assertTrue(page.evaluate("navigator.userAgent").value.contains("iPhone"))
        assertTrue(page.viewportSize().width > 0)
    }

    @Test
    fun defaultPdfOptionsWork() = runPageTest {
        page.setContent("<p>PDF</p>")
        assertEquals("%PDF-", page.pdf().bytes.copyOfRange(0, 5).decodeToString())
    }

    @Test
    fun hostWebViewGetsJavaScriptEnabledOnAttach() {
        open(KmpwrightConfig(userAgent = "KmpwrightHost/1.0"), javaScriptEnabled = false)
        runPageTest {
            page.setContent("<p id=p>js</p>")
            assertEquals("2", page.evaluate("1 + 1").value)
            assertEquals("KmpwrightHost/1.0", page.evaluate("navigator.userAgent").value)
            assertEquals(1, page.locator("#p").count())
        }
    }

    @Test
    fun routeHandlerBoundComesFromConfig() {
        open(KmpwrightConfig(timeouts = TimeoutConfig(handler = 300)))
        runPageTest {
            page.route(Regex(".*slow\\.kmpwright\\.test/api.*")) { delay(5_000); it.fulfill(body = "late") }
            page.route(Regex(".*slow\\.kmpwright\\.test/index.*")) { it.fulfill(body = "<p>slow</p>", contentType = "text/html") }
            assertTrue(page.goto("https://slow.kmpwright.test/index.html").ok)
            val started = System.nanoTime()
            page.evaluate("window.r = undefined; fetch('/api').then(function() { window.r = 'ok'; }, function() { window.r = 'failed'; }); 1")
            var value = "undefined"
            withTimeout(5_000) { while (value == "undefined") { delay(20); value = page.evaluate("String(window.r)").value } }
            assertEquals("failed", value)
            assertTrue((System.nanoTime() - started) / 1_000_000 < 4_000)
        }
    }
}
