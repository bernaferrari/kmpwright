package com.bernaferrari.kmpwright.test

import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.rules.ActivityScenarioRule
import com.bernaferrari.kmpwright.core.Browser
import com.bernaferrari.kmpwright.core.BrowserContext
import com.bernaferrari.kmpwright.core.Cookie
import com.bernaferrari.kmpwright.core.ContinueOptions
import com.bernaferrari.kmpwright.core.DialogResponse
import com.bernaferrari.kmpwright.core.Page
import com.bernaferrari.kmpwright.core.Viewport
import com.bernaferrari.kmpwright.core.ScreenshotOptions
import com.bernaferrari.kmpwright.core.ScreenshotFormat
import com.bernaferrari.kmpwright.core.Locator
import com.bernaferrari.kmpwright.core.RouteHandler
import com.bernaferrari.kmpwright.core.FulfillOptions
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
        page.scrollDown(100.0)
        val before = page.evaluate("window.scrollY").value
        val result = page.pdf(com.bernaferrari.kmpwright.core.PdfOptions(format = com.bernaferrari.kmpwright.core.PdfFormat.A4))
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
        assertEquals("null", page.evaluate("prompt('Name')").value)
    }

    @Test
    fun portableHarIncludesObservedWebViewRequestMetadata() = runPageTest {
        page.route(".*har-fixture\\.kmpwright\\.test.*") {
            RouteHandler.Fulfill(FulfillOptions(status = 200, body = "HAR fixture", contentType = "text/plain",
                headers = mapOf("Access-Control-Allow-Origin" to "*")))
        }
        page.startHarCapture()
        page.setContent("<script>fetch('https://har-fixture.kmpwright.test/data').then(r=>r.text()).then(t=>window.harReady=t).catch(e=>window.harReady=String(e))</script>")
        page.waitForFunction("window.harReady !== undefined")
        assertEquals("HAR fixture", page.evaluate("window.harReady").value)
        val archive = page.stopHarCapture()
        assertTrue(archive.contains("har-fixture.kmpwright.test/data"))
        assertTrue(archive.contains("\"version\":\"1.2\""))
    }

    @Test
    fun webVitalsCollectBufferedMetricsAndCleanUp() = runPageTest {
        page.setContent("<html><body><h1>Performance fixture</h1></body></html>")
        val report = page.webVitals(com.bernaferrari.kmpwright.core.WebVitalsOptions(reload = false, settleMs = 200))
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
        assertEquals("0", page.evaluate("Object.keys(window).filter(function(k){return k.startsWith('__kmpwrightTask_')}).length").value)
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
            if (this::browser.isInitialized) browser.close()
        }
    }

    @Test
    fun testBrowserCreation() {
        assertNotNull(browser)
        assertNotNull(page)
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
        val first = page.accessibilitySnapshot()
        val ref = first.refMap.values.first { it.name == "Keep me" }.refId
        page.evaluate("document.body.insertAdjacentHTML('afterbegin', '<button>New</button>')")
        val second = page.accessibilitySnapshot()
        assertEquals(ref, second.refMap.values.first { it.name == "Keep me" }.refId)
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
    fun testPageHasUrlAndTitle() {
        assertNotNull(page.url)
        assertNotNull(page.title)
    }

    @Test
    fun testBrowserPages() {
        val pages = browser.pages()
        assertEquals(1, pages.size)
        assertEquals(page, pages.first())
    }

    @Test
    fun testBrowserVersion() {
        val version = browser.version()
        assertNotNull(version.product)
        assertNotNull(version.version)
        assertNotNull(version.userAgent)
        assertEquals("Kmpwright", version.product)
    }

    @Test
    fun testBrowserContext() {
        try {
            browser.newContext()
            org.junit.Assert.fail("Expected Browser.newContext() to be unsupported on Android")
        } catch (e: UnsupportedOperationException) {
            assertTrue(e.message?.contains("BrowserContext semantics") == true)
        }
    }

    @Test
    fun testLocatorCreation() {
        val locator = page.locator("role=button")
        assertNotNull(locator)
    }

    @Test
    fun testLocatorWithCssSelector() {
        val locator = page.locator(".submit-button")
        assertNotNull(locator)
    }

    @Test
    fun testLocatorWithIdSelector() {
        val locator = page.locator("#main-content")
        assertNotNull(locator)
    }

    @Test
    fun testLocatorChain() {
        val locator = page.locator("role=list").locator("role=listitem")
        assertNotNull(locator)
    }

    @Test
    fun testViewportConstants() {
        val default = Viewport.DEFAULT
        assertEquals(1280, default.width)
        assertEquals(800, default.height)

        val mobile = Viewport.MOBILE
        assertEquals(390, mobile.width)
        assertEquals(844, mobile.height)
        assertTrue(mobile.isMobile)
        assertTrue(mobile.hasTouch)

        val tablet = Viewport.TABLET
        assertEquals(768, tablet.width)
        assertEquals(1024, tablet.height)
        assertTrue(tablet.isMobile)
        assertTrue(tablet.hasTouch)
    }

    @Test
    fun testScreenshotOptions() {
        val options = ScreenshotOptions()
        assertFalse(options.fullPage)
        assertEquals(1.0, options.scale, 0.001)
        assertEquals(ScreenshotFormat.PNG, options.format)
        assertEquals(90, options.quality)
    }

    @Test
    fun testScreenshotOptionsWithFullPage() {
        val options = ScreenshotOptions(fullPage = true)
        assertTrue(options.fullPage)
    }

    @Test
    fun testScreenshotOptionsWithScale() {
        val options = ScreenshotOptions(scale = 0.5)
        assertEquals(0.5, options.scale, 0.001)
    }

    @Test
    fun testScreenshotOptionsWithJpeg() {
        val options = ScreenshotOptions(format = ScreenshotFormat.JPEG)
        assertEquals(ScreenshotFormat.JPEG, options.format)
    }

    @Test
    fun testKmpwrightConfig() {
        val config = KmpwrightConfig()
        assertNotNull(config.timeouts)
        assertNotNull(config.debug)
        assertEquals(30_000L, config.timeouts.waitForSelector)
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
            browser.newContext()
            org.junit.Assert.fail("Expected Browser.newContext() to be unsupported on Android")
        } catch (e: UnsupportedOperationException) {
            assertTrue(e.message?.contains("BrowserContext semantics") == true)
        }
    }

    @Test
    fun testBrowserClose() = runPageTest {
        browser.close()
        assertTrue(browser.pages().isEmpty())
    }

    @Test
    fun testPageLocatorWithRoleSelector() {
        val button = page.locator("role=button")
        assertNotNull(button)
    }

    @Test
    fun testPageLocatorWithLinkSelector() {
        val link = page.locator("role=link")
        assertNotNull(link)
    }

    @Test
    fun testPageLocatorWithHeadingSelector() {
        val heading = page.locator("role=heading")
        assertNotNull(heading)
    }

    @Test
    fun testPageLocatorWithCheckboxSelector() {
        val checkbox = page.locator("role=checkbox")
        assertNotNull(checkbox)
    }

    @Test
    fun testPageLocatorWithDialogSelector() {
        val dialog = page.locator("role=dialog")
        assertNotNull(dialog)
    }

    @Test
    fun testLocatorsCount() {
        val buttons = page.locator("role=button")
        assertNotNull(buttons)
    }

    @Test
    fun testLocatorFirst() {
        val button = page.locator("role=button")
        val first = button.first()
        assertNotNull(first)
    }

    @Test
    fun testLocatorNth() {
        val button = page.locator("role=button")
        val second = button.nth(1)
        assertNotNull(second)
    }

    @Test
    fun testLocatorLast() {
        val button = page.locator("role=button")
        val last = button.last()
        assertNotNull(last)
    }

    @Test
    fun testLocatorFilter() {
        val button = page.locator("role=button")
        val filtered = button.filter()
        assertNotNull(filtered)
    }

    @Test
    fun testLocatorFrameLocator() {
        val frame = page.locator("iframe")
        val frameLocator = frame.frameLocator("role=button")
        assertNotNull(frameLocator)
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
    fun testPageWaitForTimeout() {
        runPageTest {
            page.waitForTimeout(100)
            assertTrue(true) // If we get here, no exception was thrown
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
            page.waitForNavigation()
            assertTrue(true) // If we get here, no exception was thrown
        }
    }

    @Test
    fun testPageWaitForEvent() {
        runPageTest {
            page.waitForEvent("domcontentloaded")
            assertTrue(true)
        }
    }

    @Test
    fun testPageEvents() {
        assertNotNull(page.events.console)
        assertNotNull(page.events.navigation)
        assertNotNull(page.events.loadState)
        assertNotNull(page.events.dialog)
    }

    @Test
    fun testPageOnDialog() {
        val handler = page.onDialog { event ->
            // Handle dialog event
            DialogResponse.Accept
        }
        assertNotNull(handler)
    }

    @Test
    fun testPageClose() {
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
            val fullPage = page.screenshot(ScreenshotOptions(fullPage = true))
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
            page.route(".*intercepted\\.kmpwright\\.test.*") {
                RouteHandler.Fulfill(
                    FulfillOptions(
                        status = 201,
                        headers = mapOf(
                            "Access-Control-Allow-Origin" to "*",
                            "Access-Control-Expose-Headers" to "X-Kmpwright-Test",
                            "X-Kmpwright-Test" to "android-route"
                        ),
                        body = """{"ok":true,"source":"route"}""",
                        contentType = "application/json; charset=UTF-8"
                    )
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
            page.route(".*abort\\.kmpwright\\.test.*") {
                RouteHandler.Abort("aborted")
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
            assertTrue(result.value.contains("\"status\":499") || result.value.contains("\"error\":"))
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

            page.route(".*continue-source\\.kmpwright\\.test.*") {
                RouteHandler.Continue(
                    ContinueOptions(
                        url = "http://127.0.0.1:${server.localPort}/continued",
                        method = "POST",
                        headers = mapOf("X-Route-Override" to "yes"),
                        postData = "payload=1"
                    )
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
            page.route(".*events\\.kmpwright\\.test.*") {
                RouteHandler.Fulfill(
                    FulfillOptions(
                        status = 203,
                        headers = mapOf("Access-Control-Allow-Origin" to "*"),
                        body = "event-ok",
                        contentType = "text/plain"
                    )
                )
            }

            val request = async { page.waitForRequest("events\\.kmpwright\\.test", timeoutMs = 5_000) }
            val response = async { page.waitForResponse("events\\.kmpwright\\.test", timeoutMs = 5_000) }
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
            page.route(".*cookies\\.kmpwright\\.test.*") {
                RouteHandler.Fulfill(
                    FulfillOptions(
                        status = 200,
                        headers = mapOf("Content-Type" to "text/html; charset=UTF-8"),
                        body = """
                        <!DOCTYPE html>
                        <html><body>cookies</body></html>
                    """.trimIndent(),
                        contentType = "text/html; charset=UTF-8"
                    )
                )
            }

            val navigation = page.goto("https://cookies.kmpwright.test/index.html")
            assertTrue(navigation.ok)

            page.setCookie(Cookie("sid", "abc123", "cookies.kmpwright.test"))
            page.waitForTimeout(150)

            val cookie = page.getCookie("sid")
            assertNotNull(cookie)
            assertEquals("abc123", cookie?.value)

            val cookies = page.getAllCookies()
            assertTrue(cookies.any { it.name == "sid" && it.domain == "cookies.kmpwright.test" })

            page.deleteAllCookies()
            page.waitForTimeout(150)
            assertEquals(null, page.getCookie("sid"))
        }
    }

    @Test
    fun testPageEvaluate() {
        runPageTest {
            val result = page.evaluate("1 + 1")
            assertNotNull(result)
        }
    }

    @Test
    fun testBrowserIsConnected() {
        assertNotNull(browser.isConnected)
    }

    @Test
    fun testBrowserConnectionError() {
        assertNotNull(browser.connectionError)
    }

    private fun assertColorNear(actual: Int, expected: Int) {
        val tolerance = 24
        val message = "Expected color ${Integer.toHexString(expected)}, got ${Integer.toHexString(actual)}"
        assertTrue(message, kotlin.math.abs(Color.red(actual) - Color.red(expected)) <= tolerance)
        assertTrue(message, kotlin.math.abs(Color.green(actual) - Color.green(expected)) <= tolerance)
        assertTrue(message, kotlin.math.abs(Color.blue(actual) - Color.blue(expected)) <= tolerance)
    }
}
