package com.bernaferrari.kmpwright.core

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlin.coroutines.cancellation.CancellationException

/** Lifecycle of a recorded request. */
@Serializable
public enum class NetworkRequestState { Pending, Finished, Failed }

/** A recorded request; the stable [id] identifies one redirect hop rather than a reused protocol id. */
@Serializable
public data class NetworkRequestRecord(val id: Long, val request: Request, val response: Response? = null,
    val state: NetworkRequestState = NetworkRequestState.Pending, val error: String? = null,
    val finishedAt: Long? = null)

/** A response body read with [NetworkHistory.responseBody]. */
@Serializable
public data class NetworkResponseBody(val content: String, val base64Encoded: Boolean = false, val truncated: Boolean = false)

/**
 * Request history of a page (agent-browser `network requests`), collected from page attachment across
 * navigations with bounded retention, plus HAR capture (agent-browser `network har start|stop`).
 */
public interface NetworkHistory {
    /** Newest-first requests whose URL contains [filter] or whose method equals it. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class)
    public fun requests(filter: String? = null, limit: Int = 100): List<NetworkRequestRecord>

    /** The record with history [id], or null when it is no longer retained. */
    public fun detail(id: Long): NetworkRequestRecord?

    /** Reads a response body by history [NetworkRequestRecord.id], waiting for the request to finish. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun responseBody(id: Long, maxBytes: Int = 1024 * 1024): NetworkResponseBody

    /**
     * Reads the body of [response] (for example one returned by `waitForResponse`): it resolves the
     * newest retained record whose request id equals [Response.id]. [Response.body] is the shortcut.
     */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun responseBody(response: Response, maxBytes: Int = 1024 * 1024): NetworkResponseBody

    /** Forgets every recorded request. */
    public fun clear()

    /** Starts recording a HAR archive of later requests. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun startHar()

    /** Stops HAR recording and returns the HAR JSON. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun stopHar(): String
}

internal class PageNetworkHistory(private val readBody: suspend (String, Int) -> NetworkResponseBody,
    private val checkRead: suspend () -> Unit,
    private val harStart: suspend () -> Unit = { throw KmpwrightUnsupportedException("HAR capture is unavailable on this backend") },
    private val harStop: suspend () -> String = { throw KmpwrightUnsupportedException("HAR capture is unavailable on this backend") },
    private val capacity: Int = 500,
    private val byteBudget: Int = 4 * 1024 * 1024,
    private val timeoutMs: Long = 30_000,
    private val closed: Deferred<Unit>? = null) : NetworkHistory {
    override suspend fun startHar() = harStart()
    override suspend fun stopHar(): String = harStop()
    private data class State(val nextId: Long = 1, val records: List<NetworkRequestRecord> = emptyList())
    private val state = MutableStateFlow(State())
    init { require(capacity > 0 && byteBudget > 0 && timeoutMs > 0) }

    override fun requests(filter: String?, limit: Int): List<NetworkRequestRecord> {
        require(limit in 1..500) { "Request limit must be between 1 and 500" }
        return state.value.records.asReversed().asSequence().filter { filter == null ||
            it.request.url.contains(filter, true) || it.request.method.equals(filter, true) }
            .take(limit).toList()
    }
    override fun detail(id: Long): NetworkRequestRecord? = state.value.records.firstOrNull { it.id == id }
    override suspend fun responseBody(id: Long, maxBytes: Int): NetworkResponseBody = awaitPageOperation(closed) {
        require(maxBytes in 1..4 * 1024 * 1024) { "Response body budget must be between 1 byte and 4 MiB" }
        withKmpwrightTimeout(timeoutMs, { "Timeout ${timeoutMs}ms exceeded reading the response body of network request $id" }) {
            checkRead()
            // Headers arrive before loadingFinished. Await the terminal snapshot, also waking
            // when clear/eviction removes the record; no polling or per-request job registry.
            val terminal = state.first { snapshot ->
                snapshot.records.firstOrNull { it.id == id }?.state != NetworkRequestState.Pending
            }
            val record = terminal.records.firstOrNull { it.id == id }
                ?: throw KmpwrightException("Network request $id is no longer retained")
            if (record.state == NetworkRequestState.Failed) {
                throw KmpwrightException("Network request $id failed: ${record.error.orEmpty()}")
            }
            if (record.response == null) throw KmpwrightException("Network request $id has no completed response")
            // CDP reuses request IDs for redirects. Its body belongs only to the latest hop.
            if (terminal.records.any { it.id > id && it.request.id == record.request.id }) {
                throw KmpwrightException("Response body is unavailable for a redirect hop")
            }
            readBody(record.request.id, maxBytes)
        }
    }
    override suspend fun responseBody(response: Response, maxBytes: Int): NetworkResponseBody {
        val record = state.value.records.lastOrNull { it.request.id == response.id }
            ?: throw KmpwrightException("The request for response ${response.url} is no longer retained")
        return responseBody(record.id, maxBytes)
    }
    override fun clear() = update { it.copy(records = emptyList()) }
    fun started(request: Request) = update { current ->
        val bounded = request.copy(url = boundedUtf8(request.url, 8192),
            method = boundedUtf8(request.method, 32), resourceType = boundedUtf8(request.resourceType, 64),
            id = boundedUtf8(request.id, 256),
            headers = boundHeaders(request.headers), postData = request.postData?.let { boundedUtf8(it, 65536) })
        val records = current.records + NetworkRequestRecord(current.nextId, bounded)
        current.copy(nextId = current.nextId + 1, records = trim(records))
    }
    fun received(response: Response) = modify(response.id) {
        it.copy(response = response.copy(url = boundedUtf8(response.url, 8192), headers = boundHeaders(response.headers),
            statusText = response.statusText?.let { text -> boundedUtf8(text, 1024) }))
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
                record.response?.url.orEmpty().length + record.error.orEmpty().length) * 3 +
                headers(record.request.headers) + headers(record.response?.headers.orEmpty()) + 512
            bytes <= byteBudget
        }.asReversed()
    }
    private inline fun update(transform: (State) -> State) {
        while (true) { val old = state.value; if (state.compareAndSet(old, transform(old))) return }
    }
}
