package com.bernaferrari.kmpwright.core

internal fun normalizeJsResult(raw: String?): String? {
    if (raw == null) return null
    val trimmed = raw.trim()
    if (trimmed == "null" || trimmed == "undefined") return null
    if (trimmed.length >= 2 && trimmed.first() == '"' && trimmed.last() == '"') {
        return runCatching {
            (kotlinx.serialization.json.Json.parseToJsonElement(trimmed) as kotlinx.serialization.json.JsonPrimitive).content
        }.getOrDefault(trimmed)
    }
    return trimmed
}

internal fun escapeJsString(str: String): String {
    return str
        .replace("\\", "\\\\")
        .replace("'", "\\'")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")
        .replace("\u2028", "\\u2028")
        .replace("\u2029", "\\u2029")
}

internal fun jsStringLiteral(str: String): String = "'${escapeJsString(str)}'"

internal fun escapeHtml(str: String): String {
    return str
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#x27;")
}
