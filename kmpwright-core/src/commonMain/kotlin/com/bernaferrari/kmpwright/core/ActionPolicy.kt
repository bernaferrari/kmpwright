package com.bernaferrari.kmpwright.core

import kotlinx.serialization.Serializable

enum class ActionType {
    Click, Type, Press, Navigate, Fill, Screenshot, Evaluate, Scroll, Drag, Hover,
    SetCookie, DeleteCookie, SetContent, AddScriptTag, AddStyleTag
}

enum class PolicyResult {
    Allow, Deny, Confirm
}

data class ActionPolicy(
    val defaultAction: PolicyResult = PolicyResult.Allow,
    val allowedActions: Set<ActionType> = emptySet(),
    val deniedActions: Set<ActionType> = emptySet(),
    val confirmActions: Set<ActionType> = emptySet(),
    val allowedDomains: Set<String> = emptySet(),
    val deniedDomains: Set<String> = emptySet()
) {
    fun check(action: ActionType, url: String?): PolicyResult {
        // 1. Check domain deny list
        val restrictionsConfigured = deniedDomains.isNotEmpty() || allowedDomains.isNotEmpty()
        val host = canonicalHttpHost(url)
        if (restrictionsConfigured && host == null) return PolicyResult.Deny
        if (host != null && deniedDomains.isNotEmpty()) {
            for (denied in deniedDomains) {
                val normalizedDenied = denied.trim().lowercase().trimEnd('.')
                if (host == normalizedDenied || host.endsWith(".$normalizedDenied")) {
                    return PolicyResult.Deny
                }
            }
        }

        // 2. Check domain allow list (whitelist if non-empty)
        if (host != null && allowedDomains.isNotEmpty()) {
            var domainAllowed = false
            for (allowed in allowedDomains) {
                val normalizedAllowed = allowed.trim().lowercase().trimEnd('.')
                if (host == normalizedAllowed || host.endsWith(".$normalizedAllowed")) {
                    domainAllowed = true
                    break
                }
            }
            if (!domainAllowed) {
                return PolicyResult.Deny
            }
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
