package com.bernaferrari.kmpwright.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Experimental browser WebMCP tools. Metadata and outputs are supplied by the page. */
interface WebMcp {
    suspend fun discover(): List<WebMcpTool>
    fun context(): WebMcpContext
    suspend fun invoke(name: String, input: JsonObject = JsonObject(emptyMap()), frameId: String? = null,
        timeoutMs: Long = 30_000, waitForResult: Boolean = true): WebMcpInvocation
    fun invocation(id: String): WebMcpInvocation
    suspend fun awaitInvocation(id: String, timeoutMs: Long = 30_000): WebMcpInvocation
    suspend fun cancelInvocation(id: String): WebMcpInvocation
}

@Serializable
data class WebMcpTool(val name: String, val description: String, val inputSchema: JsonElement?,
    val annotations: JsonElement?, val origin: String, val frameId: String, val backendNodeId: Long? = null)

@Serializable
data class WebMcpToolSummary(val name: String, val description: String, val origin: String, val frameId: String)

@Serializable
data class WebMcpContext(val observed: Boolean, val toolCount: Int, val tools: List<WebMcpToolSummary>,
    val truncated: Boolean, val experimental: Boolean = true, val untrusted: Boolean = true)

@Serializable
enum class WebMcpInvocationStatus { Pending, Completed, Canceled, Failed, TimedOut }

@Serializable
data class WebMcpInvocation(val id: String, val toolName: String, val frameId: String, val origin: String,
    val status: WebMcpInvocationStatus, val output: JsonElement? = null, val error: String? = null,
    val outputTruncated: Boolean = false, val originalOutputBytes: Int? = null,
    val durationMs: Long = 0, val rawStatus: String? = null)

internal object UnavailableWebMcp : WebMcp {
    private fun unavailable(): Nothing = throw KmpwrightException("WebMCP requires a browser exposing the experimental CDP WebMCP domain")
    override suspend fun discover(): List<WebMcpTool> = unavailable()
    override fun context() = WebMcpContext(false, 0, emptyList(), false)
    override suspend fun invoke(name: String, input: JsonObject, frameId: String?, timeoutMs: Long, waitForResult: Boolean): WebMcpInvocation = unavailable()
    override fun invocation(id: String): WebMcpInvocation = unavailable()
    override suspend fun awaitInvocation(id: String, timeoutMs: Long): WebMcpInvocation = unavailable()
    override suspend fun cancelInvocation(id: String): WebMcpInvocation = unavailable()
}

internal fun boundedUtf8(value: String, maxBytes: Int): String {
    val bytes = value.encodeToByteArray()
    if (bytes.size <= maxBytes) return value
    var end = maxBytes
    while (end > 0 && bytes[end].toInt() and 0xC0 == 0x80) end--
    return bytes.copyOf(end).decodeToString()
}

internal class GuardedWebMcp(private val backend: () -> WebMcp, private val config: KmpwrightConfig,
    private val authorize: suspend () -> Unit) : WebMcp {
    override suspend fun discover(): List<WebMcpTool> {
        if (!config.allowDomExtraction) throw KmpwrightException("DOM extraction is disabled")
        authorize()
        return backend().discover()
    }
    override fun context(): WebMcpContext = if (config.allowDomExtraction) backend().context() else WebMcpContext(false, 0, emptyList(), false)
    override suspend fun invoke(name: String, input: JsonObject, frameId: String?, timeoutMs: Long, waitForResult: Boolean): WebMcpInvocation {
        if (!config.allowEval) throw KmpwrightException("JavaScript evaluation is disabled")
        authorize()
        return backend().invoke(name, input, frameId, timeoutMs, waitForResult)
    }
    override fun invocation(id: String): WebMcpInvocation = backend().invocation(id)
    override suspend fun awaitInvocation(id: String, timeoutMs: Long): WebMcpInvocation = backend().awaitInvocation(id, timeoutMs)
    override suspend fun cancelInvocation(id: String): WebMcpInvocation = backend().cancelInvocation(id)
}
