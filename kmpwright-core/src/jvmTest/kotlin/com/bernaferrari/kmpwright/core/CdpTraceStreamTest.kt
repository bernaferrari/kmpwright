package com.bernaferrari.kmpwright.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class CdpTraceStreamTest {
    private class Stream(vararg responses: CdpResponse) : CdpSessionTransport {
        private val responses = responses.toMutableList()
        var reads = 0
        override suspend fun send(method: String, params: Map<String, Any?>): CdpResponse {
            reads++
            return responses.removeAt(0)
        }
        override suspend fun sendSession(sessionId: String, method: String, params: Map<String, Any?>): CdpResponse = error("Browser stream")
    }
    private fun chunk(json: String) = CdpResponse(1, result = Json.parseToJsonElement(json).jsonObject)

    @Test fun `text and base64 chunks preserve trace bytes`() = runBlocking {
        val stream = Stream(chunk("""{"data":"雪","eof":false}"""),
            chunk("""{"data":"AP8=","base64Encoded":true,"eof":true}"""))
        assertArrayEquals("雪".encodeToByteArray() + byteArrayOf(0, -1), readCdpTraceStream(stream, "handle"))
        assertEquals(2, stream.reads)
    }

    @Test fun `errors missing results and empty nonterminal chunks fail promptly`() = runBlocking {
        for (response in listOf(CdpResponse(1, error = CdpError(-1, "Disconnected")), CdpResponse(1),
            chunk("""{"data":"","eof":false}"""), chunk("""{"data":"partial"}"""))) {
            val stream = Stream(response)
            assertTrue(runCatching { readCdpTraceStream(stream, "handle") }.exceptionOrNull() is KmpwrightException)
            assertEquals(1, stream.reads)
        }
    }

    @Test fun `trace allocation has a strict byte bound`() = runBlocking {
        val stream = Stream(chunk("""{"data":"12345","eof":true}"""))
        assertTrue(runCatching { readCdpTraceStream(stream, "handle", maxBytes = 4) }.exceptionOrNull() is KmpwrightException)
    }
}
