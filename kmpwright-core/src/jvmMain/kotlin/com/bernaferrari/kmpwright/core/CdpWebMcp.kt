package com.bernaferrari.kmpwright.core

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.util.LinkedHashMap

internal class CdpWebMcp(private val transport: CdpEventTransport, private val sessionId: String?, private val allowedOrigins: Set<String> = emptySet(),
    private val frameReady: suspend (String) -> Unit = {}, private val frameGone: (String) -> Unit = {}) : WebMcp {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val toolSessions = mutableMapOf<Pair<String, String>, String?>()
    private val invocationSessions = mutableMapOf<String, String?>()
    private val childSubscriptions = mutableMapOf<String, List<CdpEventSubscription>>()
    private val childFrames = mutableMapOf<String, String>()
    private var observationAttempted = false
    private var autoAttachEnabled = false
    private val discoveryMutex = Mutex()
    private val tools = linkedMapOf<Pair<String, String>, WebMcpTool>()
    private val toolSizes = mutableMapOf<Pair<String, String>, Int>()
    private var catalogBytes = 2
    private val origins = mutableMapOf<String, String>()
    private val records = LinkedHashMap<String, WebMcpInvocation>()
    private val startedAt = mutableMapOf<String, Long>()
    private val frameEpochs = mutableMapOf<String, Long>()
    private val completions = mutableMapOf<String, CompletableDeferred<WebMcpInvocation>>()
    private val early = LinkedHashMap<String, JsonObject>()
    private var observed = false
    private var catalogError: String? = null
    private var generation = 0L
    private var reservations = 0
    private var closed = false
    private val subscriptions = listOf(
        transport.onEvent("WebMCP.toolsAdded", sessionId) { toolsAdded(it, sessionId) },
        transport.onEvent("WebMCP.toolsRemoved", sessionId) { data -> synchronized(lock) {
            data["tools"]?.jsonArray?.forEach { raw ->
                val item = raw.jsonObject
                removeTool(item.string("frameId") to item.string("name"))
            }
        } },
        transport.onEvent("WebMCP.toolResponded", sessionId, ::responded),
        transport.onEvent("Page.frameNavigated", sessionId) { data ->
            val frame = data["frame"]?.jsonObject ?: return@onEvent
            invalidate(if (frame["parentId"] == null) null else frame.string("id"))
            synchronized(lock) { origins[frame.string("id")] = frame.origin() }
        },
        transport.onEvent("Page.frameDetached", sessionId) { if (it.string("reason") != "swap") invalidate(it.string("frameId")) },
        transport.onEvent("Target.attachedToTarget", sessionId) { data -> scope.launch { attachChild(data) } },
        transport.onEvent("Target.detachedFromTarget", sessionId) { data -> detachChild(data.string("sessionId")) }
    )

    private suspend fun send(method: String, params: Map<String, Any?> = emptyMap(), targetSession: String? = sessionId): JsonObject {
        check(!synchronized(lock) { closed }) { "Page is closed" }
        val response = if (targetSession == null) transport.send(method, params) else transport.sendSession(targetSession, method, params)
        response.error?.let { throw KmpwrightException("$method: ${it.message}") }
        return response.result ?: JsonObject(emptyMap())
    }

    /** Optional discovery starts before application scripts register tools. */
    suspend fun observeBeforeNavigation() = discoveryMutex.withLock {
        if (observationAttempted) return@withLock
        observationAttempted = true
        try { send("WebMCP.enable"); synchronized(lock) { observed = true } }
        catch (_: KmpwrightException) { /* Network/frame observation also works without WebMCP. */ }
        try {
            send("Target.setAutoAttach", mapOf("autoAttach" to true, "waitForDebuggerOnStart" to true,
                "flatten" to true, "filter" to listOf(mapOf("type" to "iframe", "exclude" to false), mapOf("exclude" to true))))
            synchronized(lock) { autoAttachEnabled = true }
        } catch (_: KmpwrightException) { /* Unsupported target domains remain visible in capability limits. */ }
    }

    private suspend fun attachChild(data: JsonObject) {
        val childSession = data.string("sessionId")
        val info = data["targetInfo"]?.jsonObject ?: return
        if (childSession.isEmpty()) return
        try {
            if (info.string("type") != "iframe") return
            val frameId = info.string("targetId")
            val handlers = listOf(
                transport.onEvent("WebMCP.toolsAdded", childSession) { toolsAdded(it, childSession) },
                transport.onEvent("WebMCP.toolsRemoved", childSession) { payload -> synchronized(lock) {
                    payload["tools"]?.jsonArray?.forEach { raw -> removeTool(raw.jsonObject.string("frameId") to raw.jsonObject.string("name")) }
                } },
                transport.onEvent("WebMCP.toolResponded", childSession, ::responded),
                transport.onEvent("Page.frameNavigated", childSession) { payload ->
                    val frame = payload["frame"]?.jsonObject ?: return@onEvent
                    invalidate(frame.string("id")); synchronized(lock) { origins[frame.string("id")] = frame.origin() }
                },
                transport.onEvent("Page.frameDetached", childSession) { if (it.string("reason") != "swap") invalidate(it.string("frameId")) },
                transport.onEvent("Target.attachedToTarget", childSession) { payload -> scope.launch { attachChild(payload) } },
                transport.onEvent("Target.detachedFromTarget", childSession) { detachChild(it.string("sessionId")) }
            )
            synchronized(lock) {
                if (closed || childSubscriptions.size >= 128) {
                    handlers.forEach { it.cancel() }; throw KmpwrightException("WebMCP exceeds 128 iframe sessions")
                }
                childSubscriptions[childSession] = handlers
                childFrames[childSession] = frameId
                origins[frameId] = info.origin()
            }
            withTimeout(5_000) {
                send("Page.enable", targetSession = childSession)
                frameReady(childSession)
                // Recurse before the optional WebMCP domain, so ordinary frame features work on stable Chromium.
                send("Target.setAutoAttach", mapOf("autoAttach" to true, "waitForDebuggerOnStart" to true,
                    "flatten" to true, "filter" to listOf(mapOf("type" to "iframe", "exclude" to false), mapOf("exclude" to true))), childSession)
                send("WebMCP.enable", targetSession = childSession)
            }
        } catch (error: KmpwrightException) {
            synchronized(lock) { catalogError = "WebMCP iframe discovery failed: ${error.message}" }
        } catch (error: TimeoutCancellationException) {
            synchronized(lock) { catalogError = "WebMCP iframe discovery timed out" }
        } finally {
            withContext(NonCancellable) { withTimeoutOrNull(1_000) { runCatching {
                val response = transport.sendSession(childSession, "Runtime.runIfWaitingForDebugger")
                response.error?.let { throw KmpwrightException(it.message) }
            } } }
        }
    }

    private fun detachChild(childSession: String) {
        frameGone(childSession)
        synchronized(lock) {
            childSubscriptions.remove(childSession)?.forEach { runCatching { it.cancel() } }
            childFrames.remove(childSession)?.let(::invalidate)
        }
    }

    override suspend fun discover(): List<WebMcpTool> = discoveryMutex.withLock {
        val tree = send("Page.getFrameTree")["frameTree"]?.jsonObject
        fun collect(node: JsonObject) {
            node["frame"]?.jsonObject?.let { frame -> synchronized(lock) { origins[frame.string("id")] = frame.origin() } }
            node["childFrames"]?.jsonArray?.forEach { collect(it.jsonObject) }
        }
        if (tree != null) collect(tree)
        synchronized(lock) {
            if (!observed) { tools.clear(); toolSizes.clear(); toolSessions.clear(); catalogBytes = 2; catalogError = null }
        }
        send("WebMCP.enable")
        synchronized(lock) { observed = true }
        delay(250)
        synchronized(lock) { cachedTools() }
    }

    private fun cachedTools(): List<WebMcpTool> {
        catalogError?.let { throw KmpwrightException(it) }
        return tools.values.sortedWith(compareBy({ it.name }, { it.frameId })).also { catalog ->
            if (allowedOrigins.isNotEmpty() && catalog.any { tool -> allowedOrigins.none { originRuleMatches(it, tool.origin) } }) {
                throw KmpwrightException("WebMCP tool origin is not allowed")
            }
        }
    }

    private fun toolsAdded(data: JsonObject, sourceSession: String?) = synchronized(lock) {
        if (closed) return@synchronized
        for (raw in data["tools"]?.jsonArray ?: return@synchronized) {
            if (raw.toString().encodeToByteArray().size > 256 * 1024) {
                catalogError = "WebMCP tool metadata exceeds 256 KiB"; continue
            }
            val item = raw as? JsonObject
            if (item == null) { catalogError = "Invalid WebMCP tool metadata"; continue }
            val name = item.string("name")
            val frame = item.string("frameId")
            if (name.isEmpty() || frame.isEmpty()) { catalogError = "Invalid WebMCP tool metadata"; continue }
            val tool = WebMcpTool(name, item.string("description"), item["inputSchema"], item["annotations"],
                origins[frame] ?: "", frame, item["backendNodeId"]?.jsonPrimitive?.longOrNull)
            val key = frame to name
            if (key !in tools && tools.size >= 512) { catalogError = "WebMCP tool catalog exceeds 512 tools"; continue }
            val bytes = Json.encodeToString(tool).encodeToByteArray().size + 1
            val proposed = catalogBytes - (toolSizes[key] ?: 0) + bytes
            if (proposed > 2 * 1024 * 1024) { catalogError = "WebMCP tool catalog exceeds 2 MiB"; continue }
            tools[key] = tool
            toolSessions[key] = sourceSession
            toolSizes[key] = bytes
            catalogBytes = proposed
        }
    }

    override fun context(): WebMcpContext = synchronized(lock) {
        val summaries = mutableListOf<WebMcpToolSummary>()
        var truncated = catalogError != null
        for (tool in tools.values.sortedWith(compareBy({ it.name }, { it.frameId }))) {
            val description = boundedUtf8(tool.description, 160)
            val summary = WebMcpToolSummary(tool.name, description, tool.origin, tool.frameId)
            val candidate = summaries + summary
            if (candidate.size > 16 || Json.encodeToString(WebMcpContext(observed, tools.size, candidate, true)).encodeToByteArray().size > 4096) {
                truncated = true; continue
            }
            summaries += summary
            truncated = truncated || description != tool.description
        }
        WebMcpContext(observed, tools.size, summaries, truncated)
    }

    override suspend fun invoke(name: String, input: JsonObject, frameId: String?, timeoutMs: Long, waitForResult: Boolean): WebMcpInvocation {
        require(timeoutMs > 0) { "Invocation timeout must be positive" }
        require(input.toString().encodeToByteArray().size <= 1024 * 1024) { "WebMCP input exceeds 1 MiB" }
        val cached = synchronized(lock) { if (observed) cachedTools() else null }
        var matches = (cached ?: discover()).filter { it.name == name && (frameId == null || it.frameId == frameId) }
        if (matches.isEmpty() && cached != null) matches = discover().filter { it.name == name && (frameId == null || it.frameId == frameId) }
        if (matches.isEmpty()) throw KmpwrightException("WebMCP tool not found: $name")
        if (matches.size != 1) throw KmpwrightException("WebMCP tool name is ambiguous; specify frameId: $name")
        val tool = matches.single()
        val startedGeneration = synchronized(lock) {
            trimHistory()
            if (records.values.count { it.status == WebMcpInvocationStatus.Pending } + reservations >= 128) {
                throw KmpwrightException("Too many active WebMCP invocations")
            }
            reservations++
            generation to (frameEpochs[tool.frameId] ?: 0L)
        }
        val id: String
        try {
            val selectedSession = synchronized(lock) { toolSessions[tool.frameId to name] }
            id = send("WebMCP.invokeTool", mapOf("frameId" to tool.frameId, "toolName" to name, "input" to input), selectedSession).string("invocationId")
            if (id.isEmpty()) throw KmpwrightException("Browser returned no WebMCP invocation identifier")
            synchronized(lock) {
                invocationSessions[id] = selectedSession
                records[id] = WebMcpInvocation(id, name, tool.frameId, tool.origin, WebMcpInvocationStatus.Pending)
                startedAt[id] = System.nanoTime()
                completions[id] = CompletableDeferred()
                if ((generation to (frameEpochs[tool.frameId] ?: 0L)) != startedGeneration) finish(records.getValue(id).copy(status = WebMcpInvocationStatus.Failed, error = "Page context changed"))
                else early.remove(id)?.let { applyResponse(id, it) }
                trimHistory(128)
            }
        } finally { synchronized(lock) { reservations-- } }
        return if (waitForResult) awaitInvocation(id, timeoutMs) else invocation(id)
    }

    override fun invocation(id: String): WebMcpInvocation = synchronized(lock) {
        val record = records[id] ?: throw KmpwrightException("Unknown WebMCP invocation: $id")
        if (record.status == WebMcpInvocationStatus.Pending) record.copy(durationMs = elapsed(id)) else record
    }

    override suspend fun awaitInvocation(id: String, timeoutMs: Long): WebMcpInvocation {
        require(timeoutMs > 0) { "Invocation timeout must be positive" }
        val deferred = synchronized(lock) {
            val record = invocation(id)
            if (record.status != WebMcpInvocationStatus.Pending) return record
            completions.getValue(id)
        }
        try {
            val result = withTimeoutOrNull(timeoutMs) { deferred.await() }
            if (result != null) return result
            synchronized(lock) {
                val current = invocation(id)
                if (current.status == WebMcpInvocationStatus.Pending) finish(current.copy(status = WebMcpInvocationStatus.TimedOut))
            }
            cancelRemote(id)
            return invocation(id)
        } catch (error: CancellationException) {
            synchronized(lock) {
                val current = records[id]
                if (current?.status == WebMcpInvocationStatus.Pending) finish(current.copy(status = WebMcpInvocationStatus.Canceled))
            }
            cancelRemote(id)
            throw error
        }
    }

    private suspend fun cancelRemote(id: String) = withContext(NonCancellable) {
        withTimeoutOrNull(1_000) { runCatching { send("WebMCP.cancelInvocation", mapOf("invocationId" to id), synchronized(lock) { invocationSessions[id] }) } }
        Unit
    }

    override suspend fun cancelInvocation(id: String): WebMcpInvocation {
        if (invocation(id).status != WebMcpInvocationStatus.Pending) throw KmpwrightException("WebMCP invocation is no longer active: $id")
        send("WebMCP.cancelInvocation", mapOf("invocationId" to id), synchronized(lock) { invocationSessions[id] })
        return synchronized(lock) {
            val current = invocation(id)
            if (current.status == WebMcpInvocationStatus.Pending) finish(current.copy(status = WebMcpInvocationStatus.Canceled))
            invocation(id)
        }
    }

    private fun responded(data: JsonObject) = synchronized(lock) {
        if (closed) return@synchronized
        val id = data.string("invocationId")
        if (id.isEmpty()) return@synchronized
        // Normalize before retaining unknown early events so their payload cannot bypass output limits.
        val bounded = buildJsonObject {
            put("status", data["status"] ?: JsonPrimitive("Error"))
            data["output"]?.let { output ->
                val bytes = output.toString().encodeToByteArray().size
                if (bytes > 2 * 1024 * 1024) {
                    put("output", JsonPrimitive(boundedUtf8(output.toString(), 1024 * 1024 - 1)))
                    put("outputTruncated", true); put("originalOutputBytes", bytes)
                } else put("output", output)
            }
            data["errorText"]?.let { put("errorText", boundedUtf8(it.jsonPrimitive.content, 64 * 1024)) }
            data["exception"]?.let { put("errorText", boundedUtf8(it.toString(), 64 * 1024)) }
        }
        if (id in records) applyResponse(id, bounded)
        else if (reservations > 0) {
            early[id] = bounded
            while (early.size > 128) early.remove(early.keys.first())
        }
    }

    private fun applyResponse(id: String, data: JsonObject) {
        val current = records[id] ?: return
        if (current.status != WebMcpInvocationStatus.Pending) return
        val status = when (data.string("status")) {
            "Completed" -> WebMcpInvocationStatus.Completed
            "Canceled" -> WebMcpInvocationStatus.Canceled
            else -> WebMcpInvocationStatus.Failed
        }
        finish(current.copy(status = status, output = data["output"], error = data["errorText"]?.jsonPrimitive?.content,
            outputTruncated = data["outputTruncated"]?.jsonPrimitive?.booleanOrNull == true,
            originalOutputBytes = data["originalOutputBytes"]?.jsonPrimitive?.intOrNull, rawStatus = data.string("status")))
    }

    private fun finish(record: WebMcpInvocation) {
        val finished = record.copy(durationMs = elapsed(record.id))
        records[record.id] = finished
        completions.remove(record.id)?.complete(finished)
    }

    private fun trimHistory(maximum: Int = 127) {
        while (records.size > maximum) {
            val oldest = records.values.firstOrNull { it.status != WebMcpInvocationStatus.Pending } ?: break
            records.remove(oldest.id)
            startedAt.remove(oldest.id)
            invocationSessions.remove(oldest.id)
        }
    }

    private fun invalidate(frame: String?) = synchronized(lock) {
        if (frame == null) { generation++; frameEpochs.clear(); tools.clear(); toolSizes.clear(); toolSessions.clear(); catalogBytes = 2; origins.clear(); catalogError = null; early.clear() }
        else { frameEpochs[frame] = (frameEpochs[frame] ?: 0L) + 1; tools.keys.filter { it.first == frame }.forEach { removeTool(it) }; origins.remove(frame) }
        records.values.filter { it.status == WebMcpInvocationStatus.Pending && (frame == null || it.frameId == frame) }.toList()
            .forEach { finish(it.copy(status = WebMcpInvocationStatus.Failed, error = "Page context changed")) }
    }

    suspend fun disposeAsync() = withContext(NonCancellable) {
        val state = synchronized(lock) { if (closed) return@withContext; Triple(observationAttempted || autoAttachEnabled, observed, childSubscriptions.keys.toList()) }
        dispose()
        suspend fun cleanup(session: String?, method: String, params: Map<String, Any?> = emptyMap()) {
            withTimeoutOrNull(1_000) { runCatching {
                if (session == null) transport.send(method, params) else transport.sendSession(session, method, params)
            } }
        }
        withTimeoutOrNull(4_000) {
            // Stop pausing new children before removing owned child sessions.
            if (state.first) cleanup(sessionId, "Target.setAutoAttach", mapOf("autoAttach" to false, "waitForDebuggerOnStart" to false, "flatten" to true))
            state.third.chunked(16).forEach { batch -> coroutineScope {
                batch.map { child -> launch {
                    cleanup(child, "Runtime.runIfWaitingForDebugger")
                    cleanup(child, "Target.setAutoAttach", mapOf("autoAttach" to false, "waitForDebuggerOnStart" to false, "flatten" to true))
                    cleanup(null, "Target.detachFromTarget", mapOf("sessionId" to child))
                } }.joinAll()
            } }
            if (state.second) cleanup(sessionId, "WebMCP.disable")
        }
    }

    fun dispose() {
        synchronized(lock) { closed = true }
        invalidate(null)
        subscriptions.forEach { runCatching { it.cancel() } }
        synchronized(lock) { childSubscriptions.values.flatten().forEach { runCatching { it.cancel() } }; childSubscriptions.clear(); childFrames.clear() }
        scope.cancel()
        synchronized(lock) { records.clear(); startedAt.clear(); invocationSessions.clear() }
    }

    private fun removeTool(key: Pair<String, String>) {
        tools.remove(key)
        toolSessions.remove(key)
        catalogBytes -= toolSizes.remove(key) ?: 0
    }

    private fun elapsed(id: String): Long = startedAt[id]?.let { (System.nanoTime() - it) / 1_000_000 } ?: 0

    private fun JsonObject.origin(): String {
        val security = string("securityOrigin")
        if (security.isNotEmpty()) return security
        return runCatching {
            val url = java.net.URI(string("url"))
            if (url.host == null) "null" else "${url.scheme}://${url.host}" + if (url.port >= 0) ":${url.port}" else ""
        }.getOrDefault("null")
    }

    private fun JsonObject.string(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull ?: ""
}
