package com.bernaferrari.kmpwright.core

import org.junit.Assert.assertEquals
import org.junit.Test

class RequestOverridesTest {

    @Test
    fun `applyContinueOptions keeps original request values when overrides are absent`() {
        val request = Request(
            url = "https://example.com/data",
            method = "POST",
            headers = mapOf("X-Test" to "one"),
            postData = "{\"ok\":true}"
        )

        val override = request.applyContinueOptions(RouteAction.Continue(null, null, null, null))

        assertEquals("https://example.com/data", override.url)
        assertEquals("POST", override.method)
        assertEquals(mapOf("X-Test" to "one"), override.headers)
        assertEquals("{\"ok\":true}", override.postData)
    }

    @Test
    fun `applyContinueOptions applies provided overrides`() {
        val request = Request(
            url = "https://example.com/data",
            method = "GET",
            headers = mapOf("X-Test" to "one"),
            postData = null
        )

        val override = request.applyContinueOptions(
            RouteAction.Continue(
                url = "https://example.com/next",
                method = "PUT",
                headers = mapOf("Authorization" to "Bearer test"),
                postData = "payload"
            )
        )

        assertEquals("https://example.com/next", override.url)
        assertEquals("PUT", override.method)
        assertEquals(mapOf("Authorization" to "Bearer test"), override.headers)
        assertEquals("payload", override.postData)
    }
}
