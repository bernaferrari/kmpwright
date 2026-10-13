@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.bernaferrari.kmpwright.core

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.value
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.addressOf
import kotlinx.coroutines.delay
import platform.darwin.DISPATCH_QUEUE_PRIORITY_DEFAULT
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_global_queue
import platform.posix.AF_INET
import platform.posix.SOCK_STREAM
import platform.posix.SOL_SOCKET
import platform.posix.SO_REUSEADDR
import platform.posix.accept
import platform.posix.bind
import platform.posix.getsockname
import platform.posix.listen
import platform.posix.recv
import platform.posix.send
import platform.posix.setsockopt
import platform.posix.shutdown
import platform.posix.SHUT_RDWR
import platform.posix.sockaddr_in
import platform.posix.socket
import platform.posix.socklen_tVar
import kotlin.concurrent.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Real-WKWebView contracts for the native iOS hooks: the domain filter (navigation policy plus a
 * WKContentRuleList for subresources), the color-scheme override and history probing.
 *
 * "127.0.0.1" and "localhost" reach the same in-process fixture server, so a request to the disallowed
 * host would succeed (and be recorded) unless WebKit blocks it. Needs an app host like
 * [IOSWebKitDocumentTest]; excluded from the bare `iosSimTest` executable and run through
 * `scripts/run-ios-webkit-tests.sh`.
 */
class IOSNativeEmulationDocumentTest {

    @Test
    fun domain_filter_blocks_subresources_and_navigations_to_disallowed_hosts() = runWebKitTest(60_000) {
        requireWebKitDocuments()
        val server = FixtureServer()
        val page = IOSPage(IOSWebViewController())
        val allowed = "http://127.0.0.1:${server.port}"
        val blocked = "http://localhost:${server.port}"
        try {
            assertEquals(SupportLevel.Supported, page.capabilities().domainFiltering)
            page.setAllowedDomains(listOf("127.0.0.1"))
            val start = page.goto("$allowed/start")
            assertTrue(start.ok, "allowed navigation: $start")

            page.evaluate("""
                window.results = {};
                fetch('/data?allowed').then(function(r) { return r.json(); })
                  .then(function(j) { results.allowedFetch = 'ok:' + j.ok; }, function() { results.allowedFetch = 'error'; });
                fetch('$blocked/data?blocked-fetch', {mode: 'no-cors'})
                  .then(function() { results.blockedFetch = 'ok'; }, function() { results.blockedFetch = 'error'; });
                fetch('/redirect?subresource', {mode: 'no-cors'})
                  .then(function() { results.redirectFetch = 'ok'; }, function() { results.redirectFetch = 'error'; });
                var good = new Image(); good.onload = function() { results.allowedImg = 'load'; }; good.onerror = function() { results.allowedImg = 'error'; };
                good.src = '/allowed.svg';
                var bad = new Image(); bad.onload = function() { results.blockedImg = 'load'; }; bad.onerror = function() { results.blockedImg = 'error'; };
                bad.src = '$blocked/blocked-img.svg';
                var frame = document.createElement('iframe'); frame.src = '$blocked/blocked-frame'; document.body.appendChild(frame);
                var okFrame = document.createElement('iframe'); okFrame.src = '/allowed-frame';
                okFrame.onload = function() { try { results.allowedFrame = okFrame.contentDocument.title; } catch (e) { results.allowedFrame = 'error'; } };
                document.body.appendChild(okFrame);
                try { var socket = new WebSocket('ws://localhost:${server.port}/blocked-socket');
                  socket.onerror = function() { results.socket = 'error'; }; socket.onopen = function() { results.socket = 'open'; };
                } catch (e) { results.socket = 'threw'; }
                true
            """.trimIndent())
            page.waitForFunction("['allowedFetch','blockedFetch','redirectFetch','allowedImg','blockedImg','allowedFrame','socket'].every(function(k) { return results[k] !== undefined; })", 15_000)
            assertEquals("ok:true", page.evaluate("results.allowedFetch").value)
            assertEquals("error", page.evaluate("results.blockedFetch").value)
            assertEquals("error", page.evaluate("results.redirectFetch").value, "subresource redirect to a disallowed host")
            assertEquals("load", page.evaluate("results.allowedImg").value)
            assertEquals("error", page.evaluate("results.blockedImg").value)
            assertEquals("/allowed-frame", page.evaluate("results.allowedFrame").value)

            val navigation = page.goto("$blocked/blocked-nav")
            assertFalse(navigation.ok, "disallowed navigation must fail: $navigation")
            page.evaluate("location.href = '$blocked/blocked-script-nav'; true")
            delay(1_000)
            assertEquals("/start", page.evaluate("document.title").value)
            val redirected = page.goto("$allowed/redirect")
            assertFalse(redirected.ok, "main-frame redirect to a disallowed host must fail: $redirected")

            delay(500)
            val reached = server.paths().filter { it.contains("blocked") }
            assertTrue(reached.isEmpty(), "disallowed requests must never reach the server: $reached")
            assertTrue(page.goto("$allowed/after").ok)

            page.setAllowedDomains(emptyList())
            assertTrue(page.goto("$blocked/unblocked").ok)
            assertTrue(server.paths().contains("/unblocked"))
        } finally {
            page.close()
            server.close()
        }
    }

