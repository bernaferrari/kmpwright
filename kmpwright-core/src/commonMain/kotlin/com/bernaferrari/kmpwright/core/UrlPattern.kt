package com.bernaferrari.kmpwright.core

/**
 * The single URL-pattern rule shared by every String URL API ([Page.route], [Page.unroute],
 * [Page.waitForURL], [Page.waitForRequest], [Page.waitForResponse], [Page.waitForNavigation],
 * [Page.waitForDownload], `expect(page).toHaveURL`, ...), with Playwright's semantics (`urlMatches` /
 * `globToRegex`):
 *
 * - the pattern is a glob over the **full** URL: `**` matches any characters including `/`, `*` any
 *   characters except `/`, `{a,b}` either alternative, `\` escapes the next character, and every other
 *   character (including `?`) is literal; so a plain string without glob characters is an exact URL match;
 * - absolute `http(s)`/`ws(s)` patterns are normalised like a parsed URL (scheme and host lower-cased,
 *   default port removed, `/` appended to a bare origin) so `"https://Example.com"` matches `https://example.com/`;
 * - an empty pattern matches every URL;
 * - KMPwright extension: a `regex:` prefix makes the rest a regular expression searched in the URL
 *   (for String-only parameters such as the `url` of [Page.waitForNavigation]).
 *
 * Relative patterns are not resolved: Playwright resolves them only against a context `baseURL`, which
 * KMPwright does not have.
 */
internal fun urlPatternRegex(pattern: String): Regex = when {
    pattern.isEmpty() -> Regex("[\\s\\S]*")
    pattern.startsWith("regex:") -> compileUserRegex(".*(?:" + pattern.removePrefix("regex:") + ").*")
    else -> Regex(globToRegexPattern(normalizeGlobBase(pattern)))
}

/** Whether [url] matches the String URL [pattern] (see [urlPatternRegex]). */
internal fun urlMatches(pattern: String, url: String): Boolean = urlPatternRegex(pattern).matches(url)

/** Converts a Playwright URL glob into an anchored regular expression (no URL normalisation). */
internal fun urlGlobToRegex(pattern: String): Regex = Regex(globToRegexPattern(pattern))

private const val GLOB_ESCAPED = "$^+.*()|\\?{}[]"

/** Port of Playwright's `globToRegexPattern`. */
internal fun globToRegexPattern(glob: String): String {
    val tokens = StringBuilder("^")
    var inGroup = false
    var i = 0
    while (i < glob.length) {
        val c = glob[i]
        if (c == '\\' && i + 1 < glob.length) {
            val next = glob[++i]
            if (next in GLOB_ESCAPED) tokens.append('\\')
            tokens.append(next)
            i++
            continue
        }
        if (c == '*') {
            val charBefore = glob.getOrNull(i - 1)
            var starCount = 1
            while (glob.getOrNull(i + 1) == '*') { starCount++; i++ }
            if (starCount > 1) {
                val charAfter = glob.getOrNull(i + 1)
                if (charAfter == '/') {
                    tokens.append(if (charBefore == '/') "((.+/)|)" else "(.*/)")
                    i++
                } else {
                    tokens.append("(.*)")
                }
            } else {
                tokens.append("([^/]*)")
            }
            i++
            continue
        }
        when (c) {
            '{' -> { inGroup = true; tokens.append('(') }
            '}' -> { inGroup = false; tokens.append(')') }
            ',' -> if (inGroup) tokens.append('|') else tokens.append("\\,")
            else -> { if (c in GLOB_ESCAPED) tokens.append('\\'); tokens.append(c) }
        }
        i++
    }
    tokens.append('$')
    return tokens.toString()
}

private val ABSOLUTE_WEB_URL = Regex("^([A-Za-z][A-Za-z0-9+.-]*)://([^/?#]*)([\\s\\S]*)$")
private val DEFAULT_PORTS = mapOf("http" to "80", "https" to "443", "ws" to "80", "wss" to "443")

/**
 * Playwright parses non-glob-prefixed patterns as URLs before converting them; this mirrors the parts
 * of that normalisation that matter for absolute web URLs.
 */
private fun normalizeGlobBase(pattern: String): String {
    if (pattern.startsWith("*")) return pattern
    val match = ABSOLUTE_WEB_URL.matchEntire(pattern) ?: return pattern
    val scheme = match.groupValues[1].lowercase()
    val defaultPort = DEFAULT_PORTS[scheme] ?: return pattern
    var authority = match.groupValues[2]
    var rest = match.groupValues[3]
    // Credentials are kept verbatim; only the host (and port) are case/port normalised.
    val credentials = authority.substringBeforeLast('@', "").let { if (it.isEmpty()) "" else "$it@" }
    var host = authority.substringAfterLast('@')
    if (host.endsWith(":$defaultPort")) host = host.removeSuffix(":$defaultPort")
    authority = credentials + host.lowercase()
    if (rest.isEmpty()) rest = "/"
    else if (rest.startsWith("?") || rest.startsWith("#")) rest = "/$rest"
    return "$scheme://$authority$rest"
}

private fun compileUserRegex(pattern: String): Regex = try {
    Regex(pattern)
} catch (e: IllegalArgumentException) {
    throw KmpwrightException("Invalid URL regular expression \"$pattern\": ${e.message}", e)
}

internal fun originRuleMatches(rule: String, url: String): Boolean {
    val origin = canonicalHttpOrigin(url) ?: return false
    val canonicalRule = canonicalHttpOrigin(rule.trim().lowercase())
    if (canonicalRule != null) return canonicalRule == origin
    return urlGlobToRegex(rule.trim().lowercase()).matches(origin)
}

/** The wait APIs use the shared rule; kept as a named entry point for their call sites. */
internal fun waitUrlPatternMatches(pattern: String, url: String): Boolean = urlMatches(pattern, url)
