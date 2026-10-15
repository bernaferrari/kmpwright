package com.bernaferrari.kmpwright.core

/** `localStorage` / `sessionStorage` of the current document, evaluated through the page. */
internal class PageWebStorage(
    private val area: String,
    private val evalString: suspend (String) -> String?,
    private val checkPolicy: suspend (ActionType) -> Unit
) : WebStorage {
    override suspend fun get(key: String): String? {
        checkPolicy(ActionType.ReadContent)
        return evalString("$area.getItem(${jsStringLiteral(key)});")
    }

    override suspend fun set(key: String, value: String) {
        checkPolicy(ActionType.Storage)
        evalString("$area.setItem(${jsStringLiteral(key)}, ${jsStringLiteral(value)});")
    }

    override suspend fun remove(key: String) {
        checkPolicy(ActionType.Storage)
        evalString("$area.removeItem(${jsStringLiteral(key)});")
    }

    override suspend fun clear() {
        checkPolicy(ActionType.Storage)
        evalString("$area.clear();")
    }

    override suspend fun entries(): Map<String, String> {
        checkPolicy(ActionType.ReadContent)
        return readEntries()
    }

    /** Entries without a policy check, for state capture that already checked. */
    suspend fun readEntries(): Map<String, String> {
        val raw = evalString("(function() { var items = Object.create(null); for (var i = 0; i < $area.length; i++) { var k = $area.key(i); items[k] = $area.getItem(k); } return JSON.stringify(items); })()")
            ?: return emptyMap()
        return try {
            kotlinx.serialization.json.Json.decodeFromString<Map<String, String>>(raw)
        } catch (e: Exception) {
            throw KmpwrightException("Could not decode $area entries", e)
        }
    }

}
