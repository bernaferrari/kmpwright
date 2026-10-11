package com.bernaferrari.kmpwright.core

import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

typealias BrowserFarm = ResourcePool<Browser>
typealias BrowserFarmSlot = PooledResource<Browser>
typealias BrowserLease = ResourceLease<Browser>

data class PooledResource<T>(
    val id: String,
    val metadata: Map<String, String> = emptyMap(),
    val connect: suspend () -> T,
    val prepare: suspend (T) -> Unit = {},
    val cleanup: suspend (T) -> Unit = {},
    val close: suspend (T) -> Unit = {}
)

data class ResourcePoolSnapshot(
    val id: String,
    val metadata: Map<String, String>,
    val busy: Boolean,
    val initialized: Boolean,
    val broken: Boolean,
    val lastError: String? = null
)

class ResourceLease<T> internal constructor(
    val id: String,
    val metadata: Map<String, String>,
    val resource: T,
    private val releaseAction: suspend (Boolean, String?) -> Unit
) {
    private val released = atomic(false)
    val isReleased: Boolean
        get() = released.value

    suspend fun release() {
        if (!released.compareAndSet(expect = false, update = true)) return
        releaseAction(false, null)
    }

    suspend fun markBroken(reason: String? = null) {
        if (!released.compareAndSet(expect = false, update = true)) return
        releaseAction(true, reason)
    }
}

