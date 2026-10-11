package com.bernaferrari.kmpwright.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

class ResourcePoolContractTest {

    @Test
    fun concurrent_release_runs_cleanup_once() = runBlocking {
        val cleanupStarted = CompletableDeferred<Unit>()
        val allowCleanup = CompletableDeferred<Unit>()
        var cleanupCount = 0
        val pool = ResourcePool(
            listOf(
                PooledResource(
                    id = "shared",
                    connect = { "resource" },
                    cleanup = {
                        cleanupCount += 1
                        cleanupStarted.complete(Unit)
                        allowCleanup.await()
                    }
                )
            )
        )

        val lease = pool.acquire()
        val first = async { lease.release() }
        cleanupStarted.await()
        val second = async { lease.release() }
        allowCleanup.complete(Unit)
        first.await()
        second.await()

        assertEquals(1, cleanupCount)
        assertFalse(pool.snapshots().single().busy)
    }

    @Test
    fun close_defers_active_resource_until_release() = runBlocking {
        var closeCount = 0
        val pool = ResourcePool(
            listOf(
                PooledResource(
                    id = "active",
                    connect = { "resource" },
                    close = { closeCount += 1 }
                )
            )
        )

        val lease = pool.acquire()
        pool.close()
        assertEquals(0, closeCount)
        assertTrue(pool.snapshots().single().busy)
        lease.release()
        assertEquals(1, closeCount)
        assertFalse(pool.snapshots().single().busy)
        assertFalse(pool.snapshots().single().initialized)
    }

    @Test
    fun connect_timeout_quarantines_slot_and_does_not_hang() = runBlocking {
        val pool = ResourcePool(
            listOf(
                PooledResource(
                    id = "slow",
                    connect = {
                        delay(5_000)
                        "resource"
                    }
                )
            )
        )

        try {
            withTimeout(500) {
                pool.acquire(timeoutMs = 25)
                fail("Expected connect timeout")
            }
        } catch (e: KmpwrightException) {
            assertTrue(e.message?.contains("Timed out") == true)
        }
        assertTrue(pool.snapshots().single().broken)
        assertFalse(pool.snapshots().single().busy)
    }
}
