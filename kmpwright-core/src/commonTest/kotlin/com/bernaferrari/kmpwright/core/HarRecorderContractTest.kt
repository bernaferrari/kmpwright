package com.bernaferrari.kmpwright.core

import kotlin.test.*
import kotlinx.serialization.json.*

class HarRecorderContractTest {
    @Test fun bounded_har_preserves_newest_entries_and_valid_wrapper() {
        val recorder = HarRecorder()
        recorder.start()
        repeat(510) { index ->
            recorder.recordRequest("$index", HarRequest("GET", "https://fixture.test/$index"))
            recorder.recordResponse("$index", HarResponse(200, "OK"))
            recorder.recordLoadingFinished("$index")
        }
        val entries = Json.parseToJsonElement(recorder.toJson()).jsonObject.getValue("log").jsonObject.getValue("entries").jsonArray
        assertEquals(500, entries.size)
        assertEquals("https://fixture.test/10", entries.first().jsonObject.getValue("request").jsonObject.getValue("url").jsonPrimitive.content)
        assertEquals("https://fixture.test/509", entries.last().jsonObject.getValue("request").jsonObject.getValue("url").jsonPrimitive.content)
        recorder.clear()
        assertEquals(0, recorder.entryCount())
    }
    @Test fun har_body_retains_encoding_and_explicit_truncation() {
        val recorder = HarRecorder()
        recorder.recordRequest("body", HarRequest("GET", "https://fixture.test/body"))
        recorder.recordResponse("body", HarResponse(200, "OK"))
        recorder.recordResponseBody("body", "AAEC", true, truncated = true)
        val content = Json.parseToJsonElement(recorder.toJson()).jsonObject.getValue("log").jsonObject.getValue("entries").jsonArray.single()
            .jsonObject.getValue("response").jsonObject.getValue("content").jsonObject
        assertEquals("base64", content.getValue("encoding").jsonPrimitive.content)
        assertEquals(3, content.getValue("size").jsonPrimitive.int)
        assertTrue(content.getValue("_kmpwrightTruncated").jsonPrimitive.boolean)
    }
}