class ResourcePool<T>(
    resources: List<PooledResource<T>>
) {
    private val mutex = Mutex()
    private val slots = resources.map { ResourceSlot(it) }
    private var closed = false

    suspend fun acquire(
        timeoutMs: Long = 30_000,
        predicate: (ResourcePoolSnapshot) -> Boolean = { !it.broken }
    ): ResourceLease<T> {
        val deadline = currentTimeMillis() + timeoutMs
        while (currentTimeMillis() <= deadline) {
            val slot = reserveMatchingSlot(predicate)
            if (slot == null) {
                delay(minOf(25L, (deadline - currentTimeMillis()).coerceAtLeast(1L)))
                continue
            }

            val connectResult = try {
                withTimeoutOrNull((deadline - currentTimeMillis()).coerceAtLeast(1L)) {
                    ConnectedResource(ensureConnected(slot))
                }
            } catch (e: CancellationException) {
                abortSlot(slot, e.message ?: "Pooled resource acquisition was cancelled")
                throw e
            } catch (e: Exception) {
                abortSlot(slot, e.message ?: "Failed to connect pooled resource")
                continue
            }
            if (connectResult == null) {
                abortSlot(slot, "Timed out connecting pooled resource")
                continue
            }
            val resource = connectResult.resource

            val prepared = try {
                withTimeoutOrNull((deadline - currentTimeMillis()).coerceAtLeast(1L)) {
                    slot.definition.prepare(resource)
                    true
                }
            } catch (e: CancellationException) {
                abortSlot(slot, e.message ?: "Pooled resource preparation was cancelled")
                throw e
            } catch (e: Exception) {
                abortSlot(slot, e.message ?: "Failed to prepare pooled resource")
                continue
            }
            if (prepared != true) {
                abortSlot(slot, "Timed out preparing pooled resource")
                continue
            }

            // A close may have happened while prepare was suspended. Do not
            // hand out a lease for a resource that close() already detached.
            val prepareResult = try {
                mutex.withLock {
                    if (closed || !slot.busy || slot.resource !== resource) {
                        // close() may already have detached the resource.
                        // Reject either way, but only close it here while this
                        // coroutine still owns the slot reference.
                        val owned = slot.resource === resource
                        if (owned) slot.resource = null
                        slot.busy = false
                        slot.closeWhenReleased = false
                        slot.publishSnapshot()
                        true to owned
                    } else {
                        false to false
                    }
                }
            } catch (e: CancellationException) {
                abortSlot(slot, e.message ?: "Pooled resource acquisition was cancelled")
                throw e
            }
            if (prepareResult.first) {
                if (prepareResult.second) {
                    closeSafely(slot.definition.close, resource)
                }
                throw KmpwrightException("Resource pool was closed while preparing pooled resource.")
            }

            return ResourceLease(
                id = slot.definition.id,
                metadata = slot.definition.metadata,
                resource = resource
            ) { broken, reason ->
                releaseSlot(slot, broken, reason)
            }
        }

        throw KmpwrightException("Timed out waiting for an available pooled resource.")
    }

    suspend fun <R> withLease(
        timeoutMs: Long = 30_000,
        predicate: (ResourcePoolSnapshot) -> Boolean = { !it.broken },
        markBrokenOnException: Boolean = true,
        block: suspend (ResourceLease<T>) -> R
    ): R {
        val lease = acquire(timeoutMs = timeoutMs, predicate = predicate)
        return try {
            val result = block(lease)
            lease.release()
            result
        } catch (t: Throwable) {
            if (markBrokenOnException) {
                lease.markBroken(t.message)
            } else {
                lease.release()
            }
            throw t
        }
    }

    fun snapshots(): List<ResourcePoolSnapshot> {
        return slots.map { it.snapshot() }
    }

    /**
     * Quarantines an idle slot so future acquisitions skip it.
     *
     * A leased slot must be quarantined through [ResourceLease.markBroken],
     * which keeps ownership of the resource with the lease until its caller
     * has finished using it.
     */
    suspend fun quarantine(id: String, reason: String? = null): Boolean {
        var resourceToClose: T? = null
        var closeAction: (suspend (T) -> Unit)? = null
        val quarantined = mutex.withLock {
            if (closed) return@withLock false
            val slot = slots.firstOrNull { it.definition.id == id } ?: return@withLock false
            if (slot.busy || slot.broken) return@withLock false

            slot.broken = true
            slot.lastError = reason
            resourceToClose = slot.resource
            closeAction = slot.definition.close
            slot.resource = null
            slot.closeWhenReleased = false
            slot.publishSnapshot()
            true
        }
        resourceToClose?.let { resource -> closeAction?.let { closeSafely(it, resource) } }
        return quarantined
    }

    /**
     * Stops new acquisitions, closes idle resources immediately, and leaves
     * busy resources owned by their leases. Busy resources close exactly once
     * when the lease is released or marked broken.
     */
    suspend fun close() {
        val toClose = withContext(NonCancellable) {
            mutex.withLock {
                if (closed) return@withLock null
                closed = true
                slots.mapNotNull { slot ->
                    if (slot.busy) {
                        slot.closeWhenReleased = true
                        slot.publishSnapshot()
                        return@mapNotNull null
                    }
                    val resource = slot.resource
                    slot.resource = null
                    slot.busy = false
                    slot.closeWhenReleased = false
                    slot.publishSnapshot()
                    resource?.let { slot.definition.close to it }
                }
            }
        } ?: return
        closeAll(toClose)
    }

    private suspend fun reserveMatchingSlot(
        predicate: (ResourcePoolSnapshot) -> Boolean
    ): ResourceSlot<T>? {
        return mutex.withLock {
            if (closed) {
                throw KmpwrightException("Resource pool is closed.")
            }
            val slot = slots.firstOrNull { candidate ->
                !candidate.busy &&
                    !candidate.broken &&
                    predicate(candidate.currentSnapshot())
            }
            slot?.let {
                it.busy = true
                it.publishSnapshot()
            }
            slot
        }
    }

    private suspend fun ensureConnected(slot: ResourceSlot<T>): T {
        val existing = mutex.withLock {
            if (closed) throw KmpwrightException("Resource pool is closed.")
            slot.resource
        }
        existing?.let { return it }

        val resource = slot.definition.connect()
        val publish = try {
            mutex.withLock {
                // A connecting slot has no lease yet. If close() won the race,
                // the newly-created resource must be closed by this path.
                if (closed || !slot.busy) {
                    false
                } else {
                    slot.resource = resource
                    slot.lastError = null
                    slot.publishSnapshot()
                    true
                }
            }
        } catch (t: Throwable) {
            closeSafely(slot.definition.close, resource)
            throw t
        }
        if (!publish) {
            closeSafely(slot.definition.close, resource)
            throw KmpwrightException("Resource pool was closed while connecting pooled resource.")
        }
        return resource
    }

    private suspend fun releaseSlot(slot: ResourceSlot<T>, broken: Boolean, reason: String?) =
        withContext(NonCancellable) {
            val resource = mutex.withLock { slot.resource }
            if (resource == null) {
                mutex.withLock {
                    slot.busy = false
                    slot.closeWhenReleased = false
                    if (broken) {
                        slot.broken = true
                        slot.lastError = reason
                    }
                    slot.publishSnapshot()
                }
                return@withContext
            }

            if (broken) {
                abortSlot(slot, reason ?: "Marked broken by lease")
                return@withContext
            }

            var cleanupFailure: Throwable? = null
            try {
                slot.definition.cleanup(resource)
            } catch (t: Throwable) {
                cleanupFailure = t
            }
            cleanupFailure?.let { failure ->
                abortSlot(slot, failure.message ?: "Failed to clean pooled resource")
                throw failure
            }

            val closeOnRelease = mutex.withLock {
                val close = closed || slot.closeWhenReleased
                slot.busy = false
                slot.closeWhenReleased = false
                if (close) {
                    slot.resource = null
                } else {
                    slot.lastError = null
                }
                slot.publishSnapshot()
                close
            }
            if (closeOnRelease) {
                closeSafely(slot.definition.close, resource)?.let { throw it }
            }
        }

    private suspend fun abortSlot(slot: ResourceSlot<T>, reason: String) =
        withContext(NonCancellable) {
            val resource = mutex.withLock {
                slot.busy = false
                slot.broken = true
                slot.lastError = reason
                slot.closeWhenReleased = false
                slot.resource.also { slot.resource = null }
                    .also { slot.publishSnapshot() }
            }
            resource?.let { closeSafely(slot.definition.close, it) }
        }

    private suspend fun closeAll(resources: List<Pair<suspend (T) -> Unit, T>>) {
        var firstFailure: Throwable? = null
        resources.forEach { (close, resource) ->
            closeSafely(close, resource)?.let { failure ->
                if (firstFailure == null) firstFailure = failure
            }
        }
        firstFailure?.let { throw it }
    }

    private suspend fun closeSafely(close: suspend (T) -> Unit, resource: T): Throwable? {
        return try {
            withContext(NonCancellable) { close(resource) }
            null
        } catch (t: Throwable) {
            t
        }
    }

    private class ConnectedResource<T>(val resource: T)

    private class ResourceSlot<T>(
        val definition: PooledResource<T>,
        var resource: T? = null,
        var busy: Boolean = false,
        var broken: Boolean = false,
        var lastError: String? = null,
        var closeWhenReleased: Boolean = false
    ) {
        private val published = atomic(
            ResourcePoolSnapshot(
                id = definition.id,
                metadata = definition.metadata,
                busy = false,
                initialized = false,
                broken = false,
                lastError = null
            )
        )

        fun publishSnapshot() {
            published.value = currentSnapshot()
        }

        fun currentSnapshot(): ResourcePoolSnapshot = ResourcePoolSnapshot(
            id = definition.id,
            metadata = definition.metadata,
            busy = busy,
            initialized = resource != null,
            broken = broken,
            lastError = lastError
        )

        fun snapshot(): ResourcePoolSnapshot = published.value
    }
}
