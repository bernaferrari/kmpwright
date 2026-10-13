package com.bernaferrari.kmpwright.core

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.nio.file.Files
import kotlin.time.TimeSource

/**
 * Real-Chromium checks that KMPwright behaves like Playwright for input, locators, URL patterns,
 * assertions and navigation. Every input test runs twice: with native CDP input ([InputMode.Hybrid])
 * and with the shared WebView implementation that Android and iOS run ([InputMode.JsOnly]).
 * Requires KMPWRIGHT_TEST_CHROME=/path/to/chrome; skipped otherwise.
 */
class PlaywrightParityBrowserTest {
    companion object {
        private var hybrid: LaunchedBrowser? = null
        private var jsOnly: LaunchedBrowser? = null
        private var server: com.sun.net.httpserver.HttpServer? = null
        private val origin get() = "http://127.0.0.1:${server!!.address.port}"

        @BeforeClass @JvmStatic
        fun startChrome() {
            val executable = System.getenv("KMPWRIGHT_TEST_CHROME")
            if (executable.isNullOrBlank()) return
            server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/") { exchange ->
                    val path = exchange.requestURI.path
                    var status = 200
                    var body = "<title>Root</title><p id=path>$path</p>"
                    when {
                        path.startsWith("/status/") -> {
                            status = path.removePrefix("/status/").toInt()
                            body = "<title>Status $status</title><p>status $status</p>"
                        }
                        path == "/redirect" -> {
                            exchange.responseHeaders.add("Location", "/landed")
                            status = 302
                            body = ""
                        }
                        path == "/landed" -> body = "<title>Landed</title><p id=landed>landed</p>"
                        path == "/slow.png" -> { Thread.sleep(2_500); body = "" }
                        path == "/slow-load" -> body = "<title>Slow</title><p id=ready>ready</p><img src=/slow.png>"
                        path == "/api/items" -> body = """{"page":"${exchange.requestURI.query.orEmpty()}"}"""
                    }
                    val bytes = body.encodeToByteArray()
                    exchange.responseHeaders.add("Content-Type", if (path.startsWith("/api")) "application/json" else "text/html; charset=utf-8")
                    runCatching {
                        exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
                        if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) } else exchange.close()
                    }
                }
                executor = java.util.concurrent.Executors.newCachedThreadPool()
                start()
            }
            runBlocking {
                hybrid = launchBrowser(executable, config = KmpwrightConfig(timeouts = TimeoutConfig(action = 5_000)))
                jsOnly = launchBrowser(executable, config = KmpwrightConfig(inputMode = InputMode.JsOnly, timeouts = TimeoutConfig(action = 5_000)))
            }
        }

        @AfterClass @JvmStatic
        fun stopChrome() {
            runBlocking {
                runCatching { hybrid?.close() }
                runCatching { jsOnly?.close() }
            }
            server?.stop(0)
        }
    }

    /** Runs [block] on a fresh page of the native-input browser and again on the JS-input browser. */
    private fun bothModes(block: suspend (Page, String) -> Unit) {
        assumeTrue("Set KMPWRIGHT_TEST_CHROME for real browser contracts", hybrid != null)
        for ((mode, launched) in listOf("native" to hybrid!!, "js" to jsOnly!!)) {
            runBlocking {
                val page = launched.browser.newPage()
                try { block(page, mode) } catch (error: Throwable) {
                    throw AssertionError("[$mode input] ${error.message}", error)
                } finally { runCatching { page.close() } }
            }
        }
    }

    private fun nativeOnly(block: suspend (Page) -> Unit) {
        assumeTrue("Set KMPWRIGHT_TEST_CHROME for real browser contracts", hybrid != null)
        runBlocking {
            val page = hybrid!!.browser.newPage()
            try { block(page) } finally { runCatching { page.close() } }
        }
    }

    private suspend fun Page.js(expression: String) = evaluate(expression).value

    private val eventLogger = """<script>window.log=[];window.times={};
        ['mousedown','mouseup','click','dblclick','keydown','keyup','input','keypress','beforeinput'].forEach(function(t){
          document.addEventListener(t, function(e){
            log.push(t + (e.detail !== undefined && /mouse|click/.test(t) ? ':' + e.detail : '') + (e.key ? ':' + e.key : ''));
            (times[t] = times[t] || []).push(performance.now());
          }, true);
        });</script>"""

    // ------------------------------------------------------------------ 1. dblclick

    @Test
    fun `dblclick dispatches the Playwright event sequence`() = bothModes { page, _ ->
        page.setContent("<button id=b>Twice</button>$eventLogger")
        page.locator("#b").dblclick()
        assertEquals("mousedown:1,mouseup:1,click:1,mousedown:2,mouseup:2,click:2,dblclick:2", page.js("log.join(',')"))
    }

    // ------------------------------------------------------------------ 2. actionability

    @Test
    fun `actions retry until the element is enabled, uncovered and present`() = bothModes { page, _ ->
        page.setContent("""
            <button id=late disabled onclick="this.textContent='clicked'">Late</button>
            <div id=cover style="position:fixed;inset:0;background:rgba(0,0,0,.1)"></div>
            <script>
              setTimeout(function(){ late.disabled = false; }, 300);
              setTimeout(function(){ cover.remove(); }, 600);
              setTimeout(function(){ var b = document.createElement('button'); b.id = 'added'; b.textContent = 'Added';
                b.onclick = function(){ b.textContent = 'done'; }; document.body.appendChild(b); }, 400);
            </script>
        """.trimIndent())
        page.locator("#late").click()
        assertEquals("clicked", page.locator("#late").innerText())
        page.locator("#added").click()
        assertEquals("done", page.locator("#added").innerText())
    }

    @Test
    fun `actionability failures report readable messages identical in both input modes`() = bothModes { page, _ ->
        page.setContent("""<button id=b>Hidden</button><div id=cover style="position:fixed;inset:0;background:#000"></div>""")
        val covered = runCatching { page.locator("#b").click(timeoutMs = 400) }.exceptionOrNull()
        assertTrue("$covered", covered is KmpwrightTimeoutException)
        assertTrue(covered!!.message!!, covered.message!!.contains("would not receive the click: it is covered by"))
        assertFalse(covered.message!!, covered.message!!.contains("__kmpwright_"))
        page.setContent("<button id=d disabled>Off</button>")
        val disabled = runCatching { page.locator("#d").click(timeoutMs = 300) }.exceptionOrNull()
        assertTrue(disabled!!.message!!, disabled.message!!.contains("is not enabled"))
        val missing = runCatching { page.locator("#nope").hover(timeoutMs = 300) }.exceptionOrNull()
        assertTrue(missing!!.message!!, missing.message!!.contains("was not found"))
        val zero = runCatching { page.locator("#d").click(timeoutMs = 0) }.exceptionOrNull()
        assertTrue("$zero", zero is KmpwrightException)
    }

    // ------------------------------------------------------------------ 3. delays

    @Test
    fun `delayMs is the time between down and up events`() = bothModes { page, _ ->
        page.setContent("<button id=b>B</button><input id=i>$eventLogger")
        page.locator("#b").click(delayMs = 300)
        val clickGap = page.js("times.mouseup[0] - times.mousedown[0]").toDouble()
        assertTrue("mousedown->mouseup was ${clickGap}ms", clickGap >= 250)
        page.locator("#b").dblclick(delayMs = 150)
        assertEquals("150ms dblclick still dispatches both clicks",
            "click:1,click:2,dblclick:2", page.js("log.filter(function(x){return /click/.test(x)}).slice(1).join(',')"))
        val secondGap = page.js("times.mouseup[2] - times.mousedown[2]").toDouble()
        assertTrue("second press of dblclick was ${secondGap}ms", secondGap >= 120)
        page.locator("#i").press("a", delayMs = 300)
        val keyGap = page.js("times.keyup[times.keyup.length-1] - times.keydown[times.keydown.length-1]").toDouble()
        assertTrue("keydown->keyup was ${keyGap}ms", keyGap >= 250)
        assertEquals("a", page.locator("#i").inputValue())
    }

    // ------------------------------------------------------------------ 4/5. keys

    @Test
    fun `key names ControlOrMeta, Space and code names work on locators`() = bothModes { page, _ ->
        page.setContent("<input id=i value='old text'>")
        val input = page.locator("#i")
        input.press("ControlOrMeta+A")
        input.press("x")
        assertEquals("x", input.inputValue())
        input.press("Space")
        input.press("KeyB")
        input.press("Shift+Digit1")
        assertEquals("x b!", input.inputValue())
        input.press("Backspace")
        repeat(3) { input.press("ArrowLeft") }
        input.press("Delete")
        assertEquals(" b", input.inputValue())
        for (key in listOf("Enter", "Tab", "Escape", "ArrowLeft", "ArrowRight", "ArrowUp", "ArrowDown", "PageUp", "PageDown", "Insert", "F1", "F12")) {
            page.locator("#i").press(key)
        }
    }

    @Test
    fun `page keyboard press accepts combinations and type sends per-character key events`() = nativeOnly { page ->
        page.setContent("<input id=i value='abc'>$eventLogger")
        page.locator("#i").focus()
        page.keyboard.press("ControlOrMeta+A")
        page.keyboard.type("hi")
        assertEquals("hi", page.locator("#i").inputValue())
        val typed = page.js("log.filter(function(x){return /^(keydown|keyup|input)/.test(x) && !/Meta|Control/.test(x)}).slice(-6).join(',')")
        assertEquals("keydown:h,input,keyup:h,keydown:i,input,keyup:i", typed)
        page.keyboard.insertText("!")
        assertEquals("hi!", page.locator("#i").inputValue())
    }

    // ------------------------------------------------------------------ 6. refs in chains

    @Test
    fun `snapshot refs are accepted in chained locators`() = bothModes { page, _ ->
        page.setContent("<div id=box><button>Inside</button></div><button>Outside</button>")
        val snapshot = page.snapshot()
        val ref = snapshot.refs.values.first { it.name == "Inside" }.ref
        val bare = ref.removePrefix("@")
        for (form in listOf(ref, bare, "ref=$bare", "ref=$ref")) {
            assertEquals(form, "Inside", page.locator("#box").locator(form).innerText())
            assertEquals(form, "Inside", page.locator("body").locator("#box >> $form").innerText())
        }
        assertEquals(0, page.locator("#nothing").locator(ref).count())
    }

    // ------------------------------------------------------------------ 7. frame locators

    @Test
    fun `locator frameLocator is scoped to the locator and nested frames split on chevrons`() = bothModes { page, _ ->
        page.setContent("""
            <div id=first><iframe srcdoc="<p>first frame</p>"></iframe></div>
            <div id=second><iframe srcdoc="<p>second frame</p>"></iframe></div>
            <iframe id=outer srcdoc="<iframe id=mid srcdoc='<iframe id=inner srcdoc=&quot;<p>deep</p>&quot;></iframe>'></iframe>"></iframe>
        """.trimIndent())
        page.waitForFunction("document.querySelector('#outer')?.contentDocument?.querySelector('#mid')?.contentDocument?.querySelector('#inner')?.contentDocument?.querySelector('p')")
        assertEquals("second frame", page.locator("#second").frameLocator("iframe").locator("p").innerText())
        assertEquals("first frame", page.locator("#first").frameLocator("iframe").locator("p").innerText())
        assertEquals("deep", page.frameLocator("#outer").frameLocator("#mid >> #inner").locator("p").innerText())
        assertEquals("deep", page.locator("body").frameLocator("#outer >> #mid >> #inner").locator("p").innerText())
    }

    // ------------------------------------------------------------------ 8. check / files / scroll

    @Test
    fun `check waits for the element, setInputFiles honours timeouts and scrolling only happens when needed`() = bothModes { page, mode ->
        page.setContent("""<div style="height:3000px"><button id=top>top</button></div><button id=bottom>bottom</button>
            <script>setTimeout(function(){ var c = document.createElement('input'); c.type = 'checkbox'; c.id = 'c';
              document.body.insertBefore(c, document.body.firstChild); }, 300);</script>""")
        page.locator("#c").check()
        assertTrue(page.locator("#c").isChecked())
        page.locator("#c").uncheck()
        assertFalse(page.locator("#c").isChecked())

        page.js("window.scrollTo(0, 5)")
        page.locator("#top").scrollIntoViewIfNeeded()
        assertEquals("5", page.js("String(Math.round(window.scrollY))"))
        page.locator("#bottom").scrollIntoViewIfNeeded()
        assertTrue(page.js("window.scrollY").toDouble() > 1000)

        val started = TimeSource.Monotonic.markNow()
        val missing = runCatching { page.locator("#file").setInputFiles(emptyList(), timeoutMs = 300) }.exceptionOrNull()
        assertTrue("$missing", missing is KmpwrightTimeoutException)
        assertTrue(started.elapsedNow().inWholeMilliseconds < 3_000)
        page.setContent("""<input id=file type=file style="display:none"><button id=notfile>x</button>""")
        page.locator("#file").setInputFiles(emptyList())
        val wrong = runCatching { page.locator("#notfile").setInputFiles(emptyList()) }.exceptionOrNull()
        assertTrue("$wrong", wrong?.message.orEmpty().contains("HTMLInputElement"))
        if (mode == "native") {
            val upload = Files.createTempFile("kmpwright", ".txt").also { Files.writeString(it, "x") }
            page.locator("#file").setInputFiles(listOf(upload.toString()))
            assertEquals("1", page.js("String(document.querySelector('#file').files.length)"))
            val two = runCatching { page.locator("#file").setInputFiles(listOf(upload.toString(), upload.toString())) }.exceptionOrNull()
            assertTrue("$two", two?.message.orEmpty().contains("Non-multiple"))
        }
    }

    // ------------------------------------------------------------------ 9. URL patterns

    @Test
    fun `one URL rule for route, waits and toHaveURL`() = nativeOnly { page ->
        page.goto("$origin/start")
        val exact = page.waitForRequest("$origin/api/items?page=2") { evaluate("fetch('/api/items?page=2')") }
        assertEquals("$origin/api/items?page=2", exact.url)
        val glob = page.waitForResponse("**/api/items?page=3") { evaluate("fetch('/api/items?page=3')") }
        assertEquals(200, glob.status)
        val braces = page.waitForRequest("**/api/{items,other}?page=4") { evaluate("fetch('/api/items?page=4')") }
        assertTrue(braces.url.endsWith("page=4"))
        // "?" is literal, so it does not match an arbitrary character.
        val literal = runCatching { page.waitForRequest("**/api/items?page=5", 500) { evaluate("fetch('/api/itemsXpage=5')") } }.exceptionOrNull()
        assertTrue("$literal", literal is KmpwrightTimeoutException)

        page.route("**/api/items?page=9") { it.fulfill(json = """{"routed":true}""") }
        assertEquals("true", page.js("fetch('/api/items?page=9').then(function(r){return r.json()}).then(function(j){return String(j.routed)})"))
        assertEquals("page=8", page.js("fetch('/api/items?page=8').then(function(r){return r.json()}).then(function(j){return j.page})"))
        page.unroute("**/api/items?page=9")
        assertEquals("page=9", page.js("fetch('/api/items?page=9').then(function(r){return r.json()}).then(function(j){return j.page})"))

        expect(page).toHaveURL("$origin/start")
        expect(page).toHaveURL("**/start")
        expect(page).not.toHaveURL("/start", timeoutMs = 200)
        page.waitForURL(Regex("/landed$")) { evaluate("location.href = '/landed'") }
        page.waitForURL({ it.endsWith("/start") }) { goBack() }
        val navigation = page.waitForNavigation(url = "**/landed") { evaluate("location.href = '/landed'") }
        assertEquals("$origin/landed", navigation.url)
    }

    // ------------------------------------------------------------------ 10. assertions

    @Test
    fun `assertions poll once per attempt, never pass on missing elements and throw AssertionError`() = nativeOnly { page ->
        page.setContent("<p id=empty></p>")
        val started = TimeSource.Monotonic.markNow()
        val empty = runCatching { expect(page.locator("#missing")).toBeEmpty(timeoutMs = 300) }.exceptionOrNull()
        assertTrue("$empty", empty is KmpwrightAssertionError && empty is AssertionError)
        assertTrue("toBeEmpty took ${started.elapsedNow()}", started.elapsedNow().inWholeMilliseconds < 2_000)
        expect(page.locator("#empty")).toBeEmpty()
        expect(page.locator("#empty")).toHaveText("")
        val text = runCatching { expect(page.locator("#missing")).toHaveText("", timeoutMs = 200) }.exceptionOrNull()
        assertTrue("$text", text is KmpwrightAssertionError)
        val contains = runCatching { expect(page.locator("#missing")).toContainText("", timeoutMs = 200) }.exceptionOrNull()
        assertTrue("$contains", contains is KmpwrightAssertionError)
        expect(page.locator("#missing")).not.toHaveText("", timeoutMs = 200)
        // Non-assertion waits keep their timeout exception type.
        val wait = runCatching { page.locator("#missing").waitFor(SelectorState.Attached, 100) }.exceptionOrNull()
        assertTrue("$wait", wait is KmpwrightTimeoutException)
    }

    @Test
    fun `element reads wait for attachment and text assertions use textContent by default`() = bothModes { page, _ ->
        page.setContent("""<label id=lab>Name <input id=name value=Ada></label><div id=plain>x</div>
            <script>setTimeout(function(){ var p = document.createElement('p'); p.id = 'late'; p.setAttribute('data-x', '1');
              p.innerHTML = 'Hello <span style="display:none">hidden</span>world'; document.body.appendChild(p); }, 300);</script>""")
        assertEquals("1", page.locator("#late").getAttribute("data-x"))
        assertEquals("Hello hiddenworld", page.locator("#late").textContent())
        assertEquals("Hello world", page.locator("#late").innerText())
        assertTrue(page.locator("#late").innerHTML().contains("<span"))
        assertEquals("Ada", page.locator("#name").inputValue())
        assertEquals("Ada", page.locator("#lab").inputValue())
        val notInput = runCatching { page.locator("#plain").inputValue() }.exceptionOrNull()
        assertTrue("$notInput", notInput is KmpwrightException && notInput.message!!.contains("<input>"))
        expect(page.locator("#late")).toHaveText("Hello hiddenworld")
        expect(page.locator("#late")).toHaveText("Hello world", useInnerText = true)
        expect(page.locator("#late")).toContainText("hidden")
        expect(page.locator("#late")).not.toContainText("hidden", timeoutMs = 200, useInnerText = true)
        expect(page.locator("#name")).toHaveValue("Ada")
        expect(page.locator("#late")).toHaveAttribute("data-x")
        expect(page.locator("#missing")).not.toHaveAttribute("data-x", timeoutMs = 100)
        expect(page.locator("#missing")).not.toHaveValue("", timeoutMs = 100)
    }

    @Test
    fun `element reads fail when the element never appears`() {
        assumeTrue("Set KMPWRIGHT_TEST_CHROME for real browser contracts", hybrid != null)
        runBlocking {
            val launched = launchBrowser(System.getenv("KMPWRIGHT_TEST_CHROME"),
                config = KmpwrightConfig(timeouts = TimeoutConfig(action = 300)))
            try {
                val page = launched.browser.newPage()
                page.setContent("<p>x</p>")
                val started = TimeSource.Monotonic.markNow()
                for (read in listOf<suspend () -> Unit>({ page.locator("#nope").textContent() }, { page.locator("#nope").innerText() },
                        { page.locator("#nope").getAttribute("id") }, { page.locator("#nope").inputValue() }, { page.locator("#nope").innerHTML() })) {
                    val error = runCatching { read() }.exceptionOrNull()
                    assertTrue("$error", error is KmpwrightTimeoutException)
                }
                assertTrue(started.elapsedNow().inWholeMilliseconds < 5_000)
            } finally { launched.close() }
        }
    }

    // ------------------------------------------------------------------ 11. waitForFunction

    @Test
    fun `waitForFunction awaits promises, evaluates once per poll and returns the truthy value`() = nativeOnly { page ->
        page.setContent("<p>x</p>")
        assertEquals("42", page.waitForFunction("new Promise(function(r){ setTimeout(function(){ r(42) }, 200) })").value)
        page.js("window.flag = false; setTimeout(function(){ window.flag = 'ready' }, 300)")
        assertEquals("ready", page.waitForFunction("Promise.resolve(window.flag)").value)
        val counted = page.waitForFunction("(window.calls = (window.calls || 0) + 1) >= 3 && window.calls")
        assertEquals("3", counted.value)
        assertEquals("3", page.js("String(window.calls)"))
        assertEquals("fn", page.waitForFunction("() => 'fn'").value)
        val error = runCatching { page.waitForFunction("missingFunction()", 1_000) }.exceptionOrNull()
        assertTrue("$error", error is KmpwrightException && error !is KmpwrightTimeoutException)
        val timeout = runCatching { page.waitForFunction("Promise.resolve(0)", 300) }.exceptionOrNull()
        assertTrue("$timeout", timeout is KmpwrightTimeoutException)
    }

    // ------------------------------------------------------------------ 12. load states and status

    @Test
    fun `DOMContentLoaded resolves before load and statuses follow response ok`() = bothModes { page, _ ->
        val started = TimeSource.Monotonic.markNow()
        val result = page.goto("$origin/slow-load", waitUntil = LoadState.DOMContentLoaded)
        assertTrue("goto(DOMContentLoaded) took ${started.elapsedNow()}", started.elapsedNow().inWholeMilliseconds < 2_000)
        assertTrue(result.ok)
        assertEquals("ready", page.locator("#ready").innerText())
        assertEquals("loading-done", page.js("document.readyState === 'complete' ? 'complete' : 'loading-done'"))
        page.waitForLoadState(LoadState.Load)
        assertEquals("complete", page.js("document.readyState"))
        val content = TimeSource.Monotonic.markNow()
        page.setContent("<p id=c>content</p><img src='$origin/slow.png'>", waitUntil = LoadState.DOMContentLoaded)
        assertTrue("setContent(DOMContentLoaded) took ${content.elapsedNow()}", content.elapsedNow().inWholeMilliseconds < 2_000)
        assertEquals("content", page.locator("#c").innerText())

        val notFound = page.goto("$origin/status/404")
        assertEquals(404, notFound.status)
        assertFalse(notFound.ok)
        assertNull(notFound.error)
        assertEquals("$origin/status/404", page.url.value)
        val created = page.goto("$origin/status/201")
        assertEquals(201, created.status)
        assertTrue(created.ok)
        val redirected = page.goto("$origin/redirect")
        assertEquals("$origin/landed", redirected.url)
        assertEquals(200, redirected.status)
        assertTrue(redirected.ok)
    }

    // ------------------------------------------------------------------ 13. same-document navigation

    @Test
    fun `same-document navigations resolve waitForNavigation and update url and title`() = bothModes { page, _ ->
        page.goto("$origin/start")
        val pushed = page.waitForNavigation { evaluate("history.pushState({}, '', '/pushed')") }
        assertEquals("$origin/pushed", pushed.url)
        assertEquals("$origin/pushed", page.url.value)
        page.waitForNavigation { evaluate("location.hash = 'section'") }
        assertEquals("$origin/pushed#section", page.url.value)
        page.waitForNavigation { evaluate("history.replaceState({}, '', '/replaced')") }
        assertEquals("$origin/replaced", page.url.value)
        page.evaluate("document.title = 'Renamed'")
        withTimeout(3_000) { while (page.title.value != "Renamed") delay(20) }
        // replaceState replaced the #section entry, so going back lands on /pushed.
        page.waitForNavigation { goBack() }
        assertEquals("$origin/pushed", page.url.value)
    }

    @Test
    fun `waitForURL resolves on pushState without a load`() = bothModes { page, _ ->
        page.goto("$origin/start")
        coroutineScope {
            val wait = async { page.waitForURL("**/spa/route") }
            delay(100)
            page.evaluate("setTimeout(function(){ history.pushState({}, '', '/spa/route') }, 100)")
            wait.await()
        }
        if (page.url.value != "$origin/spa/route") fail("url flow is ${page.url.value}")
    }
}
