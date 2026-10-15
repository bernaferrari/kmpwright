package com.bernaferrari.kmpwright.core

import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * Document readiness and continuous network quiet share one page lifetime and one total deadline.
 * Load events race readyState probes, so a stalled native evaluation cannot hide a completed load.
 * Network activity is recorded before emission, including requests that fail between probes.
 */
internal class PageLoadStates(
    private val pendingRequests: () -> Int,
    private val closed: Deferred<Unit>,
    private val events: SharedFlow<LoadState>,
    private val readReadyState: suspend () -> String?,
    private val scope: CoroutineScope,
    private val hasLoadListeners: () -> Boolean,
    private val emitIdle: () -> Unit
) {
    private val lastActivity = atomic(TimeSource.Monotonic.markNow())
    private val changes = MutableSharedFlow<Unit>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    // No background work until the first navigation or request. Replay preserves a signal that
    // arrives before this coroutine gets dispatched; collectLatest bounds work during bursts.
    private val observer by lazy {
        scope.launch {
            changes.collectLatest {
                delay(500)
                if (!closed.isCompleted && lastActivity.value.elapsedNow() >= 500.milliseconds &&
                    runCatching { pendingRequests() }.getOrNull() == 0) emitIdle()
            }
        }
    }

    fun networkChanged() {
        if (closed.isCompleted) return
        lastActivity.value = TimeSource.Monotonic.markNow()
        if (hasLoadListeners()) {
            observer
            changes.tryEmit(Unit)
        }
    }

    suspend fun await(state: LoadState, timeoutMs: Long): Boolean = awaitPageOperation(closed) {
        require(timeoutMs > 0) { "Timeout must be positive, was ${timeoutMs}ms" }
        withTimeoutOrNull(timeoutMs) {
            when (state) {
                LoadState.DOMContentLoaded -> awaitReadyState { it == "interactive" || it == "complete" }
                LoadState.Load -> awaitReadyState { it == "complete" }
                LoadState.NetworkIdle -> awaitNetworkIdle()
            }
            true
        } ?: false
    }

    private suspend fun awaitReadyState(isReady: (String?) -> Boolean) = coroutineScope {
        val event = async(start = CoroutineStart.UNDISPATCHED) {
            events.first { it == LoadState.Load || (it == LoadState.DOMContentLoaded && isReady("interactive")) }
        }
        val probe = async(start = CoroutineStart.UNDISPATCHED) {
            while (!isReady(readReadyState())) delay(500)
        }
        try {
            select {
                event.onAwait { }
                probe.onAwait { }
            }
        } finally {
            event.cancel()
            probe.cancel()
        }
    }

    private suspend fun awaitNetworkIdle() {
        var quietSince = TimeSource.Monotonic.markNow()
        while (true) {
            if (pendingRequests() != 0) quietSince = TimeSource.Monotonic.markNow()
            else if (minOf(quietSince.elapsedNow(), lastActivity.value.elapsedNow()) >= 500.milliseconds) return
            delay(25)
        }
    }
}
