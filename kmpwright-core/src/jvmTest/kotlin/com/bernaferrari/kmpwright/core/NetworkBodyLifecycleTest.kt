package com.bernaferrari.kmpwright.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class NetworkBodyLifecycleTest {
    @Test fun `response body waits for the request to finish instead of racing the callback`() = runTest {
        var reads = 0
        val history = PageNetworkHistory({ _, _ -> reads++; NetworkResponseBody("complete") }, {})
        val request = Request("https://example.com/stream")
        val response = Response(request.url, 200, id = request.id)
        history.started(request)
        history.received(response)
        val body = async(start = CoroutineStart.UNDISPATCHED) { runCatching { history.responseBody(response) } }
        assertFalse(body.isCompleted, "A received response may still be downloading")
        assertEquals(0, reads)
        history.finished(request)
        assertEquals("complete", body.await().getOrThrow().content)
        assertEquals(1, reads)
    }

    @Test fun `failure clear eviction and page close complete pending body reads`() = runTest {
        for (ending in listOf("failed", "cleared", "evicted", "closed")) {
            var reads = 0
            val closed = CompletableDeferred<Unit>()
            val history = PageNetworkHistory({ _, _ -> reads++; NetworkResponseBody("unexpected") }, {},
                capacity = 1, timeoutMs = 100, closed = closed)
            val request = Request("https://example.com/$ending")
            val response = Response(request.url, 200, id = request.id)
            history.started(request); history.received(response)
            val body = async(start = CoroutineStart.UNDISPATCHED) { runCatching { history.responseBody(response) } }
            when (ending) {
                "failed" -> history.failed(request, "connection reset")
                "cleared" -> history.clear()
                "evicted" -> history.started(Request("https://example.com/next"))
                "closed" -> closed.complete(Unit)
            }
            runCurrent()
            val failure = body.await().exceptionOrNull()
            assertTrue(failure is KmpwrightException && failure !is KmpwrightTimeoutException, "$ending: $failure")
            assertEquals(0, reads)
        }
    }

    @Test fun `body download and pending completion share one bounded deadline`() = runTest {
        val history = PageNetworkHistory({ _, _ -> awaitCancellation() }, {}, timeoutMs = 100)
        val request = Request("https://example.com/slow")
        val response = Response(request.url, 200, id = request.id)
        history.started(request); history.received(response)
        val body = async(start = CoroutineStart.UNDISPATCHED) { runCatching { history.responseBody(response) } }
        advanceTimeBy(80)
        history.finished(request)
        runCurrent()
        advanceTimeBy(21); runCurrent()
        assertTrue(body.await().exceptionOrNull() is KmpwrightTimeoutException)
    }
}
