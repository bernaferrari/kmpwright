package com.bernaferrari.kmpwright.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assert.assertEquals
import org.junit.Test

class ResourcePoolTest {

    @Test
    fun `concurrent lease release runs cleanup only once`() = runBlocking {
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
        val first = async(kotlinx.coroutines.SupervisorJob()) { lease.release() }
        cleanupStarted.await()
        val second = async(kotlinx.coroutines.SupervisorJob()) { lease.release() }
        allowCleanup.complete(Unit)
        first.await()
        second.await()

        assertEquals(1, cleanupCount)
        assertTrue(!pool.snapshots().single().busy)
    }

    @Test
    fun `close drains idle resources but active lease closes on release`() = runBlocking {
        var cleanupCount = 0
        var closeCount = 0
        val pool = ResourcePool(
            listOf(
                PooledResource(
                    id = "active",
                    connect = { "resource" },
                    cleanup = { cleanupCount += 1 },
                    close = { closeCount += 1 }
                )
            )
        )

        val lease = pool.acquire()
        pool.close()
        assertEquals(0, closeCount)
        assertTrue(pool.snapshots().single().busy)
        assertTrue(pool.snapshots().single().initialized)

        lease.release()
        pool.close()
        assertEquals(1, cleanupCount)
        assertEquals(1, closeCount)
        assertTrue(!pool.snapshots().single().busy)
        assertTrue(!pool.snapshots().single().initialized)
    }

    @Test
    fun `close waits for cleanup and closes active resource once`() = runBlocking {
        val cleanupStarted = CompletableDeferred<Unit>()
        val allowCleanup = CompletableDeferred<Unit>()
        var closeCount = 0
        val pool = ResourcePool(
            listOf(
                PooledResource(
                    id = "draining",
                    connect = { "resource" },
                    cleanup = {
                        cleanupStarted.complete(Unit)
                        allowCleanup.await()
                    },
                    close = { closeCount += 1 }
                )
            )
        )

        val lease = pool.acquire()
        val release = async(kotlinx.coroutines.SupervisorJob()) { lease.release() }
        cleanupStarted.await()
        pool.close()
        assertEquals(0, closeCount)
        allowCleanup.complete(Unit)
        release.await()
        assertEquals(1, closeCount)
    }

    @Test
    fun `close attempts every idle resource when one close fails`() = runBlocking {
        val attempts = mutableListOf<String>()
        val pool = ResourcePool(
            listOf(
                PooledResource(
                    id = "first",
                    connect = { "first-resource" },
                    close = {
                        attempts += it
                        throw IllegalStateException("first close failed")
                    }
                ),
                PooledResource(
                    id = "second",
                    connect = { "second-resource" },
                    close = { attempts += it }
                )
            )
        )
        pool.acquire { it.id == "first" }.release()
        pool.acquire { it.id == "second" }.release()

        try {
            pool.close()
            fail("Expected close to report the first close failure")
        } catch (e: IllegalStateException) {
            assertEquals("first close failed", e.message)
        }
        assertEquals(listOf("first-resource", "second-resource"), attempts)
    }

