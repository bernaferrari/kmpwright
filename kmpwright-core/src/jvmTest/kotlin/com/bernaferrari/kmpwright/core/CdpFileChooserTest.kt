package com.bernaferrari.kmpwright.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class CdpFileChooserTest {
    @Test fun `remote chooser rejects uploads but can clear the selection`() = runBlocking<Unit> {
        val transport = ChooserTransport()
        var handle = ""
        val runtime = CdpFileChooser(transport, "root", KmpwrightConfig(cdp = CdpConfig(websocketUrl = "wss://remote.invalid"))) { token, _ -> handle = token }
        try {
            runtime.setEnabled(true)
            transport.emit("root", "Page.fileChooserOpened", """{"backendNodeId":1,"frameId":"frame","mode":"selectSingle"}""")
            kotlin.test.assertFailsWith<KmpwrightUnsupportedException> { runtime.setFiles(handle, listOf("/missing-local-file")) }
            runtime.setFiles(handle, emptyList())
            assertEquals(emptyList<String>(), transport.commands.last().second["files"])
        } finally { runtime.close() }
    }

    @Test fun `chooser validates ownership multiplicity and navigation lifetime`() = runBlocking<Unit> {
        val transport = ChooserTransport()
        val events = mutableListOf<String>()
        val runtime = CdpFileChooser(transport, "root") { handle, _ -> events += handle }
        val otherEvents = mutableListOf<String>()
        val other = CdpFileChooser(transport, "other") { handle, _ -> otherEvents += handle }
        val file = Files.createTempFile("kmpwright-chooser-", ".txt")
        try {
            runtime.setEnabled(true)
            other.setEnabled(true)
            transport.emit("root", "Page.fileChooserOpened", """{"backendNodeId":1,"frameId":"frame","mode":"selectSingle"}""")
            transport.emit("other", "Page.fileChooserOpened", """{"backendNodeId":2,"frameId":"other-frame","mode":"selectSingle"}""")
            assertNotEquals(events.single(), otherEvents.single())
            try { other.setFiles(events.single(), listOf(file.toString())); fail("Foreign handle accepted") }
            catch (error: KmpwrightException) { assertTrue(error.message!!.contains("another page")) }
            try { runtime.setFiles(events.single(), listOf(file.toString(), file.toString())); fail("Multiple files accepted") }
            catch (_: IllegalArgumentException) { }
            runtime.setFiles(events.single(), listOf(file.toString()))
            assertEquals(1L, transport.commands.last().second["backendNodeId"])
            try { runtime.setFiles(events.single(), emptyList()); fail("Consumed handle accepted") }
            catch (_: KmpwrightException) { }
            transport.emit("root", "Page.fileChooserOpened", """{"backendNodeId":3,"frameId":"frame","mode":"selectMultiple"}""")
            transport.emit("root", "Page.frameNavigated", """{"frame":{"id":"frame"}}""")
            try { runtime.setFiles(events.last(), emptyList()); fail("Navigation did not expire handle") }
            catch (_: KmpwrightException) { }
            runtime.close(); other.close()
            assertTrue(transport.listeners.isEmpty())
        } finally { runtime.close(); other.close(); Files.deleteIfExists(file) }
    }
}

private class ChooserTransport : CdpEventTransport {
    val listeners = mutableMapOf<Pair<String?, String>, (JsonObject) -> Unit>()
    val commands = mutableListOf<Pair<String, Map<String, Any?>>>()
    fun emit(session: String, method: String, params: String) { listeners[session to method]?.invoke(Json.parseToJsonElement(params).jsonObject) }
    override fun onEvent(method: String, sessionId: String?, listener: (JsonObject) -> Unit): CdpEventSubscription {
        listeners[sessionId to method] = listener
        return CdpEventSubscription { listeners.remove(sessionId to method) }
    }
    override suspend fun send(method: String, params: Map<String, Any?>): CdpResponse {
        commands += method to params
        return CdpResponse(1, result = JsonObject(emptyMap()))
    }
    override suspend fun sendSession(sessionId: String, method: String, params: Map<String, Any?>): CdpResponse = send(method, params)
}
