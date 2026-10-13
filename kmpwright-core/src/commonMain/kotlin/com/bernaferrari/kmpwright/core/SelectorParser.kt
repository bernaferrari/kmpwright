package com.bernaferrari.kmpwright.core

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
