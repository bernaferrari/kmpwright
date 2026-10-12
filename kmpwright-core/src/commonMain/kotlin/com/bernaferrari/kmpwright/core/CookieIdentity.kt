package com.bernaferrari.kmpwright.core

import io.ktor.http.Url

internal fun cookieDomainMatches(cookieDomain: String, domainOrUrl: String): Boolean {
    val host = if (domainOrUrl.contains("://")) runCatching { Url(domainOrUrl).host }.getOrNull()
        ?: return false else domainOrUrl
    val domain = cookieDomain.removePrefix(".").lowercase()
    val normalized = host.removePrefix(".").lowercase()
    return normalized == domain || (cookieDomain.startsWith(".") && normalized.endsWith(".$domain"))
}

internal fun validateCookie(cookie: Cookie) {
    require(cookie.name.isNotEmpty() && cookie.name.none { it <= ' ' || it in ";=" }) { "Invalid cookie name" }
    require(cookie.value.none { it == '\r' || it == '\n' || it == ';' }) { "Invalid cookie value" }
    require(cookie.domain.isNotBlank() && cookie.domain.none { it <= ' ' || it in ";/" }) { "Invalid cookie domain" }
    require(cookie.path.startsWith("/") && cookie.path.none { it == '\r' || it == '\n' || it == ';' }) { "Invalid cookie path" }
    require(cookie.expires == null || (cookie.expires.isFinite() && cookie.expires >= 0)) { "Invalid cookie expiry" }
    require(cookie.sameSite == null || cookie.sameSite.lowercase() in setOf("strict", "lax", "none")) { "Invalid SameSite value" }
}
