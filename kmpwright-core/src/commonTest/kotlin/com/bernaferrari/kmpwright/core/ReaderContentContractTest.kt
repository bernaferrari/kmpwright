package com.bernaferrari.kmpwright.core

import kotlin.test.*

class ReaderContentContractTest {
    @Test fun html_uses_real_markup_and_decodes_entities() {
        val result = ReaderContent.html("""<html><head><title>Ignored</title><style>.x{}</style></head><body>
            <h1>Guide &amp; setup</h1><p>A &lt; B <a href="../start">link</a></p>
            <script>secret()</script><pre>line 1
line 2</pre><ul><li>First</li></ul></body></html>""", "https://example.com/docs/page")
        assertTrue(result.contains("# Guide & setup"))
        assertTrue(result.contains("A < B"))
        assertTrue(result.contains("[link](https://example.com/start)"))
        assertTrue(result.contains("```\nline 1\nline 2\n```"))
        assertTrue(result.contains("- First"))
        assertFalse(result.contains("secret"))
        assertFalse(result.contains("Ignored"))
    }

    @Test fun headings_inside_code_fences_are_not_sections() {
        val markdown = "# Guide\n```kotlin\n# Fake\n```\n## Setup\nSteps\n### Details\nMore\n## Other\nElse"
        assertEquals(listOf("Guide", "Setup", "Details", "Other"), ReaderContent.headings(markdown).map { it.text })
        assertEquals("  - Setup", ReaderContent.outline(markdown, "setup"))
        val filtered = ReaderContent.filter(markdown, "Setup")
        assertTrue(filtered.contains("### Details"))
        assertFalse(filtered.contains("## Other"))
    }

    @Test fun reader_urls_resolve_relative_paths_and_reject_credentials() {
        assertEquals("https://example.com/start?q=1", resolveReaderUrl("https://example.com/docs/page", "../start?q=1#anchor"))
        assertEquals("https://example.com/", normalizeReaderUrl("example.com"))
        assertEquals("https://example.com/docs/page?q=2", resolveReaderUrl("https://example.com/docs/page?q=1", "?q=2"))
        assertEquals("https://example.com/docs/page?q=1", resolveReaderUrl("https://example.com/docs/page?q=1", "#part"))
        assertEquals("https://cdn.example.com/asset", resolveReaderUrl("https://example.com/docs/page", "//cdn.example.com/asset"))
        assertEquals("https://example.com/next/", resolveReaderUrl("https://example.com/docs/page", "/next/end/.."))
        assertEquals("https://example.com/start", resolveReaderUrl("https://example.com/docs/page", "%2e%2e/start"))
        for (url in listOf("file:///etc/passwd", "https://user:secret@example.com/", "https://example.com/\u0000")) {
            assertFailsWith<KmpwrightException> { normalizeReaderUrl(url) }
        }
    }
}
