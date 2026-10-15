package com.bernaferrari.kmpwright.core

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.test.assertFailsWith

/**
 * Cross-backend consistency contracts checked against real Chromium (run with
 * KMPWRIGHT_TEST_CHROME=/path/to/chrome); the pure-Kotlin parts run everywhere.
 */
class CdpConsistencyBrowserTest {

    // ------------------------------------------------------------- pure contracts

    @Test
    fun `fullPage and clip are mutually exclusive like Playwright`() {
        val error = assertFailsWith<IllegalArgumentException> {
            runBlocking { TestPage().screenshot(fullPage = true, clip = Rectangle(0.0, 0.0, 1.0, 1.0)) }
        }
        assertEquals("fullPage and clip are mutually exclusive", error.message)
    }

    @Test
    fun `clips are trimmed to the viewport and fail when entirely outside`() {
        assertEquals(Rectangle(0.0, 10.0, 50.0, 90.0), trimScreenshotClip(Rectangle(-10.0, 10.0, 60.0, 200.0), 100.0, 100.0))
        assertFailsWith<KmpwrightException> { trimScreenshotClip(Rectangle(200.0, 0.0, 10.0, 10.0), 100.0, 100.0) }
    }

    @Test
    fun `route fulfill resolves content type with Playwright precedence`() {
        // contentType wins over headers.
        assertEquals("text/html", buildFulfillAction(200, mapOf("Content-Type" to "text/plain"), "x", null, "text/html", null).contentType)
        // json sets application/json, also over a header.
        val json = buildFulfillAction(200, mapOf("content-type" to "text/plain"), null, null, null, "{\"a\":1}")
        assertEquals("application/json", json.contentType)
        assertEquals("{\"a\":1}", json.body)
        // headers are used when nothing else is set; names are lower-cased.
        val headers = buildFulfillAction(200, mapOf("Content-Type" to "image/png", "X-A" to "1"), "x", null, null, null)
        assertEquals("image/png", headers.contentType)
        assertEquals("1", headers.headers["x-a"])
        // nothing set: no content type (the browser sniffs), and a content-length is added.
        val plain = buildFulfillAction(200, emptyMap(), "hello", null, null, null)
        assertNull(plain.contentType)
        assertEquals("5", plain.headers["content-length"])
        assertFailsWith<IllegalArgumentException> { buildFulfillAction(200, emptyMap(), "x", null, null, "{}") }
    }

    @Test
    fun `postDataJSON parses JSON and form bodies`() {
        val json = Request("https://x", "POST", mapOf("Content-Type" to "application/json"), postData = """{"a":[1,2]}""").postDataJSON()
        assertEquals("[1,2]", (json as JsonObject)["a"].toString())
        val form = Request("https://x", "POST", mapOf("content-type" to "application/x-www-form-urlencoded"), postData = "a=1+2&b=%C3%A9").postDataJSON() as JsonObject
        assertEquals("1 2", form["a"]!!.jsonPrimitive.content)
        assertEquals("é", form["b"]!!.jsonPrimitive.content)
        assertNull(Request("https://x").postDataJSON())
        assertFailsWith<KmpwrightException> { Request("https://x", "POST", postData = "not json").postDataJSON() }
    }

