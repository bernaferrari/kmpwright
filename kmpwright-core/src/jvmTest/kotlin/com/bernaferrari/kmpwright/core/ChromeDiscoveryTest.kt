package com.bernaferrari.kmpwright.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChromeDiscoveryTest {

    @Test
    fun `parse active port file builds websocket url from port and path`() {
        val content = """
            9222
            /devtools/browser/abc123
        """.trimIndent()

        assertEquals(
            "ws://127.0.0.1:9222/devtools/browser/abc123",
            ChromeDiscovery.parseActivePortFile(content)
        )
    }

    @Test
    fun `parse active port file preserves explicit websocket url`() {
        val content = """
            9222
            ws://remote-host:9222/devtools/browser/xyz
        """.trimIndent()

        assertEquals(
            "ws://remote-host:9222/devtools/browser/xyz",
            ChromeDiscovery.parseActivePortFile(content)
        )
    }

    @Test
    fun `parse active port file rejects invalid content`() {
        assertNull(ChromeDiscovery.parseActivePortFile("not-a-port"))
    }

    @Test
    fun `parse version response extracts websocket url`() {
        assertEquals(
            "ws://127.0.0.1:9222/devtools/browser/abc",
            ChromeDiscovery.parseVersionWebSocketUrl(
                """{"Browser":"Chrome","webSocketDebuggerUrl":"ws://127.0.0.1:9222/devtools/browser/abc"}"""
            )
        )
    }

    @Test
    fun `parse list response prefers browser target websocket url`() {
        assertEquals(
            "ws://127.0.0.1:9222/devtools/browser/browser-id",
            ChromeDiscovery.parseListWebSocketUrl(
                """[
                    {"type":"page","webSocketDebuggerUrl":"ws://127.0.0.1:9222/devtools/page/page-id"},
                    {"type":"browser","webSocketDebuggerUrl":"ws://127.0.0.1:9222/devtools/browser/browser-id"}
                ]""".trimIndent()
            )
        )
    }

    @Test
    fun `rewrite websocket host and append query preserve remote endpoint details`() {
        val rewritten = ChromeDiscovery.rewriteWsHost(
            "ws://127.0.0.1:9222/devtools/browser/abc?existing=1",
            "remote.example.com",
            9444
        )

        assertEquals(
            "ws://remote.example.com:9444/devtools/browser/abc?existing=1&token=xyz",
            ChromeDiscovery.appendQuery(rewritten, "token=xyz")
        )
    }
}
