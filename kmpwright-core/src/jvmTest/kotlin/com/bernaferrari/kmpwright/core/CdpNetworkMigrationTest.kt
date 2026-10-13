package com.bernaferrari.kmpwright.core

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class CdpNetworkMigrationTest {
    @Test fun `a frame request finishes on its new renderer session`() = runBlocking {
        val transport = MigrationTransport()
        val controller = CdpWebViewController(transport, "root")
        val starts = CopyOnWriteArrayList<Request>()
        val responses = CopyOnWriteArrayList<Response>()
        val finishes = CopyOnWriteArrayList<Request>()
        controller.addRequestListener(object : RequestListener {
            override fun onRequestStarted(request: Request) { starts.add(request) }
            override fun onResponseReceived(response: Response) { responses.add(response) }
            override fun onRequestFinished(request: Request) { finishes.add(request) }
            override fun onRequestFailed(request: Request, error: String) {}
        })
        controller.ensureNetworkEventListeners("child")
        try {
            transport.emit("root", "Network.requestWillBeSent", """{"requestId":"navigation","type":"Document","request":{"url":"http://child.example/","method":"GET","headers":{}}}""")
            assertEquals(1, controller.getPendingRequestCount())
            transport.emit("child", "Network.requestWillBeSent", """{"requestId":"navigation","type":"Document","request":{"url":"http://child.example/","method":"GET","headers":{}}}""")
            transport.emit("child", "Network.responseReceived", """{"requestId":"navigation","type":"Document","response":{"url":"http://child.example/","status":200,"headers":{}}}""")
            transport.emit("child", "Network.loadingFinished", """{"requestId":"navigation"}""")
            assertEquals("A process swap must not leave the original request pending", 0, controller.getPendingRequestCount())
            withTimeout(1_000) { while (finishes.isEmpty() || responses.isEmpty()) delay(5) }
            assertEquals(starts.single().id, responses.single().id)
            assertEquals(starts.single().id, finishes.single().id)
            assertEquals("migrated", controller.networkResponseBody(starts.single().id!!, 100).content)
            assertEquals("child", transport.bodySession)
        } finally { controller.disposeAsync() }
    }
}

private class MigrationTransport : CdpEventTransport {
    private val listeners = mutableMapOf<Pair<String?, String>, MutableList<(JsonObject) -> Unit>>()
    var bodySession: String? = null
    fun emit(session: String, method: String, body: String) {
        listeners[session to method]?.toList()?.forEach { it(Json.parseToJsonElement(body).jsonObject) }
    }
    override fun onEvent(method: String, sessionId: String?, listener: (JsonObject) -> Unit): CdpEventSubscription {
        listeners.getOrPut(sessionId to method) { mutableListOf() }.add(listener)
        return CdpEventSubscription { listeners[sessionId to method]?.remove(listener) }
    }
    override suspend fun send(method: String, params: Map<String, Any?>): CdpResponse = CdpResponse(1, result = JsonObject(emptyMap()))
    override suspend fun sendSession(sessionId: String, method: String, params: Map<String, Any?>): CdpResponse {
        if (method == "Network.getResponseBody") { bodySession = sessionId; return CdpResponse(1, result = buildJsonObject { put("body", "migrated"); put("base64Encoded", false) }) }
        return send(method, params)
    }
}
