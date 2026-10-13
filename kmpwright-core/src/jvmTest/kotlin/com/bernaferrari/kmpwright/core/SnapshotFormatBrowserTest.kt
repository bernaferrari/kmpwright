package com.bernaferrari.kmpwright.core

import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * Real-Chromium checks that `page.snapshot` prints agent-browser's snapshot format
 * (`@e1 [heading] "Example Domain" [level=1]`). Requires KMPWRIGHT_TEST_CHROME=/path/to/chrome.
 */
class SnapshotFormatBrowserTest {
    companion object {
        private var launched: LaunchedBrowser? = null
        private var server: com.sun.net.httpserver.HttpServer? = null
        private val origin get() = "http://127.0.0.1:${server!!.address.port}"

        private const val PAGE = """<!doctype html><title>Checkout</title>
            <h1>Example Domain</h1>
            <p>Some <b>intro</b> text.</p>
            <form onsubmit="return false">
              <input type="email" placeholder="Email">
              <label><input type="checkbox" checked> Remember me</label>
              <button disabled>Later</button>
              <button>Submit</button>
            </form>
            <a href="/more">Learn more</a>
            <iframe title="payment-frame" src="/frame"></iframe>"""

        private const val FRAME = """<!doctype html>
            <input aria-label="Card number">
            <button onclick="parent.paid = 'yes'">Pay</button>"""

        @BeforeClass @JvmStatic
        fun startChrome() {
            val executable = System.getenv("KMPWRIGHT_TEST_CHROME")
            if (executable.isNullOrBlank()) return
            server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/") { exchange ->
                    val body = (if (exchange.requestURI.path == "/frame") FRAME else PAGE).encodeToByteArray()
                    exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
                    runCatching {
                        exchange.sendResponseHeaders(200, body.size.toLong())
                        exchange.responseBody.use { it.write(body) }
                    }
                }
                executor = java.util.concurrent.Executors.newCachedThreadPool()
                start()
            }
            runBlocking { launched = launchBrowser(executable, config = KmpwrightConfig(inputMode = InputMode.JsOnly)) }
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

    private val interactiveLines get() = listOf(
        "@e1 [heading] \"Example Domain\" [level=1]",
        "@e2 [input type=\"email\"] placeholder=\"Email\"",
        "@e3 [checkbox] \"Remember me\" [checked]",
        "@e4 [button] \"Later\" [disabled]",
        "@e5 [button] \"Submit\"",
        "@e6 [link] \"Learn more\" url=\"$origin/more\"",
        "@e7 [Iframe] \"payment-frame\"",
        "  @e8 [input] \"Card number\"",
        "  @e9 [button] \"Pay\"",
    )

    @Test
    fun `snapshot -i -u prints agent-browser lines with iframe content nested`() = browserTest { page ->
        page.goto("$origin/")
        assertEquals(interactiveLines.joinToString("\n"), page.runCommand("snapshot -i -u").output)
    }

    @Test
    fun `full snapshot indents structure and text like agent-browser`() = browserTest { page ->
        page.goto("$origin/")
        assertEquals(
            listOf(
                "@e1 [heading] \"Example Domain\" [level=1]",
                "[paragraph]",
                "  [StaticText] \"Some intro text.\"",
                "[form]",
                "  @e2 [input type=\"email\"] placeholder=\"Email\"",
                "  @e3 [checkbox] \"Remember me\" [checked]",
                "  @e4 [button] \"Later\" [disabled]",
                "  @e5 [button] \"Submit\"",
                "@e6 [link] \"Learn more\"",
                "@e7 [Iframe] \"payment-frame\"",
                "  @e8 [input] \"Card number\"",
                "  @e9 [button] \"Pay\"",
            ).joinToString("\n"),
            page.runCommand("snapshot").output
        )
        val compact = page.runCommand("snapshot -c").output
        assertTrue(compact, "[paragraph]" !in compact && compact.lines().containsAll(listOf("[form]", "  @e5 [button] \"Submit\"")))
    }

