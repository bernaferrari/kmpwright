package com.bernaferrari.kmpwright.core

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.InetSocketAddress
import kotlin.time.TimeSource

/**
 * Real Chromium contracts for the CDP-native emulation hooks, history probing and the sealed
 * accessibility metadata. Run with KMPWRIGHT_TEST_CHROME=/path/to/chrome; skipped otherwise.
 */
class CdpNativeEmulationBrowserTest {
    private val served = java.util.concurrent.CopyOnWriteArrayList<String>()

    private fun withBrowser(pages: Map<String, String>, block: suspend (Page, String) -> Unit) = runBlocking<Unit> {
        val executable = System.getenv("KMPWRIGHT_TEST_CHROME")
        assumeTrue("Set KMPWRIGHT_TEST_CHROME for real browser contracts", !executable.isNullOrBlank())
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            served += path
            if (path.startsWith("/redirect")) {
                exchange.responseHeaders.add("Location", "http://localhost:${server.address.port}/blocked-redirect")
                exchange.sendResponseHeaders(302, -1)
                exchange.close()
                return@createContext
            }
            val body = (pages[path] ?: if (path.endsWith(".svg")) SVG else "<title>$path</title>$path").encodeToByteArray()
            exchange.responseHeaders.add("Content-Type", when {
                path.endsWith(".json") -> "application/json"
                path.endsWith(".svg") -> "image/svg+xml"
                else -> "text/html; charset=utf-8"
            })
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        val launched = launchBrowser(BrowserLaunchOptions(executable!!))
        try {
            block(launched.browser.newPage(), "http://127.0.0.1:${server.address.port}")
        } finally {
            withTimeout(15_000) { launched.close() }
            server.stop(0)
        }
    }

    @Test
    fun `page-defined accessibility globals cannot forge names or redirect a ref`() = withBrowser(mapOf(
        "/forged" to """
            <title>forged</title>
            <button id=cancel onclick="window.clicked='cancel'">Cancel</button>
            <button id=del onclick="window.clicked='delete'">Delete account</button>
            <script>
              var del = document.getElementById('del');
              var forged = {role: 'button', name: 'Cancel', states: []};
              var fakeMeta = {get: function() { return forged; }, set: function() {}, has: function() { return true; }};
              var fakeNodes = {get: function() { return {deref: function() { return del; }}; }, set: function() {}, has: function() { return true; }};
              var fakeTree = [{nodeId: '1', role: {value: 'button'}, name: {value: 'Cancel'}, childIds: []}];
              function lock(name, value) {
                Object.defineProperty(window, name, {get: function() { return value; }, set: function() {}, configurable: false});
              }
              lock('__kmpwright_browserAX', fakeMeta);
              lock('__kmpwright_browserAXNodes', fakeNodes);
              lock('__kmpwright_browserAXTree', fakeTree);
            </script>
        """.trimIndent()
    )) { page, origin ->
        assertTrue(page.goto("$origin/forged").ok)
        val snapshot = page.snapshot()
        val buttons = snapshot.refs.values.filter { it.role == "button" }
        assertEquals("both real buttons must be listed with their own names: ${snapshot.refs}",
            setOf("Cancel", "Delete account"), buttons.map { it.name }.toSet())
        val cancel = buttons.single { it.name == "Cancel" }
        page.locator(cancel.ref).click()
        assertEquals("the ref described as Cancel must act on Cancel", "cancel", page.evaluate("String(window.clicked)").value)
        // Role selectors use the same sealed metadata.
        page.locator("role=button[name=\"Delete account\"]").click()
        assertEquals("delete", page.evaluate("String(window.clicked)").value)
    }

