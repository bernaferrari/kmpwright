package com.bernaferrari.kmpwright.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Cookies plus the localStorage and sessionStorage of one origin, like Playwright's `storageState()`.
 * Capture it with [Page.storageState], restore it with [Page.setStorageState], and persist it with
 * [save] / [load].
 *
 * @property url the page URL whose origin owns [localStorage] and [sessionStorage].
 */
public data class StorageState(
    val url: String?,
    val cookies: List<Cookie>,
    val localStorage: Map<String, String>,
    val sessionStorage: Map<String, String>
) {
    /**
     * Writes this state to [path]. It contains session cookies, so it is encrypted with [password];
     * pass `password = null` explicitly to write plaintext.
     */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun save(path: String, password: String?) {
        require(path.isNotBlank()) { "Storage state path must not be blank" }
        require(password == null || password.isNotBlank()) { "Password must not be blank; pass null to write plaintext" }
        withContext(fileDispatcher) {
            val json = SessionManager.serialize(toSessionState())
            val directory = path.replace('\\', '/').substringBeforeLast('/', "")
            if (directory.isNotEmpty()) mkdirs(directory)
            fileWrite(path, if (password != null) SessionManager.encrypt(json, password) else json)
        }
    }

    override fun toString(): String =
        "StorageState(url=$url, cookies=${cookies.size}, localStorage=${localStorage.keys}, sessionStorage=${sessionStorage.keys})"

    /** Loading persisted states. */
    public companion object {
        /**
         * Reads a state written by [save]. A [password] makes this an authenticated decryption that
         * never falls back to plaintext; pass null to read a plaintext file.
         *
         * @throws KmpwrightException when the file is missing, cannot be decrypted or is malformed.
         */
        @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
        public suspend fun load(path: String, password: String?): StorageState = withContext(fileDispatcher) {
            val raw = fileRead(path) ?: throw KmpwrightException("Storage state file not found: $path")
            val json = if (password == null) raw else SessionManager.decryptOrThrow(raw, password, "Storage state $path")
            val state = SessionManager.deserialize(json)
                ?: throw KmpwrightException("Storage state $path is not valid${if (password == null) " plaintext; pass its password" else ""}")
            StorageState(
                url = state.url?.takeIf { it.isNotEmpty() },
                cookies = state.cookies.map { Cookie(it.name, it.value, it.domain, it.path, it.expires, it.secure, it.httpOnly, it.sameSite, it.hostOnly) },
                localStorage = state.localStorage,
                sessionStorage = state.sessionStorage
            )
        }
    }

    private fun toSessionState() = SessionState(
        name = "storage-state",
        url = url ?: "",
        cookies = cookies.map { SessionCookie(it.name, it.value, it.domain, it.path, it.expires, it.secure, it.httpOnly, it.sameSite, it.hostOnly) },
        localStorage = localStorage,
        sessionStorage = sessionStorage
    )
}

/** A running autosave started by [Page.autosaveStorageState]; errors stop it and stay observable in [error]. */
public class StorageStateAutosave internal constructor(scope: CoroutineScope, intervalMs: Long, private val save: suspend () -> Unit) {
    private val _error = MutableStateFlow<String?>(null)

    /** The failure that stopped the autosave, or null. */
    public val error: StateFlow<String?> = _error.asStateFlow()

    private val _lastSavedAt = MutableStateFlow<Long?>(null)

    /** Epoch milliseconds of the last successful save. */
    public val lastSavedAt: StateFlow<Long?> = _lastSavedAt.asStateFlow()

    private val job = scope.launch(start = CoroutineStart.LAZY) {
        while (isActive) {
            delay(intervalMs)
            try { save(); _lastSavedAt.value = currentTimeMillis() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { _error.value = failure.message ?: "Storage state autosave failed"; return@launch }
        }
    }

    internal suspend fun start(): StorageStateAutosave {
        try { save(); _lastSavedAt.value = currentTimeMillis(); job.start() }
        catch (failure: Throwable) { job.cancel(); throw failure }
        return this
    }

    /** Stops saving; no write happens after this returns. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun stop() { job.cancelAndJoin() }

    /** Whether the autosave is still running. */
    public val isActive: Boolean get() = job.isActive
}

/** Validates the arguments of [Page.autosaveStorageState]. */
internal fun requireAutosaveArguments(path: String, password: String?, intervalMs: Long) {
    require(path.isNotBlank()) { "Autosave path must not be blank" }
    require(password == null || password.isNotBlank()) { "Autosave password must not be blank; pass null to write plaintext" }
    require(intervalMs in 1_000..86_400_000) { "Autosave interval must be between one second and one day" }
}
