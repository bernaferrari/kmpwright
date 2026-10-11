package com.bernaferrari.kmpwright.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CdpSessionsTest {

    @Test fun `attaching an existing target detaches without closing it`() = runBlocking {
        val transport = FakeCdpSessionTransport(sendResponses = mutableListOf(
            CdpResponse(1, result = jsonObject("sessionId" to JsonPrimitive("attached-session")))))
        val session = attachCdpPageSession(transport, "user-tab")
        assertEquals(false, session.ownsTarget)
        assertEquals(emptyList<String>(), transport.sessionCalls.map { it.method })
        closeCdpPageSession(transport, session)
        assertEquals(listOf(CdpMethods.TARGET_ATTACH_TO_TARGET, "Target.detachFromTarget"), transport.sendCalls.map { it.method })
        assertEquals("attached-session", transport.sendCalls.last().params["sessionId"])
    }

    @Test fun `domain setup failure closes only the newly created target`() = runBlocking<Unit> {
        val transport = FakeCdpSessionTransport(sendResponses = mutableListOf(
            CdpResponse(1, result = jsonObject("targetId" to JsonPrimitive("owned-tab"))),
            CdpResponse(2, result = jsonObject("sessionId" to JsonPrimitive("session")))),
            sessionResponses = mutableListOf(CdpResponse(3, error = CdpError(-1, "setup failed"))))
        try { createCdpPageSession(transport, CdpEndpointScope.Browser); org.junit.Assert.fail("Expected setup error") }
        catch (error: KmpwrightException) { org.junit.Assert.assertTrue(error.message!!.contains("setup failed")) }
        assertEquals("owned-tab", transport.sendCalls.last().params["targetId"])
        assertEquals(1, transport.sendCalls.count { it.method == CdpMethods.TARGET_CLOSE_TARGET })
    }

    @Test fun `window creation requests a separate browser window`() = runBlocking {
        val transport = FakeCdpSessionTransport(sendResponses = mutableListOf(
            CdpResponse(1, result = jsonObject("targetId" to JsonPrimitive("window-tab"))),
            CdpResponse(2, result = jsonObject("sessionId" to JsonPrimitive("session")))))
        createCdpPageSession(transport, CdpEndpointScope.Browser, newWindow = true)
        assertEquals(true, transport.sendCalls.first().params["newWindow"])
    }

    @Test
    fun `detect endpoint scope distinguishes browser and page endpoints`() = runBlocking {
        val browserTransport = FakeCdpSessionTransport(
            sendResponses = mutableListOf(
                CdpResponse(id = 1, result = jsonObject("targetInfos" to JsonPrimitive("[]")))
            )
        )
        val pageTransport = FakeCdpSessionTransport(
            sendResponses = mutableListOf(
                CdpResponse(id = 1, error = CdpError(-32601, "Method not found"))
            )
        )

        assertEquals(CdpEndpointScope.Browser, detectCdpEndpointScope(browserTransport))
        assertEquals(CdpEndpointScope.Page, detectCdpEndpointScope(pageTransport))
    }

    @Test
    fun `browser scoped session creates target attaches and enables domains`() = runBlocking {
        val transport = FakeCdpSessionTransport(
            sendResponses = mutableListOf(
                CdpResponse(id = 1, result = jsonObject("targetId" to JsonPrimitive("target-1"))),
                CdpResponse(id = 2, result = jsonObject("sessionId" to JsonPrimitive("session-1")))
            )
        )

        val session = createCdpPageSession(transport, CdpEndpointScope.Browser)

        assertEquals(CdpEndpointScope.Browser, session.scope)
        assertEquals("target-1", session.targetId)
        assertEquals("session-1", session.sessionId)
        assertEquals(
            listOf(
                CdpMethods.TARGET_CREATE_TARGET,
                CdpMethods.TARGET_ATTACH_TO_TARGET
            ),
            transport.sendCalls.map { it.method }
        )
        assertEquals(
            listOf(
                CdpMethods.PAGE_ENABLE,
                CdpMethods.RUNTIME_ENABLE,
                CdpMethods.NETWORK_ENABLE
            ),
            transport.sessionCalls.map { it.method }
        )
        assertEquals(listOf("session-1", "session-1", "session-1"), transport.sessionCalls.map { it.sessionId })
    }

    @Test
    fun `page scoped session enables domains without attaching target`() = runBlocking {
        val transport = FakeCdpSessionTransport()

        val session = createCdpPageSession(transport, CdpEndpointScope.Page)

        assertEquals(CdpEndpointScope.Page, session.scope)
        assertNull(session.targetId)
        assertNull(session.sessionId)
        assertEquals(
            listOf(
                CdpMethods.PAGE_ENABLE,
                CdpMethods.RUNTIME_ENABLE,
                CdpMethods.NETWORK_ENABLE
            ),
            transport.sendCalls.map { it.method }
        )
    }

    @Test
    fun `closing browser scoped session closes the target`() = runBlocking {
        val transport = FakeCdpSessionTransport()
        val session = CdpPageSession(
            scope = CdpEndpointScope.Browser,
            targetId = "target-42",
            sessionId = "session-42"
        )

        closeCdpPageSession(transport, session)

        assertEquals(listOf(CdpMethods.TARGET_CLOSE_TARGET), transport.sendCalls.map { it.method })
        assertEquals("target-42", transport.sendCalls.single().params["targetId"])
    }
}

private class FakeCdpSessionTransport(
    val sendResponses: MutableList<CdpResponse> = mutableListOf(),
    val sessionResponses: MutableList<CdpResponse> = mutableListOf()
) : CdpSessionTransport {
    val sendCalls = mutableListOf<FakeSendCall>()
    val sessionCalls = mutableListOf<FakeSessionSendCall>()

    override suspend fun send(method: String, params: Map<String, Any?>): CdpResponse {
        sendCalls += FakeSendCall(method, params)
        return sendResponses.removeFirstOrNull() ?: CdpResponse(id = sendCalls.size)
    }

    override suspend fun sendSession(sessionId: String, method: String, params: Map<String, Any?>): CdpResponse {
        sessionCalls += FakeSessionSendCall(sessionId, method, params)
        return sessionResponses.removeFirstOrNull() ?: CdpResponse(id = sessionCalls.size)
    }
}

private data class FakeSendCall(
    val method: String,
    val params: Map<String, Any?>
)

private data class FakeSessionSendCall(
    val sessionId: String,
    val method: String,
    val params: Map<String, Any?>
)

private fun jsonObject(vararg pairs: Pair<String, JsonPrimitive>): JsonObject =
    JsonObject(mapOf(*pairs))
