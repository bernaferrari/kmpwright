package com.bernaferrari.kmpwright.core

/**
 * Delegate for page state persistence (save/load state to memory and files).
 * Extracted from BasePage to reduce class size and improve testability.
 */
internal class PageStateDelegate(
    private val currentPageUrl: () -> String?,
    private val getAllCookies: suspend () -> List<Cookie>,
    private val getAllLocalStorage: suspend () -> Map<String, String>,
    private val getAllSessionStorage: suspend () -> Map<String, String>,
    private val restoreState: suspend (stateUrl: String?, cookies: List<Cookie>, localStorage: Map<String, String>, sessionStorage: Map<String, String>) -> Unit
) {
    private var savedStates = mapOf<String, PageState>()

    suspend fun saveState(name: String) {
        val cookies = getAllCookies()
        val localStorage = getAllLocalStorage()
        val sessionStorage = getAllSessionStorage()
        savedStates = savedStates + (name to PageState(currentPageUrl(), cookies, localStorage, sessionStorage))
    }

    suspend fun loadState(name: String): Boolean {
        val state = savedStates[name] ?: return false
        restoreState(state.url, state.cookies, state.localStorage, state.sessionStorage)
        return true
    }

    fun stateNames(): List<String> = savedStates.keys.toList()

    suspend fun deleteState(name: String) {
        savedStates = savedStates - name
    }

    suspend fun saveStateToFile(name: String, path: String?, encrypt: Boolean, password: String?) {
        val cookies = getAllCookies()
        val localStorage = getAllLocalStorage()
        val sessionStorage = getAllSessionStorage()
        val sessionState = SessionState(
            name = name,
            url = currentPageUrl() ?: "",
            cookies = cookies.map { SessionCookie(it.name, it.value, it.domain, it.path, it.expires, it.secure, it.httpOnly, it.sameSite, it.hostOnly) },
            localStorage = localStorage,
            sessionStorage = sessionStorage
        )
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            val json = SessionManager.serialize(sessionState)
            val content = if (encrypt) SessionManager.encrypt(json, password) else json
            val filePath = path ?: defaultStateFilePath(name)
            fileWrite(filePath, content)
        }
    }

    suspend fun loadStateFromFile(name: String, path: String?, password: String?): Boolean {
        val filePath = path ?: defaultStateFilePath(name)
        val raw = fileRead(filePath) ?: return false
        // A supplied password makes this an authenticated-encrypted load. Never
        // reinterpret the input as plaintext when authentication fails: doing so
        // would allow a password-protected state to be replaced with arbitrary
        // JSON and still restore cookies/storage. Plaintext loading is explicit
        // by omitting the password.
        val json = if (password.isNullOrBlank()) {
            raw
        } else {
            SessionManager.decrypt(raw, password) ?: return false
        }
        val state = SessionManager.deserialize(json) ?: return false
        restoreState(
            state.url,
            state.cookies.map { Cookie(it.name, it.value, it.domain, it.path, it.expires, it.secure, it.httpOnly, it.sameSite, it.hostOnly) },
            state.localStorage,
            state.sessionStorage
        )
        return true
    }

    fun listSavedStateFiles(dir: String?): List<String> {
        val directory = dir ?: defaultStateDir()
        return fileList(directory).filter { it.endsWith(".json") }.map { it.removeSuffix(".json") }
    }

    fun deleteStateFile(name: String, path: String?): Boolean {
        val filePath = path ?: defaultStateFilePath(name)
        return fileDelete(filePath)
    }

    private fun defaultStateDir(): String {
        return "${platformHomeDir()}/.kmpwright/states"
    }

    private fun defaultStateFilePath(name: String): String {
        return "${defaultStateDir()}/${sanitizeStateFileName(name)}.json"
    }
}
