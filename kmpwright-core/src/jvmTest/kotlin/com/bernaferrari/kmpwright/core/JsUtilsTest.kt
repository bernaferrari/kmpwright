package com.bernaferrari.kmpwright.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JsUtilsTest {

    @Test
    fun `normalizeJsResult with null input returns null`() {
        assertNull(normalizeJsResult(null))
    }

    @Test
    fun `normalizeJsResult with 'null' string returns null`() {
        assertNull(normalizeJsResult("null"))
    }

    @Test
    fun `normalizeJsResult with 'undefined' returns null`() {
        assertNull(normalizeJsResult("undefined"))
    }

    @Test
    fun `normalizeJsResult with quoted string unquotes`() {
        assertEquals("hello", normalizeJsResult("\"hello\""))
    }

    @Test
    fun `normalizeJsResult handles escaped quotes`() {
        assertEquals("say \"hello\"", normalizeJsResult("\"say \\\"hello\\\"\""))
    }

    @Test
    fun `normalizeJsResult handles newlines`() {
        assertEquals("line1\nline2", normalizeJsResult("\"line1\\nline2\""))
    }

    @Test
    fun `normalizeJsResult handles tabs`() {
        assertEquals("a\tb", normalizeJsResult("\"a\\tb\""))
    }

    @Test
    fun `normalizeJsResult with plain string returns as-is`() {
        assertEquals("123", normalizeJsResult("123"))
        assertEquals("true", normalizeJsResult("true"))
    }

    @Test
    fun `normalizeJsResult with whitespace trimmed`() {
        assertNull(normalizeJsResult("  null  "))
        assertEquals("true", normalizeJsResult("  true  "))
    }

    @Test
    fun `escapeJsString escapes backslashes`() {
        assertEquals("path\\\\to\\\\file", escapeJsString("path\\to\\file"))
    }

    @Test
    fun `escapeJsString escapes single quotes`() {
        assertEquals("it\\'s", escapeJsString("it's"))
    }

    @Test
    fun `escapeJsString escapes double quotes`() {
        assertEquals("\\\"hello\\\"", escapeJsString("\"hello\""))
    }

    @Test
    fun `escapeJsString escapes newlines`() {
        assertEquals("line1\\nline2", escapeJsString("line1\nline2"))
    }

    @Test
    fun `escapeJsString escapes carriage returns`() {
        assertEquals("line1\\rline2", escapeJsString("line1\rline2"))
    }

    @Test
    fun `escapeJsString escapes tabs`() {
        assertEquals("a\\tb", escapeJsString("a\tb"))
    }

    @Test
    fun `escapeJsString escapes javascript line separators`() {
        assertEquals("line\\u2028sep\\u2029end", escapeJsString("line\u2028sep\u2029end"))
    }

    @Test
    fun `escapeJsString with empty string returns empty`() {
        assertEquals("", escapeJsString(""))
    }

    @Test
    fun `jsStringLiteral wraps escaped content in single quotes`() {
        val original = "it's \"fine\""
        assertEquals("'${escapeJsString(original)}'", jsStringLiteral(original))
    }

    @Test
    fun `escapeHtml escapes ampersand`() {
        assertEquals("a &amp; b", escapeHtml("a & b"))
    }

    @Test
    fun `escapeHtml escapes angle brackets`() {
        assertEquals("&lt;div&gt;", escapeHtml("<div>"))
    }

    @Test
    fun `escapeHtml escapes double quotes`() {
        assertEquals("&quot;attr&quot;", escapeHtml("\"attr\""))
    }

    @Test
    fun `escapeHtml escapes single quotes`() {
        assertEquals("&#x27;attr&#x27;", escapeHtml("'attr'"))
    }

    @Test
    fun `escapeHtml with no special chars returns as-is`() {
        assertEquals("hello", escapeHtml("hello"))
    }

    @Test
    fun `normalizeJsResult round-trip with escapeJsString`() {
        val original = "hello \"world\"\nnew\tline"
        val escaped = escapeJsString(original)
        val quoted = "\"$escaped\""
        val normalized = normalizeJsResult(quoted)
        assertEquals(original, normalized)
    }
}
