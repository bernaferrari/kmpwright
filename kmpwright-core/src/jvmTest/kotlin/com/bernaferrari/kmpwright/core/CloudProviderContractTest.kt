package com.bernaferrari.kmpwright.core

import org.junit.Assert.assertEquals
import org.junit.Test

class CloudProviderContractTest {
    @Test
    fun `browserbase uses header authentication and connectUrl`() {
        val request = browserbaseCreateRequest("api-key", "project-id")
        assertEquals("api-key", request.headers["X-BB-API-Key"])
        assertEquals("{\"projectId\":\"project-id\"}", request.body)
        val session = parseBrowserbaseSession("""{"id":"session-1","connectUrl":"wss://browserbase"}""")
        assertEquals("wss://browserbase", session.websocketUrl)
    }

    @Test
    fun `browser use uses vendor header and cdpUrl`() {
        val request = browserUseCreateRequest("api-key")
        assertEquals("api-key", request.headers["X-Browser-Use-API-Key"])
        val session = parseBrowserUseSession("""{"id":"session-2","cdpUrl":"wss://browser-use"}""")
        assertEquals("wss://browser-use", session.websocketUrl)
    }
    @Test
    fun `browser use v4 request keeps profile and direct proxy semantics`() {
        val env = mapOf("BROWSER_USE_PROFILE_ID" to "profile", "BROWSER_USE_PROXY_COUNTRY" to "direct", "BROWSER_USE_ENABLE_RECORDING" to "false")
        val request = browserUseCreateRequest("key", env::get)
        assertEquals("{\"profileId\":\"profile\",\"proxyCountryCode\":null,\"enableRecording\":false}", request.body)
        assertEquals("https://api.browser-use.com/api/v4/browsers/session-2", parseBrowserUseSession("""{"id":"session-2","cdpUrl":"wss://browser-use"}""").stopUrl)
    }

    @Test
    fun `kernel uses a profile object and modern response fields`() {
        val env = mapOf("KERNEL_PROFILE_NAME" to "persisted", "KERNEL_PROFILE_SAVE_CHANGES" to "true")
        val request = kernelCreateRequest("key", env::get)
        assertEquals("Bearer key", request.headers["Authorization"])
        val body = kotlinx.serialization.json.Json.parseToJsonElement(request.body).toString()
        assertEquals("{\"headless\":true,\"stealth\":false,\"timeout_seconds\":300,\"profile\":{\"name\":\"persisted\",\"save_changes\":true}}", body)
        val session = parseKernelSession("""{"session_id":"session-3","cdp_ws_url":"wss://kernel"}""", "https://kernel.example/")
        assertEquals("wss://kernel", session.websocketUrl)
        assertEquals("https://kernel.example/browsers/session-3", session.stopUrl)
    }

}
