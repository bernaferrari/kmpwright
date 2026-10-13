package com.bernaferrari.kmpwright.test

import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.rules.ActivityScenarioRule
import com.bernaferrari.kmpwright.core.Browser
import com.bernaferrari.kmpwright.core.BrowserContext
import com.bernaferrari.kmpwright.core.Cookie
import com.bernaferrari.kmpwright.core.DialogResponse
import com.bernaferrari.kmpwright.core.Page
import com.bernaferrari.kmpwright.core.Viewport
import com.bernaferrari.kmpwright.core.ScreenshotFormat
import com.bernaferrari.kmpwright.core.Locator
import com.bernaferrari.kmpwright.core.KmpwrightConfig
import com.bernaferrari.kmpwright.core.AndroidPlatformContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.ClassRule
import org.junit.Rule
import org.junit.rules.TestRule
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KmpwrightInstrumentedTest {

    private lateinit var browser: Browser
    private lateinit var page: Page
    companion object {
        // One activity launch for the suite; each test owns a fresh WebView.
        private val host = ActivityScenarioRule(WebViewHarnessActivity::class.java)
        private var completed = 0
        private var total = 0

        @ClassRule
        @JvmField
        val suiteHost = TestRule { base, description ->
            completed = 0
            total = description.testCount()
            host.apply(base, description)
        }
    }

    @get:Rule
    val progress: TestWatcher = object : TestWatcher() {
        override fun starting(description: Description) {
            completed += 1
            host.scenario.onActivity {
                it.showProgress(completed, total, description.methodName)
            }
        }
    }

    private fun runPageTest(block: suspend CoroutineScope.() -> Unit) {
        runBlocking(Dispatchers.Main) { block() }
    }

    @Test
    fun keyboardTypesIntoActualEditors() = runPageTest {
        page.setContent("<input id=input><textarea id=area></textarea><div id=editable contenteditable=true></div>")
        for (id in listOf("input", "area", "editable")) {
            page.locator("#$id").focus()
            page.keyboard.type("Hello")
            page.keyboard.insertText(" world")
            val expression = "document.getElementById('$id')." + if (id == "editable") "textContent" else "value"
            assertEquals(id, "Hello world", page.evaluate(expression).value)
        }
    }

    @Test
    fun pdfUsesPhysicalPaperDimensionsAndMultiplePages() = runPageTest {
        page.setContent("<div style='height:3000px;background:#fdd'>First page</div><h1>Last page</h1>")
        page.scroll(com.bernaferrari.kmpwright.core.ScrollDirection.Down, 100.0)
        val before = page.evaluate("window.scrollY").value
        val result = page.pdf(format = com.bernaferrari.kmpwright.core.PdfFormat.A4)
        assertEquals(before, page.evaluate("window.scrollY").value)
        val file = java.io.File.createTempFile("kmpwright-pdf-test-", ".pdf", InstrumentationRegistry.getInstrumentation().targetContext.cacheDir)
        try {
            file.writeBytes(result.bytes)
            android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                android.graphics.pdf.PdfRenderer(descriptor).use { renderer ->
                    assertTrue("Long documents must be paginated", renderer.pageCount > 1)
                    renderer.openPage(0).use { printed ->
                        assertTrue(printed.width in 594..596)
                        assertTrue(printed.height in 840..843)
                    }
                }
            }
        } finally { file.delete() }
    }

    @Test
    fun documentStartScriptsRunBeforePageScriptsAndCanBeRemoved() = runPageTest {
        val first = page.addInitScript("window.initValue = 'registered';")
        val retained = page.addInitScript("window.retainedValue = 7;")
        page.setContent("<script>window.observed = window.initValue</script>")
        assertEquals("registered", page.evaluate("window.observed").value)
        page.removeInitScript(first)
        page.setContent("<script>window.observed = typeof window.initValue</script>")
        assertEquals("undefined", page.evaluate("window.observed").value)
        assertEquals("7", page.evaluate("window.retainedValue").value)
        page.removeInitScript(retained)
    }

    @Test
    fun dialogHandlersCanSuspendWithoutBlockingTheUi() = runPageTest {
        page.setContent("<h1>Dialog fixture</h1>")
        val handler = page.onDialog {
            kotlinx.coroutines.delay(200)
            DialogResponse.Text("Kotlin")
        }
        assertEquals("Kotlin", page.evaluate("prompt('Name', 'default')").value)
        handler.close()
        val dismissing = page.onDialog { DialogResponse.Dismiss }
        assertEquals("null", page.evaluate("prompt('Name')").value)
        dismissing.close()
    }

    @Test
    fun portableHarIncludesObservedWebViewRequestMetadata() = runPageTest {
        page.route(Regex("har-fixture\\.kmpwright\\.test")) {
            it.fulfill(status = 200, body = "HAR fixture", contentType = "text/plain",
                headers = mapOf("Access-Control-Allow-Origin" to "*"))
        }
        page.network.startHar()
        page.setContent("<script>fetch('https://har-fixture.kmpwright.test/data').then(r=>r.text()).then(t=>window.harReady=t).catch(e=>window.harReady=String(e))</script>")
        page.waitForFunction("window.harReady !== undefined")
        assertEquals("HAR fixture", page.evaluate("window.harReady").value)
        val archive = page.network.stopHar()
        assertTrue(archive.contains("har-fixture.kmpwright.test/data"))
        assertTrue(archive.contains("\"version\":\"1.2\""))
    }

    @Test
    fun webVitalsCollectBufferedMetricsAndCleanUp() = runPageTest {
        page.setContent("<html><body><h1>Performance fixture</h1></body></html>")
        // The fixture is about collecting buffered entries, so wait until the first paint exists;
        // a busy device can take longer than settleMs to produce it.
        page.waitForFunction("performance.getEntriesByType('paint').some(function(e){return e.name === 'first-contentful-paint'})")
        val report = page.webVitals(reload = false, settleMs = 200)
        assertTrue(report.supportedEntryTypes.contains("paint"))
        assertNotNull(report.fcp)
        assertEquals("0", page.evaluate("Object.keys(window).filter(function(k){return k.startsWith('__kmpwrightVitals_')}).length").value)
    }

    @Test
    fun routeNavigationAwaitsTheFrameworkRouter() = runPageTest {
        page.setContent("<h1>Router fixture</h1>")
        page.evaluate("window.next={router:{push:function(url){return new Promise(function(resolve){setTimeout(function(){history.pushState(null,'',url);window.routerUsed=true;resolve(true)},50)})}}}")
        assertTrue(page.pushState("#route").ok)
        assertEquals("true", page.evaluate("window.routerUsed").value)
        assertTrue(page.url.value!!.endsWith("#route"))
        // A statement list is evaluated verbatim, so it does not create a task of its own.
        assertEquals("0", page.evaluate("var pending = Object.keys(window).filter(function(k){return k.startsWith('__kmpwrightTask_')}).length; pending").value)
    }

    @Before
    fun setUp() {
        host.scenario.onActivity { activity ->
            browser = Browser(AndroidPlatformContext(activity, activity.resetWebView()))
            page = browser.page()
        }
    }

    @After
    fun tearDown() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            if (this::browser.isInitialized) browser.dispose()
        }
    }

    @Test
    fun testUpstreamDomContractsInWebView() = runPageTest {
        page.setContent("""<html><body>
            <button id="stable">Keep me</button>
            <select id="choice"><option value="a">Alpha</option><option value="b">  Beta   Choice </option></select>
            <iframe id="inner" srcdoc="<input id='field'>"></iframe>
            </body></html>""")
        page.locator("#choice").selectOption("Beta Choice")
        assertEquals("b", page.locator("#choice").inputValue())
        val first = page.snapshot()
        val ref = first.refs.values.first { it.name == "Keep me" }.ref
        page.evaluate("document.body.insertAdjacentHTML('afterbegin', '<button>New</button>')")
        val second = page.snapshot()
        assertEquals(ref, second.refs.values.first { it.name == "Keep me" }.ref)
        assertEquals(1, page.locator(ref).count())
        assertEquals(0, page.locator(".missing >> button").count())
        val field = page.frameLocator("#inner").locator("#field")
        field.fill("Frame value")
        assertEquals("Frame value", field.inputValue())
        page.evaluate("setTimeout(function(){document.body.insertAdjacentHTML('beforeend', '<button id=delayed>Later</button>')}, 150)")
        page.locator("#delayed").waitFor(timeoutMs = 2_000)
        try {
            page.locator("#never").waitFor(timeoutMs = 100)
            org.junit.Assert.fail("A missing selector must time out")
        } catch (_: com.bernaferrari.kmpwright.core.KmpwrightException) { }
    }

    @Test
    fun testBrowserPages() {
        val pages = browser.pages()
        assertEquals(1, pages.size)
        assertEquals(page, pages.first())
    }

    @Test
    fun testBrowserVersion() = runPageTest {
        val version = browser.version()
        assertNotNull(version.product)
        assertNotNull(version.version)
        assertNotNull(version.userAgent)
        assertEquals("Android WebView", version.product)
        assertTrue(version.version.isNotBlank() && version.version != "1.3.0")
        assertTrue(version.userAgent.contains("Android"))
    }

    @Test
    fun testBrowserNewPage() = runPageTest {
        val newPage = browser.newPage()
        assertNotNull(newPage)
        assertTrue(newPage !== page)
        assertEquals(2, browser.pages().size)
    }

    @Test
    fun testBrowserPageReturnsMostRecentPage() = runPageTest {
        val newPage = browser.newPage()
        assertEquals(newPage, browser.page())
    }

    @Test
    fun testBrowserContextIsExplicitlyUnsupported() {
        try {
            kotlinx.coroutines.runBlocking { browser.newContext() }
            org.junit.Assert.fail("Expected Browser.newContext() to be unsupported on Android")
        } catch (e: com.bernaferrari.kmpwright.core.KmpwrightUnsupportedException) {
            assertTrue(e.message?.contains("BrowserContext semantics") == true)
        }
    }

    @Test
    fun testBrowserClose() = runPageTest {
        browser.close()
        assertTrue(browser.pages().isEmpty())
    }

    @Test
    fun testPageSetContent() {
        runPageTest {
            val html = """
            <!DOCTYPE html>
            <html>
            <head><title>Test Page</title></head>
            <body>
                <h1>Hello World</h1>
                <button id="test-button">Click Me</button>
            </body>
            </html>
        """.trimIndent()
            page.setContent(html)
            assertNotNull(page.url)
        }
    }

    @Test
    fun testPageWaitForLoadState() {
        runPageTest {
            page.waitForLoadState(com.bernaferrari.kmpwright.core.LoadState.DOMContentLoaded)
            assertTrue(true)
        }
    }

    @Test
    fun testPageWaitForNavigation() {
        runPageTest {
            val navigation = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                page.waitForNavigation(timeoutMs = 3_000)
            }
            page.setContent("<h1>Navigation waiter fixture</h1>")
            assertTrue("Navigation must complete successfully", navigation.await()?.ok == true)
        }
    }

    @Test
    fun testPageWaitForEvent() {
        runPageTest {
            val event = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                page.waitForEvent("domcontentloaded", timeoutMs = 3_000)
            }
            page.setContent("<h1>Navigation event fixture</h1>")
            assertEquals(com.bernaferrari.kmpwright.core.LoadState.DOMContentLoaded, event.await())
        }
    }

    @Test
    fun testPageClose() = runPageTest {
        page.close()
        assertTrue(true) // If we get here, no exception was thrown
    }

    @Test
    fun testPageContent() {
        runPageTest {
            val html = "<html><body>Test</body></html>"
            page.setContent(html)
            val content = page.content()
            assertNotNull(content)
            assertTrue(content.contains("Test"))
        }
    }

    @Test
    fun testPageScreenshotCapturesRenderedContent() {
        runPageTest {
            val html = """
            <!DOCTYPE html>
            <html>
            <body style="margin:0;background:#f5f0e8;">
                <div style="width:240px;height:160px;background:#1f6f78;color:white;display:flex;align-items:center;justify-content:center;font-size:24px;">
                    Screenshot
                </div>
            </body>
            </html>
        """.trimIndent()
            page.setContent(html)
            val screenshot = page.screenshot()
            assertTrue(screenshot.bytes.isNotEmpty())
            assertTrue(screenshot.width > 0)
            assertTrue(screenshot.height > 0)
        }
    }

    @Test
    fun testFullPageScreenshotCapturesTallContent() {
        runPageTest {
            val html = """
            <!DOCTYPE html>
            <html>
            <head>
              <meta name="viewport" content="width=device-width, initial-scale=1.0">
              <style>
                html, body { margin: 0; padding: 0; }
                .section { height: 900px; width: 100%; }
                #top { background: #ff0000; }
                #middle { background: #00ff00; }
                #bottom { background: #0000ff; height: 780px; }
                #footer { background: #000000; height: 120px; width: 100%; }
              </style>
            </head>
            <body>
              <div id="top" class="section"></div>
              <div id="middle" class="section"></div>
              <div id="bottom" class="section"></div>
              <div id="footer"></div>
            </body>
            </html>
            """.trimIndent()

            page.setContent(html)
            page.waitForLoadState(com.bernaferrari.kmpwright.core.LoadState.Load)

            val viewport = page.screenshot()
            val fullPage = page.screenshot(fullPage = true)
            assertTrue(fullPage.bytes.isNotEmpty())
            assertTrue(fullPage.height > viewport.height * 2)

            val bitmap = BitmapFactory.decodeByteArray(fullPage.bytes, 0, fullPage.bytes.size)
            assertNotNull(bitmap)
            assertEquals(fullPage.width, bitmap.width)
            assertEquals(fullPage.height, bitmap.height)

            val sampleX = (bitmap.width / 2).coerceAtLeast(1)
            assertColorNear(bitmap.getPixel(sampleX, 50), Color.RED)
            assertColorNear(bitmap.getPixel(sampleX, bitmap.height / 2), Color.GREEN)
            assertColorNear(bitmap.getPixel(sampleX, bitmap.height * 81 / 100), Color.BLUE)
            assertColorNear(bitmap.getPixel(sampleX, bitmap.height * 98 / 100), Color.BLACK)
            bitmap.recycle()
        }
    }

    @Test
    fun testRouteFulfillServesInterceptedResponse() {
        runPageTest {
            page.route(Regex("intercepted\\.kmpwright\\.test")) {
                it.fulfill(
                        status = 201,
                        headers = mapOf(
                            "Access-Control-Allow-Origin" to "*",
                            "Access-Control-Expose-Headers" to "X-Kmpwright-Test",
                            "X-Kmpwright-Test" to "android-route"
                        ),
                        body = """{"ok":true,"source":"route"}""",
                        contentType = "application/json; charset=UTF-8"
                    )
            }

            page.setContent(
                """
            <!DOCTYPE html>
            <html>
            <body>
            <script>
            window.__routeResult = null;
            fetch('https://intercepted.kmpwright.test/data.json')
              .then(async function(response) {
                window.__routeResult = JSON.stringify({
                  status: response.status,
                  header: response.headers.get('x-kmpwright-test'),
                  body: await response.text()
                });
              })
              .catch(function(error) {
                window.__routeResult = JSON.stringify({ error: String(error) });
              });
            </script>
            </body>
            </html>
            """.trimIndent()
            )

            page.waitForFunction("window.__routeResult !== null")
            val result = page.evaluate("window.__routeResult")
            val response = org.json.JSONObject(result.value)
            assertEquals(201, response.getInt("status"))
            assertEquals("android-route", response.getString("header"))
            assertEquals("route", org.json.JSONObject(response.getString("body")).getString("source"))
        }
    }

    @Test
    fun testRouteAbortReportsFailedResponse() {
        runPageTest {
            page.route(Regex("abort\\.kmpwright\\.test")) {
                it.abort("aborted")
            }

            page.setContent(
                """
            <!DOCTYPE html>
            <html>
            <body>
            <script>
            window.__abortResult = null;
            fetch('https://abort.kmpwright.test/data.json')
              .then(function(response) {
                window.__abortResult = JSON.stringify({ status: response.status, ok: response.ok });
              })
              .catch(function(error) {
                window.__abortResult = JSON.stringify({ error: String(error) });
              });
            </script>
            </body>
            </html>
            """.trimIndent()
            )

            page.waitForFunction("window.__abortResult !== null")
            val result = page.evaluate("window.__abortResult")
            assertTrue("Aborted fetch must reject: ${result.value}", result.value.contains("\"error\":"))
        }
    }

    @Test
    fun testRouteContinueAppliesUrlMethodHeaderAndBodyOverrides() {
        runPageTest {
            val seenRequest = AtomicReference<String>()
            val server = ServerSocket(0)
            val serverThread = Thread {
                val socket = server.accept()
                val reader = socket.getInputStream().bufferedReader()
                val requestBuilder = StringBuilder()
                var contentLength = 0
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.startsWith("Content-Length:", ignoreCase = true)) {
                        contentLength = line.substringAfter(":").trim().toIntOrNull() ?: 0
                    }
                    if (line.isEmpty()) break
                    requestBuilder.append(line).append('\n')
                }
                if (contentLength > 0) {
                    val bodyChars = CharArray(contentLength)
                    val read = reader.read(bodyChars)
                    if (read > 0) requestBuilder.append(String(bodyChars, 0, read))
                }
                seenRequest.set(requestBuilder.toString())
                OutputStreamWriter(socket.getOutputStream()).use { writer ->
                    val body = """{"continued":true}"""
                    writer.write(
                        "HTTP/1.1 207 Multi-Status\r\n" +
                            "Content-Type: application/json\r\n" +
                            "Access-Control-Allow-Origin: *\r\n" +
                            "Content-Length: ${body.toByteArray().size}\r\n" +
                            "Connection: close\r\n" +
                            "\r\n" +
                            body
                    )
                }
                socket.close()
                server.close()
            }
            serverThread.start()

            page.route(Regex("continue-source\\.kmpwright\\.test")) {
                it.resume(
                        url = "http://127.0.0.1:${server.localPort}/continued",
                        method = "POST",
                        headers = mapOf("X-Route-Override" to "yes"),
                        postData = "payload=1"
                    )
            }

            page.setContent(
                """
            <!DOCTYPE html>
            <html>
            <body>
            <script>
            window.__continueResult = null;
            fetch('https://continue-source.kmpwright.test/data.json')
              .then(async function(response) {
                window.__continueResult = JSON.stringify({
                  status: response.status,
                  body: await response.text()
                });
              })
              .catch(function(error) {
                window.__continueResult = JSON.stringify({ error: String(error) });
              });
            </script>
            </body>
            </html>
            """.trimIndent()
            )

            page.waitForFunction("window.__continueResult !== null")
            val result = page.evaluate("window.__continueResult")
            val response = org.json.JSONObject(result.value)
            assertEquals(207, response.getInt("status"))
            assertTrue(org.json.JSONObject(response.getString("body")).getBoolean("continued"))
            serverThread.join(2_000)
            val request = seenRequest.get().orEmpty()
            assertTrue(request.startsWith("POST /continued"))
            assertTrue(request.contains("X-Route-Override: yes"))
            assertTrue(request.contains("payload=1"))
        }
    }

    @Test
    fun testRouteFulfillEmitsRequestAndResponseEvents() {
        runPageTest {
            page.route(Regex("events\\.kmpwright\\.test")) {
                it.fulfill(
                        status = 203,
                        headers = mapOf("Access-Control-Allow-Origin" to "*"),
                        body = "event-ok",
                        contentType = "text/plain"
                    )
            }

            val request = async { page.waitForRequest(Regex("events\\.kmpwright\\.test"), timeoutMs = 5_000) }
            val response = async { page.waitForResponse(Regex("events\\.kmpwright\\.test"), timeoutMs = 5_000) }
            page.setContent(
                """
            <!DOCTYPE html>
            <html>
            <body>
            <script>fetch('https://events.kmpwright.test/data.txt')</script>
            </body>
            </html>
            """.trimIndent()
            )

            assertTrue(request.await().url.contains("events.kmpwright.test"))
            assertEquals(203, response.await().status)
        }
    }

    @Test
    fun testCookieRoundTripAndDeleteAllCookies() {
        runPageTest {
            page.route(Regex("cookies\\.kmpwright\\.test")) {
                it.fulfill(
                        status = 200,
                        headers = mapOf("Content-Type" to "text/html; charset=UTF-8"),
                        body = """
                        <!DOCTYPE html>
                        <html><body>cookies</body></html>
                    """.trimIndent(),
                        contentType = "text/html; charset=UTF-8"
                    )
            }

            val navigation = page.goto("https://cookies.kmpwright.test/index.html")
            assertTrue(navigation.ok)

            page.addCookies(listOf(Cookie("sid", "abc123", "cookies.kmpwright.test")))
            page.waitForTimeout(150)

            val cookie = page.cookies().firstOrNull { it.name == "sid" }
            assertNotNull(cookie)
            assertEquals("abc123", cookie?.value)

            val cookies = page.cookies()
            assertTrue(cookies.any { it.name == "sid" && it.domain == "cookies.kmpwright.test" })

            page.clearCookies()
            page.waitForTimeout(150)
            assertEquals(null, page.cookies().firstOrNull { it.name == "sid" })
        }
    }


    private fun runOffMainTest(block: suspend CoroutineScope.() -> Unit) {
        runBlocking(Dispatchers.Default) {
            assertFalse(android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
            block()
        }
    }

    @Test
    fun pageApisAreSafeFromBackgroundDispatchers() = runOffMainTest {
        LocalHttpServer { LocalHttpServer.FixtureResponse(200, "OK",
            "<title>Off main</title><button id=b onclick=\"window.clicked=(window.clicked||0)+1\">Go</button>") }.use { server ->
            val navigation = page.goto("${server.origin}/index.html")
            assertTrue(navigation.error, navigation.ok)
            assertEquals(200, navigation.status)
            assertEquals("2", page.evaluate("1 + 1").value)
            page.locator("#b").click()
            assertEquals("1", page.evaluate("window.clicked").value)
            assertTrue(page.url.value!!.startsWith(server.origin))
            assertEquals("Off main", page.title.value)
            page.setViewportSize(320, 480)
            page.waitForTimeout(300)
            assertEquals("320", page.evaluate("window.innerWidth").value)
            page.addCookies(listOf(Cookie("bg", "1", "127.0.0.1")))
            assertEquals("1", page.cookies().firstOrNull { it.name == "bg" }?.value)
            val screenshot = page.screenshot()
            assertTrue(screenshot.bytes.isNotEmpty())
            val second = browser.newPage()
            assertEquals("3", second.evaluate("1 + 2").value)
            second.close()
        }
    }

    @Test
    fun touchTapAtBoundingBoxCenterHitsElement() = runPageTest {
        page.setContent("""<meta name=viewport content="width=device-width,initial-scale=1">
            <body style="margin:0"><div style="height:137px"></div>
            <button id=target style="margin-left:61px;width:120px;height:48px">Tap</button>
            <script>document.getElementById('target').addEventListener('click', function(e) {
              window.tapped = JSON.stringify({x: e.clientX, y: e.clientY});
            });</script></body>""")
        val box = page.locator("#target").boundingBox()!!
        page.touchscreen.tap(box.x + box.width / 2, box.y + box.height / 2)
        page.waitForFunction("window.tapped !== undefined")
        val point = org.json.JSONObject(page.evaluate("window.tapped").value)
        assertEquals(box.x + box.width / 2, point.getDouble("x"), 2.0)
        assertEquals(box.y + box.height / 2, point.getDouble("y"), 2.0)
    }

    @Test
    fun elementScreenshotIsBoundingBoxTimesDensity() = runPageTest {
        page.setContent("""<meta name=viewport content="width=device-width,initial-scale=1">
            <body style="margin:0;background:#fff"><div style="height:90px"></div>
            <div id=box style="margin-left:30px;width:100px;height:50px;background:#00f"></div></body>""")
        val density = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        val box = page.locator("#box").boundingBox()!!
        val shot = page.locator("#box").screenshot()
        assertEquals(box.width * density, shot.width.toDouble(), 2.0)
        assertEquals(box.height * density, shot.height.toDouble(), 2.0)
        val bitmap = BitmapFactory.decodeByteArray(shot.bytes, 0, shot.bytes.size)
        assertColorNear(bitmap.getPixel(bitmap.width / 2, bitmap.height / 2), Color.BLUE)
        assertColorNear(bitmap.getPixel(2, 2), Color.BLUE)
        bitmap.recycle()
        assertEquals(page.evaluate("window.innerWidth").value.toDouble(), page.viewportSize().width.toDouble(), 1.0)
    }

    @Test
    fun mainFrameHttpErrorStatusIsReportedOnce() = runPageTest {
        LocalHttpServer { request ->
            if (request.path.startsWith("/missing")) LocalHttpServer.FixtureResponse(404, "Not Found", "<h1>missing</h1>")
            else LocalHttpServer.FixtureResponse(500, "Internal Server Error", "<h1>boom</h1>")
        }.use { server ->
            val missing = page.goto("${server.origin}/missing")
            assertEquals(404, missing.status)
            assertFalse(missing.ok)
            val failing = page.goto("${server.origin}/fail")
            assertEquals(500, failing.status)
        }
        val refused = java.net.ServerSocket(0).use { it.localPort }
        val failed = page.goto("http://127.0.0.1:$refused/")
        assertFalse(failed.ok)
        assertEquals(0, failed.status)
        // A stale second callback from the error page must not complete the next navigation.
        LocalHttpServer { LocalHttpServer.FixtureResponse(200, "OK", "<title>ok</title>") }.use { server ->
            val ok = page.goto("${server.origin}/ok")
            assertTrue(ok.error, ok.ok)
            assertEquals("${server.origin}/ok", ok.url)
            assertEquals(200, ok.status)
        }
    }

    @Test
    fun latestRouteWinsAndFallthroughReachesOlderHandlers() = runPageTest {
        page.route(Regex("order\\.kmpwright\\.test")) {
            it.fulfill(body = "first", contentType = "text/plain", headers = mapOf("Access-Control-Allow-Origin" to "*"))
        }
        page.route(Regex("order\\.kmpwright\\.test/second")) {
            it.fulfill(body = "second", contentType = "text/plain", headers = mapOf("Access-Control-Allow-Origin" to "*"))
        }
        page.route(Regex("order\\.kmpwright\\.test/skip")) { it.fallback() }
        page.setContent("""<script>
            Promise.all(['second', 'skip'].map(function(p) {
              return fetch('https://order.kmpwright.test/' + p).then(function(r) { return r.text(); });
            })).then(function(v) { window.order = v.join(','); }, function(e) { window.order = String(e); });
            </script>""")
        page.waitForFunction("window.order !== undefined")
        assertEquals("second,first", page.evaluate("window.order").value)
    }

    @Test
    fun redirectFulfillAndThrowingHandlerFailTheRequestWithoutCrashing() = runPageTest {
        page.route(Regex("redirect\\.kmpwright\\.test")) {
            it.fulfill(status = 302, headers = mapOf("Location" to "https://example.com"))
        }
        page.route(Regex("throws\\.kmpwright\\.test")) { error("handler failure") }
        page.setContent("""<script>
            Promise.all(['redirect', 'throws'].map(function(h) {
              return fetch('https://' + h + '.kmpwright.test/x').then(function() { return 'resolved'; }, function() { return 'rejected'; });
            })).then(function(v) { window.results = v.join(','); });
            </script>""")
        page.waitForFunction("window.results !== undefined")
        assertEquals("rejected,rejected", page.evaluate("window.results").value)
    }

    @Test
    fun keyboardPressSendsShiftedCharacters() = runPageTest {
        page.setContent("<input id=field>")
        page.locator("#field").focus()
        page.keyboard.press("A")
        page.keyboard.press("b")
        page.keyboard.press("!")
        page.waitForTimeout(200)
        assertEquals("Ab!", page.evaluate("document.getElementById('field').value").value)
    }

    @Test
    fun hostClientsKeepReceivingCallbacks() {
        val finished = java.util.concurrent.CountDownLatch(1)
        val titles = java.util.Collections.synchronizedList(mutableListOf<String>())
        host.scenario.onActivity { activity ->
                browser.dispose()
                val webView = activity.resetWebView()
                webView.webViewClient = object : android.webkit.WebViewClient() {
                    override fun onPageFinished(view: android.webkit.WebView?, url: String?) { finished.countDown() }
                }
                webView.webChromeClient = object : android.webkit.WebChromeClient() {
                    override fun onReceivedTitle(view: android.webkit.WebView?, title: String?) { title?.let(titles::add) }
                }
                browser = Browser(AndroidPlatformContext(activity, webView))
                page = browser.page()
        }
        runPageTest { page.setContent("<title>Host title</title>") }
        assertTrue(finished.await(5, java.util.concurrent.TimeUnit.SECONDS))
        assertTrue(titles.toString(), titles.contains("Host title"))
    }

    @Test
    fun rendererCrashFailsThePageWithoutKillingTheApp() = runPageTest {
        page.setContent("<h1>before crash</h1>")
        val navigation = page.goto("chrome://crash", timeoutMs = 10_000)
        assertFalse(navigation.ok)
        assertTrue(navigation.error.orEmpty(), navigation.error.orEmpty().contains("crashed", ignoreCase = true))
        try {
            page.evaluate("1 + 1")
            org.junit.Assert.fail("Evaluation on a crashed page must fail")
        } catch (error: com.bernaferrari.kmpwright.core.KmpwrightException) {
            assertTrue(error.message.orEmpty(), error.message.orEmpty().contains("crash", ignoreCase = true))
        }
    }

    @Test
    fun httpCredentialsAnswerAuthChallengesForTheBoundHost() = runPageTest {
        val expected = "Basic " + android.util.Base64.encodeToString("user:secret".toByteArray(), android.util.Base64.NO_WRAP)
        LocalHttpServer { request ->
            when {
                !request.path.startsWith("/protected") ->
                    LocalHttpServer.FixtureResponse(200, "OK", "<iframe src='/protected'></iframe>")
                request.header("Authorization") == expected ->
                    LocalHttpServer.FixtureResponse(200, "OK", "<p>authorized</p>")
                else -> LocalHttpServer.FixtureResponse(401, "Unauthorized", "denied",
                    headers = mapOf("WWW-Authenticate" to "Basic realm=\"kmpwright\""))
            }
        }.use { server ->
            page.setHTTPCredentials(com.bernaferrari.kmpwright.core.HttpCredentials("user", "secret"))
            assertTrue(page.goto("${server.origin}/").ok)
            page.waitForFunction("(function(){var d=document.querySelector('iframe').contentDocument;return !!d && !!d.body && d.body.textContent.indexOf('authorized')>=0;})()")
        }
    }

    @Test
    fun fullPageScreenshotStitchesTilesWithoutGapsOrDuplicates() = runPageTest {
        val colors = listOf("#ff0000", "#00ff00", "#0000ff", "#ffff00", "#ff00ff", "#00ffff", "#000000", "#808080", "#ff8000", "#8000ff")
        val stripeCss = 337
        page.setContent("""<meta name=viewport content="width=device-width,initial-scale=1">
            <style>html,body{margin:0;padding:0}div{height:${stripeCss}px;width:100%}</style>
            ${colors.joinToString("") { "<div style='background:$it'></div>" }}""")
        val dpr = page.evaluate("window.devicePixelRatio").value.toDouble()
        val scrollHeight = page.evaluate("document.documentElement.scrollHeight").value.toDouble()
        val shot = page.screenshot(fullPage = true)
        assertEquals(scrollHeight * dpr, shot.height.toDouble(), 3.0)
        val bitmap = BitmapFactory.decodeByteArray(shot.bytes, 0, shot.bytes.size)
        try {
            assertEquals(shot.height, bitmap.height)
            val x = bitmap.width / 2
            colors.forEachIndexed { index, hex ->
                val expected = Color.parseColor(hex)
                val top = (index * stripeCss * dpr).toInt()
                val bottom = ((index + 1) * stripeCss * dpr).toInt()
                for (y in listOf(top + 3, (top + bottom) / 2, bottom - 4)) {
                    val actual = bitmap.getPixel(x, y.coerceAtMost(bitmap.height - 1))
                    assertTrue("stripe $index at row $y: expected $hex, got ${Integer.toHexString(actual)}",
                        kotlin.math.abs(Color.red(actual) - Color.red(expected)) <= 24 &&
                            kotlin.math.abs(Color.green(actual) - Color.green(expected)) <= 24 &&
                            kotlin.math.abs(Color.blue(actual) - Color.blue(expected)) <= 24)
                }
            }
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun dialogsFallBackToHostClientWithoutHandlerAndHandlerWins() {
        val hostDialogs = java.util.Collections.synchronizedList(mutableListOf<String>())
        host.scenario.onActivity { activity ->
            browser.dispose()
            val webView = activity.resetWebView()
            webView.webChromeClient = object : android.webkit.WebChromeClient() {
                override fun onJsAlert(view: android.webkit.WebView?, url: String?, message: String?, result: android.webkit.JsResult?): Boolean {
                    hostDialogs.add("alert:$message"); result?.confirm(); return true
                }
                override fun onJsConfirm(view: android.webkit.WebView?, url: String?, message: String?, result: android.webkit.JsResult?): Boolean {
                    hostDialogs.add("confirm:$message"); result?.cancel(); return true
                }
            }
            browser = Browser(AndroidPlatformContext(activity, webView))
            page = browser.page()
        }
        runPageTest {
            page.setContent("<h1>dialogs</h1>")
            assertEquals("false", page.evaluate("alert('native'); confirm('host')").value)
            assertEquals(listOf("alert:native", "confirm:host"), hostDialogs.toList())

            val handler = page.onDialog { DialogResponse.Accept }
            assertEquals("true", page.evaluate("confirm('automation')").value)
            assertEquals(2, hostDialogs.size)
            handler.close()

            assertEquals("false", page.evaluate("confirm('again')").value)
            assertEquals("confirm:again", hostDialogs.last())
        }
    }

    @Test
    fun browserMustBeCreatedOnMainThread() {
        val activity = AtomicReference<WebViewHarnessActivity>()
        host.scenario.onActivity(activity::set)
        try {
            Browser(AndroidPlatformContext(activity.get()))
            org.junit.Assert.fail("Creating a Browser off the main thread must fail clearly")
        } catch (error: IllegalStateException) {
            assertTrue(error.message.orEmpty().contains("main thread"))
        }
    }

    private fun assertColorNear(actual: Int, expected: Int) {
        val tolerance = 24
        val message = "Expected color ${Integer.toHexString(expected)}, got ${Integer.toHexString(actual)}"
        assertTrue(message, kotlin.math.abs(Color.red(actual) - Color.red(expected)) <= tolerance)
        assertTrue(message, kotlin.math.abs(Color.green(actual) - Color.green(expected)) <= tolerance)
        assertTrue(message, kotlin.math.abs(Color.blue(actual) - Color.blue(expected)) <= tolerance)
    }
}
