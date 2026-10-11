package com.bernaferrari.kmpwright.core

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class CdpWebMcpTest {
    @Test fun `async disposal disables page-owned protocol observation once`() = runBlocking {
        val transport = ToolTransport()
        val runtime = CdpWebMcp(transport, "session")
        runtime.observeBeforeNavigation()
        assertEquals(1, transport.commands.count { it == "Target.setAutoAttach" })
        runtime.disposeAsync()
        assertEquals(2, transport.commands.count { it == "Target.setAutoAttach" })
        assertEquals(1, transport.commands.count { it == "WebMCP.disable" })
        assertEquals(0, transport.listeners.values.sumOf { it.size })
        runtime.disposeAsync()
        assertEquals(2, transport.commands.count { it == "Target.setAutoAttach" })
    }

    @Test fun `response arriving before invoke reply is retained`() = runBlocking {
        val transport = ToolTransport()
        val runtime = CdpWebMcp(transport, "session")
        try {
            transport.respondImmediately = true
            val result = runtime.invoke("greet")
            assertEquals(WebMcpInvocationStatus.Completed, result.status)
            assertEquals("hello", result.output?.jsonPrimitive?.content)
            assertEquals("https://fixture.test", result.origin)
        } finally { runtime.dispose() }
        assertEquals(0, transport.listeners.values.sumOf { it.size })
    }

    @Test fun `navigation invalidates pending work and tool summaries`() = runBlocking {
        val transport = ToolTransport()
        val runtime = CdpWebMcp(transport, "session")
        try {
            val pending = runtime.invoke("greet", waitForResult = false)
            assertEquals(1, runtime.context().toolCount)
            transport.emit("Page.frameNavigated", """{"frame":{"id":"main","url":"https://fixture.test/next"}}""")
            assertEquals(WebMcpInvocationStatus.Failed, runtime.invocation(pending.id).status)
            assertEquals(0, runtime.context().toolCount)
        } finally { runtime.dispose() }
    }

    @Test fun `deadline cancels remote invocation and preserves timed out status`() = runBlocking {
        val transport = ToolTransport()
        val runtime = CdpWebMcp(transport, "session")
        try {
            val pending = runtime.invoke("greet", waitForResult = false)
            val result = runtime.awaitInvocation(pending.id, 10)
            assertEquals(WebMcpInvocationStatus.TimedOut, result.status)
            assertTrue(transport.commands.contains("WebMCP.cancelInvocation"))
            transport.emit("WebMCP.toolResponded", """{"invocationId":"${pending.id}","status":"Completed","output":"late"}""")
            assertEquals(WebMcpInvocationStatus.TimedOut, runtime.invocation(pending.id).status)
        } finally { runtime.dispose() }
    }

    @Test fun `caller cancellation is propagated and cancels remote work`() = runBlocking {
        val transport = ToolTransport()
        val runtime = CdpWebMcp(transport, "session")
        try {
            val pending = runtime.invoke("greet", waitForResult = false)
            assertNull(withTimeoutOrNull(10) { runtime.awaitInvocation(pending.id, 1_000) })
            assertEquals(WebMcpInvocationStatus.Canceled, runtime.invocation(pending.id).status)
            assertTrue(transport.commands.contains("WebMCP.cancelInvocation"))
        } finally { runtime.dispose() }
    }

    @Test fun `page metadata and invocation payloads obey byte limits`() = runBlocking {
        val transport = ToolTransport()
        transport.description = "雪".repeat(1000)
        val runtime = CdpWebMcp(transport, "session")
        try {
            runtime.discover()
            val context = runtime.context()
            assertTrue(context.truncated)
            assertTrue(context.tools.single().description.encodeToByteArray().size <= 160)
            assertTrue(Json.encodeToString(context).encodeToByteArray().size <= 4096)
            val pending = runtime.invoke("greet", waitForResult = false)
            transport.emit("WebMCP.toolResponded", buildJsonObject {
                put("invocationId", pending.id); put("status", "Completed"); put("output", "x".repeat(2 * 1024 * 1024))
            }.toString())
            assertTrue(runtime.invocation(pending.id).outputTruncated)
            assertEquals(2 * 1024 * 1024 + 2, runtime.invocation(pending.id).originalOutputBytes)
            try {
                runtime.invoke("greet", buildJsonObject { put("text", "x".repeat(1024 * 1024)) })
                fail("Oversized input must fail")
            } catch (_: IllegalArgumentException) { }
        } finally { runtime.dispose() }
    }

    @Test fun `tool origins obey the controller allowlist`() = runBlocking {
        val runtime = CdpWebMcp(ToolTransport(), "session", setOf("https://allowed.test"))
        try {
            try { runtime.discover(); fail("Foreign tool origin must be rejected") }
            catch (e: KmpwrightException) { assertTrue(e.message!!.contains("origin")) }
        } finally { runtime.dispose() }
    }

    @Test fun `active invocation limit rejects overflow without evicting pending work`() = runBlocking {
        val transport = ToolTransport()
        val runtime = CdpWebMcp(transport, "session")
        try {
            val pending = (1..128).map { runtime.invoke("greet", waitForResult = false) }
            try { runtime.invoke("greet", waitForResult = false); fail("Active history must be bounded") }
            catch (e: KmpwrightException) { assertTrue(e.message!!.contains("Too many")) }
            assertEquals(WebMcpInvocationStatus.Pending, runtime.invocation(pending.first().id).status)
            runtime.cancelInvocation(pending.first().id)
            transport.respondImmediately = true
            val completed = runtime.invoke("greet")
            assertEquals(WebMcpInvocationStatus.Completed, completed.status)
            assertEquals(1, transport.commands.count { it == "WebMCP.enable" })
        } finally { runtime.dispose() }
    }

    @Test fun `discovery refresh retains observed child tools`() = runBlocking {
        val transport = ToolTransport()
        val runtime = CdpWebMcp(transport, "session")
        try {
            runtime.discover()
            transport.emit("Page.frameNavigated", """{"frame":{"id":"child","parentId":"main","url":"https://fixture.test/frame"}}""")
            transport.emit("WebMCP.toolsAdded", """{"tools":[{"name":"child-tool","frameId":"child","description":"Child"}]}""")
            assertEquals(setOf("greet", "child-tool"), runtime.discover().map { it.name }.toSet())
        } finally { runtime.dispose() }
    }

    @Test fun `frame process swap does not invalidate its active invocation`() = runBlocking {
        val transport = ToolTransport()
        val runtime = CdpWebMcp(transport, "session")
        try {
            val pending = runtime.invoke("greet", waitForResult = false)
            transport.emit("Page.frameDetached", """{"frameId":"main","reason":"swap"}""")
            assertEquals(WebMcpInvocationStatus.Pending, runtime.invocation(pending.id).status)
            assertEquals(1, runtime.context().toolCount)
            transport.emit("Page.frameDetached", """{"frameId":"main","reason":"remove"}""")
            assertEquals(WebMcpInvocationStatus.Failed, runtime.invocation(pending.id).status)
        } finally { runtime.dispose() }
    }

    private class ToolTransport : CdpEventTransport {
        val listeners = mutableMapOf<String, MutableList<(JsonObject) -> Unit>>()
        val commands = mutableListOf<String>()
        var respondImmediately = false
        var description = "Greeting"
        private var nextId = 0
        override fun onEvent(method: String, sessionId: String?, listener: (JsonObject) -> Unit): CdpEventSubscription {
            listeners.getOrPut(method) { mutableListOf() }.add(listener)
            return CdpEventSubscription { listeners[method]?.remove(listener) }
        }
        fun emit(method: String, json: String) { listeners[method]?.toList()?.forEach { it(Json.parseToJsonElement(json).jsonObject) } }
        override suspend fun sendSession(sessionId: String, method: String, params: Map<String, Any?>): CdpResponse = send(method, params)
        override suspend fun send(method: String, params: Map<String, Any?>): CdpResponse {
            commands += method
            val result = when (method) {
                "Page.getFrameTree" -> Json.parseToJsonElement("""{"frameTree":{"frame":{"id":"main","securityOrigin":"https://fixture.test"}}}""").jsonObject
                "WebMCP.enable" -> {
                    emit("WebMCP.toolsAdded", buildJsonObject {
                        put("tools", buildJsonArray { add(buildJsonObject {
                            put("name", "greet"); put("frameId", "main"); put("description", description); put("inputSchema", buildJsonObject { put("type", "object") })
                        }) })
                    }.toString())
                    JsonObject(emptyMap())
                }
                "WebMCP.invokeTool" -> {
                    val id = "call-${++nextId}"
                    if (respondImmediately) emit("WebMCP.toolResponded", """{"invocationId":"$id","status":"Completed","output":"hello"}""")
                    buildJsonObject { put("invocationId", id) }
                }
                else -> JsonObject(emptyMap())
            }
            return CdpResponse(1, result)
        }
    }
}