    @Test
    fun `DOM walker used by WebView backends prints the same interactive lines`() = browserTest { page ->
        page.goto("$origin/")
        // includeHidden switches CDP to the DOM walker that iOS and Android always use.
        val tree = page.snapshot(interactive = true, urls = true, includeHidden = true).tree
        assertEquals(interactiveLines.joinToString("\n"), tree)
        val full = page.snapshot(includeHidden = true).tree
        assertTrue(full, full.lines().containsAll(listOf("[paragraph]", "  [StaticText] \"Some intro text.\"", "  @e3 [checkbox] \"Remember me\" [checked]")))
    }

    @Test
    fun `refs inside an iframe act on the iframe element`() = browserTest { page ->
        page.goto("$origin/")
        page.runCommand("snapshot -i")
        page.runCommand("fill @e8 4111")
        assertEquals("4111", page.frameLocator("iframe").locator("input").inputValue())
        page.runCommand("click @e9")
        assertEquals("yes", page.evaluate("window.paid").value)
    }

    @Test
    fun `annotated screenshot legend matches agent-browser`() = browserTest { page ->
        page.goto("$origin/")
        val output = page.runCommand("screenshot --annotate").output.lines()
        assertTrue(output.first(), output.first().startsWith("Screenshot "))
        assertEquals(
            listOf(
                "[1] @e1 heading \"Example Domain\"",
                "[2] @e2 textbox \"Email\"",
                "[3] @e3 checkbox \"Remember me\"",
                "[4] @e4 button \"Later\"",
                "[5] @e5 button \"Submit\"",
                "[6] @e6 link \"Learn more\"",
                "[7] @e7 Iframe \"payment-frame\"",
                "[8] @e8 textbox \"Card number\"",
                "[9] @e9 button \"Pay\"",
            ),
            output.drop(1)
        )
    }

    @Test
    fun `unknown and stale refs fail fast instead of waiting or reading empty`() = browserTest { page ->
        page.goto("$origin/")
        page.runCommand("snapshot -i")
        for (command in listOf("get text @e99", "get html @e99", "get value @e99", "get attr @e99 href", "get box @e99",
            "is visible @e99", "is focused @e99", "click @e99", "fill @e99 x", "wait @e99")) {
            val started = System.nanoTime()
            val error = runCatching { page.runCommand(command) }.exceptionOrNull()
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            assertTrue("$command: $error", error is KmpwrightException && error !is KmpwrightTimeoutException)
            assertEquals(command, "Unknown ref @e99 \u2014 run snapshot again", error!!.message)
            assertTrue("$command took ${elapsedMs}ms", elapsedMs < 2_000)
        }
        val typed = runCatching { page.locator("@e99").innerText() }.exceptionOrNull()
        assertEquals("Unknown ref @e99 \u2014 run snapshot again", typed?.message)

        page.evaluate("document.querySelector('button:not([disabled])').remove()")
        val stale = runCatching { page.runCommand("click @e5") }.exceptionOrNull()
        assertEquals("Ref @e5 is no longer attached \u2014 run snapshot again", stale?.message)
        assertEquals(false, runCatching { page.locator("@e5").isVisible() }.isSuccess)
        // A ref that is gone already satisfies a wait for it to disappear.
        page.locator("@e5").waitFor(SelectorState.Detached, 1_000)
        page.locator("@e5").waitFor(SelectorState.Hidden, 1_000)
    }

    @Test
    fun `snapshot -s accepts any selector`() = browserTest { page ->
        page.goto("$origin/")
        assertEquals("@e1 [button] \"Later\" [disabled]", page.runCommand("snapshot -i -s \"form >> button\"").output)
        assertEquals("@e2 [button] \"Submit\"", page.runCommand("snapshot -s role=button[name=\"Submit\"]").output)
        // Each snapshot replaces the ref table, so scope by a ref from the latest one.
        assertEquals("@e2 [button] \"Submit\"", page.runCommand("snapshot -s @e2").output)
        val unknown = runCatching { page.runCommand("snapshot -s @e42") }.exceptionOrNull()
        assertEquals("Unknown ref @e42 \u2014 run snapshot again", unknown?.message)
    }

    @Test
    fun `get title reads the live document title`() = browserTest { page ->
        page.goto("$origin/")
        assertEquals("Checkout", page.runCommand("get title").output)
        page.evaluate("document.title = 'Paid'")
        assertEquals("Paid", page.runCommand("get title").output)
    }
}
