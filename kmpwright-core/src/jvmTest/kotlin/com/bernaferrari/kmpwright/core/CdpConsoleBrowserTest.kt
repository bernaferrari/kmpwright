package com.bernaferrari.kmpwright.core

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * Real-Chromium console and page-error contracts (Playwright's `console` / `pageerror`), including
 * same-process and out-of-process iframes. Requires KMPWRIGHT_TEST_CHROME; skipped otherwise.
 */
class CdpConsoleBrowserTest {
    companion object {
        private var launched: LaunchedBrowser? = null
        private var server: com.sun.net.httpserver.HttpServer? = null
        private val port get() = server!!.address.port

        @BeforeClass @JvmStatic
        fun startChrome() {
            val executable = System.getenv("KMPWRIGHT_TEST_CHROME")
            if (executable.isNullOrBlank()) return
            server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/") { exchange ->
                    val body = when (exchange.requestURI.path) {
                        // localhost vs 127.0.0.1 are different sites: an out-of-process iframe under site isolation.
                        "/oopif" -> "<script>console.log('from oopif', location.hostname)</script>"
                        "/second" -> "<script>console.log('second document')</script>"
                        else -> "<p>root</p>"
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
            runBlocking { launched = launchBrowser(executable) }
        }

        @AfterClass @JvmStatic
        fun stopChrome() {
            runBlocking { runCatching { launched?.close() } }
            server?.stop(0)
        }
    }

    private fun browserTest(block: suspend kotlinx.coroutines.CoroutineScope.(Page) -> Unit) {
        assumeTrue("Set KMPWRIGHT_TEST_CHROME for real browser contracts", launched != null)
        runBlocking {
            val page = launched!!.browser.newPage()
            try { block(page) } finally { runCatching { page.close() } }
        }
    }

    private suspend fun <T> eventually(read: () -> T, done: (T) -> Boolean): T = withTimeout(10_000) {
        while (true) {
            val value = read()
            if (done(value)) return@withTimeout value
            delay(25)
        }
        @Suppress("UNREACHABLE_CODE") error("unreachable")
    }

    @Test
    fun `console messages carry Playwright types, formatted arguments and locations`() = browserTest { page ->
        val first = async(start = CoroutineStart.UNDISPATCHED) { page.events.console.first() }
        page.goto("http://127.0.0.1:$port/")
        page.setContent("""<script>
            console.log('loaded', 1, {a: 1, b: 'x'}, [1, 2]);
            console.warn('careful');
            console.error('bad', null, undefined);
            console.debug('dbg'); console.info('inf');
            console.assert(false, 'asserted');
            </script>""")
        val messages = eventually({ page.consoleMessages() }) { it.size >= 6 }
        assertEquals(listOf("log", "warning", "error", "debug", "info", "assert"), messages.map { it.type })
        assertEquals("loaded 1 {a: 1, b: x} [1, 2]", messages[0].text)
        assertEquals("careful", messages[1].text)
        assertEquals("bad null undefined", messages[2].text)
        assertEquals("asserted", messages[5].text)
        assertTrue(messages[0].location.toString(), messages[0].location!!.lineNumber >= 0)
        assertEquals("loaded 1 {a: 1, b: x} [1, 2]", first.await().text)
        // console.error is not a page error.
        assertTrue(page.pageErrors().isEmpty())
    }

    @Test
    fun `uncaught errors and unhandled rejections are page errors, not console messages`() = browserTest { page ->
        val firstError = async(start = CoroutineStart.UNDISPATCHED) { page.events.pageError.first() }
        page.setContent("""<script>
            setTimeout(function() { throw new TypeError('kaboom'); }, 0);
            setTimeout(function() { Promise.reject(new Error('rejected')); }, 10);
            setTimeout(function() { Promise.reject('plain'); }, 20);
            </script>""")
        val errors = eventually({ page.pageErrors() }) { it.size >= 3 }
        assertEquals(listOf("TypeError: kaboom", "Error: rejected", "plain"), errors)
        val structured = firstError.await()
        assertEquals("TypeError", structured.name)
        assertEquals("kaboom", structured.message)
        assertTrue(structured.stack.orEmpty(), structured.stack.orEmpty().contains("kaboom"))
        assertTrue(page.consoleMessages().toString(), page.consoleMessages().isEmpty())
        val waited = async(start = CoroutineStart.UNDISPATCHED) { page.waitForEvent("pageerror") }
        page.evaluate("setTimeout(function() { throw new Error('waited') }, 0)")
        assertEquals("waited", (waited.await() as PageError).message)
    }

    @Test
    fun `iframe console output is captured, including out-of-process frames`() = browserTest { page ->
        page.goto("http://127.0.0.1:$port/")
        page.evaluate("""(function() {
            var same = document.createElement('iframe');
            same.srcdoc = "<script>console.log('from srcdoc')<\/script>";
            document.body.appendChild(same);
            var cross = document.createElement('iframe');
            cross.src = 'http://localhost:$port/oopif';
            document.body.appendChild(cross);
        })()""")
        val texts = eventually({ page.consoleMessages().map { it.text } }) { "from srcdoc" in it && "from oopif localhost" in it }
        assertTrue(texts.toString(), texts.containsAll(listOf("from srcdoc", "from oopif localhost")))
    }

    @Test
    fun `history survives navigation and the default view starts at the last commit`() = browserTest { page ->
        page.setContent("<script>console.log('first document')</script>")
        eventually({ page.consoleMessages() }) { it.isNotEmpty() }
        page.goto("http://127.0.0.1:$port/second")
        eventually({ page.consoleMessages() }) { it.isNotEmpty() }
        assertEquals(listOf("second document"), page.consoleMessages().map { it.text })
        assertEquals(listOf("first document", "second document"), page.consoleMessages(PageMessageFilter.All).map { it.text })
        page.clearConsoleMessages()
        assertTrue(page.consoleMessages(PageMessageFilter.All).isEmpty())
    }
}
