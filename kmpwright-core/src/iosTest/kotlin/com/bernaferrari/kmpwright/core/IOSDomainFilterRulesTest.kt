package com.bernaferrari.kmpwright.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The WKContentRuleList source generated for a domain allowlist (no WebKit document needed). */
class IOSDomainFilterRulesTest {
    private fun rules(vararg domains: String) = Json.parseToJsonElement(IOSDomainFilterRules.encode(domains.toSet())).jsonArray.map {
        val rule = it.jsonObject
        rule.getValue("trigger").jsonObject.getValue("url-filter").jsonPrimitive.content to
            rule.getValue("action").jsonObject.getValue("type").jsonPrimitive.content
    }

    /** Applies the rules like WebKit: the last matching rule decides; ignore-previous-rules lifts earlier blocks. */
    private fun blocks(encoded: List<Pair<String, String>>, url: String): Boolean {
        var blocked = false
        encoded.forEach { (filter, action) ->
            if (Regex(filter, RegexOption.IGNORE_CASE).containsMatchIn(url)) blocked = action == "block"
        }
        return blocked
    }

    @Test
    fun network_urls_are_blocked_unless_their_host_is_allowed() {
        val encoded = rules("example.com", "*.allowed.org", "127.0.0.1")
        assertFalse(blocks(encoded, "https://example.com/path"))
        assertFalse(blocks(encoded, "http://example.com:8080/"))
        assertFalse(blocks(encoded, "wss://example.com/socket"))
        assertFalse(blocks(encoded, "http://127.0.0.1:5000/data"))
        assertFalse(blocks(encoded, "https://allowed.org/"))
        assertFalse(blocks(encoded, "https://a.b.allowed.org/x"))

        assertTrue(blocks(encoded, "https://sub.example.com/"), "exact entries do not allow subdomains")
        assertTrue(blocks(encoded, "https://example.com.evil.test/"))
        assertTrue(blocks(encoded, "https://evil.test/example.com/"))
        assertTrue(blocks(encoded, "https://notallowed.org/"))
        assertTrue(blocks(encoded, "http://localhost:5000/"))
        assertTrue(blocks(encoded, "ws://evil.test/"))
        assertTrue(blocks(encoded, "http://127.0.0.10/"))

        assertFalse(blocks(encoded, "data:text/plain,hi"))
        assertFalse(blocks(encoded, "about:blank"))
        assertFalse(blocks(encoded, "blob:https://evil.test/uuid"))
    }

    @Test
    fun content_blocker_regexes_avoid_unsupported_syntax() {
        rules("example.com", "*.allowed.org").forEach { (filter, _) ->
            assertFalse(filter.contains('|'), "WebKit content blockers have no alternation: $filter")
            assertFalse(filter.contains('{'), "WebKit content blockers have no counted repetition: $filter")
        }
        assertEquals(2, rules().size)
    }

    @Test
    fun malformed_entries_are_rejected_before_anything_changes() {
        assertFailsWith<KmpwrightException> { IOSDomainFilterRules.encode(setOf("exa mple.com")) }
        assertFailsWith<KmpwrightException> { IOSDomainFilterRules.encode(setOf("evil.test/(x")) }
        assertFailsWith<KmpwrightException> { IOSDomainFilterRules.encode(setOf("*.")) }
    }

    @Test
    fun ios_capability_notes_explain_native_and_fallback_emulation() {
        val notes = iosPageCapabilities().notes
        assertTrue(notes.any { it.contains("Geolocation emulation is a JavaScript fallback") })
        assertTrue(notes.any { it.contains("Color scheme emulation is native") })
        assertTrue(notes.any { it.contains("offline-only") })
        assertTrue(notes.any { it.contains("WKContentRuleList") })
    }
}
