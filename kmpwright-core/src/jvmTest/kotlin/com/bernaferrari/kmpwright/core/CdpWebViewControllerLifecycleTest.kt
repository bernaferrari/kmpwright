package com.bernaferrari.kmpwright.core

import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class CdpWebViewControllerLifecycleTest {

    @Test
    fun `dispose cancels every subscription once even when one cancellation throws`() {
        val transport = TrackingEventTransport()
        val controller = CdpWebViewController(transport)

        controller.addRequestListener(NoOpRequestListener)
        controller.addRouteHandler(".*") { it.fallback() }

        val installed = transport.subscriptionCount
        check(installed > 0)
        transport.throwOnCancelIndex = 0

        controller.dispose()
        controller.dispose()

        assertEquals(installed, transport.cancelCount)
    }

    private object NoOpRequestListener : RequestListener {
        override fun onRequestStarted(request: Request) = Unit
        override fun onResponseReceived(response: Response) = Unit
        override fun onRequestFinished(request: Request) = Unit
        override fun onRequestFailed(request: Request, error: String) = Unit
    }

    private class TrackingEventTransport : CdpEventTransport {
        var subscriptionCount = 0
        var cancelCount = 0
        var throwOnCancelIndex: Int? = null

        override suspend fun send(method: String, params: Map<String, Any?>): CdpResponse =
            CdpResponse(id = 1)

        override suspend fun sendSession(
            sessionId: String,
            method: String,
            params: Map<String, Any?>
        ): CdpResponse = CdpResponse(id = 1)

        override fun onEvent(
            method: String,
            sessionId: String?,
            listener: (JsonObject) -> Unit
        ): CdpEventSubscription {
            val index = subscriptionCount++
            return CdpEventSubscription {
                cancelCount += 1
                if (throwOnCancelIndex == index) {
                    throw IllegalStateException("synthetic cancellation failure")
                }
            }
        }
    }
}
