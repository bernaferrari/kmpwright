package com.bernaferrari.kmpwright.core

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Autosave is opt-in and belongs to its page. Errors stop the job and remain observable. */
data class StateAutosaveOptions(val name: String, val path: String? = null,
    val intervalMs: Long = 30_000, val encrypt: Boolean = true, val password: String? = null) {
    init {
        require(!encrypt || !password.isNullOrBlank()) { "Encrypted autosave requires an explicit password" }
        require(name.isNotBlank()) { "Autosave state name must not be blank" }
        require(intervalMs in 1_000..86_400_000) { "Autosave interval must be between one second and one day" }
        require(path == null || path.isNotBlank()) { "Autosave path must not be blank" }
    }
}

class StateAutosave internal constructor(scope: CoroutineScope, intervalMs: Long, private val save: suspend () -> Unit) {
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    private val _lastSavedAt = MutableStateFlow<Long?>(null)
    val lastSavedAt: StateFlow<Long?> = _lastSavedAt.asStateFlow()
    private val job = scope.launch(start = CoroutineStart.LAZY) {
        while (isActive) {
            delay(intervalMs)
            try { save(); _lastSavedAt.value = currentTimeMillis() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { _error.value = failure.message ?: "State autosave failed"; return@launch }
        }
    }
    internal suspend fun start(): StateAutosave {
        try { save(); _lastSavedAt.value = currentTimeMillis(); job.start() }
        catch (failure: Throwable) { job.cancel(); throw failure }
        return this
    }
    /** Stop before modifying/restoring this state; no final write is made after cancellation. */
    suspend fun stop() { job.cancelAndJoin() }
    val isActive: Boolean get() = job.isActive
}
