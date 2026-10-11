package com.bernaferrari.kmpwright.core

object SelectorParser {
    private val roleToCssMap = mapOf(
        "button" to "button,[role=\"button\"],input[type=\"button\"],input[type=\"submit\"],input[type=\"reset\"]",
        "link" to "a,[role=\"link\"]",
        "heading" to "h1,h2,h3,h4,h5,h6,[role=\"heading\"]",
        "img" to "img,[role=\"img\"]",
        "input" to "input,textarea,select,[role=\"textbox\"],[role=\"combobox\"]",
        "checkbox" to "input[type=\"checkbox\"],[role=\"checkbox\"]",
        "radio" to "input[type=\"radio\"],[role=\"radio\"]",
        "dialog" to "dialog,[role=\"dialog\"]",
        "list" to "ul,ol,dl,[role=\"list\"]",
        "listitem" to "li,[role=\"listitem\"]",
        "navigation" to "nav,[role=\"navigation\"]",
        "table" to "table,[role=\"table\"]",
        "textbox" to "input[type=\"text\"],textarea,[role=\"textbox\"]"
    )

    fun parse(selector: String): ParsedSelector {
        return when {
            selector.startsWith("role=") -> parseRoleSelector(selector)
            selector.startsWith("label=") -> parseLabelSelector(selector)
            selector.startsWith("text=") || selector.startsWith("text:") -> parseTextSelector(selector)
            selector.startsWith("exact_text=") -> parseExactTextSelector(selector)
            selector.startsWith("alt=") -> parseAltSelector(selector)
            selector.startsWith("testid=") -> parseTestIdSelector(selector)
            selector.startsWith("title=") -> parseTitleSelector(selector)
            selector.startsWith("xpath=") -> parseXPathSelector(selector)
            selector.contains(">>") -> parseChainSelector(selector)
            else -> ParsedSelector(selector, SelectorType.CSS)
        }
    }

    private fun parseXPathSelector(selector: String): ParsedSelector {
        val xpath = selector.removePrefix("xpath=")
        return ParsedSelector(xpath, SelectorType.XPATH, selector)
    }

    private fun parseRoleSelector(selector: String): ParsedSelector {
        val role = selector.removePrefix("role=").lowercase().replace('_', '-')
        val css = roleToCssMap[role] ?: "[role=\"$role\"]"
        return ParsedSelector(css, SelectorType.ROLE, role)
    }

    private fun parseLabelSelector(selector: String): ParsedSelector {
        val label = selector.removePrefix("label=")
        val css = "label:has-text(\"$label\"), [aria-label=\"$label\"]"
        return ParsedSelector(css, SelectorType.LABEL, label)
    }

    private fun parseTextSelector(selector: String): ParsedSelector {
        val text = selector.removePrefix("text=").removePrefix("text:")
        return ParsedSelector(text, SelectorType.TEXT, text)
    }

    private fun parseExactTextSelector(selector: String): ParsedSelector {
        val text = selector.removePrefix("exact_text=")
        return ParsedSelector(text, SelectorType.TEXT, text)
    }

    private fun parseAltSelector(selector: String): ParsedSelector {
        val alt = selector.removePrefix("alt=")
        return ParsedSelector("img[alt=\"$alt\"],[role=\"img\"][aria-label=\"$alt\"]", SelectorType.CSS, selector)
    }

    private fun parseTestIdSelector(selector: String): ParsedSelector {
        val id = selector.removePrefix("testid=")
        return ParsedSelector("[data-testid=\"$id\"]", SelectorType.CSS, selector)
    }

    private fun parseTitleSelector(selector: String): ParsedSelector {
        val title = selector.removePrefix("title=")
        return ParsedSelector("[title=\"$title\"]", SelectorType.CSS, selector)
    }

    private fun parseChainSelector(selector: String): ParsedSelector {
        return ParsedSelector(selector, SelectorType.CHAIN)
    }
}

data class ParsedSelector(
    val css: String,
    val type: SelectorType,
    val original: String = ""
)

enum class SelectorType {
    CSS,
    ROLE,
    LABEL,
    TEXT,
    CHAIN,
    XPATH
}

object Selectors {
    const val ARIA = "aria/"
    const val DATA_TEST_ID = "[data-testid=\""
    const val DATA_TEST_ID_END = "\"]"
    const val DATA_TEST_NAME = "[data-test-name=\""
    const val DATA_TEST_NAME_END = "\"]"
    const val PLACEHOLDER = "[placeholder=\""
    const val PLACEHOLDER_END = "\"]"
}

/**
 * Parses element reference strings into canonical `@eN` form.
 *
 * Accepts formats: `@e1`, `e1`, `ref=e1`, `ref=@e5`.
 * Returns null for invalid inputs.
 */
internal fun parseRef(input: String): String? {
    val value = input.trim().removePrefix("ref=").trim().removePrefix("@")
    return if (value.matches(Regex("e[0-9]+"))) "@$value" else null
}
