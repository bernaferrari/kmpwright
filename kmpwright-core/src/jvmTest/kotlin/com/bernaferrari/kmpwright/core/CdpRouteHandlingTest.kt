package com.bernaferrari.kmpwright.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList

class CdpRouteHandlingTest {

    @Test
    fun `paused fetch request invokes route handler and fulfills through cdp`() = runBlocking {
        val transport = FakeCdpEventTransport()
        val controller = CdpWebViewController(transport)

        controller.addRouteHandler("https://example.com/api/.*") { route ->
            route.fulfill(FulfillOptions(status = 202, body = "ok", contentType = "text/plain"))
            RouteHandler.Fallthrough
        }
        eventually {
            transport.sendCalls.any { it.method == CdpMethods.FETCH_ENABLE }
        }

        val listener = transport.listeners["Fetch.requestPaused"]
        assertNotNull(listener)
        listener!!.invoke(
            JsonObject(
                mapOf(
                    "requestId" to JsonPrimitive("fetch-1"),
                    "request" to JsonObject(
                        mapOf(
                            "url" to JsonPrimitive("https://example.com/api/users"),
                            "method" to JsonPrimitive("GET"),
                            "headers" to JsonObject(mapOf("Accept" to JsonPrimitive("application/json")))
                        )
                    )
                )
            )
        )

        eventually {
            transport.sendCalls.any { it.method == CdpMethods.FETCH_FULFILL_REQUEST }
        }
        val fulfill = transport.sendCalls.last { it.method == CdpMethods.FETCH_FULFILL_REQUEST }
        assertEquals("fetch-1", fulfill.params["requestId"])
        assertEquals(202, fulfill.params["responseCode"])
    }

    @Test
    fun `fetch enable uses broad cdp pattern and local regex matching`() = runBlocking {
        val transport = FakeCdpEventTransport()
        val controller = CdpWebViewController(transport)

        controller.addRouteHandler("https://example.com/private/.*") { RouteHandler.Abort("aborted") }
        eventually {
            transport.sendCalls.any { it.method == CdpMethods.FETCH_ENABLE }
        }

        val enable = transport.sendCalls.last { it.method == CdpMethods.FETCH_ENABLE }
        assertEquals(listOf(mapOf("urlPattern" to "*")), enable.params["patterns"])
    }

    @Test
    fun `domain filter is active before route handlers are evaluated`() = runBlocking {
        val transport = FakeCdpEventTransport()
        val controller = CdpWebViewController(transport)
        controller.setDomainFilterAsync(setOf("allowed.example"))
        controller.addRouteHandler("https://blocked.example/.*") {
            RouteHandler.Fulfill(FulfillOptions(status = 200, body = "should not route"))
        }

        transport.listeners["Fetch.requestPaused"]!!.invoke(
            JsonObject(
                mapOf(
                    "requestId" to JsonPrimitive("blocked-1"),
                    "request" to JsonObject(
                        mapOf(
                            "url" to JsonPrimitive("https://blocked.example/private"),
                            "method" to JsonPrimitive("GET"),
                            "headers" to JsonObject(emptyMap())
                        )
                    )
                )
            )
        )

        eventually {
            transport.sendCalls.any {
                it.method == CdpMethods.FETCH_FAIL_REQUEST && it.params["requestId"] == "blocked-1"
            }
        }
        assertFalse(transport.sendCalls.any { it.method == CdpMethods.FETCH_FULFILL_REQUEST })
    }

    @Test
    fun `credentials-only interception installs paused listener before fetch enable`() = runBlocking {
        val transport = FakeCdpEventTransport()
        val controller = CdpWebViewController(transport)
        try {
            controller.loadUrl("https://example.com/login")
            eventually { transport.sendCalls.any { it.method == CdpMethods.PAGE_NAVIGATE } }
            transport.listeners["Page.frameNavigated"]!!.invoke(JsonObject(mapOf("frame" to JsonObject(mapOf(
                "id" to JsonPrimitive("main"), "url" to JsonPrimitive("https://example.com/login")
            )))))
            assertEquals("https://example.com/login", controller.getUrl())

            controller.setHttpCredentialsAsync("user", "pass")
            assertNotNull(transport.listeners["Fetch.requestPaused"])
            assertTrue(transport.sendCalls.any { it.method == CdpMethods.FETCH_ENABLE })

            transport.listeners["Fetch.requestPaused"]!!.invoke(
                JsonObject(
                    mapOf(
                        "requestId" to JsonPrimitive("credential-1"),
                        "request" to JsonObject(
                            mapOf(
                                "url" to JsonPrimitive("https://example.com/protected"),
                                "method" to JsonPrimitive("GET"),
                                "headers" to JsonObject(emptyMap())
                            )
                        ),
                        "resourceType" to JsonPrimitive("Document")
                    )
                )
            )
            eventually {
                transport.sendCalls.any {
                    it.method == CdpMethods.FETCH_CONTINUE_REQUEST &&
                        (it.params["headers"] as? List<*>)?.any { header ->
                            header.toString().contains("Basic dXNlcjpwYXNz")
                        } == true
                }
            }
        } finally {
            controller.dispose()
        }
    }

