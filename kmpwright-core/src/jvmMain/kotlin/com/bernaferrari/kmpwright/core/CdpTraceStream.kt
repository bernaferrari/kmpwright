package com.bernaferrari.kmpwright.core

import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.util.Base64

/** Read a finished tracing stream with a deadline, a size bound and explicit protocol validation. */
internal suspend fun readCdpTraceStream(
    transport: CdpSessionTransport,
    handle: String,
    maxBytes: Int = 64 * 1024 * 1024,
    timeoutMs: Long = 10_000
): ByteArray = withTimeout(timeoutMs) {
    val output = ByteArrayOutputStream()
    while (true) {
        val response = transport.send("IO.read", mapOf("handle" to handle, "size" to 64 * 1024))
        response.error?.let { throw KmpwrightException("Trace stream read failed: ${it.message}") }
        val result = response.result ?: throw KmpwrightException("Trace stream returned no result")
        val data = result["data"]?.jsonPrimitive?.content ?: throw KmpwrightException("Trace stream returned no data")
        val eof = result["eof"]?.jsonPrimitive?.booleanOrNull ?: throw KmpwrightException("Trace stream omitted eof")
        val bytes = if (result["base64Encoded"]?.jsonPrimitive?.booleanOrNull == true) Base64.getDecoder().decode(data)
            else data.encodeToByteArray()
        if (bytes.size > maxBytes - output.size()) throw KmpwrightException("Trace exceeds $maxBytes bytes")
        output.write(bytes)
        if (eof) return@withTimeout output.toByteArray()
        if (bytes.isEmpty()) throw KmpwrightException("Trace stream made no progress")
    }
    @Suppress("UNREACHABLE_CODE") error("Unreachable")
}
