package com.bernaferrari.kmpwright.core

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class CdpFrameNetworkTest {
    @Test fun `frame observation works without WebMCP and namespaces request bodies`() = runBlocking {
        val transport = FrameNetworkTransport()
        val controller = CdpWebViewController(transport, "root")
        val requests = java.util.concurrent.CopyOnWriteArrayList<Request>()
        val finished = java.util.concurrent.CopyOnWriteArrayList<String>()
        controller.addRequestListener(object : RequestListener {
            override fun onRequestStarted(request: Request) { requests += request }
            override fun onResponseReceived(response: Response) { }
            override fun onRequestFinished(request: Request) { finished += request.id }
            override fun onRequestFailed(request: Request, error: String) { }
        })
        try {
            controller.observeFrames()
            transport.emit("root", "Page.frameNavigated", """{"frame":{"id":"main","url":"https://fixture.test/root"}}""")
            transport.emit("root", "Target.attachedToTarget", """{"sessionId":"child","targetInfo":{"targetId":"frame","type":"iframe","url":"https://fixture.test/child"}}""")
            withTimeout(3_000) { transport.childEnabled.await() }
            for (session in listOf("root", "child")) {
                transport.emit(session, "Network.requestWillBeSent", """{"requestId":"same-id","request":{"url":"https://fixture.test/$session"},"type":"Fetch"}""")
                transport.emit(session, "Network.responseReceived", """{"requestId":"same-id","response":{"url":"https://fixture.test/$session","status":200},"type":"Document","frameId":"frame"}""")
                transport.emit(session, "Network.loadingFinished", """{"requestId":"same-id"}""")
            }
            // Listener callbacks are delivered off the CDP event pump, in order.
            withTimeout(3_000) { while (requests.size < 2 || finished.size < 2) delay(10) }
            assertEquals(2, requests.size)
            assertNotEquals(requests[0].id, requests[1].id)
            assertEquals(2, finished.size)
            assertEquals("root body", controller.networkResponseBody(requests[0].id, 100).content)
            assertEquals("child body", controller.networkResponseBody(requests[1].id, 100).content)
            assertEquals("https://fixture.test/root", controller.getUrl())
        } finally { controller.disposeAsync() }
        assertEquals(0, transport.listeners.values.sumOf { it.size })
    }
}

private class FrameNetworkTransport : CdpEventTransport {
    val listeners = java.util.concurrent.ConcurrentHashMap<Pair<String?, String>, java.util.concurrent.CopyOnWriteArrayList<(JsonObject) -> Unit>>()
    val childEnabled = CompletableDeferred<Unit>()
    override fun onEvent(method: String, sessionId: String?, listener: (JsonObject) -> Unit): CdpEventSubscription {
        val list = listeners.computeIfAbsent(sessionId to method) { java.util.concurrent.CopyOnWriteArrayList() }
        list += listener
        return CdpEventSubscription { list.remove(listener) }
    }
    fun emit(session: String, method: String, json: String) { listeners[session to method]?.forEach { it(Json.parseToJsonElement(json).jsonObject) } }
    override suspend fun send(method: String, params: Map<String, Any?>): CdpResponse = sendSession("root", method, params)
    override suspend fun sendSession(sessionId: String, method: String, params: Map<String, Any?>): CdpResponse {
        if (method == "WebMCP.enable") return CdpResponse(1, error = CdpError(-32601, "unsupported"))
        if (method == "Network.enable" && sessionId == "child") childEnabled.complete(Unit)
        return CdpResponse(1, result = buildJsonObject { if (method == "Network.getResponseBody") { put("body", "$sessionId body"); put("base64Encoded", false) } })
    }
}
