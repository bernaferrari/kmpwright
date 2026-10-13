package com.bernaferrari.kmpwright.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** Real Chromium: the `proxy` of [launchBrowser] routes page traffic, not just the DevTools socket. */
class CdpProxyBrowserTest {
    @Test
    fun `launch proxy routes page traffic through the proxy`() = runBlocking<Unit> {
        val executable = System.getenv("KMPWRIGHT_TEST_CHROME")
        assumeTrue("Set KMPWRIGHT_TEST_CHROME for real browser contracts", !executable.isNullOrBlank())

        // An HTTP proxy receives absolute-URI requests; this one answers every request itself,
        // so a hostname that does not exist only loads if Chromium really used the proxy.
        val seen = CopyOnWriteArrayList<String>()
        val proxy = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        proxy.createContext("/") { exchange ->
            seen += exchange.requestURI.toString()
            val body = "<title>Via proxy</title><h1 id=via>${exchange.requestHeaders.getFirst("Host")}</h1>".encodeToByteArray()
            exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        proxy.start()
        val launched = launchBrowser(
            executable!!,
            // Chromium bypasses proxies for loopback by default; "<-loopback>" is not needed for a fake host.
            proxy = ProxyConfig("http://127.0.0.1:${proxy.address.port}"),
        )
        try {
            val page = launched.browser.newPage()
            val result = page.goto("http://kmpwright-proxy.test/hello")
            assertTrue("navigation through the proxy failed: $result", result.ok)
            assertEquals("kmpwright-proxy.test", page.locator("#via").innerText())
            assertTrue("proxy saw: $seen", seen.any { it.contains("kmpwright-proxy.test/hello") })
        } finally {
            launched.close()
            proxy.stop(0)
        }
    }
}
