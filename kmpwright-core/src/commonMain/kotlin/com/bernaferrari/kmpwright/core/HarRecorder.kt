package com.bernaferrari.kmpwright.core

import kotlinx.serialization.Serializable

/**
 * HAR 1.2 data classes for HTTP Archive format export.
 */
@Serializable
internal data class HarLog(
    val version: String = "1.2",
    val creator: HarCreator = HarCreator(),
    val entries: MutableList<HarEntry> = mutableListOf()
)

@Serializable
internal data class HarEntry(
    val startedDateTime: String,
    val request: HarRequest,
    val response: HarResponse,
    val time: Double = 0.0,
    val cache: Map<String, String> = emptyMap(),
    val timings: HarTimings = HarTimings()
)

/** Coarse event timings; unavailable connection phases use HAR's -1 sentinel. */
@Serializable
internal data class HarTimings(
    val blocked: Double = -1.0,
    val dns: Double = -1.0,
    val connect: Double = -1.0,
    val send: Double = 0.0,
    val wait: Double = 0.0,
    val receive: Double = 0.0,
    val ssl: Double = -1.0
)

@Serializable
internal data class HarRequest(
    val method: String,
    val url: String,
    val httpVersion: String = "HTTP/1.1",
    val headers: List<HarHeader> = emptyList(),
    val headersSize: Long = -1,
    val bodySize: Long = -1,
    val postData: HarPostData? = null,
    val cookies: List<HarHeader> = emptyList(),
    val queryString: List<HarHeader> = emptyList()
)

@Serializable
internal data class HarResponse(
    val status: Int,
    val statusText: String,
    val httpVersion: String = "HTTP/1.1",
    val headers: List<HarHeader> = emptyList(),
    val headersSize: Long = -1,
    val bodySize: Long = -1,
    val content: HarContent = HarContent(0),
    val cookies: List<HarHeader> = emptyList(),
    val redirectURL: String = ""
)

@Serializable
internal data class HarContent(
    val size: Long,
    val mimeType: String = "application/octet-stream",
    val text: String? = null,
    val encoding: String? = null,
    @kotlinx.serialization.SerialName("_kmpwrightTruncated") val truncated: Boolean = false
)

@Serializable
internal data class HarHeader(
    val name: String,
    val value: String
)

@Serializable
internal data class HarPostData(
    val mimeType: String? = null,
    val text: String? = null
)

@Serializable
internal data class HarCreator(
    val name: String = "kmpwright",
    val version: String = "1.0"
)

/**
 * Internal expect for platform-specific ISO-8601 formatting.
 */
internal expect fun formatIso8601(timestamp: Long): String
internal expect fun parseIso8601ToMillis(iso: String): Long

/** Thread-safe HAR 1.2 recorder: at most 500 entries and 16 MiB of serialized entry data. */
internal class HarRecorder {
    private val lock = kotlinx.atomicfu.locks.SynchronizedObject()
    private val entries = mutableListOf<HarEntry>()
    private val sizes = mutableListOf<Int>()
    private var bytes = 0L
    private val pending = linkedMapOf<String, Pair<HarRequest, Long>>()
    private val responses = mutableMapOf<String, Int>()
    private val json = kotlinx.serialization.json.Json { encodeDefaults = true; ignoreUnknownKeys = true }

    fun start(): Unit = clear()
    fun recordRequest(requestId: String, request: HarRequest): Unit = kotlinx.atomicfu.locks.synchronized(lock) {
        pending[requestId] = request.copy(url = boundedUtf8(request.url, 8192), headers = headers(request.headers),
            postData = request.postData?.copy(text = request.postData.text?.let { boundedUtf8(it, 65536) })) to currentTimeMillis()
        while (pending.size > 500) pending.remove(pending.keys.first())
    }
    fun recordResponse(requestId: String, response: HarResponse): Unit = kotlinx.atomicfu.locks.synchronized(lock) {
        val (request, started) = pending.remove(requestId) ?: return@synchronized
        val elapsed = (currentTimeMillis() - started).toDouble().coerceAtLeast(0.0)
        val entry = HarEntry(formatIso8601(started), request, response.copy(headers = headers(response.headers),
            statusText = boundedUtf8(response.statusText, 1024), redirectURL = boundedUtf8(response.redirectURL, 8192),
            content = response.content.copy(text = null)), elapsed, timings = HarTimings(wait = elapsed))
        responses[requestId] = entries.size
        entries.add(entry)
        val size = size(entry); sizes.add(size); bytes += size
        trim()
    }
    fun recordLoadingFinished(requestId: String): Unit = kotlinx.atomicfu.locks.synchronized(lock) {
        val index = responses[requestId] ?: return@synchronized
        val entry = entries[index]
        val total = (currentTimeMillis() - parseIso8601ToMillis(entry.startedDateTime)).toDouble().coerceAtLeast(entry.time)
        replace(index, entry.copy(time = total, timings = entry.timings.copy(receive = total - entry.timings.wait)))
    }
    fun recordResponseBody(requestId: String, body: String, base64Encoded: Boolean, truncated: Boolean = false): Unit = kotlinx.atomicfu.locks.synchronized(lock) {
        val index = responses.remove(requestId) ?: return@synchronized
        val entry = entries[index]
        val text = if (base64Encoded) body.take(1_398_100) else boundedUtf8(body, 1024 * 1024)
        val size = if (base64Encoded) kotlin.io.encoding.Base64.decode(text).size else text.encodeToByteArray().size
        replace(index, entry.copy(response = entry.response.copy(content = entry.response.content.copy(
            size = size.toLong(), text = text, encoding = if (base64Encoded) "base64" else null, truncated = truncated || text != body))))
    }
    fun toJson(): String = kotlinx.atomicfu.locks.synchronized(lock) {
        kotlinx.serialization.json.buildJsonObject { put("log", json.encodeToJsonElement(HarLog.serializer(), HarLog(entries = entries.toMutableList()))) }.toString()
    }
    fun entryCount(): Int = kotlinx.atomicfu.locks.synchronized(lock) { entries.size }
    fun clear(): Unit = kotlinx.atomicfu.locks.synchronized(lock) {
        entries.clear(); sizes.clear(); pending.clear(); responses.clear(); bytes = 0
    }
    private fun headers(values: List<HarHeader>): List<HarHeader> {
        var remaining = 32768
        return buildList {
            for (header in values.take(128)) {
                if (remaining <= 0) break
                val name = boundedUtf8(header.name, minOf(remaining, 256)); remaining -= name.encodeToByteArray().size
                val value = boundedUtf8(header.value, remaining); remaining -= value.encodeToByteArray().size
                add(HarHeader(name, value))
            }
        }
    }
    private fun size(entry: HarEntry): Int = json.encodeToString(HarEntry.serializer(), entry).encodeToByteArray().size
    private fun replace(index: Int, entry: HarEntry) {
        bytes -= sizes[index]; entries[index] = entry
        sizes[index] = size(entry); bytes += sizes[index]
        trim()
    }
    private fun trim() {
        var removed = 0
        while (entries.size > 500 || bytes > 16 * 1024 * 1024) {
            entries.removeAt(0); bytes -= sizes.removeAt(0); removed++
        }
        if (removed > 0) {
            responses.entries.removeAll { it.value < removed }
            responses.entries.forEach { it.setValue(it.value - removed) }
        }
    }
}