    @Test
    fun `color scheme geolocation and network conditions are emulated natively`() = withBrowser(mapOf(
        "/scheme" to """
            <title>scheme</title>
            <style>body{background:rgb(255,255,255)} @media (prefers-color-scheme: dark){body{background:rgb(0,0,0)}}</style>
            <p>scheme</p>
        """.trimIndent(),
        "/data.json" to "{\"ok\":true}"
    )) { page, origin ->
        assertTrue(page.goto("$origin/scheme").ok)
        val background = "getComputedStyle(document.body).backgroundColor"

        page.emulateMedia(colorScheme = ColorScheme.Dark)
        // CSS media queries (not only matchMedia) observe the native override.
        assertEquals("rgb(0, 0, 0)", page.evaluate(background).value)
        assertEquals("true", page.evaluate("String(matchMedia('(prefers-color-scheme: dark)').matches)").value)
        assertTrue(page.goto("$origin/scheme?again").ok)
        assertEquals("override must survive navigation", "rgb(0, 0, 0)", page.evaluate(background).value)
        page.emulateMedia(colorScheme = ColorScheme.Light)
        assertEquals("rgb(255, 255, 255)", page.evaluate(background).value)
        page.emulateMedia(colorScheme = null)

        page.setGeolocation(Geolocation(48.8584, 2.2945))
        val position = page.evaluate("""
            new Promise(function(resolve, reject) {
              navigator.geolocation.getCurrentPosition(function(p) { resolve(p.coords.latitude + ',' + p.coords.longitude); },
                function(e) { resolve('error:' + e.code + ':' + e.message); }, {timeout: 5000});
            })
        """.trimIndent()).value
        assertEquals("48.8584,2.2945", position)
        assertEquals("geolocation is native, not a patched function", "true",
            page.evaluate("String(/\\[native code\\]/.test(navigator.geolocation.getCurrentPosition.toString()))").value)

        page.emulateNetworkConditions(NetworkConditions(latencyMs = 600))
        val started = TimeSource.Monotonic.markNow()
        assertEquals("true", page.evaluate("fetch('/data.json?latency', {cache: 'no-store'}).then(function(r) { return r.json(); }).then(function(j) { return String(j.ok); })").value)
        assertTrue("latency must be applied, took ${started.elapsedNow()}", started.elapsedNow().inWholeMilliseconds >= 500)

        page.setOffline(true)
        assertEquals("false", page.evaluate("String(navigator.onLine)").value)
        assertEquals("failed", page.evaluate("fetch('/data.json?offline', {cache: 'no-store'}).then(function() { return 'ok'; }, function() { return 'failed'; })").value)
        page.setOffline(false)
        assertEquals("ok", page.evaluate("fetch('/data.json?online', {cache: 'no-store'}).then(function() { return 'ok'; }, function() { return 'failed'; })").value)

        val capabilities = page.capabilities()
        assertEquals(SupportLevel.Supported, capabilities.networkEmulation)
        assertTrue(capabilities.notes.any { it.contains("geolocation", ignoreCase = true) })
    }

    @Test
    fun `domain filter fails disallowed hosts at the request layer`() = withBrowser(emptyMap()) { page, origin ->
        val blocked = origin.replace("127.0.0.1", "localhost")
        page.setAllowedDomains(listOf("127.0.0.1"))
        assertTrue(page.goto("$origin/start").ok)
        page.evaluate("""
            window.results = {};
            fetch('/data.json?allowed').then(function(r) { results.allowedFetch = 'ok'; }, function() { results.allowedFetch = 'error'; });
            fetch('$blocked/data.json?blocked-fetch', {mode: 'no-cors'}).then(function() { results.blockedFetch = 'ok'; }, function() { results.blockedFetch = 'error'; });
            fetch('/redirect?sub', {mode: 'no-cors'}).then(function() { results.redirectFetch = 'ok'; }, function() { results.redirectFetch = 'error'; });
            var bad = new Image(); bad.onload = function() { results.blockedImg = 'load'; }; bad.onerror = function() { results.blockedImg = 'error'; };
            bad.src = '$blocked/blocked-img.svg';
            var frame = document.createElement('iframe'); frame.src = '$blocked/blocked-frame'; document.body.appendChild(frame);
            try { var socket = new WebSocket('${blocked.replace("http", "ws")}/blocked-socket');
              socket.onerror = function() { results.socket = 'error'; }; socket.onopen = function() { results.socket = 'open'; };
            } catch (e) { results.socket = 'threw'; }
            true
        """.trimIndent())
        page.waitForFunction("['allowedFetch','blockedFetch','redirectFetch','blockedImg','socket'].every(function(k) { return results[k] !== undefined; })", 10_000)
        assertEquals("ok", page.evaluate("results.allowedFetch").value)
        assertEquals("error", page.evaluate("results.blockedFetch").value)
        assertEquals("error", page.evaluate("results.redirectFetch").value)
        assertEquals("error", page.evaluate("results.blockedImg").value)
        assertFalse(page.goto("$blocked/blocked-nav").ok)
        assertFalse(page.goto("$origin/redirect").ok)
        kotlinx.coroutines.delay(500)
        // WebSocket handshakes are invisible to Fetch interception (documented in the capability notes).
        val reached = served.filter { it.contains("blocked") && it != "/blocked-socket" }
        assertTrue("disallowed requests must never reach the server: $reached", reached.isEmpty())
    }

    @Test
    fun `history traversal uses the browser navigation history`() = withBrowser(emptyMap()) { page, origin ->
        // A fresh page has a single (about:blank) history entry.
        val started = TimeSource.Monotonic.markNow()
        assertNull("no back entry", page.goBack())
        assertNull("no forward entry", page.goForward())
        assertTrue("traversal without history must not wait for a probe, took ${started.elapsedNow()}",
            started.elapsedNow().inWholeMilliseconds < 1_000)

        assertTrue(page.goto("$origin/a").ok)
        assertTrue(page.goto("$origin/b").ok)
        val back = page.goBack()
        assertNotNull(back)
        assertTrue(back!!.url, back.url.endsWith("/a"))
        val forward = page.goForward()
        assertNotNull(forward)
        assertTrue(forward!!.url, forward.url.endsWith("/b"))
        assertNull(page.goForward())
        assertFalse(page.url.value.orEmpty().endsWith("/a"))
    }
}

private const val SVG = "<svg xmlns='http://www.w3.org/2000/svg' width='2' height='2'><rect width='2' height='2'/></svg>"
