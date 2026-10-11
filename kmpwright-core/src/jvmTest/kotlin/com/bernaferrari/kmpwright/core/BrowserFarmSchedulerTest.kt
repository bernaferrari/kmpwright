package com.bernaferrari.kmpwright.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BrowserFarmSchedulerTest {

    @Test
    fun `selector and release expose reset and metrics`() = runBlocking {
        var resetCount = 0
        var closeCount = 0
        val scheduler = ResourceScheduler(
            listOf(
                PooledResource(
                    id = "cheap",
                    metadata = mapOf("platform" to "linux", "costUsdPerHour" to "0.02"),
                    connect = { "cheap-browser" },
                    cleanup = { resetCount++ },
                    close = { closeCount++ }
                ),
                PooledResource(
                    id = "expensive",
                    metadata = mapOf("platform" to "linux", "costUsdPerHour" to "0.40"),
                    connect = { "expensive-browser" },
                    close = { closeCount++ }
                )
            )
        )

        try {
            val lease = scheduler.acquire(
                selector = ResourceSelector(
                    requiredMetadata = mapOf("platform" to "linux"),
                    maxCostUsdPerHour = 0.05
                )
            )
            assertEquals("cheap", lease.id)
            assertEquals("cheap-browser", lease.resource)
            lease.release()

            val metrics = scheduler.metrics()
            assertEquals(2, metrics.capacity)
            assertEquals(2, metrics.available)
            assertEquals(1L, metrics.acquisitions)
            assertEquals(1L, metrics.releases)
            assertEquals(0L, metrics.acquisitionFailures)
            assertEquals(1, resetCount)
        } finally {
            scheduler.close()
        }
        assertEquals(1, closeCount)
    }

    @Test
    fun `health failure emits events and quarantines slot`() = runBlocking {
        val scheduler = ResourceScheduler(
            listOf(
                PooledResource(
                    id = "worker",
                    connect = { "browser" },
                    close = {}
                )
            )
        )

        try {
            assertFalse(scheduler.healthCheck("worker") { false })

            val events = scheduler.events.replayCache
            assertTrue(events.any { it is ResourceSchedulerEvent.HealthCheckFailed })
            assertTrue(events.any { it is ResourceSchedulerEvent.Quarantined })
            assertTrue(scheduler.snapshots().single().broken)
            assertEquals(1, scheduler.metrics().quarantined)
            assertEquals(1L, scheduler.metrics().acquisitions)
            assertTrue(scheduler.metrics().totalWaitMs >= 0)
        } finally {
            scheduler.close()
        }
    }

    @Test
    fun `idle quarantine closes initialized slot exactly once`() = runBlocking {
        var closeCount = 0
        val scheduler = ResourceScheduler(
            listOf(
                PooledResource(
                    id = "retiring",
                    connect = { "browser" },
                    close = { closeCount++ }
                )
            )
        )

        try {
            scheduler.acquire().release()
            assertTrue(scheduler.quarantine("retiring", "provider retired"))
            assertFalse(scheduler.quarantine("retiring", "duplicate signal"))
            assertEquals(1, closeCount)
            assertTrue(scheduler.snapshots().single().broken)
            assertTrue(scheduler.events.replayCache.any {
                it == ResourceSchedulerEvent.Quarantined("retiring", "provider retired")
            })
        } finally {
            scheduler.close()
        }
        assertEquals(1, closeCount)
    }

    @Test
    fun `custom capability predicate selects matching slot`() = runBlocking {
        val scheduler = ResourceScheduler(
            listOf(
                PooledResource(
                    id = "us",
                    metadata = mapOf("region" to "us"),
                    connect = { "us" }
                ),
                PooledResource(
                    id = "eu",
                    metadata = mapOf("region" to "eu"),
                    connect = { "eu" }
                )
            )
        )

        try {
            val lease = scheduler.acquire(
                selector = ResourceSelector(predicate = { it.metadata["region"] == "eu" })
            )
            assertEquals("eu", lease.id)
            lease.release()
        } finally {
            scheduler.close()
        }
    }

    @Test
    fun `cost selector rejects negative and non-finite metadata`() = runBlocking {
        val scheduler = ResourceScheduler(
            listOf(
                PooledResource(
                    id = "nan",
                    metadata = mapOf("costUsdPerHour" to "NaN"),
                    connect = { "nan" }
                ),
                PooledResource(
                    id = "negative",
                    metadata = mapOf("costUsdPerHour" to "-1"),
                    connect = { "negative" }
                )
            )
        )

        try {
            try {
                scheduler.acquire(
                    timeoutMs = 25,
                    selector = ResourceSelector(maxCostUsdPerHour = 0.05)
                )
                fail("Expected invalid costs to be rejected")
            } catch (e: KmpwrightException) {
                assertTrue(e.message?.contains("Timed out") == true)
            }
        } finally {
            scheduler.close()
        }
    }
}
