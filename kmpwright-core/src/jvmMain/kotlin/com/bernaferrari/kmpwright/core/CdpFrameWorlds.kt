package com.bernaferrari.kmpwright.core

import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/** Owns only sessions attached by this operation; existing page sessions remain intact. */
internal class CdpFrameWorlds(private val transport: CdpSessionTransport, private val rootSession: String?,
    private val worldName: String, private val refreshContexts: Boolean = false,
    private val onDetached: suspend (String) -> Unit = {}) {
    data class World(val frameId: String, val sessionId: String?, val contextId: Long)
    private val worlds = mutableMapOf<String, World>()
    private val ownedSessions = mutableListOf<String>()
    private val attachedFrames = mutableMapOf<String, String>()

    suspend fun root(): World {
        val tree = send(rootSession, "Page.getFrameTree").getValue("frameTree").jsonObject
        return world(tree.getValue("frame").jsonObject.getValue("id").jsonPrimitive.content, rootSession)
    }

    suspend fun world(frameId: String, parentSession: String?): World {
        if (!refreshContexts) worlds[frameId]?.let { return it }
        var session = attachedFrames[frameId] ?: parentSession
        var response = rawSend(session, "Page.createIsolatedWorld", mapOf("frameId" to frameId, "worldName" to worldName))
        if (response.error != null) {
            // Chrome may not publish a newly swapped iframe target to this
            // connection until discovery runs. Refresh before attaching.
            val targets = send(null, "Target.getTargets")["targetInfos"]?.jsonArray
            if (targets != null) {
                val live = targets.mapNotNull { it.jsonObject["targetId"]?.jsonPrimitive?.content }.toSet()
                val staleSessions = attachedFrames.keys.filter { it !in live || it == frameId }.toList().mapNotNull { stale ->
                    val owned = attachedFrames.remove(stale) ?: return@mapNotNull null
                    worlds.remove(stale); ownedSessions.remove(owned); owned
                }
                withContext(NonCancellable) { withTimeoutOrNull(3_000) {
                    staleSessions.chunked(16).forEach { batch -> coroutineScope {
                        batch.map { owned -> launch { withTimeoutOrNull(500) { runCatching {
                            runCatching { onDetached(owned) }
                            send(null, "Target.detachFromTarget", mapOf("sessionId" to owned))
                        } } } }.joinAll()
                    } }
                } }
            }
            if (ownedSessions.size >= 128) throw KmpwrightException("Frame operation exceeds 128 attached sessions")
            val attached = send(null, "Target.attachToTarget", mapOf("targetId" to frameId, "flatten" to true))
            session = attached.getValue("sessionId").jsonPrimitive.content
            ownedSessions += session
            attachedFrames[frameId] = session
            send(session, "Page.enable")
            response = rawSend(session, "Page.createIsolatedWorld", mapOf("frameId" to frameId, "worldName" to worldName))
        }
        response.error?.let { throw KmpwrightException("Could not access frame $frameId: ${it.message}") }
        val context = response.result?.get("executionContextId")?.jsonPrimitive?.longOrNull
            ?: throw KmpwrightException("Frame $frameId has no execution context")
        return World(frameId, session, context).also { if (!refreshContexts) worlds[frameId] = it }
    }

    suspend fun evaluate(world: World, expression: String, returnByValue: Boolean = true): JsonObject {
        val result = send(world.sessionId, "Runtime.evaluate", mapOf("expression" to expression,
            "contextId" to world.contextId, "returnByValue" to returnByValue, "awaitPromise" to returnByValue))
        result["exceptionDetails"]?.let { throw KmpwrightException("Frame JavaScript failed: $it") }
        return result.getValue("result").jsonObject
    }

    suspend fun evaluateJson(world: World, expression: String): JsonObject {
        val value = evaluate(world, expression)["value"]?.jsonPrimitive?.content
            ?: throw KmpwrightException("Frame returned no JSON result")
        return Json.parseToJsonElement(value).jsonObject
    }

    suspend fun child(parent: World, expression: String): World {
        for (attempt in 0..9) {
            try { return childOnce(parent, expression) }
            catch (error: KmpwrightException) {
                val message = error.message.orEmpty()
                if (attempt == 9 || !(message.contains("No target with given id") || message.contains("No frame for given id"))) throw error
                delay(50)
            }
        }
        error("Unreachable frame resolution")
    }

    private suspend fun childOnce(parent: World, expression: String): World {
        val owner = evaluate(parent, expression, false)["objectId"]?.jsonPrimitive?.content
            ?: throw KmpwrightException("Could not resolve frame element")
        val node = try { send(parent.sessionId, "DOM.describeNode", mapOf("objectId" to owner, "depth" to 1))
            .getValue("node").jsonObject } finally {
            withContext(NonCancellable) { withTimeoutOrNull(1_000) { runCatching {
                send(parent.sessionId, "Runtime.releaseObject", mapOf("objectId" to owner))
            } } }
        }
        val id = (node["contentDocument"]?.jsonObject?.get("frameId") ?: node["frameId"])?.jsonPrimitive?.content
            ?: throw KmpwrightException("Selected element has no browsing context")
        return world(id, parent.sessionId)
    }

    suspend fun close() = withContext(NonCancellable) {
        withTimeoutOrNull(3_000) {
            ownedSessions.asReversed().chunked(16).forEach { batch -> coroutineScope {
                batch.map { session -> launch { withTimeoutOrNull(1_000) { runCatching {
                    runCatching { onDetached(session) }
                    send(null, "Target.detachFromTarget", mapOf("sessionId" to session))
                } } } }.joinAll()
            } }
        }
        ownedSessions.clear()
        attachedFrames.clear()
        worlds.clear()
    }

    private suspend fun rawSend(session: String?, method: String, params: Map<String, Any?>): CdpResponse =
        if (session == null) transport.send(method, params) else transport.sendSession(session, method, params)

    private suspend fun send(session: String?, method: String, params: Map<String, Any?> = emptyMap()): JsonObject {
        val response = rawSend(session, method, params)
        response.error?.let { throw KmpwrightException("$method: ${it.message}") }
        return response.result ?: throw KmpwrightException("$method returned no result")
    }
}
