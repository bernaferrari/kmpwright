package com.bernaferrari.kmpwright.core

import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Element
import com.fleeksoft.ksoup.nodes.Node
import com.fleeksoft.ksoup.nodes.TextNode

internal object ReaderContent {
    private val whitespace = Regex("\\s+")
    private val headingPattern = Regex("^(#{1,6})\\s+(.+?)\\s*#*\\s*$")
    private data class Visit(val node: Node?, val text: String = "")
    data class Heading(val line: Int, val level: Int, val text: String)

    /** Parse HTML rather than applying regex to arbitrary markup. Traversal is iterative. */
    fun html(html: String, baseUrl: String): String {
        val document = Ksoup.parse(html, baseUrl)
        document.select("script,style,noscript,svg,template").remove()
        val root = document.body()
        val result = StringBuilder()
        val tasks = ArrayDeque<Visit>().apply { addLast(Visit(root)) }
        var visited = 0
        while (tasks.isNotEmpty()) {
            if (++visited > 1_000_000 || result.length > 4 * 1024 * 1024) throw KmpwrightException("HTML extraction exceeds its content budget")
            val task = tasks.removeLast()
            val node = task.node
            if (node == null) { result.append(task.text); continue }
            if (node is TextNode) { result.append(node.getWholeText().replace(whitespace, " ")); continue }
            if (node !is Element) continue
            val tag = node.normalName()
            val (prefix, suffix) = when {
                tag.matches(Regex("h[1-6]")) -> "\n\n${"#".repeat(tag.last().digitToInt())} " to "\n\n"
                tag == "pre" -> {
                    result.append("\n\n```\n").append(node.wholeText()).append("\n```\n\n"); continue
                }
                tag == "br" -> "\n" to ""
                tag == "hr" -> "\n\n---\n\n" to ""
                tag == "li" -> "\n- " to "\n"
                tag == "blockquote" -> "\n\n> " to "\n\n"
                tag == "code" -> "`" to "`"
                tag in setOf("strong", "b") -> "**" to "**"
                tag in setOf("em", "i") -> "*" to "*"
                tag == "a" && node.hasAttr("href") -> {
                    val link = runCatching { resolveReaderUrl(baseUrl, node.attr("href")) }.getOrNull()
                    if (link != null) "[" to "]($link)" else "" to ""
                }
                tag == "img" -> { result.append(node.attr("alt")); continue }
                tag in setOf("td", "th") -> " " to " |"
                tag in setOf("p", "div", "section", "article", "main", "ul", "ol", "table", "tr") -> "\n\n" to "\n\n"
                else -> "" to ""
            }
            result.append(prefix)
            tasks.addLast(Visit(null, suffix))
            node.childNodes().asReversed().forEach { tasks.addLast(Visit(it)) }
        }
        return result.toString().replace(Regex("[ \\t]+\\n"), "\n").replace(Regex("\\n{3,}"), "\n\n").trim()
    }

    fun headings(content: String): List<Heading> {
        var fence: String? = null
        val result = mutableListOf<Heading>()
        content.lines().forEachIndexed { index, line ->
            val trimmed = line.trimStart()
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                val marker = trimmed.take(3)
                if (fence == null) fence = marker else if (fence == marker) fence = null
            } else if (fence == null) {
                headingPattern.matchEntire(line)?.let { result += Heading(index, it.groupValues[1].length, it.groupValues[2]) }
            }
        }
        return result
    }

    fun outline(content: String, filter: String?): String = headings(content)
        .filter { filter == null || it.text.contains(filter, true) }
        .joinToString("\n") { "  ".repeat(it.level - 1) + "- " + it.text }
        .ifEmpty { "No matching headings" }

    /** Keep selected headings and descendants; do not promote unrelated sibling sections. */
    fun filter(content: String, filter: String, empty: String = "No matching page sections"): String {
        val lines = content.lines()
        val headings = headings(content)
        if (headings.isEmpty()) return if (content.contains(filter, true)) content else empty
        val selected = linkedSetOf<Int>()
        for ((index, heading) in headings.withIndex()) {
            val ownEnd = headings.getOrNull(index + 1)?.line ?: lines.size
            if (heading.text.contains(filter, true) || lines.subList(heading.line, ownEnd).any { it.contains(filter, true) }) {
                val end = headings.drop(index + 1).firstOrNull { it.level <= heading.level }?.line ?: lines.size
                selected += heading.line until end
            }
        }
        return selected.joinToString("\n") { lines[it] }.trim().ifEmpty { empty }
    }
}
