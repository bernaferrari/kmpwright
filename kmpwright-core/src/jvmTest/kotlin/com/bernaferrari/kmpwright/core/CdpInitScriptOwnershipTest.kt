package com.bernaferrari.kmpwright.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

class CdpInitScriptOwnershipTest {
    @Test fun shutdown_removes_owned_registrations_and_preserves_host_scripts() = runBlocking<Unit> {
        val transport = ScriptTransport()
        val controller = CdpWebViewController(transport, "root")
        val removed = controller.addInitScript("window.one=true")
        val remaining = controller.addInitScript("window.two=true")
        controller.removeInitScript(removed)
        assertEquals(setOf("host", remaining), transport.registered)
        controller.disposeAsync()
        assertEquals(setOf("host"), transport.registered)
        assertEquals(0, transport.listeners.size)
        controller.disposeAsync()
        assertEquals(setOf("host"), transport.registered)
        assertFailsWith<IllegalStateException> { controller.addInitScript("window.late=true") }
    }
}

private class ScriptTransport : CdpEventTransport {
    val registered = java.util.concurrent.ConcurrentHashMap.newKeySet<String>().apply { add("host") }
    val listeners = java.util.concurrent.ConcurrentHashMap<String, (JsonObject) -> Unit>()
    private var next = 0
    override fun onEvent(method: String, sessionId: String?, listener: (JsonObject) -> Unit): CdpEventSubscription {
        listeners[method] = listener
        return CdpEventSubscription { listeners.remove(method) }
    }
    override suspend fun send(method: String, params: Map<String, Any?>): CdpResponse = reply(method, params)
    override suspend fun sendSession(sessionId: String, method: String, params: Map<String, Any?>): CdpResponse = reply(method, params)
    private fun reply(method: String, params: Map<String, Any?>): CdpResponse {
        val result = buildJsonObject {
            if (method == "Page.addScriptToEvaluateOnNewDocument") {
                val id = "owned${++next}"; registered += id; put("identifier", id)
            } else if (method == "Page.removeScriptToEvaluateOnNewDocument") registered -= params.getValue("identifier").toString()
        }
        return CdpResponse(1, result = result)
    }
}
