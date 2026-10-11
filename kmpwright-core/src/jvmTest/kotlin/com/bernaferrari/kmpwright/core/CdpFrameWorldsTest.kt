package com.bernaferrari.kmpwright.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class CdpFrameWorldsTest {
    @Test fun `removed iframe sessions are pruned before the attachment limit`() = runBlocking {
        val transport = FrameTransport()
        val worlds = CdpFrameWorlds(transport, "root", "fixture", refreshContexts = true)
        repeat(150) { index ->
            transport.currentTarget = "frame$index"
            val world = worlds.world(transport.currentTarget, "root")
            assertEquals("child${index + 1}", world.sessionId)
        }
        assertEquals(149, transport.detached.size)
        assertFalse(transport.detached.contains("root"))
        worlds.close()
        assertEquals(150, transport.detached.size)
        worlds.close()
        assertEquals(150, transport.detached.size)
    }

    @Test fun `local session cleanup runs even when Chromium already detached it`() = runBlocking {
        val transport = FrameTransport().apply { detachAlreadyGone = true }
        val cleaned = java.util.concurrent.CopyOnWriteArrayList<String>()
        val worlds = CdpFrameWorlds(transport, "root", "fixture", refreshContexts = true, onDetached = { cleaned += it })
        worlds.world("frame", "root")
        transport.currentTarget = "replacement"
        worlds.world("replacement", "root")
        assertEquals(listOf("child1"), cleaned.toList())
        worlds.close()
        assertEquals(listOf("child1", "child2"), cleaned.toList())
        worlds.close()
        assertEquals(2, cleaned.size)
    }

    @Test fun `same process worlds never detach the supplied parent session`() = runBlocking {
        val transport = FrameTransport().apply { sameProcess = true }
        val worlds = CdpFrameWorlds(transport, "root", "fixture")
        assertEquals("parent", worlds.world("same", "parent").sessionId)
        worlds.close()
        assertTrue(transport.detached.isEmpty())
    }
}

private class FrameTransport : CdpSessionTransport {
    var currentTarget = "frame"
    var sameProcess = false
    var detachAlreadyGone = false
    var attached = 0
    val detached = mutableListOf<String>()
    override suspend fun send(method: String, params: Map<String, Any?>): CdpResponse = when (method) {
        "Target.getTargets" -> success(buildJsonObject { put("targetInfos", buildJsonArray {
            add(buildJsonObject { put("targetId", currentTarget) })
        }) })
        "Target.attachToTarget" -> success(buildJsonObject { put("sessionId", "child${++attached}") })
        "Target.detachFromTarget" -> { detached += params.getValue("sessionId").toString(); if (detachAlreadyGone) CdpResponse(1, error = CdpError(-32000, "No session with given id")) else success() }
        else -> success()
    }
    override suspend fun sendSession(sessionId: String, method: String, params: Map<String, Any?>): CdpResponse {
        if (method == "Page.createIsolatedWorld") {
            return if (sameProcess || sessionId.startsWith("child")) success(buildJsonObject { put("executionContextId", 7) })
            else CdpResponse(1, error = CdpError(-32000, "No frame for given id"))
        }
        return success()
    }
    private fun success(result: JsonObject = buildJsonObject {}) = CdpResponse(1, result = result)
}
