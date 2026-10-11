package com.bernaferrari.kmpwright.core

/**
 * Delegate for localStorage and sessionStorage operations.
 * Extracted from BasePage to reduce class size and improve testability.
 */
internal class PageStorageDelegate(
    private val evalString: suspend (String) -> String?
) {
    suspend fun setLocalStorage(key: String, value: String) {
        val escapedKey = escapeJsString(key)
        val escapedValue = escapeJsString(value)
        evalString("localStorage.setItem('$escapedKey', '$escapedValue');")
    }

    suspend fun getLocalStorage(key: String): String? {
        val escapedKey = escapeJsString(key)
        return evalString("return localStorage.getItem('$escapedKey');")
    }

    suspend fun removeLocalStorage(key: String) {
        val escapedKey = escapeJsString(key)
        evalString("localStorage.removeItem('$escapedKey');")
    }

    suspend fun getAllLocalStorage(): Map<String, String> {
        val raw = evalString("(function() { var items = {}; for (var i = 0; i < localStorage.length; i++) { var k = localStorage.key(i); items[k] = localStorage.getItem(k); } return JSON.stringify(items); })()") ?: return emptyMap()
        return try {
            kotlinx.serialization.json.Json.decodeFromString<Map<String, String>>(raw)
        } catch (e: Exception) {
            emptyMap()
        }
    }

    suspend fun clearLocalStorage() {
        evalString("localStorage.clear();")
    }

    suspend fun setSessionStorage(key: String, value: String) {
        val escapedKey = escapeJsString(key)
        val escapedValue = escapeJsString(value)
        evalString("sessionStorage.setItem('$escapedKey', '$escapedValue');")
    }

    suspend fun getSessionStorage(key: String): String? {
        val escapedKey = escapeJsString(key)
        return evalString("return sessionStorage.getItem('$escapedKey');")
    }

    suspend fun removeSessionStorage(key: String) {
        val escapedKey = escapeJsString(key)
        evalString("sessionStorage.removeItem('$escapedKey');")
    }

    suspend fun getAllSessionStorage(): Map<String, String> {
        val raw = evalString("(function() { var items = {}; for (var i = 0; i < sessionStorage.length; i++) { var k = sessionStorage.key(i); items[k] = sessionStorage.getItem(k); } return JSON.stringify(items); })()") ?: return emptyMap()
        return try {
            kotlinx.serialization.json.Json.decodeFromString<Map<String, String>>(raw)
        } catch (e: Exception) {
            emptyMap()
        }
    }

    suspend fun clearSessionStorage() {
        evalString("sessionStorage.clear();")
    }
}
