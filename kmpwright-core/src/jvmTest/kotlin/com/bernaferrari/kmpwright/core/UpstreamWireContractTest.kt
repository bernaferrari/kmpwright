package com.bernaferrari.kmpwright.core

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class UpstreamWireContractTest {
    @Test
    fun `browser scoped CDP commands omit null session IDs`() {
        val request = Json.parseToJsonElement(CdpSerializer.serializeRequest(CdpRequest(1, "Target.getTargets"))).jsonObject
        assertFalse(request.containsKey("sessionId"))
        val session = Json.parseToJsonElement(CdpSerializer.serializeRequest(CdpRequest(2, "Page.enable", sessionId = "page"))).jsonObject
        assertEquals("page", session["sessionId"]!!.jsonPrimitive.content)
    }

    @Test
    fun `parallel CDP callers receive distinct IDs`() = runBlocking {
        val ids = (1..1000).map { async(Dispatchers.Default) { CdpIdGenerator.next() } }.awaitAll()
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `malformed JSON shapes do not prevent the next valid event`() {
        val connection = CdpConnection("ws://127.0.0.1:1")
        var events = 0
        connection.onEvent("test") { events++ }
        try {
            listOf("[]", "null", "true", "{\"id\":{}}", "{\"method\":\"test\",\"params\":[]}").forEach(connection::dispatchIncoming)
            connection.dispatchIncoming("""{"method":"test","params":{}}""")
            assertEquals(1, events)
        } finally { connection.close() }
    }

    @Test
    fun `JS string decoding preserves escaped backslashes and unicode`() {
        val original = "literal \\n and 雪"
        assertEquals(original, normalizeJsResult(JsonPrimitive(original).toString()))
        assertEquals("雪", normalizeJsResult("\"\\u96ea\""))
    }

    @Test
    fun `ref prefix does not accept malformed references`() {
        listOf("ref=@e", "ref=@evil", "ref=@e1x", "ref=", "ref=ref=e1").forEach { assertNull(parseRef(it)) }
    }

    @Test
    fun `bare snapshot references are normalized in locator chains`() = runBlocking {
        val controller = MockWebViewController()
        TestLocator("@e3", controller).count()
        assertTrue(controller.executedScripts.last().contains("['ref=@e3']"))
        TestLocator("#root >> ref=e4", controller).count()
        assertTrue(controller.executedScripts.last().contains("['#root','ref=@e4']"))
    }
}
