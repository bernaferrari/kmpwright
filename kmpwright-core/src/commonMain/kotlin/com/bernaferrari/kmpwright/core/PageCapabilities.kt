package com.bernaferrari.kmpwright.core

enum class BackendKind {
    Unknown,
    AndroidWebView,
    IOSWebView,
    JvmCdp
}

/**
 * A capability advertised by a [Page]. Keep this list in lockstep with the
 * support-level properties on [PageCapabilities]. Using a closed set makes a
 * workflow requirement reviewable and prevents stringly-typed preflight checks.
 */
enum class PageCapability {
    RouteInterception,
    TouchInput,
    MouseInput,
    KeyboardInput,
    Pdf,
    BrowserContexts,
    ServiceWorkers,
    DomainFiltering,
    NetworkEmulation,
    HarRecording,
    Downloads,
    FileChooser,
    Tracing,
    Profiling,
    Tabs,
    Cdp,
    InitScripts,
    WebMcp,
    AccessibilityAudit,
    WebVitals,
    ReactInspection,
    HttpRead,
    NetworkHistory,
    Screencast,
    VideoRecording
}

enum class SupportLevel {
    Supported,
    Partial,
    Unsupported
}

/** A minimum support level a portable workflow needs from a page. */
data class PageCapabilityRequirement(
    val capability: PageCapability,
    val minimum: SupportLevel = SupportLevel.Supported,
    val reason: String? = null
) {
    /** Descriptive alias for callers that use requirement terminology. */
    val requiredLevel: SupportLevel get() = minimum
}

/** Short alias for callers that do not need the more explicit type name. */
typealias CapabilityRequirement = PageCapabilityRequirement

/** A capability that did not satisfy a workflow's preflight requirement. */
data class MissingPageCapability(
    val requirement: PageCapabilityRequirement,
    val actual: SupportLevel
)

data class PageCapabilities(
    val backend: BackendKind = BackendKind.Unknown,
    val routeInterception: SupportLevel = SupportLevel.Unsupported,
    val touchInput: SupportLevel = SupportLevel.Unsupported,
    val mouseInput: SupportLevel = SupportLevel.Unsupported,
    val keyboardInput: SupportLevel = SupportLevel.Unsupported,
    val pdf: SupportLevel = SupportLevel.Unsupported,
    val browserContexts: SupportLevel = SupportLevel.Unsupported,
    val serviceWorkers: SupportLevel = SupportLevel.Unsupported,
    val domainFiltering: SupportLevel = SupportLevel.Unsupported,
    val networkEmulation: SupportLevel = SupportLevel.Unsupported,
    val harRecording: SupportLevel = SupportLevel.Unsupported,
    val downloads: SupportLevel = SupportLevel.Unsupported,
    val fileChooser: SupportLevel = SupportLevel.Unsupported,
    val tracing: SupportLevel = SupportLevel.Unsupported,
    val profiling: SupportLevel = SupportLevel.Unsupported,
    val tabs: SupportLevel = SupportLevel.Unsupported,
    val cdp: SupportLevel = SupportLevel.Unsupported,
    val initScripts: SupportLevel = SupportLevel.Unsupported,
    val webMcp: SupportLevel = SupportLevel.Unsupported,
    val accessibilityAudit: SupportLevel = SupportLevel.Unsupported,
    val webVitals: SupportLevel = SupportLevel.Unsupported,
    val reactInspection: SupportLevel = SupportLevel.Unsupported,
    val httpRead: SupportLevel = SupportLevel.Unsupported,
    val networkHistory: SupportLevel = SupportLevel.Unsupported,
    val screencast: SupportLevel = SupportLevel.Unsupported,
    val videoRecording: SupportLevel = SupportLevel.Unsupported,
    val notes: List<String> = emptyList()
)

