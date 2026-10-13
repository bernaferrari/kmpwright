package com.bernaferrari.kmpwright.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlPatternTest {
    @Test
    fun `double star crosses path separators`() {
        val regex = urlGlobToRegex("https://api.example.com/**")
        assertTrue(regex.matches("https://api.example.com/users/42"))
        assertTrue(regex.matches("https://api.example.com/"))
        assertFalse(regex.matches("https://other.example.com/users/42"))
    }

    @Test
    fun `single star remains within one path segment`() {
        val regex = urlGlobToRegex("https://example.com/users/*")
        assertTrue(regex.matches("https://example.com/users/42"))
        assertFalse(regex.matches("https://example.com/users/42/settings"))
    }

    @Test
    fun `question mark is a literal like in Playwright`() {
        val regex = urlGlobToRegex("https://example.com/file.json?version=1")
        assertTrue(regex.matches("https://example.com/file.json?version=1"))
        assertFalse(regex.matches("https://example.com/file.jsonXversion=1"))
        assertFalse(regex.matches("https://exampleXcom/file.json?version=1"))
    }

    @Test
    fun `plain strings are exact URL matches and query strings keep matching`() {
        assertTrue(urlMatches("http://localhost:3000/api/items?page=2", "http://localhost:3000/api/items?page=2"))
        assertFalse(urlMatches("/api/items?page=2", "http://localhost:3000/api/items?page=2"))
        assertTrue(urlMatches("**/api/items?page=2", "http://localhost:3000/api/items?page=2"))
        assertFalse(urlMatches("**/api/items?page=2", "http://localhost:3000/api/items?page=3"))
        assertFalse(urlMatches("/dashboard", "http://localhost:3000/dashboard"))
        assertTrue(urlMatches("**/dashboard", "http://localhost:3000/dashboard"))
        assertFalse(urlMatches("**/dashboard", "http://localhost:3000/settings"))
        assertTrue(urlMatches("regex:report\\.pdf$", "https://example.com/report.pdf"))
        assertTrue(urlMatches("", "https://anything.example/"))
    }

    @Test
    fun `brace alternatives and star semantics follow Playwright globToRegex`() {
        assertTrue(urlMatches("**/*.{png,jpg}", "https://cdn.example.com/a/b/c.png"))
        assertTrue(urlMatches("**/*.{png,jpg}", "https://cdn.example.com/c.jpg"))
        assertFalse(urlMatches("**/*.{png,jpg}", "https://cdn.example.com/c.gif"))
        assertTrue(urlMatches("https://example.com/*/settings", "https://example.com/users/settings"))
        assertFalse(urlMatches("https://example.com/*/settings", "https://example.com/a/b/settings"))
        assertTrue(urlMatches("https://example.com/**/settings", "https://example.com/a/b/settings"))
        assertTrue(urlMatches("https://example.com/**/settings", "https://example.com/settings"))
        assertTrue(urlMatches("**/a,b", "https://example.com/a,b"))
        assertTrue(urlMatches("https://example.com/\\*literal", "https://example.com/*literal"))
        assertFalse(urlMatches("https://example.com/\\*literal", "https://example.com/xliteral"))
    }

    @Test
    fun `absolute patterns are normalised like parsed URLs`() {
        assertTrue(urlMatches("https://Example.COM", "https://example.com/"))
        assertTrue(urlMatches("https://example.com:443/path", "https://example.com/path"))
        assertTrue(urlMatches("http://localhost:3000", "http://localhost:3000/"))
        assertFalse(urlMatches("http://localhost:3000", "http://localhost:3000/other"))
    }

    @Test
    fun `route strings are always globs even when they contain regex-looking dots`() {
        val bundle = urlGlobToRegex("**/bundle.*")
        assertTrue(bundle.matches("https://cdn.example.com/static/bundle.js"))
        assertFalse(bundle.matches("https://cdn.example.com/static/bundleXjs"))
        assertTrue(waitUrlPatternMatches("**/bundle.*", "https://cdn.example.com/bundle.css"))
    }

    @Test
    fun `invalid explicit regular expressions are reported as KmpwrightException`() {
        org.junit.Assert.assertThrows(KmpwrightException::class.java) { waitUrlPatternMatches("regex:([", "https://example.com") }
    }

    @Test
    fun `origin rules compare canonical scheme host and default port`() {
        assertTrue(originRuleMatches("https://example.com:443", "HTTPS://Example.COM/path"))
        assertFalse(originRuleMatches("https://example.com", "http://example.com/path"))
        assertFalse(originRuleMatches("https://example.com", "https://user:pass@example.com/path"))
        assertFalse(originRuleMatches("https://example.com", "not-a-url"))
    }

    @Test
    fun `origin wildcard matches subdomains without treating star as a literal host`() {
        assertTrue(originRuleMatches("https://*.example.com", "https://api.example.com/path"))
        assertFalse(originRuleMatches("https://*.example.com", "https://example.com/path"))
        assertFalse(originRuleMatches("https://*.example.com", "https://api.example.net/path"))
    }
}
