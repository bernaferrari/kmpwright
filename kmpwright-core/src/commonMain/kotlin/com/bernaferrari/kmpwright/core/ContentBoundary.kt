package com.bernaferrari.kmpwright.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Wraps page content in boundary markers so LLMs can distinguish
 * trusted tool output from untrusted page content.
 *
 * Usage:
 * ```kotlin
 * val boundary = ContentBoundary.create("https://example.com")
 * val wrapped = boundary.wrap("<html>...</html>")
 * println(wrapped)
 * // --- AGENT_BROWSER_PAGE_CONTENT nonce=a1b2c3d4... origin=https://example.com ---
 * // <html>...</html>
 * // --- END_AGENT_BROWSER_PAGE_CONTENT nonce=a1b2c3d4... ---
 * ```
 */
public class ContentBoundary private constructor(
    public val nonce: String,
    public val origin: String
) {
    /**
     * Wraps content in boundary markers.
     */
    public fun wrap(content: String): String {
        return buildString {
            appendLine("--- AGENT_BROWSER_PAGE_CONTENT nonce=$nonce origin=$origin ---")
            appendLine(content)
            append("--- END_AGENT_BROWSER_PAGE_CONTENT nonce=$nonce ---")
        }
    }

    /**
     * Wraps content for a specific element selector.
     */
    public fun wrapWithSelector(selector: String, content: String): String {
        return buildString {
            appendLine("--- AGENT_BROWSER_PAGE_CONTENT nonce=$nonce origin=$origin element=$selector ---")
            appendLine(content)
            append("--- END_AGENT_BROWSER_PAGE_CONTENT nonce=$nonce ---")
        }
    }

    /**
     * Checks whether a string is bounded by this boundary.
     */
    public fun isBounded(text: String): Boolean {
        val startMarker = "--- AGENT_BROWSER_PAGE_CONTENT nonce=$nonce "
        val endMarker = "--- END_AGENT_BROWSER_PAGE_CONTENT nonce=$nonce ---"
        return text.contains(startMarker) && text.contains(endMarker)
    }

    /**
     * Extracts the content between boundary markers.
     */
    public fun unwrap(text: String): String? {
        val startMarker = "--- AGENT_BROWSER_PAGE_CONTENT nonce=$nonce "
        val startIndex = text.indexOf(startMarker)
        if (startIndex < 0) return null
        val contentStart = text.indexOf('\n', startIndex) + 1
        val endMarker = "--- END_AGENT_BROWSER_PAGE_CONTENT nonce=$nonce ---"
        val endIndex = text.indexOf(endMarker, contentStart)
        if (endIndex < 0) return null
        return text.substring(contentStart, endIndex).trimEnd()
    }

    /** Creates boundaries with fresh nonces. */
    public companion object {
        private val hexChars = "0123456789abcdef"

        /**
         * Creates a new ContentBoundary with a cryptographically random nonce.
         */
        public fun create(origin: String): ContentBoundary {
            val bytes = secureRandomBytes(16)
            val nonce = buildString(bytes.size * 2) {
                bytes.forEach { byte ->
                    val value = byte.toInt() and 0xff
                    append(hexChars[value ushr 4])
                    append(hexChars[value and 0x0f])
                }
            }
            return ContentBoundary(nonce, origin)
        }
    }
}
