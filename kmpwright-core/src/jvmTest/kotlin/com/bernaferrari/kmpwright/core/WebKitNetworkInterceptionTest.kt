package com.bernaferrari.kmpwright.core

import org.junit.Assert.assertTrue
import org.junit.Test

class WebKitNetworkInterceptionTest {

    @Test
    fun `network interception script installs fetch and xhr bridges`() {
        val script = buildWebKitNetworkInterceptionScript()

        assertTrue(script.contains("window.fetch = function"))
        assertTrue(script.contains("window.XMLHttpRequest.prototype.open = function"))
        assertTrue(script.contains("window.XMLHttpRequest.prototype.send = function"))
        assertTrue(script.contains("window.__kmpwrightNetworkRespond = function"))
    }

    @Test
    fun `network interception script reports unified response and failure messages`() {
        val script = buildWebKitNetworkInterceptionScript()

        assertTrue(script.contains("type: 'response'"))
        assertTrue(script.contains("type: 'requestFailed'"))
        assertTrue(script.contains("statusText"))
        assertTrue(script.contains("originalXHRGetAllResponseHeaders"))
    }

    @Test
    fun `network interception script embeds message handler name safely`() {
        val script = buildWebKitNetworkInterceptionScript("bridge-name")

        assertTrue(script.contains("var handlerName = 'bridge-name';"))
        assertTrue(script.contains("window.webkit.messageHandlers[handlerName]"))
    }

    @Test
    fun `network interception script reports real fetch and xhr outcomes`() {
        val script = buildWebKitNetworkInterceptionScript()

        assertTrue(script.contains("type: 'response'"))
        assertTrue(script.contains("type: 'requestFailed'"))
        assertTrue(script.contains("observeFetchNetworkResult"))
        assertTrue(script.contains("observeXhrNetworkResult"))
    }
}
