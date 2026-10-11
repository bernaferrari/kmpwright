package com.bernaferrari.kmpwright.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WebKitNetworkBridgeMessageTest {

    @Test
    fun `parses request started messages`() {
        val message = parseWebKitNetworkBridgeMessage(
            """{"type":"fetch","id":"req-1","url":"https://example.com","method":"POST","headers":{"x-test":"1"},"body":"hello"}"""
        ) as WebKitNetworkBridgeMessage.RequestStarted

        assertEquals("fetch", message.kind)
        assertEquals("req-1", message.id)
        assertEquals("https://example.com", message.url)
        assertEquals("POST", message.method)
        assertEquals(mapOf("x-test" to "1"), message.headers)
        assertEquals("hello", message.body)
    }

    @Test
    fun `parses response received messages`() {
        val message = parseWebKitNetworkBridgeMessage(
            """{"type":"response","id":"req-2","url":"https://example.com/api","status":418,"statusText":"I'm a teapot","headers":{"content-type":"application/json"}}"""
        ) as WebKitNetworkBridgeMessage.ResponseReceived

        assertEquals("req-2", message.id)
        assertEquals("https://example.com/api", message.url)
        assertEquals(418, message.status)
        assertEquals("I'm a teapot", message.statusText)
        assertEquals(mapOf("content-type" to "application/json"), message.headers)
    }

    @Test
    fun `parses request failure messages`() {
        val message = parseWebKitNetworkBridgeMessage(
            """{"type":"requestFailed","id":"req-3","url":"https://example.com/offline","error":"offline"}"""
        ) as WebKitNetworkBridgeMessage.RequestFailed

        assertEquals("req-3", message.id)
        assertEquals("https://example.com/offline", message.url)
        assertEquals("offline", message.error)
    }

    @Test
    fun `ignores unrelated bridge messages`() {
        assertNull(parseWebKitNetworkBridgeMessage("""{"type":"other"}"""))
    }
}
