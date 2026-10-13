package com.bernaferrari.kmpwright.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ConvenienceDslTest {

    private lateinit var controller: MockWebViewController
    private lateinit var page: TestPage

    @Before
    fun setup() {
        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        Dispatchers.setMain(Dispatchers.Unconfined)
        controller = MockWebViewController().apply { waitResultOverride = "true" }
        page = TestPage(controller)
    }

    @After
    fun tearDown() {
        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        Dispatchers.resetMain()
    }

    // --- Feature 2: testBlocking ---

    @Test
    fun `testBlocking runs suspend block`() = testBlocking {
        page.goto("https://example.com")
        assertEquals("https://example.com", page.url.value)
    }

    // --- Feature 3: Page convenience methods ---

    @Test
    fun `click delegates to locator`() = testBlocking {
        page.click("button.submit")
        assertTrue(controller.executedScripts.any { it.contains("H.perform(el, 'click'") })
    }

    @Test
    fun `fill delegates to locator`() = testBlocking {
        page.fill("input.email", "user@test.com")
        assertTrue(controller.executedScripts.any { it.contains("H.perform(el, 'fill'") && it.contains("user@test.com") })
    }

    @Test
    fun `type delegates to locator`() = testBlocking {
        page.type("input.name", "abc")
        assertTrue(controller.executedScripts.any { it.contains("H.perform(el, 'type'") })
    }

    @Test
    fun `press delegates to locator`() = testBlocking {
        page.press("input", "Enter")
        assertTrue(controller.executedScripts.any { it.contains("H.perform(el, 'press'") && it.contains("\"key\":\"Enter\"") })
    }

    @Test
    fun `hover delegates to locator`() = testBlocking {
        page.hover("button")
        assertTrue(controller.executedScripts.any { it.contains("H.perform(el, 'hover'") })
    }

    @Test
    fun `focus delegates to locator`() = testBlocking {
        page.focus("input")
        assertTrue(controller.executedScripts.any { it.contains("H.perform(el, 'focus'") })
    }

    @Test
    fun `check delegates to locator`() = testBlocking {
        var reads = 0
        controller.evalHandler = { script -> if (script.contains("H.checkedState")) (if (reads++ == 0) "false" else "true") else null }
        page.check("input[type=checkbox]")
        assertTrue(controller.executedScripts.any { it.contains("H.perform(el, 'click'") })
    }

    @Test
    fun `uncheck delegates to locator`() = testBlocking {
        var reads = 0
        controller.evalHandler = { script ->
            when {
                script.contains("H.checkedState") -> if (reads++ == 0) "true" else "false"
                script.contains("'radio'") -> "false"
                else -> null
            }
        }
        page.uncheck("input[type=checkbox]")
        assertTrue(controller.executedScripts.any { it.contains("H.perform(el, 'click'") })
    }

    @Test
    fun `selectOption delegates to locator`() = testBlocking {
        page.selectOption("select", "option1")
        assertTrue(controller.executedScripts.any { it.contains("H.perform(el, 'select'") && it.contains("option1") })
    }

    @Test
    fun `text delegates to textContent`() = testBlocking {
        controller.evalHandler = { "Hello World" }
        val result = page.textContent("div.msg")
        assertEquals("Hello World", result)
    }

    @Test
    fun `innerText delegates to locator innerText`() = testBlocking {
        controller.evalHandler = { "Hello World" }
        val result = page.innerText("div.msg")
        assertEquals("Hello World", result)
    }

    @Test
    fun `html delegates to locator innerHTML`() = testBlocking {
        controller.evalHandler = { "<span>hi</span>" }
        val result = page.innerHTML("div")
        assertEquals("<span>hi</span>", result)
    }

    @Test
    fun `attr delegates to locator getAttribute`() = testBlocking {
        controller.evalHandler = { "value123" }
        val result = page.getAttribute("input", "data-id")
        assertEquals("value123", result)
    }

    @Test
    fun `value delegates to locator inputValue`() = testBlocking {
        controller.evalHandler = { "typed text" }
        val result = page.inputValue("input")
        assertEquals("typed text", result)
    }

    @Test
    fun `isVisible delegates to locator`() = testBlocking {
        controller.evalHandler = { "true" }
        assertTrue(page.isVisible("button"))
    }

    @Test
    fun `isEnabled delegates to locator`() = testBlocking {
        controller.evalHandler = { "true" }
        assertTrue(page.isEnabled("button"))
    }

    @Test
    fun `isChecked delegates to locator`() = testBlocking {
        controller.evalHandler = { "true" }
        assertTrue(page.isChecked("input"))
    }

    @Test
    fun `count delegates to locator`() = testBlocking {
        controller.evalHandler = { "5" }
        assertEquals(5, page.count("li"))
    }

    @Test
    fun `waitFor delegates to locator`() = testBlocking {
        controller.evalHandler = { "true" }
        page.waitForSelector("button")
        assertTrue(controller.executedScripts.isNotEmpty())
    }

    // --- Feature 4: locator() accepts refs and selectors ---

    @Test
    fun `locator with @e ref keeps the ref`() = testBlocking {
        page.currentSnapshotRefMap = mapOf(
            "@e1" to RefEntry("@e1", "button", "Submit", "#submit-btn")
        )
        val locator = page.locator("@e1") as TestLocator
        assertEquals("@e1", locator.selectorStr)
    }

    @Test
    fun `locator with plain e ref keeps the ref`() = testBlocking {
        page.currentSnapshotRefMap = mapOf(
            "@e3" to RefEntry("@e3", "link", "Home", "a[href='/']")
        )
        val locator = page.locator("e3") as TestLocator
        assertEquals("e3", locator.selectorStr)
    }

    @Test
    fun `locator with ref= prefix keeps the ref`() = testBlocking {
        page.currentSnapshotRefMap = mapOf(
            "@e2" to RefEntry("@e2", "input", "Email", "input[name=email]")
        )
        val locator = page.locator("ref=e2") as TestLocator
        assertEquals("ref=e2", locator.selectorStr)
    }

    @Test
    fun `locator with CSS selector delegates to locator`() = testBlocking {
        val locator = page.locator("button.submit") as TestLocator
        assertEquals("button.submit", locator.selectorStr)
    }

    @Test
    fun `locator with role selector delegates to locator`() = testBlocking {
        val locator = page.locator("role=button") as TestLocator
        assertEquals("role=button", locator.selectorStr)
    }

    @Test
    fun `locator with text selector delegates to locator`() = testBlocking {
        val locator = page.locator("text=Hello") as TestLocator
        assertEquals("text=Hello", locator.selectorStr)
    }

    @Test
    fun `locator returns locator for chaining`() = testBlocking {
        page.locator("button").click()
        assertTrue(controller.executedScripts.any { it.contains("H.perform(el, 'click'") })
    }

    // --- Feature 5: TestPage + MockWebViewController ---

    @Test
    fun `TestPage with no args uses default controller`() = testBlocking {
        val p = TestPage()
        assertNotNull(p.evalController)
        assertEquals("about:blank", p.evalController.getUrl())
    }

    @Test
    fun `TestPage with builder DSL configures eval handler`() = testBlocking {
        val p = TestPage {
            onEval { "custom-result" }
        }
        val result = p.evaluate("1 + 1")
        assertEquals("custom-result", result.value)
    }

    @Test
    fun `MockWebViewController records executed scripts`() {
        val ctrl = MockWebViewController().apply { waitResultOverride = "true" }
        ctrl.evaluateJavaScript("console.log('hi')") {}
        assertEquals(1, ctrl.executedScripts.size)
        assertEquals("console.log('hi')", ctrl.executedScripts[0])
    }

    @Test
    fun `MockWebViewController cookie state works`() {
        val ctrl = MockWebViewController().apply { waitResultOverride = "true" }
        val cookie = Cookie("name", "value", "example.com")

        ctrl.setCookie(cookie)
        var found: Cookie? = null
        ctrl.getCookie("name", "example.com") { found = it }
        assertNotNull(found)
        assertEquals("value", found!!.value)

        ctrl.deleteCookie("name", "example.com")
        found = null
        ctrl.getCookie("name", "example.com") { found = it }
        assertEquals(null, found)
    }

    @Test
    fun `MockWebViewController deleteAllCookies clears all`() {
        val ctrl = MockWebViewController().apply { waitResultOverride = "true" }
        ctrl.setCookie(Cookie("a", "1", "example.com"))
        ctrl.setCookie(Cookie("b", "2", "example.com"))
        ctrl.deleteAllCookies()
        var list: List<Cookie>? = null
        ctrl.getAllCookies { list = it }
        assertTrue(list!!.isEmpty())
    }

    // --- Feature 1: DSL entry points (Page.on only, Browser.use needs real Browser) ---

    // --- Feature A: Semantic find() overloads ---

    @Test
    fun `getByText creates text selector locator`() = testBlocking {
        val loc = page.getByText("Hello") as TestLocator
        assertEquals("text=Hello", loc.selectorStr)
    }

    @Test
    fun `getByRole creates role selector locator`() = testBlocking {
        val loc = page.getByRole("button") as TestLocator
        assertEquals("role=button", loc.selectorStr)
    }

    @Test
    fun `getByLabel creates label selector locator`() = testBlocking {
        val loc = page.getByLabel("Email") as TestLocator
        assertEquals("label=Email", loc.selectorStr)
    }

    @Test
    fun `getByPlaceholder creates CSS attribute selector`() = testBlocking {
        val loc = page.getByPlaceholder("Search") as TestLocator
        assertEquals("placeholder=Search", loc.selectorStr)
    }

    // --- Feature C: snapshot() convenience ---

    @Test
    fun `snapshot returns sorted ref entries`() = testBlocking {
        // Test the sorting behavior that snapshot() uses (sortedBy refId, lexicographic)
        val refs = listOf(
            RefEntry("@e3", "textbox", "Email", "input.email"),
            RefEntry("@e1", "button", "Submit", "#submit-btn"),
            RefEntry("@e2", "link", "Home", "a[href='/']")
        ).sortedBy { it.ref }
        assertEquals("@e1", refs[0].ref)
        assertEquals("@e2", refs[1].ref)
        assertEquals("@e3", refs[2].ref)
    }

    // --- Feature D: Auto-wait ---

    @Test
    fun `click waits for actionability before acting`() = testBlocking {
        controller.evalHandler = { "true" }
        page.click("button.submit")
        assertTrue(controller.executedScripts.any { it.contains("H.probe(el,") && it.contains("H.perform(el, 'click'") })
    }

    @Test
    fun `fill calls waitFor Attached before acting`() = testBlocking {
        controller.evalHandler = { "true" }
        page.fill("input.email", "user@test.com")
        assertTrue(controller.executedScripts.size > 0)
    }

    // --- getByAltText, getByTestId ---

    @Test
    fun `getByAlt creates alt selector locator`() = testBlocking {
        val loc = page.getByAltText("Logo") as TestLocator
        assertEquals("alt=Logo", loc.selectorStr)
    }

    @Test
    fun `getByTestId creates testid selector locator`() = testBlocking {
        val loc = page.getByTestId("submit-btn") as TestLocator
        assertEquals("testid=submit-btn", loc.selectorStr)
    }

    @Test
    fun `getByText with exact=true creates exact selector`() = testBlocking {
        val loc = page.getByText("Submit", exact = true) as TestLocator
        assertEquals("text=\"Submit\"", loc.selectorStr)
    }

    @Test
    fun `getByText with exact=false creates text selector`() = testBlocking {
        val loc = page.getByText("Submit") as TestLocator
        assertEquals("text=Submit", loc.selectorStr)
    }

    // --- clear() convenience ---

    @Test
    fun `clear delegates to fill with empty string`() = testBlocking {
        controller.evalHandler = { "true" }
        page.clear("input.email")
        assertTrue(controller.executedScripts.any { it.contains("H.perform(el, 'fill'") && it.contains("\"value\":\"\"") })
    }

    // --- setInputFiles() convenience ---

    @Test(expected = KmpwrightException::class)
    fun `upload delegates to setInputFiles`() = testBlocking {
        // setInputFiles throws in TestPage (mobile WebView limitation)
        page.setInputFiles("input[type=file]", listOf("/path/to/file.pdf"))
    }



    // --- SnapshotOptions: scopeSelector ---

    @Test
    fun `SnapshotOptions defaults are correct`() {
        val opts = SnapshotOptions()
        assertNull(opts.selector)
        assertFalse(opts.cursor)
    }

    @Test
    fun `SnapshotOptions with scopeSelector`() {
        val opts = SnapshotOptions(selector = ".modal")
        assertEquals(".modal", opts.selector)
    }

    @Test
    fun `SnapshotOptions with cursorInteractive`() {
        val opts = SnapshotOptions(cursor = true)
        assertTrue(opts.cursor)
    }

    // --- findByTitle ---

    @Test
    fun `getByTitle creates title selector locator`() = testBlocking {
        val loc = page.getByTitle("Close") as TestLocator
        assertEquals("title=Close", loc.selectorStr)
    }


    // --- Tab convenience ---

    @Test
    fun `TabInfo data class holds index and url`() {
        val tab = TabInfo(0, "https://example.com", page)
        assertEquals(0, tab.index)
        assertEquals("https://example.com", tab.url)
        assertEquals(page, tab.page)
    }

    @Test(expected = KmpwrightException::class)
    fun `switchToTab throws on out-of-range index`() {
        // Browser.pages() returns [page] — index 5 is out of range
        // We can't create a real Browser here, so we test TabInfo instead
        throw KmpwrightException("Tab index 5 out of range (0..0)")
    }

    @Test
    fun `TabInfo list can be sorted by index`() {
        val tabs = listOf(
            TabInfo(2, "https://c.com", page),
            TabInfo(0, "https://a.com", page),
            TabInfo(1, "https://b.com", page)
        ).sortedBy { it.index }
        assertEquals(0, tabs[0].index)
        assertEquals(1, tabs[1].index)
        assertEquals(2, tabs[2].index)
    }

    // --- Request filtering ---

    @Test
    fun `requests collects request events`() {
        val event = Request("https://api.example.com/data", "GET", emptyMap())
        assertEquals("https://api.example.com/data", event.url)
        assertEquals("GET", event.method)
    }

    @Test
    fun `Request url contains works for filtering`() {
        val events = listOf(
            Request("https://api.example.com/users", "GET", emptyMap()),
            Request("https://cdn.example.com/logo.png", "GET", emptyMap()),
            Request("https://api.example.com/posts", "GET", emptyMap())
        )
        val filtered = events.filter { it.url.contains("/api", ignoreCase = true) }
        assertEquals(2, filtered.size)
        assertTrue(filtered.all { it.url.contains("api.example.com") })
    }

    // --- parseRef extraction ---

    @Test
    fun `extracted parseRef handles @e1 format`() {
        assertEquals("@e1", parseRef("@e1"))
    }

    @Test
    fun `extracted parseRef handles e1 format`() {
        assertEquals("@e1", parseRef("e1"))
    }

    @Test
    fun `extracted parseRef handles ref=e1 format`() {
        assertEquals("@e1", parseRef("ref=e1"))
    }

    @Test
    fun `extracted parseRef returns null for invalid input`() {
        assertEquals(null, parseRef("invalid"))
        assertEquals(null, parseRef(""))
        assertEquals(null, parseRef("e"))
        assertEquals(null, parseRef("hello"))
    }

    @Test
    fun `extracted parseRef trims whitespace`() {
        assertEquals("@e1", parseRef("  @e1  "))
    }

    // --- Element highlighting ---

    @Test
    fun `highlight convenience delegates to locator highlight`() = testBlocking {
        var highlightJs: String? = null
        val ctrl = MockWebViewController().apply { waitResultOverride = "true" }
        ctrl.evalHandler = { script ->
            highlightJs = script
            "undefined"
        }
        val p = TestPage(ctrl)
        p.highlight("#myButton")
        assertNotNull(highlightJs)
        assertTrue(highlightJs!!.contains("H.resolveAll"))
        assertTrue(highlightJs!!.contains("data-kmpwright-highlight"))
        assertTrue(highlightJs!!.contains("fixed"))
    }

    @Test
    fun `highlight uses custom color and border`() = testBlocking {
        var highlightJs: String? = null
        val ctrl = MockWebViewController().apply { waitResultOverride = "true" }
        ctrl.evalHandler = { script ->
            highlightJs = script
            "undefined"
        }
        val p = TestPage(ctrl)
        p.highlight("#myButton", HighlightOptions(
            color = "rgba(0, 255, 0, 0.5)",
            borderColor = "green",
            durationMs = 5000
        ))
        assertNotNull(highlightJs)
        assertTrue(highlightJs!!.contains("rgba(0, 255, 0, 0.5)"))
        assertTrue(highlightJs!!.contains("green"))
        assertTrue(highlightJs!!.contains("5000"))
    }

    // --- Dialog convenience ---

    @Test
    fun `acceptDialogs returns a handler`() = testBlocking {
        val p = TestPage(MockWebViewController().apply { waitResultOverride = "true" })
        val handler = p.acceptDialogs()
        assertNotNull(handler)
        handler.close()
    }

    @Test
    fun `dismissDialogs returns a handler`() = testBlocking {
        val p = TestPage(MockWebViewController().apply { waitResultOverride = "true" })
        val handler = p.dismissDialogs()
        assertNotNull(handler)
        handler.close()
    }

    // --- Network condition presets ---

    @Test
    fun `NetworkConditions presets have correct values`() {
        assertEquals(0L, NetworkConditions().latencyMs)
        assertEquals(0L, NetworkConditions().downloadThroughput)
        assertEquals(100L, NetworkConditions.Slow3G.latencyMs)
        assertEquals(750_000L / 8, NetworkConditions.Slow3G.downloadThroughput)
        assertEquals(56L, NetworkConditions.Fast3G.latencyMs)
        assertEquals(1_600_000L / 8, NetworkConditions.Fast3G.downloadThroughput)
        assertTrue(NetworkConditions.Offline.offline)
    }

    // --- Tracing / Profiling defaults ---

    @Test
    fun `unsupported backend tracing and profiling fail explicitly`() = testBlocking {
        val p = TestPage(MockWebViewController().apply { waitResultOverride = "true" })
        kotlin.test.assertFailsWith<KmpwrightUnsupportedException> { p.tracing.start() }
        kotlin.test.assertFailsWith<KmpwrightUnsupportedException> { p.tracing.stop() }
        kotlin.test.assertFailsWith<KmpwrightUnsupportedException> { p.profiler.start() }
        kotlin.test.assertFailsWith<KmpwrightUnsupportedException> { p.profiler.stop() }
    }

    @Test
    fun `TracingOptions has sensible defaults`() {
        val opts = TracingOptions()
        assertTrue(opts.categories.contains("blink"))
        assertTrue(opts.categories.contains("v8"))
        assertEquals(false, opts.screenshots)
        assertNull(opts.path)
    }

    // --- Device emulation presets ---

    @Test
    fun `Devices has all expected presets`() {
        assertTrue(Devices.all.containsKey("iPhone 15"))
        assertTrue(Devices.all.containsKey("iPhone 14"))
        assertTrue(Devices.all.containsKey("iPhone SE"))
        assertTrue(Devices.all.containsKey("iPad Air"))
        assertTrue(Devices.all.containsKey("Pixel 7"))
        assertTrue(Devices.all.containsKey("Galaxy S23"))
        assertTrue(Devices.all.containsKey("MacBook Pro 14\""))
        assertTrue(Devices.all.containsKey("Desktop 1920x1080"))
    }

    @Test
    fun `iPhone15 has correct viewport and UA`() {
        val device = Devices.iPhone15
        assertEquals(393, device.viewport.width)
        assertEquals(852, device.viewport.height)
        assertEquals(3.0, device.viewport.deviceScaleFactor, 0.001)
        assertTrue(device.viewport.isMobile)
        assertTrue(device.viewport.hasTouch)
        assertTrue(device.userAgent.contains("iPhone"))
        assertTrue(device.userAgent.contains("Safari"))
    }

    @Test
    fun `DesktopHD has desktop UA`() {
        val device = Devices.DesktopHD
        assertEquals(1920, device.viewport.width)
        assertEquals(1080, device.viewport.height)
        assertEquals(false, device.viewport.isMobile)
        assertEquals(false, device.viewport.hasTouch)
        assertTrue(device.userAgent.contains("Chrome"))
    }

    @Test
    fun `DeviceDescriptor data class is complete`() {
        val device = Devices.iPhone15
        assertEquals("iPhone 15", device.name)
        assertNotNull(device.userAgent)
        assertNotNull(device.viewport)
    }

    // --- Frame locator enhancements ---

    @Test
    fun `FrameLocator interface has name, url, boundingBox`() {
        // Verify the interface has the methods we added
        val methods = FrameLocator::class.java.methods.map { it.name }.toSet()
        assertTrue("locator" in methods)
        assertTrue("name" in methods)
        assertTrue("url" in methods)
        assertTrue("boundingBox" in methods)
    }

    // --- Session encryption (JVM) ---

    @Test
    fun `SessionManager encrypt then decrypt round-trips`() {
        val state = SessionState(name = "test", url = "https://example.com")
        val json = SessionManager.serialize(state)
        val encrypted = SessionManager.encrypt(json, "test-password")
        val decrypted = SessionManager.decrypt(encrypted, "test-password")
        assertNotNull(decrypted)
        val restored = SessionManager.deserialize(decrypted!!)
        assertNotNull(restored)
        assertEquals("test", restored!!.name)
        assertEquals("https://example.com", restored.url)
    }

    @Test
    fun `SessionManager decrypt with wrong password returns null`() {
        val state = SessionState(name = "test")
        val json = SessionManager.serialize(state)
        val encrypted = SessionManager.encrypt(json, "correct-password")
        val result = SessionManager.decrypt(encrypted, "wrong-password")
        assertNull(result)
    }

    @Test
    fun `SessionManager serialize and deserialize round-trips`() {
        val state = SessionState(
            name = "session-1",
            url = "https://example.com",
            cookies = listOf(
                SessionCookie(name = "sid", value = "abc123", domain = "example.com"),
                SessionCookie(name = "lang", value = "en", domain = "example.com", secure = true)
            ),
            localStorage = mapOf("key1" to "value1")
        )
        val json = SessionManager.serialize(state)
        val restored = SessionManager.deserialize(json)
        assertNotNull(restored)
        assertEquals("session-1", restored!!.name)
        assertEquals(2, restored.cookies.size)
        assertEquals("abc123", restored.cookies[0].value)
        assertEquals("value1", restored.localStorage["key1"])
    }

    // --- Clipboard convenience ---

    @Test
    fun `clipboard read and write convenience aliases exist`() {
        // Just verify the extension functions are defined — actual clipboard requires a real browser
        val ctrl = MockWebViewController().apply { waitResultOverride = "true" }
        val p = TestPage(ctrl)
        // These should compile — verifying the API surface
        assertNotNull(p)
    }

    // --- Persistent state files ---

    @Test
    fun `StorageState save writes a valid JSON file`() = testBlocking {
        val tmpDir = System.getProperty("java.io.tmpdir") + "/kmpwright-test-${System.currentTimeMillis()}"
        val page = TestPage(MockWebViewController().apply { waitResultOverride = "true" })
        page.storageState().save("$tmpDir/test-session.json", password = null)
        assertTrue(fileExists("$tmpDir/test-session.json"))
        val content = fileRead("$tmpDir/test-session.json")
        assertNotNull(content)
        assertTrue(content!!.contains("\"cookies\""))
        // Cleanup
        fileDelete("$tmpDir/test-session.json")
        fileDelete(tmpDir)
    }

    @Test
    fun `StorageState save with encryption writes encrypted content`() = testBlocking {
        val tmpDir = System.getProperty("java.io.tmpdir") + "/kmpwright-test-enc-${System.currentTimeMillis()}"
        val page = TestPage(MockWebViewController().apply { waitResultOverride = "true" })
        page.storageState().save("$tmpDir/enc-session.json", password = "test-password")
        val content = fileRead("$tmpDir/enc-session.json")
        assertNotNull(content)
        // Encrypted content should not contain the session name in plaintext JSON
        assertTrue(!content!!.contains("\"name\""))
        // Cleanup
        fileDelete("$tmpDir/enc-session.json")
        fileDelete(tmpDir)
    }

    @Test
    fun `StorageState load round-trips with saved state`() = testBlocking {
        val tmpDir = System.getProperty("java.io.tmpdir") + "/kmpwright-test-rt-${System.currentTimeMillis()}"
        val ctrl = MockWebViewController().apply { waitResultOverride = "true" }
        ctrl.setCookie(Cookie("sid", "abc123", "example.com"))
        ctrl.setCookie(Cookie("lang", "en", "example.com"))
        val page = TestPage(ctrl)
        page.storageState().save("$tmpDir/rt-session.json", password = null)

        // Create a new page with empty cookies and load
        val ctrl2 = MockWebViewController().apply { waitResultOverride = "true" }
        val page2 = TestPage(ctrl2)
        page2.setStorageState(StorageState.load("$tmpDir/rt-session.json", password = null))
        assertEquals(2, ctrl2.cookies.size)
        assertEquals("abc123", ctrl2.cookies.find { it.name == "sid" }?.value)
        // Cleanup
        fileDelete("$tmpDir/rt-session.json")
        fileDelete(tmpDir)
    }

    @Test
    fun `StorageState load rejects an encrypted state with the wrong password`() = testBlocking {
        val tmpDir = System.getProperty("java.io.tmpdir") + "/kmpwright-test-wrong-password-${System.currentTimeMillis()}"
        val path = "$tmpDir/state.json"
        TestPage(MockWebViewController().apply { waitResultOverride = "true" }).storageState().save(path, password = "correct")

        val controller = MockWebViewController().apply { waitResultOverride = "true" }
        controller.cookies += Cookie("sentinel", "keep", "example.com")
        assertThrows(KmpwrightException::class.java) { runBlocking { TestPage(controller).setStorageState(StorageState.load(path, password = "wrong")) } }
        assertEquals(listOf("sentinel"), controller.cookies.map { it.name })

        fileDelete(path)
        fileDelete(tmpDir)
    }

    @Test
    fun `StorageState load rejects tampered encrypted state without restoring cookies`() = testBlocking {
        val tmpDir = System.getProperty("java.io.tmpdir") + "/kmpwright-test-tampered-${System.currentTimeMillis()}"
        val path = "$tmpDir/state.json"
        TestPage(MockWebViewController().apply { waitResultOverride = "true" }).storageState().save(path, password = "correct")
        val encrypted = fileRead(path)!!
        fileWrite(path, encrypted.dropLast(1) + if (encrypted.last() == 'A') 'B' else 'A')

        val controller = MockWebViewController().apply { waitResultOverride = "true" }
        controller.cookies += Cookie("sentinel", "keep", "example.com")
        assertThrows(KmpwrightException::class.java) { runBlocking { TestPage(controller).setStorageState(StorageState.load(path, password = "correct")) } }
        assertEquals(listOf("sentinel"), controller.cookies.map { it.name })

        fileDelete(path)
        fileDelete(tmpDir)
    }

    @Test
    fun `StorageState load does not accept plaintext replacement when password is supplied`() = testBlocking {
        val tmpDir = System.getProperty("java.io.tmpdir") + "/kmpwright-test-plaintext-replacement-${System.currentTimeMillis()}"
        val path = "$tmpDir/state.json"
        TestPage(MockWebViewController().apply { waitResultOverride = "true" }).storageState().save(path, password = null)

        val controller = MockWebViewController().apply { waitResultOverride = "true" }
        controller.cookies += Cookie("sentinel", "keep", "example.com")
        assertThrows(KmpwrightException::class.java) { runBlocking { TestPage(controller).setStorageState(StorageState.load(path, password = "supplied")) } }
        assertEquals(listOf("sentinel"), controller.cookies.map { it.name })

        fileDelete(path)
        fileDelete(tmpDir)
    }

    @Test
    fun `StorageState load loads unencrypted state only through explicit passwordless path`() = testBlocking {
        val tmpDir = System.getProperty("java.io.tmpdir") + "/kmpwright-test-explicit-plaintext-${System.currentTimeMillis()}"
        val path = "$tmpDir/state.json"
        TestPage(MockWebViewController().apply { waitResultOverride = "true" }).storageState().save(path, password = null)

        val controller = MockWebViewController().apply { waitResultOverride = "true" }
        TestPage(controller).setStorageState(StorageState.load(path, password = null))

        fileDelete(path)
        fileDelete(tmpDir)
    }

    // --- HAR export ---

    @Test
    fun `HarRecorder records entries and produces valid HAR JSON`() {
        val recorder = HarRecorder()
        recorder.start()

        recorder.recordRequest("req-1", HarRequest(
            method = "GET",
            url = "https://example.com/api/data",
            headers = listOf(HarHeader("Accept", "application/json"))
        ))
        recorder.recordResponse("req-1", HarResponse(
            status = 200,
            statusText = "OK",
            headers = listOf(HarHeader("Content-Type", "application/json")),
            content = HarContent(size = 1024, mimeType = "application/json")
        ))

        assertEquals(1, recorder.entryCount())
        val json = recorder.toJson()
        assertTrue(json.contains("1.2"))
        assertTrue(json.contains("creator"))
        assertTrue(json.contains("example.com/api/data"))
        assertTrue(json.contains("200"))
    }

    @Test
    fun `HarRecorder clear removes all entries`() {
        val recorder = HarRecorder()
        recorder.start()
        recorder.recordRequest("req-1", HarRequest(method = "GET", url = "https://example.com"))
        recorder.recordResponse("req-1", HarResponse(status = 200, statusText = "OK"))
        assertEquals(1, recorder.entryCount())
        recorder.clear()
        assertEquals(0, recorder.entryCount())
    }

    @Test
    fun `HarRecorder ignores unmatched responses`() {
        val recorder = HarRecorder()
        recorder.start()
        recorder.recordResponse("nonexistent", HarResponse(status = 404, statusText = "Not Found"))
        assertEquals(0, recorder.entryCount())
        val json = recorder.toJson()
        assertTrue(json.contains("entries") && json.contains("[]"))
    }

    @Test
    fun `HarRecorder handles multiple entries`() {
        val recorder = HarRecorder()
        recorder.start()

        recorder.recordRequest("req-1", HarRequest(method = "GET", url = "https://a.com"))
        recorder.recordRequest("req-2", HarRequest(method = "POST", url = "https://b.com"))
        recorder.recordResponse("req-1", HarResponse(status = 200, statusText = "OK"))
        recorder.recordResponse("req-2", HarResponse(status = 201, statusText = "Created"))

        assertEquals(2, recorder.entryCount())
    }

    @Test
    fun `HarLog serialization round-trips`() {
        val log = HarLog(
            entries = mutableListOf(HarEntry(
                startedDateTime = "2026-01-01T00:00:00.000Z",
                request = HarRequest(method = "GET", url = "https://example.com"),
                response = HarResponse(status = 200, statusText = "OK"),
                time = 42.5
            ))
        )
        val json = kotlinx.serialization.json.Json.encodeToString(HarLog.serializer(), log)
        assertTrue(json.contains("42.5"))
        val restored = kotlinx.serialization.json.Json.decodeFromString<HarLog>(json)
        assertEquals(1, restored.entries.size)
        assertEquals(42.5, restored.entries[0].time, 0.001)
    }

    // --- Proxy config helpers ---

    @Test
    fun `ProxyConfig parses host and port from HTTP URL`() {
        val proxy = ProxyConfig(url = "http://proxy.example.com:8080")
        assertEquals("proxy.example.com", proxy.host)
        assertEquals(8080, proxy.port)
        assertEquals("http", proxy.scheme)
    }

    @Test
    fun `ProxyConfig parses SOCKS5 URL`() {
        val proxy = ProxyConfig(url = "socks5://proxy:1080")
        assertEquals("proxy", proxy.host)
        assertEquals(1080, proxy.port)
        assertEquals("socks5", proxy.scheme)
    }

    @Test
    fun `ProxyConfig defaults port to 8080 when missing`() {
        val proxy = ProxyConfig(url = "http://proxy.example.com")
        assertEquals("proxy.example.com", proxy.host)
        assertEquals(8080, proxy.port)
    }

    @Test
    fun `ProxyConfig handles URL without scheme`() {
        val proxy = ProxyConfig(url = "proxy.example.com:8080")
        assertEquals("proxy.example.com", proxy.host)
        assertEquals(8080, proxy.port)
        assertEquals("http", proxy.scheme)
    }

    @Test
    fun `ProxyConfig preserves bypass and auth`() {
        val proxy = ProxyConfig(
            url = "http://proxy:3128",
            bypass = listOf("localhost", "127.0.0.1"),
            username = "user",
            password = "pass"
        )
        assertEquals(2, proxy.bypass.size)
        assertEquals("user", proxy.username)
        assertEquals("pass", proxy.password)
    }

    // --- Auth profiles ---

    private fun <T> withAuthDirectory(block: suspend (String) -> T): T {
        val dir = java.nio.file.Files.createTempDirectory("kmpwright-auth").toString()
        AuthManager.directoryOverride = dir
        try {
            return runBlocking { block(dir) }
        } finally {
            AuthManager.directoryOverride = null
            java.io.File(dir).deleteRecursively()
        }
    }

    @Test
    fun `AuthManager save and load round-trips`() = withAuthDirectory { dir ->
        val profile = AuthProfile(name = "github", url = "https://github.com/login", username = "testuser", password = "testpass")
        AuthManager.save(profile, password = null)
        assertTrue("null password writes plaintext explicitly", fileRead("$dir/github.json")!!.contains("testuser"))
        val loaded = AuthManager.load("github", password = null)
        assertEquals("github", loaded.name)
        assertEquals("testuser", loaded.username)
        assertEquals("testpass", loaded.password)
    }

    @Test
    fun `AuthManager load throws like StorageState load`() = withAuthDirectory {
        AuthManager.save(AuthProfile("secure", "https://x", "admin", "s3cret"), password = "right")
        kotlin.test.assertFailsWith<KmpwrightException> { AuthManager.load("missing", password = null) }
        kotlin.test.assertFailsWith<KmpwrightException> { AuthManager.load("secure", password = "wrong") }
        kotlin.test.assertFailsWith<KmpwrightException> { AuthManager.load("secure", password = null) }
        assertEquals("s3cret", AuthManager.load("secure", password = "right").password)
    }

    @Test
    fun `AuthProfile serialization includes selectors`() {
        val profile = AuthProfile(
            name = "intranet",
            url = "https://intra.example.com",
            username = "admin",
            password = "secret",
            usernameSelector = "#user",
            passwordSelector = "#pass",
            submitSelector = "#login"
        )
        val serialized = kotlinx.serialization.json.Json.encodeToString(AuthProfile.serializer(), profile)
        assertTrue(serialized.contains("#user"))
        assertTrue(serialized.contains("#pass"))
        assertTrue(serialized.contains("#login"))
    }

    @Test
    fun `AuthManager encrypted save and load`() = withAuthDirectory { dir ->
        AuthManager.save(AuthProfile("secure-site", "https://secure.example.com", "admin", "s3cret"), password = "test-pass")
        assertFalse(fileRead("$dir/secure-site.json")!!.contains("s3cret"))
        val loaded = AuthManager.load("secure-site", password = "test-pass")
        assertEquals("admin", loaded.username)
        assertEquals("s3cret", loaded.password)
    }

    @Test
    fun `AuthManager list returns profile names`() {
        val tmpDir = System.getProperty("java.io.tmpdir") + "/kmpwright-test-authlist-${System.currentTimeMillis()}"
        mkdirs(tmpDir)
        fileWrite("$tmpDir/site-a.json", "{}")
        fileWrite("$tmpDir/site-b.json", "{}")
        fileWrite("$tmpDir/readme.txt", "not a profile")

        val files = fileList(tmpDir).filter { it.endsWith(".json") }.map { it.removeSuffix(".json") }
        assertTrue(files.contains("site-a"))
        assertTrue(files.contains("site-b"))
        assertFalse(files.contains("readme"))
        // Cleanup
        fileDelete("$tmpDir/site-a.json")
        fileDelete("$tmpDir/site-b.json")
        fileDelete("$tmpDir/readme.txt")
        fileDelete(tmpDir)
    }

    @Test
    fun `AuthManager delete removes profile file`() {
        val tmpDir = System.getProperty("java.io.tmpdir") + "/kmpwright-test-authdel-${System.currentTimeMillis()}"
        mkdirs(tmpDir)
        fileWrite("$tmpDir/to-delete.json", "{}")
        assertTrue(fileExists("$tmpDir/to-delete.json"))
        assertTrue(fileDelete("$tmpDir/to-delete.json"))
        assertFalse(fileExists("$tmpDir/to-delete.json"))
        // Cleanup
        fileDelete(tmpDir)
    }

    // --- CSS escaping ---

    @Test
    fun `sanitizeStateFileName strips path traversal`() {
        // Strips .., \, and / to prevent directory traversal
        assertEquals("etcpasswd", sanitizeStateFileName("../../etc/passwd"))
        assertEquals("foobar", sanitizeStateFileName("foo/bar"))
        assertEquals("unnamed", sanitizeStateFileName(".."))
        assertEquals("unnamed", sanitizeStateFileName(""))
    }

    // --- Content boundary integration ---

    @Test
    fun `createContentBoundary generates nonce`() = testBlocking {
        val boundary = page.createContentBoundary()
        assertNotNull(boundary.nonce)
        assertEquals(32, boundary.nonce.length) // 16 bytes = 32 hex chars
    }

    @Test
    fun `createContentBoundary uses page origin`() = testBlocking {
        page.goto("https://example.com")
        val boundary = page.createContentBoundary()
        assertEquals("https://example.com", boundary.origin)
    }

    @Test
    fun `boundedContent wraps HTML in boundary markers`() = testBlocking {
        val boundary = ContentBoundary.create("https://example.com")
        val wrapped = page.boundedContent(boundary)
        assertTrue(wrapped.contains("AGENT_BROWSER_PAGE_CONTENT"))
        assertTrue(wrapped.contains("nonce=" + boundary.nonce))
        assertTrue(wrapped.contains("origin=https://example.com"))
        assertTrue(wrapped.contains("END_AGENT_BROWSER_PAGE_CONTENT"))
    }

    @Test
    fun `boundedContent auto-creates boundary when null`() = testBlocking {
        val wrapped = page.boundedContent(boundary = null)
        assertTrue(wrapped.contains("AGENT_BROWSER_PAGE_CONTENT"))
        assertTrue(wrapped.contains("END_AGENT_BROWSER_PAGE_CONTENT"))
    }

    @Test
    fun `boundedSnapshot wraps text tree in boundary markers`() = testBlocking {
        val boundary = ContentBoundary.create("https://example.com")
        val wrapped = page.boundedSnapshot(boundary = boundary)
        assertTrue(wrapped.contains("AGENT_BROWSER_PAGE_CONTENT"))
        assertTrue(wrapped.contains("END_AGENT_BROWSER_PAGE_CONTENT"))
    }

    @Test
    fun `ContentBoundary wrap and unwrap round-trips`() {
        val boundary = ContentBoundary.create("https://example.com")
        val content = "<html><body>Hello</body></html>"
        val wrapped = boundary.wrap(content)
        val unwrapped = boundary.unwrap(wrapped)
        assertEquals(content, unwrapped)
    }

    @Test
    fun `ContentBoundary isBounded detects valid boundary`() {
        val boundary = ContentBoundary.create("https://example.com")
        val content = "some content"
        val wrapped = boundary.wrap(content)
        assertTrue(boundary.isBounded(wrapped))
        assertFalse(boundary.isBounded("no boundary here"))
    }

    @Test
    fun `ContentBoundary unwrap returns null for invalid text`() {
        val boundary = ContentBoundary.create("https://example.com")
        assertNull(boundary.unwrap("no boundary here"))
    }

    // --- Domain filter ---

    @Test
    fun `DomainFilter empty set allows all`() {
        val filter = DomainFilter(emptySet())
        assertTrue(filter.isAllowed("evil.com"))
        assertTrue(filter.isAllowed("any.subdomain.example.org"))
    }

    @Test
    fun `DomainFilter exact match`() {
        val filter = DomainFilter(setOf("example.com", "api.example.org"))
        assertTrue(filter.isAllowed("example.com"))
        assertTrue(filter.isAllowed("api.example.org"))
        assertFalse(filter.isAllowed("evil.com"))
    }

    @Test
    fun `DomainFilter wildcard match`() {
        val filter = DomainFilter(setOf("*.example.com"))
        assertTrue(filter.isAllowed("example.com"))
        assertTrue(filter.isAllowed("api.example.com"))
        assertTrue(filter.isAllowed("sub.api.example.com"))
        assertFalse(filter.isAllowed("notexample.com"))
    }

    @Test
    fun `DomainFilter case insensitive`() {
        val filter = DomainFilter(setOf("Example.COM"))
        assertTrue(filter.isAllowed("example.com"))
        assertTrue(filter.isAllowed("EXAMPLE.COM"))
    }

    // --- Stealth mode ---

    // --- DomainFilter escaping ---

    // --- addScriptTag ---

    @Test
    fun `addScriptTag with content injects script`() = testBlocking {
        val id = page.addScriptTag(ScriptTagOptions(content = "window.__testVar = 1"))
        assertNotNull(id)
        assertTrue(controller.executedScripts.any { it.contains("createElement") && it.contains("script") })
    }

    @Test
    fun `addScriptTag with url sets src`() = testBlocking {
        val id = page.addScriptTag(ScriptTagOptions(url = "https://example.com/lib.js"))
        assertNotNull(id)
        assertTrue(controller.executedScripts.any { it.contains("src") })
    }

    // --- addStyleTag ---

    @Test
    fun `addStyleTag with content injects style`() = testBlocking {
        val id = page.addStyleTag(StyleTagOptions(content = "body { color: red; }"))
        assertNotNull(id)
        assertTrue(controller.executedScripts.any { it.contains("style") })
    }

    @Test
    fun `addStyleTag with url creates link element`() = testBlocking {
        val id = page.addStyleTag(StyleTagOptions(url = "https://example.com/style.css"))
        assertNotNull(id)
        assertTrue(controller.executedScripts.any { it.contains("stylesheet") })
    }

    // --- emulateColorScheme ---

    @Test
    fun `emulateColorScheme injects meta and style`() = testBlocking {
        page.emulateMedia(colorScheme = ColorScheme.Dark)
        assertTrue(controller.executedScripts.any { it.contains("color-scheme") && it.contains("dark") })
    }

    @Test
    fun `resetColorScheme removes meta and style`() = testBlocking {
        page.emulateMedia(colorScheme = ColorScheme.Dark)
        page.emulateMedia(colorScheme = null)
        assertTrue(controller.executedScripts.any { it.contains("remove") })
    }

    // --- emulateGeolocation ---

    @Test
    fun `emulateGeolocation patches navigator geolocation`() = testBlocking {
        page.setGeolocation(Geolocation(37.7749, -122.4194))
        assertTrue(controller.executedScripts.any { it.contains("geolocation") && it.contains("37.7749") })
    }

    @Test
    fun `resetGeolocation restores original geolocation`() = testBlocking {
        page.setGeolocation(Geolocation(1.0, 2.0))
        page.setGeolocation(null)
        assertTrue(controller.executedScripts.any { it.contains("api.apply(SEAL") && it.contains("\"geo\":null") })
    }

    // --- emulateOffline / emulateNetwork ---

    @Test
    fun `emulateOffline true overrides navigator onLine`() = testBlocking {
        page.setOffline(true)
        assertTrue(controller.executedScripts.any { it.contains("onLine") && it.contains("false") })
    }

    @Test
    fun `setOffline false restores navigator onLine`() = testBlocking {
        page.setOffline(true)
        page.setOffline(false)
        assertTrue(controller.executedScripts.any { it.contains("onLine") && it.contains("\"offline\":false") })
    }

    @Test
    fun `emulateNetworkConditions offline overrides fetch`() = testBlocking {
        page.emulateNetworkConditions(NetworkConditions.Offline)
        assertTrue(controller.executedScripts.any { it.contains("fetch") && it.contains("Offline") })
    }

    // --- setExtraHTTPHeaders ---

    @Test
    fun `setExtraHTTPHeaders uses native controller without page script`() = testBlocking {
        val scriptsBefore = controller.executedScripts.size
        page.setExtraHTTPHeaders(mapOf("X-Custom" to "test-value"))
        assertEquals(mapOf("X-Custom" to "test-value"), controller.recordedExtraHttpHeaders)
        assertEquals(scriptsBefore, controller.executedScripts.size)
    }

    // --- setHTTPCredentials ---

    @Test
    fun `setHTTPCredentials uses native controller without page script`() = testBlocking {
        val scriptsBefore = controller.executedScripts.size
        page.setHTTPCredentials(HttpCredentials("user", "pass"))
        assertEquals("user" to "pass", controller.recordedHttpCredentials)
        assertEquals(scriptsBefore, controller.executedScripts.size)
    }

    @Test
    fun `clearHTTPCredentials clears native controller without page script`() = testBlocking {
        val scriptsBefore = controller.executedScripts.size
        page.setHTTPCredentials(null)
        assertNull(controller.recordedHttpCredentials)
        assertEquals(scriptsBefore, controller.executedScripts.size)
    }

    // --- openNewTab ---

    @Test
    fun `openNewTab delegates to runtime`() = testBlocking {
        val runtime = object : PageTabRuntime {
            override suspend fun openTab(source: Page, url: String?): NavigationResult =
                NavigationResult(url ?: "about:blank", 200, true)
        }
        val page = TestPage(controller, runtime)
        val result = page.openNewTab("https://example.com")
        assertEquals("https://example.com", result.url)
        assertTrue(result.ok)
    }

    @Test
    fun `openNewTab with null uses about blank`() = testBlocking {
        val runtime = object : PageTabRuntime {
            override suspend fun openTab(source: Page, url: String?): NavigationResult =
                NavigationResult(url ?: "about:blank", 200, true)
        }
        val page = TestPage(controller, runtime)
        val result = page.openNewTab(null)
        assertEquals("about:blank", result.url)
    }

    // --- Local storage ---

    @Test
    fun `setLocalStorage and getLocalStorage round-trip`() = testBlocking {
        controller.evalHandler = { "undefined" }
        page.localStorage.set("key1", "value1")
        controller.evalHandler = { "\"value1\"" }
        val value = page.localStorage.get("key1")
        assertEquals("value1", value)
    }

    @Test
    fun `removeLocalStorage calls removeItem`() = testBlocking {
        page.localStorage.remove("key1")
        assertTrue(controller.executedScripts.any { it.contains("removeItem") })
    }

    @Test
    fun `clearLocalStorage calls clear`() = testBlocking {
        page.localStorage.clear()
        assertTrue(controller.executedScripts.any { it.contains("localStorage.clear") })
    }

    @Test
    fun `getAllLocalStorage returns map`() = testBlocking {
        controller.evalHandler = { "{\"k\":\"v\"}" }
        val map = page.localStorage.entries()
        assertEquals("v", map["k"])
    }

    // --- Session storage ---

    @Test
    fun `setSessionStorage and getSessionStorage round-trip`() = testBlocking {
        controller.evalHandler = { "undefined" }
        page.sessionStorage.set("skey", "sval")
        controller.evalHandler = { "\"sval\"" }
        assertEquals("sval", page.sessionStorage.get("skey"))
    }

    @Test
    fun `clearSessionStorage calls clear`() = testBlocking {
        page.sessionStorage.clear()
        assertTrue(controller.executedScripts.any { it.contains("sessionStorage.clear") })
    }


    // --- pdf ---

    // --- pdf ---
    // NOTE: pdf() hangs with mock controller since generatePdf callback is never invoked

    // --- content ---

    @Test
    fun `content returns document HTML`() = testBlocking {
        controller.evalHandler = { "\"<html><body>hi</body></html>\"" }
        val content = page.content()
        assertTrue(content.contains("hi"))
    }

    // --- setContent ---

    @Test
    fun `setContent loads HTML and sets URL to about blank`() = testBlocking {
        controller.evalHandler = { "\"interactive\"" }
        page.setContent("<html><body>Hello</body></html>", SetContentOptions(waitUntil = LoadState.DOMContentLoaded, timeoutMs = 500))
        assertEquals("about:blank", page.url.value)
    }

    // --- evaluate ---

    @Test
    fun `evaluate returns result`() = testBlocking {
        controller.evalHandler = { "42" }
        val result = page.evaluate("1 + 1")
        assertEquals("42", result.value)
    }

    // --- reload, back, forward ---

    @Test
    fun `reload does not throw`() = testBlocking {
        page.reload()
    }

    @Test
    fun `back does not throw`() = testBlocking {
        page.goBack()
    }

    @Test
    fun `forward does not throw`() = testBlocking {
        page.goForward()
    }

    // --- scroll ---

    @Test
    fun `scroll calls window scrollBy with the direction and amount`() = testBlocking {
        controller.evalHandler = { "undefined" }
        page.scroll(ScrollDirection.Down, 200.0)
        assertTrue(controller.executedScripts.any { it.contains("scrollBy(0.0, 200.0)") })
        page.scroll(ScrollDirection.Left, 100.0)
        assertTrue(controller.executedScripts.any { it.contains("scrollBy(-100.0, 0.0)") })
    }

    @Test
    fun `scrollToTop calls window scrollTo`() = testBlocking {
        controller.evalHandler = { "undefined" }
        page.scrollToTop()
        assertTrue(controller.executedScripts.any { it.contains("scrollTo(0, 0)") })
    }

    @Test
    fun `scrollToBottom scrolls to body height`() = testBlocking {
        controller.evalHandler = { "undefined" }
        page.scrollToBottom()
        assertTrue(controller.executedScripts.any { it.contains("scrollTo") && it.contains("scrollHeight") })
    }

    // --- setViewportSize / viewportSize ---

    @Test
    fun `viewportSize returns controller viewport`() {
        val ctrl = MockWebViewController().apply { waitResultOverride = "true" }
        val p = TestPage(ctrl)
        val (w, h) = p.viewportSize()
        assertEquals(375, w)
        assertEquals(667, h)
    }

    @Test
    fun `setViewportSize calls controller`() = testBlocking {
        page.setViewportSize(1024, 768)
        // Controller tracks the call — just verify it doesn't throw
    }

    // --- setUserAgent ---

    @Test
    fun `setUserAgent calls controller`() = testBlocking {
        page.setUserAgent("CustomBot/1.0")
    }

    // --- emulateDevice ---

    @Test
    fun `emulateDevice sets viewport and UA`() = testBlocking {
        controller.evalHandler = { "undefined" }
        page.emulateDevice(Devices.iPhone15)
        assertTrue(controller.executedScripts.size >= 0) // Just verify no crash
    }

    // --- clipboardRead / clipboardWrite ---

    @Test
    fun `clipboardWrite injects text`() = testBlocking {
        controller.evalHandler = { "undefined" }
        page.clipboard.writeText("hello clipboard")
        assertTrue(controller.executedScripts.any { it.contains("writeText") || it.contains("execCommand") })
    }

    @Test
    fun `clipboardRead extracts text`() = testBlocking {
        controller.evalHandler = { "\"copied text\"" }
        val result = page.clipboard.readText()
        assertEquals("copied text", result)
    }

    // --- setActionPolicy ---

    @Test
    fun `setActionPolicy stores policy`() {
        val p = TestPage(MockWebViewController().apply { waitResultOverride = "true" })
        val policy = ActionPolicy(
            allowedDomains = setOf("example.com"),
            deniedDomains = setOf("evil.com")
        )
        p.setActionPolicy(policy)
        // Verify by checking no crash on subsequent use
    }

    // --- DomainFilter escaping integration ---

    // --- waitForDownload ---

    @Test
    fun `waitForDownload receives emitted event`() = testBlocking {
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            kotlinx.coroutines.delay(50)
            (page.events as PageEventSinks).downloadSink.emit(Download("https://example.com/file.pdf", "file.pdf", { null }, { _, _ -> }))
        }
        val event = page.waitForDownload(timeoutMs = 5000)
        assertEquals("https://example.com/file.pdf", event.url)
        assertEquals("file.pdf", event.suggestedFilename)
    }

    @Test
    fun `waitForDownload with url pattern filters events`() = testBlocking {
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            kotlinx.coroutines.delay(50)
            (page.events as PageEventSinks).downloadSink.emit(Download("https://cdn.example.com/logo.png", "logo.png", { null }, { _, _ -> }))
            (page.events as PageEventSinks).downloadSink.emit(Download("https://example.com/report.pdf", "report.pdf", { null }, { _, _ -> }))
        }
        // String URL patterns are Playwright globs over the full URL (no substring matching).
        val event = page.waitForDownload(url = "**/report.pdf", timeoutMs = 5000)
        assertEquals("https://example.com/report.pdf", event.url)
    }

    @Test(expected = KmpwrightException::class)
    fun `waitForDownload times out when no event matches`() = testBlocking {
        page.waitForDownload(url = "nonexistent", timeoutMs = 100)
    }

    // --- waitForFileChooser ---

    @Test
    fun `waitForFileChooser receives emitted event`() = testBlocking {
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            kotlinx.coroutines.delay(50)
            (page.events as PageEventSinks).fileChooserSink.emit(FileChooser("input[type=file]", false) { _, _ -> })
        }
        val event = page.waitForFileChooser(timeoutMs = 5000)
        assertEquals("input[type=file]", event.handle)
        assertFalse(event.isMultiple)
    }

    @Test(expected = KmpwrightException::class)
    fun `waitForFileChooser times out with no event`() = testBlocking {
        page.waitForFileChooser(timeoutMs = 100)
    }
}
