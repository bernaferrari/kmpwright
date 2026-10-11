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
    fun `regex metacharacters in literal URLs are escaped`() {
        val regex = urlGlobToRegex("https://example.com/file.json?version=1")
        assertTrue(regex.matches("https://example.com/file.jsonXversion=1"))
        assertFalse(regex.matches("https://exampleXcom/file.jsonXversion=1"))
    }

    @Test
    fun `wait patterns support both globs and substrings`() {
        assertTrue(waitUrlPatternMatches("**/dashboard", "http://localhost:3000/dashboard"))
        assertTrue(waitUrlPatternMatches("/dashboard", "http://localhost:3000/dashboard"))
        assertFalse(waitUrlPatternMatches("**/dashboard", "http://localhost:3000/settings"))
        assertTrue(waitUrlPatternMatches(".*report.*", "https://example.com/report.pdf"))
        assertTrue(routeUrlPatternToRegex(".*api\\.example\\.com.*").matches("https://api.example.com/users"))
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