    @Test
    fun `visual diff refuses images it cannot decode instead of reporting a mismatch`() = runBlocking {
        val page = TestPage(MockWebViewController())
        val png = java.io.ByteArrayOutputStream().also {
            ImageIO.write(java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_ARGB), "png", it)
        }.toByteArray()
        val webp = "RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".encodeToByteArray()
        val error = assertFailsWith<KmpwrightException> { page.visualDiff(ScreenshotResult(png, 2, 2), ScreenshotResult(webp, 2, 2)) }
        assertTrue(error.message!!, error.message!!.contains("WEBP"))
        assertTrue(page.visualDiff(ScreenshotResult(png, 2, 2), ScreenshotResult(png, 2, 2)).match)
    }

    @Test fun `ambiguous frame owners never choose the first match`() = withChrome { browser, _ ->
        val page = browser.newPage()
        try {
            page.setContent("<div id=frames><iframe srcdoc='<button>First</button>'></iframe><iframe srcdoc='<button>Second</button>'></iframe></div>")
            for (frame in listOf(page.frameLocator("iframe"), page.locator("#frames").frameLocator("iframe"))) {
                val error = assertFailsWith<KmpwrightException> { frame.locator("button").click(timeoutMs = 300) }
                assertTrue(error.message!!, error.message!!.contains("Strict", ignoreCase = true))
            }
            assertEquals("Second", page.frameLocator("iframe:nth-of-type(2)").locator("button").innerText())
        } finally { page.close() }
    }

    @Test fun `clearData clears navigated and closed origins without touching other contexts`() = withChrome { browser, origin ->
        val context = browser.newContext()
        val other = browser.newContext()
        try {
            val a = context.newPage()
            val independent = other.newPage()
            independent.goto(origin)
            independent.localStorage.set("owner", "independent")
            a.goto(origin)
            a.localStorage.set("secret", "first")
            val second = origin.replace("127.0.0.1", "localhost")
            a.goto(second)
            a.localStorage.set("secret", "second")
            a.close()
            assertTrue(context.pages().isEmpty())
            context.clearData()
            val fresh = context.newPage()
            fresh.goto(origin)
            assertNull(fresh.localStorage.get("secret"))
            fresh.goto(second)
            assertNull(fresh.localStorage.get("secret"))
            assertEquals("independent", independent.localStorage.get("owner"))
            fresh.close()
            context.close()
            assertFailsWith<IllegalStateException> { context.newPage() }
        } finally { context.close(); other.close() }
    }

    @Test fun `saved storage never follows a cross origin redirect and empty state clears`() = withChrome { browser, origin ->
        val page = browser.newPage()
        page.goto(origin)
        page.localStorage.set("own", "preserve")
        val state = StorageState("$origin/redirect-cross", emptyList(), mapOf("token" to "private"), mapOf("session" to "private"))
        assertFailsWith<KmpwrightException> { page.setStorageState(state) }
        assertNull(page.localStorage.get("token"))
        assertNull(page.sessionStorage.get("session"))
        page.goto(origin)
        assertEquals("preserve", page.localStorage.get("own"))
        page.setStorageState(StorageState(origin, emptyList(), mapOf("__proto__" to "literal", "quoted'key" to "value;\nline"), emptyMap()))
        assertEquals("literal", page.localStorage.get("__proto__"))
        val captured = page.storageState()
        assertEquals("literal", captured.localStorage["__proto__"])
        page.localStorage.clear()
        page.setStorageState(captured)
        assertEquals("literal", page.localStorage.get("__proto__"))
        assertEquals("value;\nline", page.localStorage.get("quoted'key"))
        page.setStorageState(StorageState("$origin/redirect-same", emptyList(), emptyMap(), emptyMap()))
        assertTrue(page.localStorage.entries().isEmpty())
        assertTrue(page.sessionStorage.entries().isEmpty())
        page.close()
    }

    @Test fun `state round trip keeps multi origin cookies and contexts stay isolated`() = withChrome { browser, origin ->
        val context = browser.newContext()
        val other = browser.newContext()
        try {
            val page = context.newPage()
            page.goto(origin)
            page.addCookies(listOf(Cookie("first", "saved", "127.0.0.1"), Cookie("offpath", "saved", "127.0.0.1", path = "/private")))
            page.goto(origin.replace("127.0.0.1", "localhost"))
            page.addCookies(listOf(Cookie("second", "saved", "localhost")))
            val state = page.storageState()
            assertEquals(setOf("first", "offpath", "second"), state.cookies.map { it.name }.toSet())
            page.clearCookies()
            page.setStorageState(state)
            assertEquals(state.cookies.map { it.name }.toSet(), page.cookies().map { it.name }.toSet())
            val independent = other.newPage()
            independent.goto(origin)
            assertTrue(independent.cookies().isEmpty())
        } finally { context.close(); other.close() }
    }

    @Test fun `batched ref boxes agree with locator boxes`() = withChrome { browser, _ ->
        val page = browser.newPage()
        page.setContent("<button>One</button><button>Two</button>")
        val refs = page.snapshot(interactive = true).refs.keys.toList()
        val boxes = page.refBoundingBoxes(refs)
        refs.forEach { assertEquals(page.locator(it).boundingBox(), boxes[it]) }
        page.close()
    }

    @Test fun `repeated contexts do not inherit routes state or pending waiters`() = withChrome { browser, origin ->
        repeat(8) { round ->
            val context = browser.newContext()
            try {
                val page = context.newPage()
                page.goto(origin)
                assertTrue("Cookies leaked into round $round", page.cookies().isEmpty())
                assertTrue("Storage leaked into round $round", page.localStorage.entries().isEmpty())
                page.route("**/data.json") { it.fulfill(body = "intercepted-$round") }
                val intercepted = page.waitForResponse("**/data.json") { evaluate("fetch('/data.json').then(r => r.text())") }
                assertEquals("intercepted-$round", intercepted.text())
                page.unroute("**/data.json")
                val actual = page.waitForResponse("**/data.json") { evaluate("fetch('/data.json').then(r => r.text())") }
                assertEquals("""{"hello":"world"}""", actual.text())
                page.addCookies(listOf(Cookie("owner", "$round", "127.0.0.1")))
                page.localStorage.set("owner", "$round")
                val saved = page.storageState()
                page.localStorage.set("owner", "changed")
                page.setStorageState(saved)
                assertEquals("$round", page.localStorage.get("owner"))
                kotlinx.coroutines.coroutineScope {
                    val wait = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                        runCatching { page.waitForResponse("**/never", 60_000) }
                    }
                    page.close()
                    val failure = withTimeout(1_000) { wait.await() }.exceptionOrNull()
                    assertTrue("Closing round $round left a waiter alive: $failure", failure is KmpwrightException)
                }
                assertTrue(context.pages().isEmpty())
            } finally { context.close() }
        }
    }

    // ------------------------------------------------------------- real Chromium

    private fun withChrome(config: KmpwrightConfig = KmpwrightConfig(), block: suspend (Browser, String) -> Unit): Unit = runBlocking {
        val executable = System.getenv("KMPWRIGHT_TEST_CHROME")
        assumeTrue("Set KMPWRIGHT_TEST_CHROME for real browser contracts", !executable.isNullOrBlank())
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            if (exchange.requestURI.path.startsWith("/redirect-")) {
                val host = if (exchange.requestURI.path == "/redirect-cross") "localhost" else "127.0.0.1"
                exchange.responseHeaders.add("Location", "http://$host:${server.address.port}/arrived")
                exchange.sendResponseHeaders(302, -1)
                exchange.close()
                return@createContext
            }
            val body = when (exchange.requestURI.path) {
                "/data.json" -> """{"hello":"world"}"""
                else -> "<title>Fixture</title><p id=p>Hello</p>"
            }.encodeToByteArray()
            exchange.responseHeaders.add("Content-Type", if (exchange.requestURI.path.endsWith(".json")) "application/json" else "text/html; charset=utf-8")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        val launched = launchBrowser(executable!!, config = config)
        try {
            block(launched.browser, "http://127.0.0.1:${server.address.port}")
        } finally {
            withTimeout(15_000) { launched.close() }
            server.stop(0)
        }
    }

    @Test
    fun `element screenshot below the fold captures the element after scrolling`() = withChrome { browser, _ ->
        val page = browser.newPage()
        page.setViewportSize(400, 300)
        page.setContent("""<style>body{margin:0;background:#fff}</style>
            <div style="height:1500px;background:rgb(0,0,255)"></div>
            <div id=target style="width:120px;height:60px;margin-left:30px;background:rgb(255,0,0)"></div>
            <div style="height:1500px;background:rgb(0,255,0)"></div>""")
        val shot = page.locator("#target").screenshot()
        val image = ImageIO.read(ByteArrayInputStream(shot.bytes))
        assertEquals(120, image.width)
        assertEquals(60, image.height)
        for ((x, y) in listOf(2 to 2, 60 to 30, 117 to 57)) {
            assertEquals("pixel ($x,$y) must belong to the element", 0xFF0000, image.getRGB(x, y) and 0xFFFFFF)
        }
        assertTrue("the element must have been scrolled into view", page.evaluate("window.scrollY").value.toDouble() > 0)

        // A page clip is viewport-relative even when the document is scrolled.
        page.evaluate("window.scrollTo(0, 3100)")
        val clip = ImageIO.read(ByteArrayInputStream(page.screenshot(clip = Rectangle(10.0, 10.0, 20.0, 20.0)).bytes))
        assertEquals(0x00FF00, clip.getRGB(10, 10) and 0xFFFFFF)
        assertFailsWith<KmpwrightException> { page.screenshot(clip = Rectangle(1000.0, 1000.0, 10.0, 10.0)) }

        // JPEG and WEBP element screenshots are honored; WEBP cannot be diffed on the JVM.
        val jpeg = page.locator("#target").screenshot(format = ScreenshotFormat.Jpeg, quality = 50)
        assertEquals("JPEG", imageFormatOf(jpeg.bytes))
        val webp = page.locator("#target").screenshot(format = ScreenshotFormat.Webp)
        assertEquals("WEBP", imageFormatOf(webp.bytes))
        assertFailsWith<KmpwrightException> { page.visualDiff(webp, webp) }
    }

    @Test
    fun `route fulfill content type reaches the page as Playwright sends it`() = withChrome { browser, origin ->
        val page = browser.newPage()
        assertTrue(page.goto("$origin/").ok)
        page.route(Regex(".*/api/none")) { it.fulfill(body = "plain") }
        page.route(Regex(".*/api/override")) { it.fulfill(headers = mapOf("Content-Type" to "text/plain"), body = "<b>x</b>", contentType = "text/html") }
        page.route(Regex(".*/api/json")) { it.fulfill(json = """{"ok":true}""") }
        page.route(Regex(".*/api/header")) { it.fulfill(headers = mapOf("Content-Type" to "text/csv"), body = "a,b") }
        suspend fun contentType(path: String) =
            page.evaluate("fetch('$path').then(r => String(r.headers.get('content-type')))").value
        assertEquals("text/html", contentType("/api/override"))
        assertEquals("application/json", contentType("/api/json"))
        assertEquals("text/csv", contentType("/api/header"))
        assertFalse("no content type is invented: ${contentType("/api/none")}", contentType("/api/none").contains("json"))

        // Request.resourceType comes from Chromium; a fulfilled route sees "fetch".
        var routedType: String? = null
        page.route(Regex(".*/api/kind")) { routedType = it.request.resourceType; it.fulfill(body = "") }
        page.evaluate("fetch('/api/kind').then(r => r.status)")
        // Chromium's Fetch domain only knows "xhr"; the Network record (when already seen) says "fetch".
        assertTrue("$routedType", routedType == "fetch" || routedType == "xhr")
    }

    @Test
    fun `responses from waitForResponse expose their body`() = withChrome { browser, origin ->
        val page = browser.newPage()
        assertTrue(page.goto("$origin/").ok)
        val response = page.waitForResponse(Regex(".*/data\\.json")) { evaluate("fetch('/data.json').then(r => r.text())") }
        assertEquals(200, response.status)
        assertEquals("""{"hello":"world"}""", response.text())
        assertEquals("world", (response.json() as JsonObject)["hello"]!!.jsonPrimitive.content)
        assertEquals("""{"hello":"world"}""", page.network.responseBody(response).content)
        val record = page.network.requests("data.json").first()
        assertEquals("fetch", record.request.resourceType)
        // A detached copy has no page to read from.
        assertFailsWith<KmpwrightException> { response.copy().text() }
    }

    @Test
    fun `route handler bound comes from TimeoutConfig handler`() = withChrome(KmpwrightConfig(timeouts = TimeoutConfig(handler = 300))) { browser, origin ->
        val page = browser.newPage()
        assertTrue(page.goto("$origin/").ok)
        page.route(Regex(".*/slow")) { delay(5_000); it.fulfill(body = "late") }
        val started = System.nanoTime()
        val outcome = page.evaluate("fetch('/slow').then(() => 'ok', () => 'failed')").value
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertEquals("failed", outcome)
        assertTrue("the configured 300ms bound must abort the request, took ${elapsedMs}ms", elapsedMs < 3_000)
    }

    @Test
    fun `waitForDialog counts as a dialog listener`() = withChrome { browser, _ ->
        val page = browser.newPage()
        page.setContent("<button id=b onclick=\"window.answer = confirm('Sure?')\">B</button>")
        val dialog = page.waitForDialog { locator("#b").click() }
        assertEquals(DialogType.Confirm, dialog.type)
        assertEquals("Sure?", dialog.message)
        assertEquals("false", page.evaluate("String(window.answer)").value)
        page.acceptDialogs()
        val accepted = page.waitForDialog { locator("#b").click() }
        assertEquals("Sure?", accepted.message)
        assertEquals("true", page.evaluate("String(window.answer)").value)
    }

    @Test
    fun `disabling DOM extraction keeps structural queries working`() = withChrome(KmpwrightConfig(allowDomExtraction = false)) { browser, _ ->
        val page = browser.newPage()
        page.setContent("<p class=x>secret</p><p class=x>two</p>")
        assertEquals(2, page.locator(".x").count())
        assertTrue(page.locator(".x").first().boundingBox()!!.height > 0)
        assertTrue(page.locator(".x").first().isVisible())
        assertFailsWith<KmpwrightException> { page.locator(".x").first().textContent() }
        assertFailsWith<KmpwrightException> { page.content() }
    }

    @Test
    fun `config viewport and user agent apply to new pages and devices emulate mobile and touch`() =
        withChrome(KmpwrightConfig(viewport = Viewport(500, 400), userAgent = "KmpwrightAgent/1.0")) { browser, _ ->
            val page = browser.newPage()
            assertEquals(ViewportSize(500, 400), page.viewportSize())
            assertEquals("500x400", page.evaluate("innerWidth + 'x' + innerHeight").value)
            assertEquals("KmpwrightAgent/1.0", page.evaluate("navigator.userAgent").value)

            page.emulateDevice(Devices.iPhone15)
            // isMobile honors the viewport meta tag, exactly like Playwright's mobile emulation.
            page.setContent("<meta name=viewport content='width=device-width,initial-scale=1'><p>mobile</p>")
            assertEquals("393", page.evaluate("String(innerWidth)").value)
            assertEquals("3", page.evaluate("String(devicePixelRatio)").value)
            assertEquals("true", page.evaluate("String(navigator.maxTouchPoints > 0)").value)
            assertEquals("true", page.evaluate("String(matchMedia('(pointer: coarse)').matches)").value)
            assertTrue(page.evaluate("navigator.userAgent").value.contains("iPhone"))
        }

    @Test
    fun `clearCookies removes host-only and path-scoped cookies`() = withChrome { browser, origin ->
        val page = browser.newPage()
        assertTrue(page.goto("$origin/").ok)
        page.addCookies(listOf(
            Cookie("hostonly", "1", "127.0.0.1", path = "/a/b"),
            Cookie("other", "2", "127.0.0.1", path = "/")
        ))
        assertEquals(setOf("hostonly", "other"), page.cookies().map { it.name }.toSet())
        page.clearCookies(name = "hostonly")
        assertEquals(setOf("other"), page.cookies().map { it.name }.toSet())
        page.clearCookies()
        assertTrue(page.cookies().isEmpty())
    }

    @Test
    fun `pdf defaults and timeout option work`() = withChrome { browser, _ ->
        val page = browser.newPage()
        page.setContent("<p>PDF</p>")
        assertTrue(page.pdf().bytes.decodeToString(0, 5) == "%PDF-")
        assertTrue(page.pdf(timeoutMs = 20_000).bytes.isNotEmpty())
    }

    @Test
    fun `starting a second video recording is a KmpwrightException`() = withChrome { browser, _ ->
        val page = browser.newPage()
        val ffmpeg = runCatching { ProcessBuilder("ffmpeg", "-version").start().waitFor() == 0 }.getOrDefault(false)
        assumeTrue("ffmpeg is required for video recording", ffmpeg)
        page.setContent("<p>video</p>")
        val dir = java.nio.file.Files.createTempDirectory("kmpwright-video")
        page.video.start(path = dir.resolve("a.webm").toString())
        try {
            val error = assertFailsWith<KmpwrightException> { page.video.start(path = dir.resolve("b.webm").toString()) }
            assertTrue(error.message!!.contains("already active"))
        } finally {
            runCatching { page.video.stop() }
            dir.toFile().deleteRecursively()
        }
    }
}
