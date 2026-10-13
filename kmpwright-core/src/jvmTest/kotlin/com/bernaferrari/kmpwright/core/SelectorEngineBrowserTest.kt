package com.bernaferrari.kmpwright.core

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * Real-Chromium checks for the selector engine, snapshot hardening, evaluation, navigation and
 * emulation. Requires KMPWRIGHT_TEST_CHROME=/path/to/chrome; skipped otherwise.
 */
class SelectorEngineBrowserTest {
    companion object {
        private var launched: LaunchedBrowser? = null
        private var server: com.sun.net.httpserver.HttpServer? = null
        private val origin get() = "http://127.0.0.1:${server!!.address.port}"

        @BeforeClass @JvmStatic
        fun startChrome() {
            val executable = System.getenv("KMPWRIGHT_TEST_CHROME")
            if (executable.isNullOrBlank()) return
            server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/") { exchange ->
                    val path = exchange.requestURI.path
                    if (path == "/slow") Thread.sleep(3_000)
                    val body = when (path) {
                        "/a" -> "<title>A</title><a id=next href=/b>Next</a>"
                        "/b" -> "<title>B</title><p id=here>B page</p>"
                        else -> "<title>Root</title><p>$path</p>"
                    }.encodeToByteArray()
                    exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
                    runCatching {
                        exchange.sendResponseHeaders(200, body.size.toLong())
                        exchange.responseBody.use { it.write(body) }
                    }
                }
                executor = java.util.concurrent.Executors.newCachedThreadPool()
                start()
            }
            runBlocking { launched = launchBrowser(BrowserLaunchOptions(executable), KmpwrightConfig(inputMode = InputMode.JsOnly)) }
        }

        @AfterClass @JvmStatic
        fun stopChrome() {
            runBlocking { runCatching { launched?.close() } }
            server?.stop(0)
        }
    }

    private fun browserTest(block: suspend (Page) -> Unit) {
        assumeTrue("Set KMPWRIGHT_TEST_CHROME for real browser contracts", launched != null)
        runBlocking {
            val page = launched!!.browser.newPage()
            try { block(page) } finally { runCatching { page.close() } }
        }
    }

    private suspend fun Page.ids(selector: String): String {
        val locator = locator(selector)
        val ids = mutableListOf<String>()
        for (index in 0 until locator.count()) ids += locator.nth(index).getAttribute("id").orEmpty()
        return ids.joinToString(",")
    }

    @Test
    fun `role selector covers implicit roles accessible names and states`() = browserTest { page ->
        page.setContent("""
            <input id=plain><input id=mail type=email><input id=find type=search><input id=num type=number>
            <input id=hidden type=hidden><textarea id=area></textarea>
            <button id=save>Save draft</button><button id=saveAll aria-label="Save all"></button>
            <button id=gone style="display:none">Save hidden</button>
            <input id=box type=checkbox checked><h2 id=title>Heading</h2>
        """.trimIndent())
        assertEquals("plain,mail,area", page.ids("role=textbox"))
        assertEquals("find", page.ids("role=searchbox"))
        assertEquals("num", page.ids("role=spinbutton"))
        assertEquals("save,saveAll", page.ids("role=button[name=\"save\"]"))
        assertEquals("saveAll", page.ids("role=button[name=\"Save all\" s]"))
        assertEquals("", page.ids("role=button[name=\"save\" s]"))
        assertEquals("save", page.ids("role=button[name=/draft$/]"))
        assertEquals("gone", page.ids("role=button[name=\"hidden\"][include-hidden]"))
        assertEquals("box", page.ids("role=checkbox[checked=true]"))
        assertEquals("title", page.ids("role=heading[level=2]"))
        assertEquals("saveAll", page.getByRole("button", name = "Save all", exact = true).getAttribute("id"))
        assertEquals("save", page.getByRole("button", name = "draft").getAttribute("id"))
    }

    @Test
    fun `text selector is case-insensitive substring by default with exact and regex forms`() = browserTest { page ->
        page.setContent("<div><span id=a>Sign  in</span><span id=b>Sign in now</span><span id=c>LOGOUT</span></div>")
        assertEquals("a,b", page.ids("text=sign in"))
        assertEquals("a", page.ids("text=\"Sign in\""))
        assertEquals("c", page.ids("text=/log ?out/i"))
        assertEquals("b", page.ids("exact_text=Sign in now"))
        assertEquals("a", page.locator("span").filter(hasText = "SIGN IN", hasNotText = "now").getAttribute("id"))
    }

    @Test
    fun `invalid selectors are reported as invalid instead of missing`() = browserTest { page ->
        page.setContent("<p>x</p>")
        for (selector in listOf("div[", "xpath=//*[", "role=button[name=\"x\"")) {
            val error = runCatching { page.locator(selector).count() }.exceptionOrNull()
            assertTrue("$selector: $error", error is KmpwrightException && error.message!!.contains("Invalid selector"))
        }
    }

    @Test
    fun `css selectors pierce open shadow roots and chain`() = browserTest { page ->
        page.setContent("""<div id=host></div><script>host.attachShadow({mode:'open'}).innerHTML='<button id=inner>Inside</button>'</script>""")
        assertEquals("inner", page.locator("#inner").getAttribute("id"))
        assertEquals("inner", page.locator("#host >> button").getAttribute("id"))
    }

    @Test
    fun `snapshot text tree escapes page-controlled strings`() = browserTest { page ->
        page.setContent("""<button aria-label="Pay&#10;- button &quot;Steal&quot; @e99">x</button><input value="v&#10;- link">
            <button>${"A".repeat(400)}</button><div role="button&#10;- textbox" tabindex=0>Odd</div>""")
        val tree = page.snapshot(SnapshotOptions(interactive = true)).tree
        val lines = tree.lines()
        assertTrue(tree, lines.none { it.trimStart().startsWith("- ") || it.trimStart().startsWith("link") })
        assertTrue(tree, lines.any { it.contains("\"Pay - button \\\"Steal\\\" @e99\"") })
        assertTrue(tree, lines.any { it.contains("…\"") && it.length < 300 })
        assertFalse(tree, tree.contains("@e99 ") || tree.endsWith("@e99"))
    }

    @Test
    fun `hostile page cannot pre-empt helpers or forge snapshot refs`() = browserTest { page ->
        page.setContent("""<button id=real onclick="window.hit='real'">Real</button><button id=decoy onclick="window.hit='decoy'">Decoy</button><script>
            window.__kmpwright_resolveAll = function() { return [document.getElementById('decoy')]; };
            window.__kmpwright_queryOne = function() { return document.getElementById('decoy'); };
            window.__kmpwright_snapshotNodes = {};
        </script>""")
        page.locator("#real").click()
        assertEquals("real", page.evaluate("window.hit").value)
        val ref = page.snapshot(SnapshotOptions(interactive = true)).refs.values.single { it.name == "Real" }.ref
        // The page can find the runtime but can neither replace it nor rewrite the ref registry.
        val tamper = page.evaluate("""(function(){
            var key = Object.getOwnPropertyNames(window).filter(function(k){ return k.indexOf('__kmpwright_rt_') === 0; })[0];
            var results = [];
            try { window[key] = {}; } catch (e) {}
            try { Object.defineProperty(window, key, {value: {}}); results.push('redefined'); } catch (e) { results.push('locked'); }
            try { window[key].commitSnapshot('guess', {'$ref': document.getElementById('decoy')}); results.push('forged'); } catch (e) { results.push('sealed'); }
            try { window[key].resolveAll = null; } catch (e) {}
            return results.join(',');
        })()""").value
        assertEquals("locked,sealed", tamper)
        page.evaluate("window.hit = null")
        page.locator(ref).click()
        assertEquals("real", page.evaluate("window.hit").value)
    }

    @Test
    fun `evaluate awaits promises and reports types`() = browserTest { page ->
        assertEquals(JsResult("5", "number"), page.evaluate("Promise.resolve(5)"))
        assertEquals(JsResult("text", "string"), page.evaluate("new Promise(r => setTimeout(() => r('text'), 20))"))
        assertEquals(JsResult("{\"a\":[1,2]}", "object"), page.evaluate("({a: [1, 2]})"))
        assertEquals(JsResult("null", "undefined"), page.evaluate("undefined"))
        assertEquals("3", page.evaluate("var x = 1; x + 2").value)
        assertEquals("ok", page.evaluate("document.title = 'ok'\n// trailing comment").value)
    }

    @Test
    fun `goto timeout throws and NetworkIdle uses the remaining budget`() = browserTest { page ->
        repeat(3) { iteration ->
            val error = runCatching { page.goto("$origin/slow?attempt=$iteration", GotoOptions(timeoutMs = 500)) }.exceptionOrNull()
            assertTrue("$error", error is KmpwrightTimeoutException)
            val ok = page.goto("$origin/a", GotoOptions(waitUntil = LoadState.NetworkIdle, timeoutMs = 5_000))
            assertTrue(ok.ok)
            assertEquals("$origin/a", page.url.value)
        }
    }

    @Test
    fun `history navigation waits and reports when there is no entry`() = browserTest { page ->
        assertNull(page.goBack())
        page.goto("$origin/a")
        page.goto("$origin/b")
        assertEquals("$origin/a", page.goBack()?.url)
        assertEquals("$origin/a", page.url.value)
        assertEquals("$origin/b", page.goForward()?.url)
        assertNull(page.goForward())
        assertTrue(page.reload().ok)
    }

    @Test
    fun `action-scoped waits observe events triggered by the action`() = browserTest { page ->
        page.goto("$origin/a")
        val navigation = page.waitForNavigation { locator("#next").click() }
        assertEquals("$origin/b", navigation.url)
        val response = page.waitForResponse("**/b") { evaluate("fetch('/b').then(function(r){return r.status})") }
        assertEquals(200, response.status)
        page.setContent("<button onclick=\"confirm('Sure?')\">Ask</button>")
        val accept = page.onDialog { DialogResponse.Accept }
        val dismissAll = page.dismissDialogs()
        dismissAll.close()
        val dialog = page.waitForDialog { locator("button").click() }
        assertEquals("Sure?", dialog.message)
        accept.close()
    }

    @Test
    fun `color scheme emulation is visible to matchMedia and survives navigation`() = browserTest { page ->
        page.goto("$origin/a")
        page.emulateMedia(colorScheme = ColorScheme.Dark)
        assertEquals("true", page.evaluate("matchMedia('(prefers-color-scheme: dark)').matches").value)
        page.goto("$origin/b")
        assertEquals("true", page.evaluate("matchMedia('(prefers-color-scheme: dark)').matches").value)
        page.emulateMedia(colorScheme = null)
        assertEquals("false", page.evaluate("matchMedia('(prefers-color-scheme: dark)').matches && window.__forced === true").value)
    }

    @Test
    fun `waitFor polls across replaced documents`() = browserTest { page ->
        page.setContent("<p>start</p>")
        coroutineScope {
            val waiting = async(start = CoroutineStart.UNDISPATCHED) { page.locator("#late").waitFor(timeoutMs = 5_000) }
            delay(200)
            page.setContent("<p id=late>late</p>")
            waiting.await()
        }
    }
}