    @Test
    fun color_scheme_override_changes_css_media_queries_natively() = runWebKitTest {
        requireWebKitDocuments()
        // Appearance reaches WebKit through the view hierarchy's trait collection, as in an app.
        val window = platform.UIKit.UIWindow(platform.CoreGraphics.CGRectMake(0.0, 0.0, 390.0, 844.0))
        val webView = platform.WebKit.WKWebView(platform.CoreGraphics.CGRectMake(0.0, 0.0, 390.0, 844.0), platform.WebKit.WKWebViewConfiguration())
        window.addSubview(webView)
        window.makeKeyAndVisible()
        val page = IOSPage(IOSWebViewController(existingWebView = webView))
        try {
            page.setContent("""
                <style>body{background:rgb(255,255,255)} @media (prefers-color-scheme: dark){body{background:rgb(0,0,0)}}</style><p>scheme</p>
            """.trimIndent())
            val background = "getComputedStyle(document.body).backgroundColor"
            page.emulateMedia(colorScheme = ColorScheme.Dark)
            assertEquals("rgb(0, 0, 0)", page.evaluate(background).value)
            assertEquals("true", page.evaluate("String(matchMedia('(prefers-color-scheme: dark)').matches)").value)
            assertEquals("true", page.evaluate("String(/\\[native code\\]/.test(window.matchMedia.toString()))").value,
                "native override: matchMedia must not be patched")
            page.emulateMedia(colorScheme = ColorScheme.Light)
            assertEquals("rgb(255, 255, 255)", page.evaluate(background).value)
            page.emulateMedia(colorScheme = null)
        } finally {
            page.close()
        }
        // A window-less web view never receives the appearance: the page falls back to JavaScript.
        val detached = IOSPage(IOSWebViewController())
        try {
            detached.setContent("<p>detached</p>")
            detached.emulateMedia(colorScheme = ColorScheme.Dark)
            assertEquals("true", detached.evaluate("String(matchMedia('(prefers-color-scheme: dark)').matches)").value)
            assertEquals("dark", detached.evaluate("document.documentElement.style.colorScheme").value)
        } finally {
            detached.close()
        }
    }

    @Test
    fun history_traversal_uses_the_web_view_back_forward_list() = runWebKitTest(30_000) {
        requireWebKitDocuments()
        val server = FixtureServer()
        val page = IOSPage(IOSWebViewController())
        val origin = "http://127.0.0.1:${server.port}"
        try {
            val started = kotlin.time.TimeSource.Monotonic.markNow()
            assertNull(page.goBack())
            assertNull(page.goForward())
            assertTrue(started.elapsedNow().inWholeMilliseconds < 1_000, "no-history traversal must not wait for a probe")
            assertTrue(page.goto("$origin/one").ok)
            assertTrue(page.goto("$origin/two").ok)
            assertTrue(page.goBack()!!.url.endsWith("/one"))
            assertTrue(page.goForward()!!.url.endsWith("/two"))
            assertNull(page.goForward())
        } finally {
            page.close()
            server.close()
        }
    }
}

