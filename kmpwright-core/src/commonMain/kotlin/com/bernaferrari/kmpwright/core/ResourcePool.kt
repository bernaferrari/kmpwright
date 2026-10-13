package com.bernaferrari.kmpwright.core

import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** A pool of browsers. */
public typealias BrowserFarm = ResourcePool<Browser>
/** A pooled browser. */
public typealias BrowserFarmSlot = PooledResource<Browser>
/** A leased browser. */
public typealias BrowserLease = ResourceLease<Browser>

/** A resource with its connect, prepare and close hooks. */
public data class PooledResource<T>(
    val id: String,
    val metadata: Map<String, String> = emptyMap(),
    val connect: suspend () -> T,
    val prepare: suspend (T) -> Unit = {},
    val cleanup: suspend (T) -> Unit = {},
    val close: suspend (T) -> Unit = {}
)

/** The state of one pooled resource. */
public data class ResourcePoolSnapshot(
    val id: String,
    val metadata: Map<String, String>,
    val busy: Boolean,
    val initialized: Boolean,
    val broken: Boolean,
    val lastError: String? = null
)

/** Exclusive use of a pooled resource until [release]. */
public class ResourceLease<T> internal constructor(
    public val id: String,
    public val metadata: Map<String, String>,
    public val resource: T,
    private val releaseAction: suspend (Boolean, String?) -> Unit
) {
    private val released = atomic(false)
    /** Whether the lease was returned. */
    public val isReleased: Boolean
        get() = released.value

    /** Returns the resource to the pool. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun release() {
        if (!released.compareAndSet(expect = false, update = true)) return
        releaseAction(false, null)
    }

    /** Removes the resource from rotation. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun markBroken(reason: String? = null) {
        if (!released.compareAndSet(expect = false, update = true)) return
        releaseAction(true, reason)
    }
}

/**
 * A fixed set of lazily connected resources handed out as exclusive leases.
 *
 * A slot whose connect/prepare/cleanup fails is quarantined with [ResourcePoolSnapshot.lastError] and
 * becomes eligible again after [transientFailureCooldownMs] (a fresh connection is made). Slots
 * quarantined explicitly ([ResourceLease.markBroken], [quarantine]) stay out until [restore].
 */
public class ResourcePool<T>(
    resources: List<PooledResource<T>>,
    private val transientFailureCooldownMs: Long = 30_000
) {
    private val mutex = Mutex()
    private val slots = resources.map { ResourceSlot(it) }
    private var closed = false

    /** Leases a resource, waiting up to [timeoutMs]. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun acquire(
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
                abortSlot(slot, e.message ?: "Pooled resource acquisition was cancelled", quarantine = false)
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
                abortSlot(slot, e.message ?: "Pooled resource preparation was cancelled", quarantine = false)
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
                abortSlot(slot, e.message ?: "Pooled resource acquisition was cancelled", quarantine = false)
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

    /**
     * Runs [block] with a lease. A failure marks the slot broken when [markBrokenOnException] is set;
     * cancellation of the caller is not a resource failure, so the lease is released and the
     * cancellation rethrown.
     */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun <R> withLease(
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
        } catch (cancelled: CancellationException) {
            lease.release()
            throw cancelled
        } catch (t: Throwable) {
            if (markBrokenOnException) {
                lease.markBroken(t.message)
            } else {
                lease.release()
            }
            throw t
        }
    }

    /**
     * Returns a quarantined idle slot to service; it reconnects on its next lease.
     * @return false when the slot is unknown, busy, or not quarantined.
     */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun restore(id: String): Boolean = mutex.withLock {
        if (closed) return@withLock false
        val slot = slots.firstOrNull { it.definition.id == id } ?: return@withLock false
        if (slot.busy || !slot.broken) return@withLock false
        slot.broken = false
        slot.brokenUntil = null
        slot.publishSnapshot()
        true
    }

    /** State of every pooled resource. */
    public fun snapshots(): List<ResourcePoolSnapshot> {
        return slots.map { it.snapshot() }
    }

    /**
     * Quarantines an idle slot so future acquisitions skip it.
     *
     * A leased slot must be quarantined through [ResourceLease.markBroken],
     * which keeps ownership of the resource with the lease until its caller
     * has finished using it.
     */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun quarantine(id: String, reason: String? = null): Boolean {
        var resourceToClose: T? = null
        var closeAction: (suspend (T) -> Unit)? = null
        val quarantined = mutex.withLock {
            if (closed) return@withLock false
            val slot = slots.firstOrNull { it.definition.id == id } ?: return@withLock false
            if (slot.busy || slot.broken) return@withLock false

            slot.broken = true
            slot.brokenUntil = null
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
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun close() {
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
            val now = currentTimeMillis()
            slots.forEach { candidate ->
                val until = candidate.brokenUntil
                if (candidate.broken && !candidate.busy && until != null && now >= until) {
                    candidate.broken = false
                    candidate.brokenUntil = null
                    candidate.publishSnapshot()
                }
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
                        slot.brokenUntil = null
                        slot.lastError = reason
                    }
                    slot.publishSnapshot()
                }
                return@withContext
            }

            if (broken) {
                abortSlot(slot, reason ?: "Marked broken by lease", permanent = true)
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

    /**
     * Frees the slot and closes its resource. With [quarantine] the slot is marked broken (until
     * [restore] when [permanent], otherwise for [transientFailureCooldownMs]); a cancelled caller
     * passes false because cancellation says nothing about the resource's health.
     */
    private suspend fun abortSlot(slot: ResourceSlot<T>, reason: String, permanent: Boolean = false, quarantine: Boolean = true) =
        withContext(NonCancellable) {
            val resource = mutex.withLock {
                slot.busy = false
                if (quarantine) {
                    slot.broken = true
                    slot.brokenUntil = if (permanent) null else currentTimeMillis() + transientFailureCooldownMs
                    slot.lastError = reason
                }
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
        var closeWhenReleased: Boolean = false,
        /** End of a transient quarantine; null while healthy or when quarantined until [restore]. */
        var brokenUntil: Long? = null
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