    @Test
    fun `connect timeout quarantines slot without hanging or leaking`() = runBlocking {
        var closeCount = 0
        val pool = ResourcePool(
            listOf(
                PooledResource(
                    id = "slow-connect",
                    connect = {
                        kotlinx.coroutines.delay(5_000)
                        "resource"
                    },
                    close = { closeCount += 1 }
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
        assertEquals(0, closeCount)
        assertTrue(pool.snapshots().single().broken)
        assertTrue(!pool.snapshots().single().busy)
    }

    @Test
    fun `prepare timeout closes and quarantines initialized slot`() = runBlocking {
        var closeCount = 0
        val pool = ResourcePool(
            listOf(
                PooledResource(
                    id = "slow-prepare",
                    connect = { "resource" },
                    prepare = { kotlinx.coroutines.delay(5_000) },
                    close = { closeCount += 1 }
                )
            )
        )

        try {
            withTimeout(500) {
                pool.acquire(timeoutMs = 25)
                fail("Expected prepare timeout")
            }
        } catch (e: KmpwrightException) {
            assertTrue(e.message?.contains("Timed out") == true)
        }
        assertEquals(1, closeCount)
        assertTrue(pool.snapshots().single().broken)
        assertTrue(!pool.snapshots().single().initialized)
    }

    @Test
    fun `cancellation during prepare closes and releases slot`() = runBlocking {
        val prepareStarted = CompletableDeferred<Unit>()
        val allowPrepare = CompletableDeferred<Unit>()
        var closeCount = 0
        val pool = ResourcePool(
            listOf(
                PooledResource(
                    id = "cancelled",
                    connect = { "resource" },
                    prepare = {
                        prepareStarted.complete(Unit)
                        allowPrepare.await()
                    },
                    close = { closeCount += 1 }
                )
            )
        )

        val acquisition = async(kotlinx.coroutines.SupervisorJob()) {
            pool.acquire(timeoutMs = 5_000)
        }
        prepareStarted.await()
        acquisition.cancel()
        try {
            acquisition.await()
            fail("Expected cancellation")
        } catch (_: CancellationException) {
            // Expected: cancellation must not strand a busy slot.
        }
        assertEquals(1, closeCount)
        assertTrue(!pool.snapshots().single().busy)
        assertTrue(!pool.snapshots().single().initialized)
    }

    @Test
    fun `close during connect closes the late resource and never publishes a lease`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val proceed = CompletableDeferred<Unit>()
        var closeCount = 0
        val pool = ResourcePool(
            listOf(
                PooledResource(
                    id = "slow",
                    connect = {
                        entered.complete(Unit)
                        proceed.await()
                        "late-resource"
                    },
                    close = { closeCount += 1 }
                )
            )
        )

        // Isolate the expected acquisition failure from this runBlocking
        // scope so it can be asserted without cancelling the test body.
        val acquisition = async(kotlinx.coroutines.SupervisorJob()) { pool.acquire(timeoutMs = 5_000) }
        entered.await()
        pool.close()
        proceed.complete(Unit)

        try {
            acquisition.await()
            fail("Expected acquire to fail after pool close")
        } catch (e: KmpwrightException) {
            assertEquals("Resource pool is closed.", e.message)
        }
        assertEquals(1, closeCount)
        val snapshot = pool.snapshots().single()
        assertTrue(!snapshot.busy)
        assertTrue(!snapshot.initialized)
    }

    @Test
    fun `close during prepare rejects the lease without double closing`() = runBlocking {
        val prepared = CompletableDeferred<Unit>()
        val proceed = CompletableDeferred<Unit>()
        var closeCount = 0
        val pool = ResourcePool(
            listOf(
                PooledResource(
                    id = "slow-prepare",
                    connect = { "prepared-resource" },
                    prepare = {
                        prepared.complete(Unit)
                        proceed.await()
                    },
                    close = { closeCount += 1 }
                )
            )
        )

        val acquisition = async(kotlinx.coroutines.SupervisorJob()) { pool.acquire(timeoutMs = 5_000) }
        prepared.await()
        pool.close()
        proceed.complete(Unit)

        try {
            acquisition.await()
            fail("Expected acquire to fail after pool close during prepare")
        } catch (e: KmpwrightException) {
            // close() owns and closes the detached resource; acquire must not
            // publish another lease or close that resource a second time.
            assertTrue(e.message?.contains("closed") == true)
        }
        assertEquals(1, closeCount)
        val snapshot = pool.snapshots().single()
        assertTrue(!snapshot.busy)
        assertTrue(!snapshot.initialized)
    }

    @Test
    fun `lease release runs cleanup and keeps slot reusable`() = runBlocking {
        val events = mutableListOf<String>()
        val pool = ResourcePool(
            listOf(
                PooledResource(
                    id = "device-1",
                    metadata = mapOf("platform" to "ios"),
                    connect = {
                        events += "connect"
                        "resource-1"
                    },
                    prepare = { events += "prepare:$it" },
                    cleanup = { events += "cleanup:$it" }
                )
            )
        )

        val lease1 = pool.acquire()
        assertEquals("device-1", lease1.id)
        assertEquals("resource-1", lease1.resource)
        lease1.release()

        val lease2 = pool.acquire()
        assertEquals("resource-1", lease2.resource)
        lease2.release()

        assertEquals(
            listOf(
                "connect",
                "prepare:resource-1",
                "cleanup:resource-1",
                "prepare:resource-1",
                "cleanup:resource-1"
            ),
            events
        )
    }

    @Test
    fun `pool predicate can target a specific worker`() = runBlocking {
        val pool = ResourcePool(
            listOf(
                PooledResource(id = "android-1", metadata = mapOf("platform" to "android"), connect = { "a" }),
                PooledResource(id = "ios-1", metadata = mapOf("platform" to "ios"), connect = { "b" })
            )
        )

        val lease = pool.acquire { it.metadata["platform"] == "ios" }
        assertEquals("ios-1", lease.id)
        assertEquals("b", lease.resource)
        lease.release()
    }

    @Test
    fun `cleanup failure breaks slot and next acquire skips it`() = runBlocking {
        val pool = ResourcePool(
            listOf(
                PooledResource(
                    id = "bad",
                    connect = { "bad-resource" },
                    cleanup = { throw IllegalStateException("cleanup failed") }
                ),
                PooledResource(
                    id = "good",
                    connect = { "good-resource" }
                )
            )
        )

        val firstLease = pool.acquire { it.id == "bad" }
        try {
            firstLease.release()
        } catch (_: IllegalStateException) {
        }

        val nextLease = pool.acquire()
        assertEquals("good", nextLease.id)
        assertEquals("good-resource", nextLease.resource)
        nextLease.release()

        val brokenSnapshot = pool.snapshots().first { it.id == "bad" }
        assertTrue(brokenSnapshot.broken)
        assertEquals("cleanup failed", brokenSnapshot.lastError)
    }

    @Test
    fun `markBroken quarantines a worker`() = runBlocking {
        val closed = mutableListOf<String>()
        val pool = ResourcePool(
            listOf(
                PooledResource(
                    id = "device-1",
                    connect = { "resource-1" },
                    close = { closed += it }
                )
            )
        )

        val lease = pool.acquire()
        lease.markBroken("health check failed")

        val snapshot = pool.snapshots().single()
        assertTrue(snapshot.broken)
        assertEquals("health check failed", snapshot.lastError)
        assertEquals(listOf("resource-1"), closed)
    }

    @Test
    fun `withLease marks worker broken when block throws by default`() = runBlocking {
        val pool = ResourcePool(
            listOf(
                PooledResource(id = "device-1", connect = { "resource-1" })
            )
        )

        try {
            pool.withLease {
                throw IllegalStateException("boom")
            }
            fail("Expected withLease to rethrow the block failure")
        } catch (e: IllegalStateException) {
            assertEquals("boom", e.message)
        }

        val snapshot = pool.snapshots().single()
        assertTrue(snapshot.broken)
        assertEquals("boom", snapshot.lastError)
    }

    @Test
    fun `withLease can preserve worker after block failure when requested`() = runBlocking {
        val pool = ResourcePool(
            listOf(
                PooledResource(id = "device-1", connect = { "resource-1" })
            )
        )

        try {
            pool.withLease(markBrokenOnException = false) {
                throw IllegalStateException("boom")
            }
            fail("Expected withLease to rethrow the block failure")
        } catch (e: IllegalStateException) {
            assertEquals("boom", e.message)
        }

        val snapshot = pool.snapshots().single()
        assertTrue(!snapshot.broken)
        assertTrue(!snapshot.busy)

        val lease = pool.acquire()
        assertEquals("resource-1", lease.resource)
        lease.release()
    }
}
