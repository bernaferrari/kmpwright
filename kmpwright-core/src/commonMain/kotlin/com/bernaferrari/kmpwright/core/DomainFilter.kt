package com.bernaferrari.kmpwright.core

/**
 * Host allowlist used by [Page.setAllowedDomains] and the `allowedDomains` of [HttpReader.read], with
 * agent-browser `--allowed-domains` semantics (see [hostMatchesDomainPattern]); [ActionPolicy]
 * domain lists use the same rule.
 *
 * Enforcement: backends with a request-layer hook (CDP Fetch interception, Android
 * shouldInterceptRequest/shouldOverrideUrlLoading, iOS navigation policy + WKContentRuleList) block
 * requests natively; backends without one fall back to a document-start JavaScript patch of
 * script-initiated network APIs, which is best effort only (see [Page.setAllowedDomains]).
 */
internal data class DomainFilter(
    val allowedDomains: Set<String> = emptySet()
) {
    /**
     * Check if a hostname matches the allowed list.
     * Empty allowedDomains = allow all (no restriction).
     * Supports wildcard patterns: "*.example.com" matches "example.com", "api.example.com", etc.
     */
    fun isAllowed(hostname: String): Boolean {
        if (allowedDomains.isEmpty()) return true
        return allowedDomains.any { hostMatchesDomainPattern(hostname, it) }
    }
}

/**
 * agent-browser `--allowed-domains` matching, shared by every KMPwright domain list: a plain entry
 * such as `example.com` matches exactly that host; `*.example.com` matches the apex `example.com`
 * and every subdomain. Comparison ignores case and a trailing dot.
 */
internal fun hostMatchesDomainPattern(hostname: String, pattern: String): Boolean {
    val host = hostname.trim().lowercase().trimEnd('.')
    val normalized = pattern.trim().lowercase().trimEnd('.')
    if (normalized.startsWith("*.")) {
        val suffix = normalized.removePrefix("*.")
        return host == suffix || host.endsWith(".$suffix")
    }
    return host == normalized
}
