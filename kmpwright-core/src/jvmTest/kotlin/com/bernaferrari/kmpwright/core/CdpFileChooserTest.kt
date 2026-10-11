package com.bernaferrari.kmpwright.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class CdpFileChooserTest {
    @Test fun `chooser validates ownership multiplicity and navigation lifetime`() = runBlocking<Unit> {
        val transport = ChooserTransport()
        val events = mutableListOf<FileChooserEvent>()
        val runtime = CdpFileChooser(transport, "root", events::add)
        val otherEvents = mutableListOf<FileChooserEvent>()
        val other = CdpFileChooser(transport, "other", otherEvents::add)
        val file = Files.createTempFile("kmpwright-chooser-", ".txt")
        try {
            runtime.setEnabled(true)
            other.setEnabled(true)
            transport.emit("root", "Page.fileChooserOpened", """{"backendNodeId":1,"frameId":"frame","mode":"selectSingle"}""")
            transport.emit("other", "Page.fileChooserOpened", """{"backendNodeId":2,"frameId":"other-frame","mode":"selectSingle"}""")
            assertNotEquals(events.single().element, otherEvents.single().element)
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
