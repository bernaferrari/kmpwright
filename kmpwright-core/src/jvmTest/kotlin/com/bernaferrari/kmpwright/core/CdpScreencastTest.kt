package com.bernaferrari.kmpwright.core

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class CdpScreencastTest {
    @Test fun `transport disconnect terminates a quiet capture and releases subscriptions`() = runBlocking {
        val transport = CastTransport().apply { emitFrame = false }
        val runtime = CdpScreencast(transport, CdpPageSession(CdpEndpointScope.Browser, "target", "root"))
        supervisorScope {
            val collector = async { runtime.frames(ScreencastOptions()).first() }
            transport.started.await()
            transport.connectionState.value = false
            try { withTimeout(3_000) { collector.await() }; fail("Disconnected capture must fail") }
            catch (error: KmpwrightException) { assertTrue(error.message!!.contains("connection closed")) }
        }
        assertEquals(0, transport.listeners.size)
        assertEquals(listOf("capture"), transport.detached)
        runtime.close()
    }

    @Test fun `first frame cancellation releases only the capture session`() = runBlocking {
        val transport = CastTransport()
        val runtime = CdpScreencast(transport, CdpPageSession(CdpEndpointScope.Browser, "target", "root"))
        val frame = withTimeout(3_000) { runtime.frames(ScreencastOptions()).first() }
        assertEquals(listOf<Byte>(1, 2, 3), frame.bytes.toList())
        assertEquals(1000.0, frame.timestampMs, 0.0)
        assertEquals(0, transport.listeners.size)
        assertTrue(transport.methods.contains("Page.stopScreencast"))
        assertEquals(listOf("capture"), transport.detached)
        runtime.close()
        assertEquals(listOf("capture"), transport.detached)
    }

    @Test fun `failed capture start removes subscriptions and detaches ownership`() = runBlocking {
        val transport = CastTransport().apply { failStart = true }
        val runtime = CdpScreencast(transport, CdpPageSession(CdpEndpointScope.Browser, "target", "root"))
        try { withTimeout(3_000) { runtime.frames(ScreencastOptions()).first() }; fail("Expected protocol failure") }
        catch (error: KmpwrightException) { assertTrue(error.message!!.contains("Page.startScreencast")) }
        assertEquals(0, transport.listeners.size)
        assertEquals(listOf("capture"), transport.detached)
        runtime.close()
    }

    @Test fun `page close cancels collectors and rejects subsequent capture`() = runBlocking {
        val transport = CastTransport().apply { emitFrame = false }
        val runtime = CdpScreencast(transport, CdpPageSession(CdpEndpointScope.Browser, "target", "root"))
        val collector = async { runtime.frames(ScreencastOptions()).first() }
        transport.started.await()
        runtime.close()
        withTimeout(1_000) { collector.join() }
        assertTrue(collector.isCancelled)
        assertEquals(0, transport.listeners.size)
        assertEquals(listOf("capture"), transport.detached)
        try { runtime.frames(ScreencastOptions()).first(); fail("Expected closed runtime") }
        catch (error: IllegalStateException) { assertEquals("Page is closed", error.message) }
    }
}

private class CastTransport : CdpEventTransport {
    override val connectionState = kotlinx.coroutines.flow.MutableStateFlow(true)
    val methods = java.util.Collections.synchronizedList(mutableListOf<String>())
    val listeners = java.util.concurrent.ConcurrentHashMap<String, (JsonObject) -> Unit>()
    val detached = java.util.Collections.synchronizedList(mutableListOf<String>())
    val started = CompletableDeferred<Unit>()
    var failStart = false
    var emitFrame = true
    override fun onEvent(method: String, sessionId: String?, listener: (JsonObject) -> Unit): CdpEventSubscription {
        listeners[method] = listener
        return CdpEventSubscription { listeners.remove(method) }
    }
    override suspend fun send(method: String, params: Map<String, Any?>): CdpResponse {
        methods += method
        if (method == "Target.detachFromTarget") detached += params.getValue("sessionId").toString()
        return CdpResponse(1, result = buildJsonObject { if (method == "Target.attachToTarget") put("sessionId", "capture") })
    }
    override suspend fun sendSession(sessionId: String, method: String, params: Map<String, Any?>): CdpResponse {
        assertEquals("capture", sessionId)
        methods += method
        if (method == "Page.startScreencast") {
            started.complete(Unit)
            if (failStart) return CdpResponse(1, error = CdpError(-1, "unsupported"))
            if (emitFrame) listeners["Page.screencastFrame"]?.invoke(Json.parseToJsonElement(
                """{"sessionId":7,"data":"AQID","metadata":{"timestamp":1.0,"deviceWidth":320,"deviceHeight":240}}""").jsonObject)
        }
        return CdpResponse(1, result = buildJsonObject {})
    }
}
