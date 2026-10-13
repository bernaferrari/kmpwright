package com.bernaferrari.kmpwright.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class CdpNavigationOrderingTest {
    @Test fun `a newer navigation supersedes an older command still being prepared`() = runBlocking {
        val transport = NavigationOrderingTransport()
        val controller = CdpWebViewController(transport)
        try {
            val older = controller.launchNavigation("https://old.example/")
            withTimeout(2_000) { transport.discoveryStarted.await() }
            val newer = controller.launchNavigation("https://new.example/")
            transport.releaseDiscovery.complete(Unit)
            withTimeout(2_000) { joinAll(older, newer) }
            assertEquals(listOf("https://new.example/"), transport.navigations)
        } finally { controller.disposeAsync() }
    }
}

private class NavigationOrderingTransport : CdpEventTransport {
    val discoveryStarted = CompletableDeferred<Unit>()
    val releaseDiscovery = CompletableDeferred<Unit>()
    val navigations = CopyOnWriteArrayList<String>()
    override fun onEvent(method: String, sessionId: String?, listener: (JsonObject) -> Unit): CdpEventSubscription =
        CdpEventSubscription {}
    override suspend fun send(method: String, params: Map<String, Any?>): CdpResponse {
        if (method == "WebMCP.enable") {
            discoveryStarted.complete(Unit)
            releaseDiscovery.await()
        }
        val result = when (method) {
            "Page.getFrameTree" -> buildJsonObject {
                putJsonObject("frameTree") { putJsonObject("frame") { put("id", "main") } }
            }
            "Page.navigate" -> {
                navigations.add(params.getValue("url") as String)
                buildJsonObject { put("frameId", "main"); put("loaderId", "document") }
            }
            else -> JsonObject(emptyMap())
        }
        return CdpResponse(1, result = result)
    }
    override suspend fun sendSession(sessionId: String, method: String, params: Map<String, Any?>): CdpResponse = send(method, params)
}
