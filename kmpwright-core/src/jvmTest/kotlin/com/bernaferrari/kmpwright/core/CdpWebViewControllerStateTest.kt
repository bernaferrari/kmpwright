package com.bernaferrari.kmpwright.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class CdpWebViewControllerStateTest {
    @Test
    fun `javascript evaluation returns the remote object scalar`() = runBlocking {
        val transport = StateTransport {
            response(
                result = JsonObject(
                    mapOf("result" to JsonObject(mapOf("value" to JsonPrimitive("ready"))))
                )
            )
        }
        val controller = CdpWebViewController(transport)
        try {
            assertEquals("ready", controller.evaluateJavaScriptAsync("'ready'"))
        } finally {
            controller.dispose()
        }
    }

    @Test
    fun `javascript exception details propagate from runtime evaluate`() = runBlocking {
        val transport = StateTransport {
            response(
                result = JsonObject(
                    mapOf(
                        "result" to JsonObject(mapOf("type" to JsonPrimitive("undefined"))),
                        "exceptionDetails" to JsonObject(mapOf("text" to JsonPrimitive("boom")))
                    )
                )
            )
        }
        val controller = CdpWebViewController(transport)
        try {
            try {
                controller.evaluateJavaScriptAsync("throw new Error('boom')")
                fail("Expected JavaScript evaluation to fail")
            } catch (e: KmpwrightException) {
                assertEquals(true, e.message?.contains("boom"))
            }
        } finally {
            controller.dispose()
        }
    }

    @Test
    fun `header and credential mutations propagate CDP errors`() = runBlocking {
        val transport = StateTransport { method ->
            when (method) {
                CdpMethods.NETWORK_ENABLE -> response()
                CdpMethods.NETWORK_SET_EXTRA_HTTP_HEADERS ->
                    response(error = CdpError(-32000, "headers unavailable"))
                else -> response()
            }
        }
        val controller = CdpWebViewController(transport)
        try {
            assertFails { controller.setExtraHttpHeadersAsync(mapOf("X-Test" to "1")) }
            assertFails { controller.setHttpCredentialsAsync("user", "pass") }
        } finally {
            controller.dispose()
        }
    }

    private fun assertFails(block: suspend () -> Unit) {
        try {
            runBlocking { block() }
            fail("Expected CDP mutation to fail")
        } catch (_: KmpwrightException) {
            // expected
        }
    }

    private class StateTransport(
        private val responder: (String) -> CdpResponse
    ) : CdpEventTransport {
        override suspend fun send(method: String, params: Map<String, Any?>): CdpResponse = responder(method)

        override suspend fun sendSession(
            sessionId: String,
            method: String,
            params: Map<String, Any?>
        ): CdpResponse = responder(method)

        override fun onEvent(
            method: String,
            sessionId: String?,
            listener: (JsonObject) -> Unit
        ): CdpEventSubscription = CdpEventSubscription {}
    }

    private fun response(
        result: JsonObject? = null,
        error: CdpError? = null
    ) = CdpResponse(id = 1, result = result, error = error)
}