/** Minimal blocking HTTP/1.1 server on 127.0.0.1 (one response per connection) for WebKit fixtures. */
private class FixtureServer {
    private val served = AtomicReference(emptyList<String>())
    private val listener: Int = socket(AF_INET, SOCK_STREAM, 0)
    val port: Int

    init {
        check(listener >= 0) { "socket() failed" }
        port = memScoped {
            val one = alloc<IntVar>().apply { value = 1 }
            setsockopt(listener, SOL_SOCKET, SO_REUSEADDR, one.ptr, sizeOf<IntVar>().convert())
            val address = alloc<sockaddr_in>()
            address.sin_family = AF_INET.convert()
            address.sin_port = 0u
            address.sin_addr.s_addr = 0x0100007Fu // 127.0.0.1 in network byte order (little-endian host)
            check(bind(listener, address.ptr.reinterpret(), sizeOf<sockaddr_in>().convert()) == 0) { "bind() failed" }
            check(listen(listener, 16) == 0) { "listen() failed" }
            val length = alloc<socklen_tVar>().apply { value = sizeOf<sockaddr_in>().convert() }
            getsockname(listener, address.ptr.reinterpret(), length.ptr)
            val networkOrder = address.sin_port.toInt()
            ((networkOrder and 0xff) shl 8) or ((networkOrder shr 8) and 0xff)
        }
        val fd = listener
        val record = served
        dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.convert(), 0u)) {
            while (true) {
                val client = accept(fd, null, null)
                if (client < 0) break
                dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.convert(), 0u)) { serve(client, record, port()) }
            }
        }
    }

    private fun port(): Int = port

    fun paths(): List<String> = served.value

    fun close() {
        shutdown(listener, SHUT_RDWR)
        platform.posix.close(listener)
    }

    companion object {
        private const val SVG = "<svg xmlns='http://www.w3.org/2000/svg' width='2' height='2'><rect width='2' height='2'/></svg>"

        fun serve(client: Int, record: AtomicReference<List<String>>, port: Int) {
            try {
                // A peer that aborts (blocked loads, WebSocket handshakes) must not SIGPIPE the test process.
                memScoped {
                    val one = alloc<IntVar>().apply { value = 1 }
                    setsockopt(client, SOL_SOCKET, platform.posix.SO_NOSIGPIPE, one.ptr, sizeOf<IntVar>().convert())
                }
                val head = StringBuilder()
                memScoped {
                    val buffer = allocArray<ByteVar>(4096)
                    while (!head.contains("\r\n\r\n")) {
                        val read = recv(client, buffer, 4096.convert(), 0)
                        if (read <= 0) break
                        head.append(buffer.readBytes(read.toInt()).decodeToString())
                    }
                }
                val path = head.lineSequence().firstOrNull()?.split(' ')?.getOrNull(1) ?: "/"
                while (true) {
                    val before = record.value
                    if (record.compareAndSet(before, before + path)) break
                }
                val (status, type, body, extra) = when {
                    path.startsWith("/redirect") -> listOf("302 Found", "text/plain", "", "Location: http://localhost:$port/blocked-redirect\r\n")
                    path.endsWith(".svg") -> listOf("200 OK", "image/svg+xml", SVG, "")
                    path.startsWith("/data") -> listOf("200 OK", "application/json", "{\"ok\":true}", "")
                    else -> listOf("200 OK", "text/html; charset=utf-8", "<title>$path</title><p>$path</p>", "")
                }
                val bytes = body.encodeToByteArray()
                val response = ("HTTP/1.1 $status\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\n$extra" +
                    "Connection: close\r\n\r\n").encodeToByteArray() + bytes
                response.usePinned { pinned -> send(client, pinned.addressOf(0), response.size.convert(), 0) }
            } finally {
                platform.posix.close(client)
            }
        }
    }
}
