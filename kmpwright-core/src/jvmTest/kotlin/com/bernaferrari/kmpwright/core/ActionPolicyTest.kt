package com.bernaferrari.kmpwright.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionPolicyTest {

    @Test
    fun `empty policy allows all by default`() {
        val policy = ActionPolicy()
        assertEquals(PolicyResult.Allow, policy.check(ActionType.Click, "https://example.com"))
        assertEquals(PolicyResult.Allow, policy.check(ActionType.Navigate, null))
        assertEquals(PolicyResult.Allow, policy.check(ActionType.Evaluate, "https://evil.com"))
    }

    @Test
    fun `denied actions are denied`() {
        val policy = ActionPolicy(deniedActions = setOf(ActionType.Evaluate, ActionType.SetCookie))
        assertEquals(PolicyResult.Deny, policy.check(ActionType.Evaluate, "https://example.com"))
        assertEquals(PolicyResult.Deny, policy.check(ActionType.SetCookie, "https://example.com"))
        assertEquals(PolicyResult.Allow, policy.check(ActionType.Click, "https://example.com"))
    }

    @Test
    fun `confirm actions require confirmation`() {
        val policy = ActionPolicy(confirmActions = setOf(ActionType.Navigate))
        assertEquals(PolicyResult.Confirm, policy.check(ActionType.Navigate, "https://example.com"))
        assertEquals(PolicyResult.Allow, policy.check(ActionType.Click, "https://example.com"))
    }

    @Test
    fun `allowed actions whitelist blocks others`() {
        val policy = ActionPolicy(allowedActions = setOf(ActionType.Click, ActionType.Fill))
        assertEquals(PolicyResult.Allow, policy.check(ActionType.Click, "https://example.com"))
        assertEquals(PolicyResult.Allow, policy.check(ActionType.Fill, "https://example.com"))
        assertEquals(PolicyResult.Deny, policy.check(ActionType.Navigate, "https://example.com"))
        assertEquals(PolicyResult.Deny, policy.check(ActionType.Evaluate, "https://example.com"))
    }

    @Test
    fun `default action can be set to Deny`() {
        val policy = ActionPolicy(defaultAction = PolicyResult.Deny)
        assertEquals(PolicyResult.Deny, policy.check(ActionType.Click, "https://example.com"))
        assertEquals(PolicyResult.Deny, policy.check(ActionType.Navigate, null))
    }

    @Test
    fun `denied domain blocks all actions`() {
        val policy = ActionPolicy(deniedDomains = setOf("evil.com"))
        assertEquals(PolicyResult.Deny, policy.check(ActionType.Click, "https://evil.com/page"))
        // Plain entries are exact hosts (agent-browser --allowed-domains semantics).
        assertEquals(PolicyResult.Allow, policy.check(ActionType.Navigate, "http://sub.evil.com"))
        assertEquals(PolicyResult.Allow, policy.check(ActionType.Click, "https://good.com"))
        val wildcard = ActionPolicy(deniedDomains = setOf("*.evil.com"))
        assertEquals(PolicyResult.Deny, wildcard.check(ActionType.Navigate, "http://sub.evil.com"))
        assertEquals(PolicyResult.Deny, wildcard.check(ActionType.Navigate, "https://evil.com"))
    }

    @Test
    fun `allowed domains whitelist acts as filter`() {
        val policy = ActionPolicy(allowedDomains = setOf("trusted.com"))
        assertEquals(PolicyResult.Allow, policy.check(ActionType.Click, "https://trusted.com"))
        assertEquals(PolicyResult.Deny, policy.check(ActionType.Navigate, "https://sub.trusted.com"))
        assertEquals(PolicyResult.Deny, policy.check(ActionType.Click, "https://untrusted.com"))
        val wildcard = ActionPolicy(allowedDomains = setOf("*.trusted.com"))
        assertEquals(PolicyResult.Allow, wildcard.check(ActionType.Navigate, "https://sub.trusted.com"))
        assertEquals(PolicyResult.Allow, wildcard.check(ActionType.Navigate, "https://trusted.com"))
    }

    @Test
    fun `policy domains and setAllowedDomains share one matcher`() {
        val patterns = setOf("example.com", "*.api.io")
        val filter = DomainFilter(patterns)
        val policy = ActionPolicy(allowedDomains = patterns)
        for (host in listOf("example.com", "www.example.com", "api.io", "v1.api.io", "other.com", "EXAMPLE.com.")) {
            assertEquals(host, filter.isAllowed(host), policy.check(ActionType.Navigate, "https://${host.trimEnd('.')}/") == PolicyResult.Allow)
        }
    }

    @Test
    fun `domain check with null URL fails closed`() {
        val policy = ActionPolicy(
            deniedDomains = setOf("evil.com"),
            allowedDomains = setOf("trusted.com")
        )
        assertEquals(PolicyResult.Deny, policy.check(ActionType.Click, null))
    }

    @Test
    fun `domain check with invalid URL fails closed`() {
        val policy = ActionPolicy(deniedDomains = setOf("evil.com"))
        assertEquals(PolicyResult.Deny, policy.check(ActionType.Click, "not-a-url"))
    }

    @Test
    fun `domain deny takes precedence over domain allow`() {
        val policy = ActionPolicy(
            deniedDomains = setOf("evil.com"),
            allowedDomains = setOf("evil.com")
        )
        assertEquals(PolicyResult.Deny, policy.check(ActionType.Click, "https://evil.com"))
    }

    @Test
    fun `action deny takes precedence over action allow`() {
        val policy = ActionPolicy(
            allowedActions = setOf(ActionType.Click, ActionType.Navigate),
            deniedActions = setOf(ActionType.Navigate)
        )
        assertEquals(PolicyResult.Allow, policy.check(ActionType.Click, "https://example.com"))
        assertEquals(PolicyResult.Deny, policy.check(ActionType.Navigate, "https://example.com"))
    }

    @Test
    fun `action confirm takes precedence over action allow`() {
        val policy = ActionPolicy(
            allowedActions = setOf(ActionType.Navigate),
            confirmActions = setOf(ActionType.Navigate)
        )
        assertEquals(PolicyResult.Confirm, policy.check(ActionType.Navigate, "https://example.com"))
    }

    @Test
    fun `domain deny checked first, then action deny`() {
        val policy = ActionPolicy(
            deniedDomains = setOf("evil.com"),
            deniedActions = setOf(ActionType.Evaluate)
        )
        // Domain deny wins
        assertEquals(PolicyResult.Deny, policy.check(ActionType.Evaluate, "https://evil.com"))
        // Action deny still works for allowed domains
        assertEquals(PolicyResult.Deny, policy.check(ActionType.Evaluate, "https://good.com"))
        // Other actions on denied domain still denied
        assertEquals(PolicyResult.Deny, policy.check(ActionType.Click, "https://evil.com"))
    }

    @Test
    fun `domain allow checked before action lists`() {
        val policy = ActionPolicy(
            allowedDomains = setOf("good.com"),
            allowedActions = setOf(ActionType.Click)
        )
        // Good domain, allowed action
        assertEquals(PolicyResult.Allow, policy.check(ActionType.Click, "https://good.com"))
        // Good domain, non-allowed action
        assertEquals(PolicyResult.Deny, policy.check(ActionType.Navigate, "https://good.com"))
        // Bad domain, allowed action
        assertEquals(PolicyResult.Deny, policy.check(ActionType.Click, "https://bad.com"))
    }

    @Test
    fun `ActionType enum has all expected values`() {
        val actionTypes = ActionType.entries
        assertTrue(actionTypes.contains(ActionType.Click))
        assertTrue(actionTypes.contains(ActionType.Type))
        assertTrue(actionTypes.contains(ActionType.Press))
        assertTrue(actionTypes.contains(ActionType.Navigate))
        assertTrue(actionTypes.contains(ActionType.Fill))
        assertTrue(actionTypes.contains(ActionType.Screenshot))
        assertTrue(actionTypes.contains(ActionType.Evaluate))
        assertTrue(actionTypes.contains(ActionType.Scroll))
        assertTrue(actionTypes.contains(ActionType.Drag))
        assertTrue(actionTypes.contains(ActionType.Hover))
        assertTrue(actionTypes.contains(ActionType.SetCookie))
        assertTrue(actionTypes.contains(ActionType.DeleteCookie))
        assertTrue(actionTypes.contains(ActionType.SetContent))
        assertTrue(actionTypes.contains(ActionType.AddScriptTag))
        assertTrue(actionTypes.contains(ActionType.AddStyleTag))
        assertTrue(actionTypes.containsAll(listOf(ActionType.Focus, ActionType.Select, ActionType.ReadContent,
            ActionType.Storage, ActionType.Clipboard, ActionType.Network, ActionType.Emulation)))
        assertEquals(22, actionTypes.size)
    }

    @Test
    fun `PolicyResult enum has all values`() {
        val results = PolicyResult.entries
        assertTrue(results.contains(PolicyResult.Allow))
        assertTrue(results.contains(PolicyResult.Deny))
        assertTrue(results.contains(PolicyResult.Confirm))
        assertEquals(3, results.size)
    }

    @Test
    fun `combined policy check order`() {
        // Domain deny → Domain allow → Action deny → Action confirm → Action allow → Default
        val policy = ActionPolicy(
            defaultAction = PolicyResult.Deny,
            deniedDomains = setOf("blocked.com"),
            allowedDomains = setOf("safe.com"),
            deniedActions = setOf(ActionType.Evaluate),
            confirmActions = setOf(ActionType.Navigate),
            allowedActions = setOf(ActionType.Click, ActionType.Fill, ActionType.Navigate)
        )

        // Blocked domain → Deny
        assertEquals(PolicyResult.Deny, policy.check(ActionType.Click, "https://blocked.com"))
        // Safe domain, denied action → Deny
        assertEquals(PolicyResult.Deny, policy.check(ActionType.Evaluate, "https://safe.com"))
        // Safe domain, confirm action → Confirm
        assertEquals(PolicyResult.Confirm, policy.check(ActionType.Navigate, "https://safe.com"))
        // Safe domain, allowed action → Allow
        assertEquals(PolicyResult.Allow, policy.check(ActionType.Click, "https://safe.com"))
        // No domain info, no action match → Default Deny
        assertEquals(PolicyResult.Deny, policy.check(ActionType.Scroll, null))
        // Missing domain information fails closed when domain restrictions are configured.
        assertEquals(PolicyResult.Deny, policy.check(ActionType.Navigate, null))
    }
}
