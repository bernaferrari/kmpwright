package com.bernaferrari.kmpwright.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OriginBoundRequestConfigTest {
    @Test
    fun `configuration on blank page binds to first http origin`() {
        val scope = OriginBoundRequestConfig()
        scope.update(active = true, currentUrl = "about:blank")

        assertNull(scope.boundOrigin())
        assertTrue(scope.allows("https://Example.com:443/login"))
        assertEquals("https://example.com", scope.boundOrigin())
        assertTrue(scope.allows("https://example.com/account"))
        assertFalse(scope.allows("https://other.example/account"))
        assertFalse(scope.allows("http://example.com/account"))
    }

    @Test
    fun `configuration on an existing page is immediately origin bound`() {
        val scope = OriginBoundRequestConfig()
        scope.update(active = true, currentUrl = "http://example.com:80/start?x=1#fragment")

        assertEquals("http://example.com", scope.boundOrigin())
        assertTrue(scope.allows("http://example.com/next"))
        assertFalse(scope.allows("http://example.com.evil.test/next"))
    }

    @Test
    fun `clearing all request configuration clears the bound origin`() {
        val scope = OriginBoundRequestConfig()
        scope.update(active = true, currentUrl = "https://example.com")
        scope.update(active = false, currentUrl = "https://example.com")

        assertNull(scope.boundOrigin())
        assertFalse(scope.allows("https://example.com"))
    }

    @Test
    fun `canonical origin rejects credentials and non-http schemes`() {
        assertNull(canonicalHttpOrigin("https://user:pass@example.com"))
        assertNull(canonicalHttpOrigin("file:///tmp/page.html"))
        assertNull(canonicalHttpOrigin("not a url"))
        assertEquals("https://[::1]:8443", canonicalHttpOrigin("https://[::1]:8443/path"))
    }

    @Test
    fun `mobile policy rejects arbitrary extra headers`() {
        val policy = MobileHttpRequestPolicy()

        assertTrue(policy.supportsExtraHeaders(emptyMap()))
        assertFalse(policy.supportsExtraHeaders(mapOf("X-Api-Key" to "secret")))
    }

    @Test
    fun `mobile policy sends credentials only to their bound origin`() {
        val policy = MobileHttpRequestPolicy()
        policy.setCredentials("user", "secret", "about:blank")

        assertEquals(
            mapOf("Authorization" to "Basic encoded(user:secret)"),
            policy.requestHeaders("https://example.com/login") { "encoded($it)" }
        )
        assertEquals(
            emptyMap<String, String>(),
            policy.requestHeaders("https://other.example/login") { "encoded($it)" }
        )
        assertEquals(
            emptyMap<String, String>(),
            policy.requestHeaders("http://example.com/login") { "encoded($it)" }
        )
    }

    @Test
    fun `clearing mobile credentials removes the header and resets origin binding`() {
        val policy = MobileHttpRequestPolicy()
        policy.setCredentials("first", "secret", "https://first.example")
        policy.setCredentials(null, null, "https://first.example")

        assertEquals(
            emptyMap<String, String>(),
            policy.requestHeaders("https://first.example") { "encoded($it)" }
        )

        policy.setCredentials("second", "secret", "https://second.example")
        assertEquals(
            mapOf("Authorization" to "Basic encoded(second:secret)"),
            policy.requestHeaders("https://second.example") { "encoded($it)" }
        )
    }
}
