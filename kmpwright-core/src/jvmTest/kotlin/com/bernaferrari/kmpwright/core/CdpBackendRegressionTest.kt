package com.bernaferrari.kmpwright.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Regression coverage for JVM/CDP backend review findings, driven through a fake transport. */
class CdpBackendRegressionTest {

    @Test
    fun `navigation errorText is reported as a failed navigation`() = runBlocking {
        val transport = RecordingTransport { method, _ ->
            if (method == CdpMethods.PAGE_NAVIGATE) result("frameId" to "main", "loaderId" to "l1", "errorText" to "net::ERR_NAME_NOT_RESOLVED")
            else null
        }
        val controller = CdpWebViewController(transport)
        try {
            val navigation = CompletableDeferred<NavigationResult>()
            controller.setNavigationCallback { navigation.complete(it) }
            controller.loadUrl("https://does-not-exist.invalid/")
            val result = withTimeout(2_000) { navigation.await() }
            assertFalse(result.ok)
            assertEquals(0, result.status)
            assertEquals("net::ERR_NAME_NOT_RESOLVED", result.error)
        } finally {
            controller.dispose()
        }
    }

    @Test
    fun `same document navigation resolves without a load event`() = runBlocking {
        val transport = RecordingTransport { method, _ ->
            if (method == CdpMethods.PAGE_NAVIGATE) result("frameId" to "main") else null
        }
        val controller = CdpWebViewController(transport)
        try {
            val navigation = CompletableDeferred<NavigationResult>()
            controller.setNavigationCallback { navigation.complete(it) }
            controller.loadUrl("https://example.com/#section")
            val result = withTimeout(2_000) { navigation.await() }
            assertTrue(result.ok)
            assertEquals("https://example.com/#section", result.url)
        } finally {
            controller.dispose()
        }
    }

    @Test
    fun `last registered matching route wins and re-registration moves a route last`() = runBlocking {
        val transport = RecordingTransport()
        val controller = CdpWebViewController(transport)
        try {
            controller.addRouteHandler("https://example\\.com/.*") { it.fulfill(status = 201) }
            controller.addRouteHandler("https://example\\.com/api/.*") { it.fulfill(status = 202) }
            transport.pause("r1", "https://example.com/api/x")
            assertEquals(202, transport.awaitCall(CdpMethods.FETCH_FULFILL_REQUEST, "r1").params["responseCode"])

            controller.addRouteHandler("https://example\\.com/.*") { it.fulfill(status = 203) }
            transport.pause("r2", "https://example.com/api/x")
            assertEquals(203, transport.awaitCall(CdpMethods.FETCH_FULFILL_REQUEST, "r2").params["responseCode"])
        } finally {
            controller.dispose()
        }
    }

    @Test
    fun `fallthrough falls back to the next older matching route`() = runBlocking {
        val transport = RecordingTransport()
        val controller = CdpWebViewController(transport)
        val invoked = CopyOnWriteArrayList<String>()
        try {
            controller.addRouteHandler(".*") { invoked += "older"; it.fulfill(status = 299) }
            controller.addRouteHandler("https://.*") { invoked += "middle"; it.fallback() }
            controller.addRouteHandler("https://example\\.com/.*") { invoked += "newest"; it.fallback() }
            transport.pause("chain", "https://example.com/")
            assertEquals(299, transport.awaitCall(CdpMethods.FETCH_FULFILL_REQUEST, "chain").params["responseCode"])
            assertEquals(listOf("newest", "middle", "older"), invoked)
        } finally {
            controller.dispose()
        }
    }

    @Test
    fun `failing route handler aborts instead of silently continuing`() = runBlocking {
        val transport = RecordingTransport()
        val controller = CdpWebViewController(transport)
        try {
            controller.addRouteHandler(".*") { throw IllegalStateException("handler bug") }
            transport.pause("boom", "https://example.com/")
            transport.awaitCall(CdpMethods.FETCH_FAIL_REQUEST, "boom")
            assertFalse(transport.calls.any { it.method == CdpMethods.FETCH_CONTINUE_REQUEST && it.params["requestId"] == "boom" })
        } finally {
            controller.dispose()
        }
    }

