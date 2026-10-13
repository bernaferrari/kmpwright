package com.bernaferrari.kmpwright.core

import kotlinx.serialization.Serializable

/** Kinds of page actions an [ActionPolicy] can allow, deny or require confirmation for. */
public enum class ActionType {
    Click, Type, Press, Navigate, Fill, Screenshot, Evaluate, Scroll, Drag, Hover,
    SetCookie, DeleteCookie, SetContent, AddScriptTag, AddStyleTag,
    /** Moving keyboard focus without a pointer action. */
    Focus,
    /** Choosing options in a `<select>`. */
    Select,
    /** Reading page content: HTML, text, accessibility snapshots, cookies and storage values. */
    ReadContent,
    /** Writing or clearing localStorage / sessionStorage. */
    Storage,
    /** Reading or writing the clipboard. */
    Clipboard,
    /** Changing request headers or HTTP credentials. */
    Network,
    /** Viewport, user agent, color scheme, geolocation and network-condition emulation. */
    Emulation
}

/** A policy decision. */
public enum class PolicyResult {
    Allow, Deny, Confirm
}

/**
 * Allow/deny/confirm rules for page actions and domains (agent-browser `--action-policy`).
 *
 * [allowedDomains] and [deniedDomains] use agent-browser `--allowed-domains` matching, exactly like
 * [Page.setAllowedDomains]: `example.com` matches only that host, `*.example.com` matches
 * `example.com` and all of its subdomains. To block a site and its subdomains, deny `*.example.com`.
 */
public data class ActionPolicy(
    val defaultAction: PolicyResult = PolicyResult.Allow,
    val allowedActions: Set<ActionType> = emptySet(),
    val deniedActions: Set<ActionType> = emptySet(),
    val confirmActions: Set<ActionType> = emptySet(),
    val allowedDomains: Set<String> = emptySet(),
    val deniedDomains: Set<String> = emptySet()
) {
    /** The decision for [action] on [url]. */
    public fun check(action: ActionType, url: String?): PolicyResult {
        // 1. Check domain deny list
        val restrictionsConfigured = deniedDomains.isNotEmpty() || allowedDomains.isNotEmpty()
        val host = canonicalHttpHost(url)
        if (restrictionsConfigured && host == null) return PolicyResult.Deny
        if (host != null && deniedDomains.isNotEmpty()) {
            if (deniedDomains.any { hostMatchesDomainPattern(host, it) }) return PolicyResult.Deny
        }

        // 2. Check domain allow list (whitelist if non-empty)
        if (host != null && allowedDomains.isNotEmpty()) {
            if (allowedDomains.none { hostMatchesDomainPattern(host, it) }) return PolicyResult.Deny
        }

        // 3. Check action deny list
        if (action in deniedActions) {
            return PolicyResult.Deny
        }

        // 4. Check action confirm list
        if (action in confirmActions) {
            return PolicyResult.Confirm
        }

        // 5. Check action allow list (whitelist if non-empty)
        if (allowedActions.isNotEmpty()) {
            return if (action in allowedActions) PolicyResult.Allow else PolicyResult.Deny
        }

        // 6. Fall back to default
        return defaultAction
    }

}
