package com.bernaferrari.kmpwright.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class HarBodyContractTest {
    @Test
    fun `HAR wraps its log and preserves text and binary response bodies`() {
        val recorder = HarRecorder()
        recorder.start()
        recorder.recordRequest("text", HarRequest("GET", "https://fixture.invalid/text"))
        recorder.recordResponse("text", HarResponse(200, "OK", content = HarContent(0, "text/plain")))
        recorder.recordLoadingFinished("text")
        recorder.recordResponseBody("text", "雪", false)
        recorder.recordRequest("binary", HarRequest("GET", "https://fixture.invalid/binary"))
        recorder.recordResponse("binary", HarResponse(200, "OK", content = HarContent(0, "application/octet-stream")))
        recorder.recordResponseBody("binary", "AAEC", true)
        val entries = Json.parseToJsonElement(recorder.toJson()).jsonObject["log"]!!.jsonObject["entries"]!!.jsonArray
        val text = entries[0].jsonObject["response"]!!.jsonObject["content"]!!.jsonObject
        assertEquals("雪", text["text"]!!.jsonPrimitive.content)
        assertEquals(3, text["size"]!!.jsonPrimitive.int)
        val binary = entries[1].jsonObject["response"]!!.jsonObject["content"]!!.jsonObject
        assertEquals("base64", binary["encoding"]!!.jsonPrimitive.content)
        assertEquals("AAEC", binary["text"]!!.jsonPrimitive.content)
        assertEquals(3, binary["size"]!!.jsonPrimitive.int)
    }
}
