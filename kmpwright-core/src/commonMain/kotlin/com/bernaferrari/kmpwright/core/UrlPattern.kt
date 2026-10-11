package com.bernaferrari.kmpwright.core

/** Converts Playwright-style URL globs to an anchored regular expression. */
internal fun urlGlobToRegex(pattern: String): Regex {
    val result = StringBuilder("^")
    var index = 0
    while (index < pattern.length) {
        when (val char = pattern[index]) {
            '*' -> {
                if (index + 1 < pattern.length && pattern[index + 1] == '*') {
                    result.append(".*")
                    index += 2
                } else {
                    result.append("[^/]*")
                    index++
                }
            }
            '?' -> {
                result.append('.')
                index++
            }
            else -> {
                if (char in "\\.^$|()[]{}+") result.append('\\')
                result.append(char)
                index++
            }
        }
    }
    result.append('$')
    return Regex(result.toString())
}

internal fun routeUrlPatternToRegex(pattern: String): Regex = when {
    pattern.startsWith("regex:") -> Regex(pattern.removePrefix("regex:"))
    ".*" in pattern || "\\" in pattern -> Regex(pattern)
    else -> urlGlobToRegex(pattern)
}

internal fun originRuleMatches(rule: String, url: String): Boolean {
    val origin = canonicalHttpOrigin(url) ?: return false
    val canonicalRule = canonicalHttpOrigin(rule.trim().lowercase())
    if (canonicalRule != null) return canonicalRule == origin
    return urlGlobToRegex(rule.trim().lowercase()).matches(origin)
}

/** Matches full URL globs while preserving substring semantics for plain wait patterns. */
internal fun waitUrlPatternMatches(pattern: String, url: String): Boolean =
    when {
        pattern.startsWith("regex:") -> Regex(pattern.removePrefix("regex:")).containsMatchIn(url)
        ".*" in pattern || "\\" in pattern -> Regex(pattern).containsMatchIn(url)
        '*' in pattern || '?' in pattern -> urlGlobToRegex(pattern).matches(url)
        else -> url.contains(pattern)
    }
