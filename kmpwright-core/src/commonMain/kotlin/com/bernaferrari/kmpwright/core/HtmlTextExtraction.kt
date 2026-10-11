package com.bernaferrari.kmpwright.core

internal fun htmlToSnapshotText(html: String): String {
    if (html.isBlank()) return ""

    val withoutScripts = html
        .replace(Regex("(?is)<script\\b.*?</script>"), " ")
        .replace(Regex("(?is)<style\\b.*?</style>"), " ")
        .replace(Regex("(?is)<!--.*?-->"), " ")

    val withBreaks = withoutScripts
        .replace(Regex("(?i)</?(p|div|section|article|main|aside|header|footer|nav|ul|ol|li|table|tr|td|th|h[1-6]|br)\\b[^>]*>"), "\n")
        .replace(Regex("(?is)<[^>]+>"), " ")

    return decodeHtmlEntities(withBreaks)
        .lines()
        .map { it.replace(Regex("\\s+"), " ").trim() }
        .filter { it.isNotEmpty() }
        .joinToString("\n") { line ->
            val clipped = if (line.length > 100) line.take(100) else line
            "[line] text \"$clipped\""
        }
}

private fun decodeHtmlEntities(input: String): String {
    val named = input
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")

    return Regex("&#(x?[0-9A-Fa-f]+);").replace(named) { match ->
        val raw = match.groupValues[1]
        val codePoint = if (raw.startsWith("x", ignoreCase = true)) {
            raw.drop(1).toIntOrNull(16)
        } else {
            raw.toIntOrNull()
        }
        codePoint?.toChar()?.toString() ?: match.value
    }
}