/** Returns the advertised support level for a typed capability. */
fun PageCapabilities.supportLevel(capability: PageCapability): SupportLevel = when (capability) {
    PageCapability.RouteInterception -> routeInterception
    PageCapability.TouchInput -> touchInput
    PageCapability.MouseInput -> mouseInput
    PageCapability.KeyboardInput -> keyboardInput
    PageCapability.Pdf -> pdf
    PageCapability.BrowserContexts -> browserContexts
    PageCapability.ServiceWorkers -> serviceWorkers
    PageCapability.DomainFiltering -> domainFiltering
    PageCapability.NetworkEmulation -> networkEmulation
    PageCapability.HarRecording -> harRecording
    PageCapability.Downloads -> downloads
    PageCapability.FileChooser -> fileChooser
    PageCapability.Tracing -> tracing
    PageCapability.Profiling -> profiling
    PageCapability.Tabs -> tabs
    PageCapability.Cdp -> cdp
    PageCapability.InitScripts -> initScripts
    PageCapability.WebMcp -> webMcp
    PageCapability.AccessibilityAudit -> accessibilityAudit
    PageCapability.WebVitals -> webVitals
    PageCapability.ReactInspection -> reactInspection
    PageCapability.HttpRead -> httpRead
    PageCapability.NetworkHistory -> networkHistory
    PageCapability.Screencast -> screencast
    PageCapability.VideoRecording -> videoRecording
}

/** Whether a particular capability meets the requested minimum level. */
fun PageCapabilities.supports(
    capability: PageCapability,
    minimum: SupportLevel = SupportLevel.Supported
): Boolean = supportLevel(capability).meets(minimum)

/**
 * The complete advertised matrix. This is intentionally explicit: adding a
 * PageCapabilities property without adding it here is a compile-time review
 * point and makes contract tests fail loudly.
 */
fun PageCapabilities.supportLevels(): Map<PageCapability, SupportLevel> =
    PageCapability.values().associateWith(::supportLevel)

/** Requirements that this page cannot satisfy, preserving caller order. */
fun PageCapabilities.missing(
    requirements: Iterable<PageCapabilityRequirement>
): List<MissingPageCapability> = requirements.mapNotNull { requirement ->
    val actual = supportLevel(requirement.capability)
    if (actual.meets(requirement.minimum)) null else MissingPageCapability(requirement, actual)
}

/**
 * Fails before a workflow starts if any required capability is unavailable.
 * Partial support satisfies a Partial requirement, while only full support
 * satisfies the default Supported requirement.
 */
fun PageCapabilities.require(
    requirements: Iterable<PageCapabilityRequirement>
): PageCapabilities {
    val missing = missing(requirements)
    if (missing.isNotEmpty()) {
        val details = missing.joinToString(separator = "; ") { item ->
            val reason = item.requirement.reason?.let { " ($it)" }.orEmpty()
            "${item.requirement.capability}: requires ${item.requirement.minimum}, actual ${item.actual}$reason"
        }
        throw KmpwrightException("Page capability requirements are not satisfied: $details")
    }
    return this
}

fun PageCapabilities.requireCapabilities(
    vararg requirements: PageCapabilityRequirement
): PageCapabilities = require(*requirements)

fun PageCapabilities.require(
    vararg requirements: PageCapabilityRequirement
): PageCapabilities = require(requirements.asList())

/** Convenience for workflows that only need fully-supported capabilities. */
fun PageCapabilities.require(
    vararg capabilities: PageCapability
): PageCapabilities = require(capabilities.map { PageCapabilityRequirement(it) })

fun PageCapability.require(
    minimum: SupportLevel = SupportLevel.Supported,
    reason: String? = null
): PageCapabilityRequirement = PageCapabilityRequirement(this, minimum, reason)

/** Minimum-level comparison used by workflow preflight checks. */
fun SupportLevel.meets(minimum: SupportLevel): Boolean = when (minimum) {
    SupportLevel.Unsupported -> true
    SupportLevel.Partial -> this == SupportLevel.Partial || this == SupportLevel.Supported
    SupportLevel.Supported -> this == SupportLevel.Supported
}

fun PageCapabilities.supports(level: SupportLevel): Boolean =
    level == SupportLevel.Supported || level == SupportLevel.Partial

val SupportLevel.isAvailable: Boolean
    get() = this == SupportLevel.Supported || this == SupportLevel.Partial