    @Test
    fun `rewritten route target is checked against the active domain filter`() = runBlocking {
        val transport = FakeCdpEventTransport()
        val controller = CdpWebViewController(transport)
        try {
            controller.setDomainFilterAsync(setOf("allowed.example"))
            controller.addRouteHandler("https://allowed.example/api/.*") {
                RouteHandler.Continue(ContinueOptions(url = "https://blocked.example/escape"))
            }
            transport.listeners["Fetch.requestPaused"]!!.invoke(
                JsonObject(
                    mapOf(
                        "requestId" to JsonPrimitive("rewrite-1"),
                        "request" to JsonObject(
                            mapOf(
                                "url" to JsonPrimitive("https://allowed.example/api/data"),
                                "method" to JsonPrimitive("GET"),
                                "headers" to JsonObject(emptyMap())
                            )
                        )
                    )
                )
            )
            eventually {
                transport.sendCalls.any {
                    it.method == CdpMethods.FETCH_FAIL_REQUEST && it.params["requestId"] == "rewrite-1"
                }
            }
            assertFalse(transport.sendCalls.any {
                it.method == CdpMethods.FETCH_CONTINUE_REQUEST && it.params["requestId"] == "rewrite-1"
            })
        } finally {
            controller.dispose()
        }
    }

    @Test
    fun `rejected committed navigation does not let its load event report success and later allowed navigation recovers`() = runBlocking {
        val transport = FakeCdpEventTransport()
        val controller = CdpWebViewController(transport)
        val results = CopyOnWriteArrayList<NavigationResult>()
        controller.setNavigationGuard { !it.contains("evil") }
        controller.setNavigationCallback { results += it }
        try {
            transport.listeners["Page.frameNavigated"]!!.invoke(
                JsonObject(
                    mapOf("frame" to JsonObject(mapOf("url" to JsonPrimitive("https://evil.example/"))))
                )
            )
            transport.listeners["Page.loadEventFired"]!!.invoke(JsonObject(emptyMap()))
            eventually { results.size == 1 }
            assertFalse(results.single().ok)

            transport.listeners["Page.frameNavigated"]!!.invoke(
                JsonObject(
                    mapOf("frame" to JsonObject(mapOf("url" to JsonPrimitive("https://good.example/"))))
                )
            )
            transport.listeners["Page.loadEventFired"]!!.invoke(JsonObject(emptyMap()))
            eventually { results.size == 2 }
            assertTrue(results.last().ok)
            assertEquals("https://good.example/", results.last().url)
        } finally {
            controller.dispose()
        }
    }

    @Test
    fun `navigation guard rejects a paused document before commit`() = runBlocking {
        val transport = FakeCdpEventTransport()
        val controller = CdpWebViewController(transport)
        try {
            controller.setNavigationGuard { false }
            eventually { transport.sendCalls.any { it.method == CdpMethods.FETCH_ENABLE } }
            transport.listeners["Fetch.requestPaused"]!!.invoke(
                JsonObject(
                    mapOf(
                        "requestId" to JsonPrimitive("document-1"),
                        "resourceType" to JsonPrimitive("Document"),
                        "request" to JsonObject(
                            mapOf(
                                "url" to JsonPrimitive("https://evil.example/redirect"),
                                "method" to JsonPrimitive("GET"),
                                "headers" to JsonObject(emptyMap())
                            )
                        )
                    )
                )
            )
            eventually {
                transport.sendCalls.any {
                    it.method == CdpMethods.FETCH_FAIL_REQUEST && it.params["requestId"] == "document-1"
                }
            }
        } finally {
            controller.dispose()
        }
    }

