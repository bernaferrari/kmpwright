package com.bernaferrari.kmpwright.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertNull

class WebKitNetworkBridgeTest {

    @Test
    fun parsesFetchRequestStartedMessage() {
        val message = parseWebKitNetworkBridgeMessage(
            """
            {
              "type": "fetch",
              "id": "fetch-1",
              "url": "https://example.com/api",
              "method": "POST",
              "headers": { "Content-Type": "application/json" },
              "body": "{\"ok\":true}"
            }
            """.trimIndent()
        )

        val request = assertIs<WebKitNetworkBridgeMessage.RequestStarted>(message)
        assertEquals("fetch", request.kind)
        assertEquals("fetch-1", request.id)
        assertEquals("https://example.com/api", request.url)
        assertEquals("POST", request.method)
        assertEquals("application/json", request.headers["Content-Type"])
        assertEquals("""{"ok":true}""", request.body)
    }

    @Test
    fun parsesXhrRequestStartedMessageWithDefaults() {
        val message = parseWebKitNetworkBridgeMessage(
            """{"type":"xhr","id":"xhr-1","url":"https://example.com/data","headers":{}}"""
        )

        val request = assertIs<WebKitNetworkBridgeMessage.RequestStarted>(message)
        assertEquals("xhr", request.kind)
        assertEquals("GET", request.method)
        assertEquals(emptyMap(), request.headers)
        assertEquals(null, request.body)
    }

    @Test
    fun parsesResponseAndFailureMessages() {
        val response = assertIs<WebKitNetworkBridgeMessage.ResponseReceived>(
            parseWebKitNetworkBridgeMessage(
                """{"type":"response","id":"fetch-2","url":"https://example.com","status":203,"statusText":"Partial","headers":{"X-Test":"yes"}}"""
            )
        )
        assertEquals("fetch-2", response.id)
        assertEquals(203, response.status)
        assertEquals("Partial", response.statusText)
        assertEquals("yes", response.headers["X-Test"])

        val failed = assertIs<WebKitNetworkBridgeMessage.RequestFailed>(
            parseWebKitNetworkBridgeMessage(
                """{"type":"requestFailed","id":"fetch-3","url":"https://example.com","error":"aborted"}"""
            )
        )
        assertEquals("fetch-3", failed.id)
        assertEquals("aborted", failed.error)
    }

    @Test
    fun generatedScriptContainsRouteResponseAndOverridePaths() {
        val script = buildWebKitNetworkInterceptionScript("kmpwright-test", "test-nonce")

        assertTrue(script.contains("window.__kmpwrightNetworkRespond"))
        assertTrue(script.contains("msg.action === 'fulfill'"))
        assertTrue(script.contains("msg.action === 'abort'"))
        assertTrue(script.contains("msg.action === 'continue'"))
        assertTrue(script.contains("postToNative({ type: 'fetch'"))
        assertTrue(script.contains("postToNative({"))
        assertTrue(script.contains("payload.nonce = bridgeNonce"))
        assertTrue(script.contains("test-nonce"))
        assertNotNull(script)
    }

    @Test
    fun rejectsMissingOrSpoofedNonce() {
        val message = """{"type":"fetch","id":"req-1","url":"https://example.com","method":"GET","headers":{},"nonce":"right"}"""
        assertNull(parseWebKitNetworkBridgeMessage(message, expectedNonce = "wrong"))
        assertNull(parseWebKitNetworkBridgeMessage(
            """{"type":"fetch","id":"req-1","url":"https://example.com","method":"GET","headers":{}}""",
            expectedNonce = "right"
        ))
    }

    @Test
    fun rejectsMalformedAndOversizedMessages() {
        assertNull(parseWebKitNetworkBridgeMessage("not-json", expectedNonce = "nonce"))
        assertNull(parseWebKitNetworkBridgeMessage(
            """{"type":"fetch","id":"req-1","url":"https://example.com","method":"GET","headers":{"x":[1]},"nonce":"nonce"}""",
            expectedNonce = "nonce"
        ))
        val limits = WebKitNetworkBridgeLimits(maxPayloadChars = 100)
        assertNull(parseWebKitNetworkBridgeMessage(" ".repeat(101), expectedNonce = "nonce", limits = limits))
    }

    @Test
    fun rejectsUnboundedHeadersBodiesAndInvalidEvents() {
        val limits = WebKitNetworkBridgeLimits(maxBodyChars = 4, maxHeaders = 1)
        assertNull(parseWebKitNetworkBridgeMessage(
            """{"type":"fetch","id":"req-1","url":"https://example.com","method":"GET","headers":{},"body":"12345","nonce":"nonce"}""",
            expectedNonce = "nonce", limits = limits
        ))
        assertNull(parseWebKitNetworkBridgeMessage(
            """{"type":"fetch","id":"req-1","url":"https://example.com","method":"GET","headers":{"a":"1","b":"2"},"nonce":"nonce"}""",
            expectedNonce = "nonce", limits = limits
        ))
        assertNull(parseWebKitNetworkBridgeMessage(
            """{"type":"response","id":"req-1","status":99,"headers":{},"nonce":"nonce"}""",
            expectedNonce = "nonce"
        ))
    }

    @Test
    fun acceptsAuthenticatedBoundedMessage() {
        val request = assertIs<WebKitNetworkBridgeMessage.RequestStarted>(parseWebKitNetworkBridgeMessage(
            """{"type":"xhr","id":"req-1","url":"https://example.com/api","method":"POST","headers":{"x-test":"1"},"body":"ok","nonce":"nonce"}""",
            expectedNonce = "nonce"
        ))
        assertEquals("req-1", request.id)
        assertEquals("ok", request.body)
    }

    @Test
    fun rejectsWrongOriginAndSubframeMetadata() {
        assertTrue(!isTrustedWebKitNetworkBridgeFrame(WebKitNetworkBridgeFrameMetadata(
            isMainFrame = true,
            isExpectedWebView = true,
            frameOrigin = "https://attacker.example:443",
            expectedOrigin = "https://example.com:443"
        )))
        assertTrue(!isTrustedWebKitNetworkBridgeFrame(WebKitNetworkBridgeFrameMetadata(
            isMainFrame = false,
            isExpectedWebView = true,
            frameOrigin = "https://example.com:443",
            expectedOrigin = "https://example.com:443"
        )))
        assertTrue(!isTrustedWebKitNetworkBridgeFrame(WebKitNetworkBridgeFrameMetadata(
            isMainFrame = true,
            isExpectedWebView = false,
            frameOrigin = "https://example.com:443",
            expectedOrigin = "https://example.com:443"
        )))
    }

    @Test
    fun rateLimiterBoundsFloodAndResetsWindow() {
        val limiter = WebKitNetworkBridgeRateLimiter(maxMessagesPerWindow = 2)
        assertTrue(limiter.allow(1000))
        assertTrue(limiter.allow(1001))
        assertTrue(!limiter.allow(1002))
        assertTrue(limiter.allow(2000))
    }
}
