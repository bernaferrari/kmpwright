package com.bernaferrari.kmpwright.core

/** Pins sensitive native request configuration to one HTTP origin. */
internal class OriginBoundRequestConfig {
    private var active = false
    private var origin: String? = null

    fun update(active: Boolean, currentUrl: String?) {
        this.active = active
        if (!active) {
            origin = null
        } else if (origin == null) {
            origin = canonicalHttpOrigin(currentUrl)
        }
    }

    fun allows(url: String?): Boolean {
        if (!active) return false
        val targetOrigin = canonicalHttpOrigin(url) ?: return false
        if (origin == null) origin = targetOrigin
        return origin == targetOrigin
    }

    internal fun boundOrigin(): String? = origin
}

/**
 * Backend-owned policy for the deliberately small HTTP surface mobile WebViews
 * can implement without copying secrets into the page content world.
 *
 * Android and iOS both use this class for their initial navigation requests.
 * Keeping the origin and support decisions here makes their behavior identical
 * and independently testable without constructing a native WebView.
 */
internal class MobileHttpRequestPolicy {
    private var credentials: Pair<String, String>? = null
    private val credentialScope = OriginBoundRequestConfig()

    fun supportsExtraHeaders(headers: Map<String, String>): Boolean = headers.isEmpty()

    fun setCredentials(username: String?, password: String?, currentUrl: String?) {
        credentials = if (username != null && password != null) username to password else null
        credentialScope.update(active = credentials != null, currentUrl = currentUrl)
    }

    fun requestHeaders(
        url: String?,
        encodeBasicCredentials: (String) -> String
    ): Map<String, String> {
        val activeCredentials = credentials ?: return emptyMap()
        if (!credentialScope.allows(url)) return emptyMap()
        val token = encodeBasicCredentials("${activeCredentials.first}:${activeCredentials.second}")
        return mapOf("Authorization" to "Basic $token")
    }
}

internal fun canonicalHttpOrigin(url: String?): String? {
    val value = url?.trim().orEmpty()
    if (value.isEmpty() || value.any { it.isWhitespace() || it.code < 0x20 }) return null
    val schemeEnd = value.indexOf("://")
    if (schemeEnd <= 0) return null

    val scheme = value.substring(0, schemeEnd).lowercase()
    if (scheme != "http" && scheme != "https") return null

    val authorityStart = schemeEnd + 3
    val authorityEnd = sequenceOf(
        value.indexOf('/', authorityStart),
        value.indexOf('?', authorityStart),
        value.indexOf('#', authorityStart)
    ).filter { it >= 0 }.minOrNull() ?: value.length
    val authority = value.substring(authorityStart, authorityEnd)
    if (authority.isBlank() || '@' in authority) return null
    // Wildcards are origin-rule syntax, never valid URL-host syntax.
    if ('*' in authority || '?' in authority) return null

    val (host, port) = if (authority.startsWith('[')) {
        val bracketEnd = authority.indexOf(']')
        if (bracketEnd <= 1) return null
        val parsedHost = authority.substring(0, bracketEnd + 1).lowercase()
        val suffix = authority.substring(bracketEnd + 1)
        val parsedPort = when {
            suffix.isEmpty() -> null
            suffix.startsWith(':') -> suffix.substring(1).toIntOrNull() ?: return null
            else -> return null
        }
        parsedHost to parsedPort
    } else {
        val colon = authority.lastIndexOf(':')
        val hasPort = colon > 0 && authority.substring(colon + 1).all(Char::isDigit)
        val rawHost = if (hasPort) authority.substring(0, colon) else authority
        val parsedHost = rawHost.lowercase().trimEnd('.')
        if (parsedHost.isBlank() || ':' in parsedHost) return null
        parsedHost to if (hasPort) authority.substring(colon + 1).toIntOrNull() else null
    }

    if (port != null && port !in 1..65535) return null
    val normalizedPort = when {
        scheme == "http" && port == 80 -> null
        scheme == "https" && port == 443 -> null
        else -> port
    }
    return buildString {
        append(scheme)
        append("://")
        append(host)
        if (normalizedPort != null) {
            append(':')
            append(normalizedPort)
        }
    }
}

internal fun canonicalHttpHost(url: String?): String? {
    val origin = canonicalHttpOrigin(url) ?: return null
    val authority = origin.substringAfter("://")
    return if (authority.startsWith("[")) authority.substringBefore(']') + "]"
    else authority.substringBefore(':')
}
