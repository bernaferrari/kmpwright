package com.bernaferrari.kmpwright.core

/**
 * Builds selector-engine strings for the Playwright `getBy*` finders shared by [Page], [Locator] and
 * [FrameLocator]. Plain text matches case-insensitively as a substring; `exact = true` requires the
 * whole (whitespace-normalised) string with matching case; a [Regex] is tested against the text.
 */
internal object GetBy {
    fun role(
        role: String,
        name: String?,
        nameRegex: Regex?,
        exact: Boolean,
        checked: Boolean?,
        disabled: Boolean?,
        expanded: Boolean?,
        selected: Boolean?,
        pressed: Boolean?,
        level: Int?,
        includeHidden: Boolean?
    ): String = buildString {
        append("role=").append(role)
        when {
            nameRegex != null -> append("[name=").append(jsRegexLiteral(nameRegex)).append(']')
            name != null -> append("[name=").append(quoteSelectorValue(name)).append(if (exact) " s" else "").append(']')
        }
        checked?.let { append("[checked=").append(it).append(']') }
        disabled?.let { append("[disabled=").append(it).append(']') }
        expanded?.let { append("[expanded=").append(it).append(']') }
        selected?.let { append("[selected=").append(it).append(']') }
        pressed?.let { append("[pressed=").append(it).append(']') }
        level?.let { append("[level=").append(it).append(']') }
        includeHidden?.let { append("[include-hidden=").append(it).append(']') }
    }

    /** `engine=value`: substring by default, quoted for [exact]. */
    fun text(engine: String, text: String, exact: Boolean): String =
        if (exact) "$engine=${quoteSelectorValue(text)}" else "$engine=$text"

    fun text(engine: String, regex: Regex): String = "$engine=${jsRegexLiteral(regex)}"
}

/** Renders a Kotlin [Regex] as a JavaScript regular-expression literal (`/source/flags`). */
internal fun jsRegexLiteral(regex: Regex): String {
    val source = buildString {
        var escaped = false
        for (char in regex.pattern) {
            if (char == '/' && !escaped) append('\\')
            append(char)
            escaped = char == '\\' && !escaped
        }
    }
    val flags = buildString {
        if (RegexOption.IGNORE_CASE in regex.options) append('i')
        if (RegexOption.MULTILINE in regex.options) append('m')
        // This option exists on JVM/Native but is absent from the common enum.
        if (regex.options.any { it.name == "DOT_MATCHES_ALL" }) append('s')
    }
    return "/$source/$flags"
}

/** Quotes a value for selector engines that accept `"..."` (backslash-escaping quotes and backslashes). */
internal fun quoteSelectorValue(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
