package com.bernaferrari.kmpwright.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * WebKit content-blocker rules enforcing a [DomainFilter] host allowlist for every load of a WKWebView
 * (documents, iframes, fetch/XHR, images, scripts, WebSockets, redirects).
 *
 * `if-domain`/`unless-domain` triggers match the *page's* domain, not the request's, so the request
 * host is matched with `url-filter` instead: first block every http(s)/ws(s) URL, then lift the block
 * (`ignore-previous-rules`) for URLs whose host is allowed. Content-blocker regular expressions have no
 * alternation, hence one rule per scheme family and host form. Non-network schemes (data:, blob:,
 * about:) are never blocked.
 */
internal object IOSDomainFilterRules {
    private val schemes = listOf("https?", "wss?")
    private val hostCharacters = Regex("^[a-z0-9.\\-:\\[\\]]+$")

    fun encode(domains: Set<String>): String {
        val rules = mutableListOf<kotlinx.serialization.json.JsonObject>()
        schemes.forEach { scheme -> rules += rule("^$scheme://", "block") }
        domains.forEach { raw ->
            val pattern = raw.trim().lowercase().trimEnd('.')
            val wildcard = pattern.startsWith("*.")
            val host = if (wildcard) pattern.removePrefix("*.") else pattern
            if (host.isEmpty() || !hostCharacters.matches(host)) {
                throw KmpwrightException("Domain filter entry '$raw' is not a host name or '*.' wildcard")
            }
            val escaped = escape(host)
            schemes.forEach { scheme ->
                rules += rule("^$scheme://$escaped[:/]", "ignore-previous-rules")
                if (wildcard) rules += rule("^$scheme://[a-z0-9.-]*\\.$escaped[:/]", "ignore-previous-rules")
            }
        }
        return JsonArray(rules).toString()
    }

    private fun rule(filter: String, action: String) = buildJsonObject {
        put("trigger", buildJsonObject { put("url-filter", JsonPrimitive(filter)) })
        put("action", buildJsonObject { put("type", JsonPrimitive(action)) })
    }

    private fun escape(host: String): String = buildString {
        host.forEach { char ->
            if (char in ".[]") append('\\')
            append(char)
        }
    }
}
