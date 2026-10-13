package com.bernaferrari.kmpwright.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.coroutines.cancellation.CancellationException

/**
 * Experimental browser WebMCP tools (agent-browser `webmcp`). Metadata and outputs are supplied by the
 * page. A null `timeoutMs` uses [TimeoutConfig.default].
 */
public interface WebMcp {
    /** Tools registered by the page and its frames. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun discover(): List<WebMcpTool>

    /** A bounded summary of the observed tools. */
    public fun context(): WebMcpContext

    /** Invokes tool [name] with [input]; waits for the result unless [waitForResult] is false. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun invoke(name: String, input: JsonObject = JsonObject(emptyMap()), frameId: String? = null,
        timeoutMs: Long? = null, waitForResult: Boolean = true): WebMcpInvocation

    /** The current state of invocation [id]. */
    public fun invocation(id: String): WebMcpInvocation

    /** Waits for invocation [id] to finish. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun awaitInvocation(id: String, timeoutMs: Long? = null): WebMcpInvocation

    /** Cancels invocation [id]. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun cancelInvocation(id: String): WebMcpInvocation
}

/** A tool registered by the page. */
@Serializable
public data class WebMcpTool(val name: String, val description: String, val inputSchema: JsonElement?,
    val annotations: JsonElement?, val origin: String, val frameId: String, val backendNodeId: Long? = null)

/** A compact tool description. */
@Serializable
public data class WebMcpToolSummary(val name: String, val description: String, val origin: String, val frameId: String)

/** Summary of the observed tools. */
@Serializable
public data class WebMcpContext(val observed: Boolean, val toolCount: Int, val tools: List<WebMcpToolSummary>,
    val truncated: Boolean, val experimental: Boolean = true, val untrusted: Boolean = true)

/** Lifecycle of a tool invocation. */
@Serializable
public enum class WebMcpInvocationStatus { Pending, Completed, Canceled, Failed, TimedOut }

/** A tool invocation and its result. */
@Serializable
public data class WebMcpInvocation(val id: String, val toolName: String, val frameId: String, val origin: String,
    val status: WebMcpInvocationStatus, val output: JsonElement? = null, val error: String? = null,
    val outputTruncated: Boolean = false, val originalOutputBytes: Int? = null,
    val durationMs: Long = 0, val rawStatus: String? = null)

internal object UnavailableWebMcp : WebMcp {
    private fun unavailable(): Nothing = throw KmpwrightException("WebMCP requires a browser exposing the experimental CDP WebMCP domain")
    override suspend fun discover(): List<WebMcpTool> = unavailable()
    override fun context() = WebMcpContext(false, 0, emptyList(), false)
    override suspend fun invoke(name: String, input: JsonObject, frameId: String?, timeoutMs: Long?, waitForResult: Boolean): WebMcpInvocation = unavailable()
    override fun invocation(id: String): WebMcpInvocation = unavailable()
    override suspend fun awaitInvocation(id: String, timeoutMs: Long?): WebMcpInvocation = unavailable()
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
    override suspend fun invoke(name: String, input: JsonObject, frameId: String?, timeoutMs: Long?, waitForResult: Boolean): WebMcpInvocation {
        if (!config.allowEval) throw KmpwrightException("JavaScript evaluation is disabled")
        authorize()
        return backend().invoke(name, input, frameId, config.defaultTimeout(timeoutMs), waitForResult)
    }
    override fun invocation(id: String): WebMcpInvocation = backend().invocation(id)
    override suspend fun awaitInvocation(id: String, timeoutMs: Long?): WebMcpInvocation = backend().awaitInvocation(id, config.defaultTimeout(timeoutMs))
    override suspend fun cancelInvocation(id: String): WebMcpInvocation = backend().cancelInvocation(id)
}
