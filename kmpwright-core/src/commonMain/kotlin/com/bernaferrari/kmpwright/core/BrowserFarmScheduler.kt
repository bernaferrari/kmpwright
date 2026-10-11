package com.bernaferrari.kmpwright.core

import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Capability and cost constraints applied when selecting a pooled resource.
 *
 * Metadata matching is exact. A cost constraint requires a numeric
 * finite, non-negative `costUsdPerHour` (or `cost`) metadata value, so an
 * unpriced or invalidly priced slot is never accidentally selected for a
 * cost-bounded request.
 */
data class ResourceSelector(
    val requiredMetadata: Map<String, String> = emptyMap(),
    val maxCostUsdPerHour: Double? = null,
    val predicate: (ResourcePoolSnapshot) -> Boolean = { true }
) {
    internal fun matches(snapshot: ResourcePoolSnapshot): Boolean {
        if (!requiredMetadata.all { (key, value) -> snapshot.metadata[key] == value }) return false
        val maxCost = maxCostUsdPerHour
        if (maxCost != null) {
            val cost = snapshot.metadata["costUsdPerHour"]?.toDoubleOrNull()
                ?: snapshot.metadata["cost"]?.toDoubleOrNull()
                ?: return false
            if (!cost.isFinite() || cost < 0.0 || cost > maxCost) return false
        }
        return predicate(snapshot)
    }
}

/** A point-in-time view of scheduler capacity and lifecycle counters. */
data class ResourceSchedulerMetrics(
    val capacity: Int,
    val available: Int,
    val busy: Int,
    val initialized: Int,
    val quarantined: Int,
    val acquisitions: Long,
    val releases: Long,
    val acquisitionFailures: Long,
    val totalWaitMs: Long
)

/** Lifecycle signals suitable for logs, dashboards, or a health supervisor. */
sealed interface ResourceSchedulerEvent {
    data class Acquired(val id: String, val waitMs: Long) : ResourceSchedulerEvent
    data class Released(val id: String) : ResourceSchedulerEvent
    data class Quarantined(val id: String, val reason: String?) : ResourceSchedulerEvent
    data class HealthCheckFailed(val id: String, val reason: String) : ResourceSchedulerEvent
    data class AcquisitionFailed(val reason: String) : ResourceSchedulerEvent
}

/**
 * A small library-level scheduler over [ResourcePool].
 *
 * It deliberately owns no server, queue, or background worker. Callers
 * choose a slot with metadata/capability predicates, use the returned lease,
 * and either release it for reset or mark it broken to quarantine it.
 */
class ResourceScheduler<T>(resources: List<PooledResource<T>>) {
    private val pool = ResourcePool(resources)
    private val _events = MutableSharedFlow<ResourceSchedulerEvent>(
        replay = 16,
        extraBufferCapacity = 64
    )
    private val acquisitions = atomic(0L)
    private val releases = atomic(0L)
    private val quarantines = atomic(0L)
    private val acquisitionFailures = atomic(0L)
    private val totalWaitMs = atomic(0L)

    /** Recent lifecycle events; events contain identifiers, never resources. */
    val events: SharedFlow<ResourceSchedulerEvent> = _events.asSharedFlow()

    suspend fun acquire(
        timeoutMs: Long = 30_000,
        selector: ResourceSelector = ResourceSelector()
    ): ResourceLease<T> {
        val startedAt = currentTimeMillis()
        val lease = try {
            pool.acquire(timeoutMs = timeoutMs, predicate = selector::matches)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            acquisitionFailures.incrementAndGet()
            _events.tryEmit(ResourceSchedulerEvent.AcquisitionFailed(t.message ?: "Acquisition failed"))
            throw t
        }

        val waitMs = (currentTimeMillis() - startedAt).coerceAtLeast(0L)
        acquisitions.incrementAndGet()
        totalWaitMs.addAndGet(waitMs)
        _events.tryEmit(ResourceSchedulerEvent.Acquired(lease.id, waitMs))

        return ResourceLease(
            id = lease.id,
            metadata = lease.metadata,
            resource = lease.resource
        ) { broken, reason ->
            if (broken) {
                try {
                    lease.markBroken(reason)
                } finally {
                    quarantines.incrementAndGet()
                    _events.tryEmit(ResourceSchedulerEvent.Quarantined(lease.id, reason))
                }
            } else {
                try {
                    lease.release()
                    releases.incrementAndGet()
                    _events.tryEmit(ResourceSchedulerEvent.Released(lease.id))
                } catch (t: Throwable) {
                    quarantines.incrementAndGet()
                    _events.tryEmit(ResourceSchedulerEvent.Quarantined(lease.id, t.message))
                    throw t
                }
            }
        }
    }

    suspend fun <R> withLease(
        timeoutMs: Long = 30_000,
        selector: ResourceSelector = ResourceSelector(),
        markBrokenOnException: Boolean = true,
        block: suspend (ResourceLease<T>) -> R
    ): R {
        val lease = acquire(timeoutMs, selector)
        return try {
            val result = block(lease)
            lease.release()
            result
        } catch (t: Throwable) {
            if (markBrokenOnException) lease.markBroken(t.message) else lease.release()
            throw t
        }
    }

    /**
     * Runs a caller-provided health probe against one selected slot.
     * A failed probe marks that slot broken and emits both health and
     * quarantine events; the next acquire will skip it.
     */
    suspend fun healthCheck(
        id: String,
        timeoutMs: Long = 30_000,
        check: suspend (T) -> Boolean
    ): Boolean {
        val lease = acquire(
            timeoutMs = timeoutMs,
            selector = ResourceSelector(predicate = { it.id == id })
        )
        val healthy = try {
            check(lease.resource)
        } catch (t: Throwable) {
            if (t is CancellationException) {
                runCatching { lease.release() }
                throw t
            }
            lease.markBroken(t.message ?: "Health check failed")
            _events.tryEmit(ResourceSchedulerEvent.HealthCheckFailed(id, t.message ?: "Health check failed"))
            return false
        }

        if (healthy) {
            lease.release()
        } else {
            val reason = "Health check failed"
            lease.markBroken(reason)
            _events.tryEmit(ResourceSchedulerEvent.HealthCheckFailed(id, reason))
        }
        return healthy
    }

    /** Quarantines an idle slot; use a lease's [ResourceLease.markBroken] while busy. */
    suspend fun quarantine(id: String, reason: String? = null): Boolean {
        val quarantined = pool.quarantine(id, reason)
        if (quarantined) {
            quarantines.incrementAndGet()
            _events.tryEmit(ResourceSchedulerEvent.Quarantined(id, reason))
        }
        return quarantined
    }

    fun snapshots(): List<ResourcePoolSnapshot> = pool.snapshots()

    fun metrics(): ResourceSchedulerMetrics {
        val snapshots = pool.snapshots()
        return ResourceSchedulerMetrics(
            capacity = snapshots.size,
            available = snapshots.count { !it.busy && !it.broken },
            busy = snapshots.count { it.busy },
            initialized = snapshots.count { it.initialized },
            quarantined = snapshots.count { it.broken },
            acquisitions = acquisitions.value,
            releases = releases.value,
            acquisitionFailures = acquisitionFailures.value,
            totalWaitMs = totalWaitMs.value
        )
    }

    suspend fun close() = pool.close()
}

typealias BrowserFarmScheduler = ResourceScheduler<Browser>
typealias BrowserFarmSelector = ResourceSelector
typealias BrowserFarmMetrics = ResourceSchedulerMetrics
typealias BrowserFarmEvent = ResourceSchedulerEvent
