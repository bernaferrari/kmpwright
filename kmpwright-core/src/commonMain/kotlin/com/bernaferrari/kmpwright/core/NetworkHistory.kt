package com.bernaferrari.kmpwright.core

import kotlinx.atomicfu.atomic
import kotlinx.serialization.Serializable

@Serializable
enum class NetworkRequestState { Pending, Finished, Failed }

/** A stable history ID identifies one redirect hop, rather than a reused protocol ID. */
@Serializable
data class NetworkRequestRecord(val id: Long, val request: Request, val response: Response? = null,
    val state: NetworkRequestState = NetworkRequestState.Pending, val error: String? = null,
    val finishedAt: Long? = null)

@Serializable
data class NetworkResponseBody(val content: String, val base64Encoded: Boolean = false, val truncated: Boolean = false)

/** Metadata is collected from page attachment, across navigations, with bounded retention. */
interface NetworkHistory {
    fun requests(filter: String? = null, limit: Int = 100): List<NetworkRequestRecord>
    fun detail(id: Long): NetworkRequestRecord?
    suspend fun responseBody(id: Long, maxBytes: Int = 1024 * 1024): NetworkResponseBody
    fun clear()
}

internal class PageNetworkHistory(private val readBody: suspend (String, Int) -> NetworkResponseBody,
    private val checkRead: suspend () -> Unit, private val capacity: Int = 500,
    private val byteBudget: Int = 4 * 1024 * 1024) : NetworkHistory {
    private data class State(val nextId: Long = 1, val records: List<NetworkRequestRecord> = emptyList())
    private val state = atomic(State())
    init { require(capacity > 0 && byteBudget > 0) }

    override fun requests(filter: String?, limit: Int): List<NetworkRequestRecord> {
        require(limit in 1..500) { "Request limit must be between 1 and 500" }
        return state.value.records.asReversed().asSequence().filter { filter == null ||
            it.request.url.contains(filter, true) || it.request.method.equals(filter, true) }
            .take(limit).toList()
    }
    override fun detail(id: Long): NetworkRequestRecord? = state.value.records.firstOrNull { it.id == id }
    override suspend fun responseBody(id: Long, maxBytes: Int): NetworkResponseBody {
        require(maxBytes in 1..4 * 1024 * 1024) { "Response body budget must be between 1 byte and 4 MiB" }
        checkRead()
        val record = detail(id) ?: throw KmpwrightException("Network request $id is no longer retained")
        if (record.state != NetworkRequestState.Finished || record.response == null) {
            throw KmpwrightException("Network request $id has no completed response")
        }
        // CDP reuses request IDs for redirects. Its body belongs only to the latest hop.
        if (state.value.records.any { it.id > id && it.request.id == record.request.id }) {
            throw KmpwrightException("Response body is unavailable for a redirect hop")
        }
        return readBody(record.request.id, maxBytes)
    }
    override fun clear() = update { it.copy(records = emptyList()) }
    fun started(request: Request) = update { current ->
        val bounded = request.copy(url = boundedUtf8(request.url, 8192),
            method = boundedUtf8(request.method, 32), resourceType = boundedUtf8(request.resourceType, 64),
            id = boundedUtf8(request.id, 256),
            headers = boundHeaders(request.headers), postData = request.postData?.let { boundedUtf8(it, 65536) },
            postDataJSON = request.postDataJSON?.let { boundedUtf8(it, 65536) })
        val records = current.records + NetworkRequestRecord(current.nextId, bounded)
        current.copy(nextId = current.nextId + 1, records = trim(records))
    }
    fun received(response: Response) = modify(response.id) {
        it.copy(response = response.copy(url = boundedUtf8(response.url, 8192), headers = boundHeaders(response.headers),
            body = null, statusText = response.statusText?.let { text -> boundedUtf8(text, 1024) }))
    }
    fun finished(request: Request) = modify(request.id) {
        if (it.state == NetworkRequestState.Failed) it else it.copy(state = NetworkRequestState.Finished, finishedAt = currentTimeMillis())
    }
    fun failed(request: Request, error: String) = modify(request.id) {
        it.copy(state = NetworkRequestState.Failed, error = boundedUtf8(error, 4096), finishedAt = currentTimeMillis())
    }
    private fun modify(protocolId: String, change: (NetworkRequestRecord) -> NetworkRequestRecord) = update { current ->
        val index = current.records.indexOfLast { it.request.id == protocolId }
        if (index < 0) current else current.copy(records = trim(current.records.toMutableList().apply { this[index] = change(this[index]) }))
    }
    private fun boundHeaders(headers: Map<String, String>): Map<String, String> {
        var remaining = 32768
        return buildMap {
            for ((name, value) in headers.entries.take(128)) {
                if (remaining <= 0) break
                val key = boundedUtf8(name, minOf(remaining, 256))
                remaining -= key.encodeToByteArray().size
                val text = boundedUtf8(value, remaining)
                remaining -= text.encodeToByteArray().size
                put(key, text)
            }
        }
    }
    private fun trim(records: List<NetworkRequestRecord>): List<NetworkRequestRecord> {
        var bytes = 0
        return records.asReversed().take(capacity).takeWhile { record ->
            fun headers(values: Map<String, String>) = values.entries.sumOf { (it.key.length + it.value.length) * 3 }
            bytes += (record.request.url.length + record.request.postData.orEmpty().length +
                record.request.postDataJSON.orEmpty().length + record.response?.url.orEmpty().length + record.error.orEmpty().length) * 3 +
                headers(record.request.headers) + headers(record.response?.headers.orEmpty()) + 512
            bytes <= byteBudget
        }.asReversed()
    }
    private inline fun update(transform: (State) -> State) {
        while (true) { val old = state.value; if (state.compareAndSet(old, transform(old))) return }
    }
}