    @Test
    fun `blocking request listener does not stall the event pump`() = runBlocking {
        val transport = RecordingTransport()
        val controller = CdpWebViewController(transport)
        val release = CountDownLatch(1)
        val started = CopyOnWriteArrayList<String>()
        try {
            controller.addRequestListener(object : RequestListener {
                override fun onRequestStarted(request: Request) { started += request.url; release.await(5, TimeUnit.SECONDS) }
                override fun onResponseReceived(response: Response) {}
                override fun onRequestFinished(request: Request) {}
                override fun onRequestFailed(request: Request, error: String) {}
            })
            val event = JsonObject(mapOf("requestId" to JsonPrimitive("n1"),
                "request" to JsonObject(mapOf("url" to JsonPrimitive("https://example.com/a")))))
            val begin = System.nanoTime()
            transport.emit("Network.requestWillBeSent", event)
            transport.emit("Network.requestWillBeSent", JsonObject(event + ("requestId" to JsonPrimitive("n2"))))
            assertTrue("event dispatch must not wait for the listener",
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin) < 1_000)
            assertEquals(2, controller.getPendingRequestCount())
            eventually { started.size == 1 }
            release.countDown()
            eventually { started.size == 2 }
        } finally {
            release.countDown()
            controller.dispose()
        }
    }

    @Test
    fun `scaled full page screenshot captures beyond the viewport`() = runBlocking {
        val png = Base64.getEncoder().encodeToString(pngHeader(640, 2000))
        val transport = RecordingTransport { method, _ ->
            when (method) {
                CdpMethods.PAGE_GET_LAYOUT_METRICS -> CdpResponse(1, JsonObject(mapOf(
                    "cssContentSize" to obj("x" to 0, "y" to 0, "width" to 1280, "height" to 4000),
                    "cssLayoutViewport" to obj("pageX" to 0, "pageY" to 0, "clientWidth" to 1280, "clientHeight" to 720))))
                CdpMethods.PAGE_CAPTURE_SCREENSHOT -> result("data" to png)
                else -> null
            }
        }
        val controller = CdpWebViewController(transport)
        try {
            val shot = controller.captureScreenshot(ScreenshotOptions(fullPage = true, scale = 0.5))
            assertEquals(640 to 2000, shot.width to shot.height)
            val params = transport.calls.last { it.method == CdpMethods.PAGE_CAPTURE_SCREENSHOT }.params
            assertEquals(true, params["captureBeyondViewport"])
            assertEquals(4000.0, (params["clip"] as Map<*, *>)["height"])
        } finally {
            controller.dispose()
        }
    }

    @Test
    fun `viewport size reflects the css layout viewport not the document`() = runBlocking {
        val transport = RecordingTransport { method, _ ->
            if (method == CdpMethods.PAGE_GET_LAYOUT_METRICS) CdpResponse(1, JsonObject(mapOf(
                "cssContentSize" to obj("width" to 1280, "height" to 9000),
                "cssLayoutViewport" to obj("clientWidth" to 1280.4, "clientHeight" to 719.6))))
            else null
        }
        val controller = CdpWebViewController(transport)
        try {
            eventually { controller.getViewportSize() != (0 to 0) }
            assertEquals(1280 to 719, controller.getViewportSize())
        } finally {
            controller.dispose()
        }
    }

    @Test
    fun `return scripts run in an async function tolerant of trailing comments`() = runBlocking {
        val transport = RecordingTransport { method, _ ->
            if (method == CdpMethods.RUNTIME_EVALUATE) CdpResponse(1, JsonObject(mapOf("result" to obj("value" to 3)))) else null
        }
        val controller = CdpWebViewController(transport)
        try {
            assertEquals("3", controller.evaluateJavaScriptAsync("return await Promise.resolve(3) // done"))
            val expression = transport.calls.last { it.method == CdpMethods.RUNTIME_EVALUATE }.params["expression"] as String
            assertTrue(expression.startsWith("(async function() {\n"))
            assertTrue(expression.endsWith("\n})()"))
        } finally {
            controller.dispose()
        }
    }

    @Test
    fun `dialog listener that never answers is dismissed after the timeout`() = runBlocking {
        val transport = RecordingTransport()
        val controller = CdpWebViewController(transport, handlerTimeoutMs = 50)
        try {
            controller.addDialogListener(object : DialogListener {
                override suspend fun onDialogAsync(type: DialogType, message: String, defaultPrompt: String?): DialogResponse =
                    awaitCancellation()
            })
            transport.emit("Page.javascriptDialogOpening", JsonObject(mapOf("type" to JsonPrimitive("confirm"), "message" to JsonPrimitive("?"))))
            val handled = transport.awaitCall("Page.handleJavaScriptDialog")
            assertEquals(false, handled.params["accept"])
        } finally {
            controller.dispose()
        }
    }

    @Test
    fun `pdf is streamed through IO read`() = runBlocking {
        val pdf = "%PDF-1.7 streamed".encodeToByteArray()
        val transport = RecordingTransport { method, _ ->
            when (method) {
                CdpMethods.PAGE_PRINT_TO_PDF -> result("stream" to "pdf-handle")
                "IO.read" -> CdpResponse(1, JsonObject(mapOf("data" to JsonPrimitive(Base64.getEncoder().encodeToString(pdf)),
                    "base64Encoded" to JsonPrimitive(true), "eof" to JsonPrimitive(true))))
                else -> null
            }
        }
        val controller = CdpWebViewController(transport)
        try {
            assertArrayEquals(pdf, controller.generatePdfAsync(PdfOptions()))
            assertEquals("ReturnAsStream", transport.calls.first { it.method == CdpMethods.PAGE_PRINT_TO_PDF }.params["transferMode"])
            assertTrue(transport.calls.any { it.method == CdpMethods.IO_CLOSE && it.params["handle"] == "pdf-handle" })
        } finally {
            controller.dispose()
        }
    }

    @Test
    fun `oversized message fails only its command and keeps reassembling`() {
        val assembler = CdpMessageAssembler(maxChars = 64)
        assertNull(assembler.accept("""{"id":42,"result":{"data":"""", last = false))
        val oversized = assembler.accept("x".repeat(100) + "\"}}", last = true)
        assertEquals(42, (oversized as CdpMessageAssembler.Result.Oversized).id)
        val next = assembler.accept("""{"id":43,"result":{}}""", last = true)
        assertEquals(CdpMessageAssembler.Result.Complete("""{"id":43,"result":{}}"""), next)
        val event = assembler.accept("""{"method":"Network.dataReceived","params":{"x":"${"y".repeat(100)}"}}""", last = true)
        assertNull((event as CdpMessageAssembler.Result.Oversized).id)
    }

    @Test
    fun `png dimensions are read from the IHDR header`() {
        assertEquals(1234 to 567, decodeImageSize(pngHeader(1234, 567)))
        assertEquals(0 to 0, decodeImageSize("not an image".encodeToByteArray()))
    }

    @Test
    fun `browserless release authenticates with its api key`() {
        val env = mapOf("BROWSERLESS_API_KEY" to "k", "KERNEL_API_KEY" to "kk")
        assertEquals(mapOf("Authorization" to "Bearer k"), releaseHeaders("browserless", env::get))
        assertEquals(mapOf("Authorization" to "Bearer kk"), releaseHeaders("kernel", env::get))
        assertNull(releaseHeaders("browserless") { null })
        assertEquals(emptyMap<String, String>(), releaseHeaders("direct") { null })
    }

    private suspend fun eventually(predicate: () -> Boolean) {
        repeat(150) {
            if (predicate()) return
            delay(20)
        }
        throw AssertionError("Condition was not met")
    }
}

private fun obj(vararg entries: Pair<String, Any>): JsonObject = JsonObject(entries.associate { (key, value) ->
    key to when (value) {
        is Number -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        else -> JsonPrimitive(value.toString())
    } as JsonElement
})

private fun result(vararg entries: Pair<String, Any>): CdpResponse = CdpResponse(1, obj(*entries))

private fun pngHeader(width: Int, height: Int): ByteArray {
    val bytes = ByteArray(33)
    byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A).copyInto(bytes)
    bytes[11] = 13
    "IHDR".encodeToByteArray().copyInto(bytes, 12)
    fun put(offset: Int, value: Int) { for (i in 0..3) bytes[offset + i] = (value ushr (24 - 8 * i)).toByte() }
    put(16, width); put(20, height)
    return bytes
}

