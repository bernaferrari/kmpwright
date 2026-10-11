package com.bernaferrari.kmpwright.core

import kotlinx.coroutines.runBlocking
import kotlin.test.*

class NetworkHistoryContractTest {
    private fun history(capacity: Int = 500, byteBudget: Int = 4 * 1024 * 1024) =
        PageNetworkHistory({ id, bytes -> NetworkResponseBody("$id:$bytes") }, {}, capacity, byteBudget)

    @Test fun redirects_have_stable_ids_and_only_latest_hop_has_a_body() = runBlocking {
        val history = history()
        val first = Request("https://example.com/start", id = "shared")
        history.started(first)
        history.received(Response(first.url, 302, id = first.id))
        history.finished(first)
        val oldId = history.requests().single().id
        val last = first.copy(url = "https://example.com/end")
        history.started(last)
        history.received(Response(last.url, 200, id = last.id))
        history.finished(last)
        val newest = history.requests().first()
        assertNotEquals(oldId, newest.id)
        assertEquals(302, history.detail(oldId)?.response?.status)
        assertFailsWith<KmpwrightException> { history.responseBody(oldId) }
        assertEquals("shared:32", history.responseBody(newest.id, 32).content)
        history.clear()
        history.started(Request("https://example.com/next", id = "new"))
        assertTrue(history.requests().single().id > newest.id)
    }

    @Test fun failure_is_not_overwritten_by_a_finished_callback() {
        val history = history()
        val request = Request("https://example.com/failure")
        history.started(request); history.failed(request, "connection reset"); history.finished(request)
        val record = history.requests().single()
        assertEquals(NetworkRequestState.Failed, record.state)
        assertEquals("connection reset", record.error)
        assertNotNull(record.finishedAt)
    }

    @Test fun history_evicts_oldest_records_and_ignores_late_responses() {
        val history = history(capacity = 2)
        repeat(3) { history.started(Request("https://example.com/$it", id = "$it")) }
        history.received(Response("https://example.com/0", 200, id = "0"))
        assertEquals(listOf("2", "1"), history.requests().map { it.request.id })
        assertEquals("1", history.requests("/1").single().request.id)
        assertTrue(history.requests().all { it.response == null })
    }

    @Test fun oversized_metadata_cannot_escape_retention_budget() {
        val history = history(byteBudget = 200_000)
        repeat(100) { history.started(Request("https://example.com/" + "x".repeat(10_000),
            postData = "payload".repeat(100_000), headers = mapOf("secret" to "v".repeat(100_000)))) }
        assertTrue(history.requests().size < 3)
        assertTrue(history.requests().all { it.request.postData!!.length <= 65536 })
    }
}