    @Test
    fun `screenshot hides and restores scrollbars when enabled`() = runBlocking {
        val transport = FakeCdpEventTransport()
        val screenshotData = Base64.getEncoder().encodeToString("image-bytes".encodeToByteArray())
        transport.responseFor = { method, _ ->
            when (method) {
                CdpMethods.RUNTIME_EVALUATE -> booleanResponse(true)
                CdpMethods.PAGE_CAPTURE_SCREENSHOT -> CdpResponse(
                    id = 1,
                    result = JsonObject(mapOf("data" to JsonPrimitive(screenshotData)))
                )
                else -> CdpResponse(id = 1)
            }
        }
        val controller = CdpWebViewController(
            transport,
            hideScrollbarsForScreenshots = true
        )
        eventually {
            transport.sendCalls.any { it.method == CdpMethods.PAGE_GET_LAYOUT_METRICS }
        }
        transport.sendCalls.clear()
        val done = CompletableDeferred<ByteArray?>()

        controller.takeScreenshot(ScreenshotOptions()) { bytes, _, _ ->
            done.complete(bytes)
        }

        assertEquals("image-bytes", withTimeout(1_000) { done.await() }?.decodeToString())
        eventually {
            transport.sendCalls.count { it.method == CdpMethods.RUNTIME_EVALUATE } == 2
        }
        assertEquals(
            listOf(
                CdpMethods.RUNTIME_EVALUATE,
                CdpMethods.PAGE_CAPTURE_SCREENSHOT,
                CdpMethods.RUNTIME_EVALUATE
            ),
            transport.sendCalls.map { it.method }
        )
        assertTrue((transport.sendCalls[0].params["expression"] as String).contains("__kmpwright_hide_scrollbars_style__"))
        assertTrue((transport.sendCalls[2].params["expression"] as String).contains("Math.max"))
    }

    @Test
    fun `screenshot skips scrollbar style when disabled`() = runBlocking {
        val transport = FakeCdpEventTransport()
        val screenshotData = Base64.getEncoder().encodeToString("image-bytes".encodeToByteArray())
        transport.responseFor = { method, _ ->
            if (method == CdpMethods.PAGE_CAPTURE_SCREENSHOT) {
                CdpResponse(id = 1, result = JsonObject(mapOf("data" to JsonPrimitive(screenshotData))))
            } else {
                CdpResponse(id = 1)
            }
        }
        val controller = CdpWebViewController(
            transport,
            hideScrollbarsForScreenshots = false
        )
        eventually {
            transport.sendCalls.any { it.method == CdpMethods.PAGE_GET_LAYOUT_METRICS }
        }
        transport.sendCalls.clear()
        val done = CompletableDeferred<ByteArray?>()

        controller.takeScreenshot(ScreenshotOptions()) { bytes, _, _ ->
            done.complete(bytes)
        }

        assertEquals("image-bytes", withTimeout(1_000) { done.await() }?.decodeToString())
        assertFalse(transport.sendCalls.any { it.method == CdpMethods.RUNTIME_EVALUATE })
        assertEquals(listOf(CdpMethods.PAGE_CAPTURE_SCREENSHOT), transport.sendCalls.map { it.method })
    }

    private suspend fun eventually(predicate: () -> Boolean) {
        repeat(100) {
            if (predicate()) return
            delay(20)
        }
        throw AssertionError("Condition was not met")
    }
}

private class FakeCdpEventTransport : CdpEventTransport {
    val sendCalls = CopyOnWriteArrayList<FakeCdpSendCall>()
    val sessionCalls = CopyOnWriteArrayList<FakeCdpSessionSendCall>()
    val listeners = java.util.concurrent.ConcurrentHashMap<String, (JsonObject) -> Unit>()
    var responseFor: (String, Map<String, Any?>) -> CdpResponse = { _, _ ->
        CdpResponse(id = sendCalls.size + sessionCalls.size)
    }

    override suspend fun send(method: String, params: Map<String, Any?>): CdpResponse {
        sendCalls += FakeCdpSendCall(method, params)
        return responseFor(method, params)
    }

    override suspend fun sendSession(sessionId: String, method: String, params: Map<String, Any?>): CdpResponse {
        sessionCalls += FakeCdpSessionSendCall(sessionId, method, params)
        return CdpResponse(id = sessionCalls.size)
    }

    override fun onEvent(
        method: String,
        sessionId: String?,
        listener: (JsonObject) -> Unit
    ): CdpEventSubscription {
        listeners[method] = listener
        return CdpEventSubscription {
            if (listeners[method] === listener) listeners.remove(method)
        }
    }
}

private fun booleanResponse(value: Boolean): CdpResponse =
    CdpResponse(
        id = 1,
        result = JsonObject(
            mapOf(
                "result" to JsonObject(mapOf("value" to JsonPrimitive(value)))
            )
        )
    )

private data class FakeCdpSendCall(
    val method: String,
    val params: Map<String, Any?>
)

private data class FakeCdpSessionSendCall(
    val sessionId: String,
    val method: String,
    val params: Map<String, Any?>
)