private class RecordingTransport(
    private val respond: (String, Map<String, Any?>) -> CdpResponse? = { _, _ -> null }
) : CdpEventTransport {
    data class Call(val method: String, val params: Map<String, Any?>)
    val calls = CopyOnWriteArrayList<Call>()
    private val listeners = java.util.concurrent.ConcurrentHashMap<String, CopyOnWriteArrayList<(JsonObject) -> Unit>>()

    override suspend fun send(method: String, params: Map<String, Any?>): CdpResponse {
        calls += Call(method, params)
        return respond(method, params) ?: CdpResponse(calls.size)
    }

    override suspend fun sendSession(sessionId: String, method: String, params: Map<String, Any?>): CdpResponse =
        send(method, params)

    override fun onEvent(method: String, sessionId: String?, listener: (JsonObject) -> Unit): CdpEventSubscription {
        listeners.computeIfAbsent(method) { CopyOnWriteArrayList() } += listener
        return CdpEventSubscription { listeners[method]?.remove(listener) }
    }

    fun emit(method: String, params: JsonObject) { listeners[method]?.forEach { it(params) } }

    fun pause(requestId: String, url: String) = emit("Fetch.requestPaused", JsonObject(mapOf(
        "requestId" to JsonPrimitive(requestId),
        "request" to JsonObject(mapOf("url" to JsonPrimitive(url), "method" to JsonPrimitive("GET"), "headers" to JsonObject(emptyMap())))
    )))

    suspend fun awaitCall(method: String, requestId: String? = null): Call {
        repeat(150) {
            calls.lastOrNull { it.method == method && (requestId == null || it.params["requestId"] == requestId) }?.let { return it }
            delay(20)
        }
        throw AssertionError("No $method call for $requestId; saw ${calls.map { it.method }}")
    }
}
