package com.bernaferrari.kmpwright.core

/**
 * Browser-wide configuration: security policy, timeouts, input mode and backend options.
 *
 * [allowDomExtraction] = false blocks reading page *content*: `Locator.textContent/innerText/
 * innerHTML/inputValue/getAttribute/allTextContents/allInnerTexts`, `Page.content`, snapshots,
 * `read`, metrics, accessibility audits, response bodies and HAR capture. Structural queries
 * (`count`, `isVisible`/`isHidden`/`isEnabled`..., `boundingBox`, actionability checks, actions and
 * `expect(...).toHaveCount/toBeVisible`) keep working. Content assertions such as `toHaveText` need it.
 *
 * [viewport] and [userAgent] are applied to every page KMPwright creates on every backend (CDP
 * also applies [Viewport.deviceScaleFactor], `isMobile` and `hasTouch`); a host-provided Android
 * WebView gets [userAgent] but keeps the host's layout size.
 */
public data class KmpwrightConfig(
    val allowedOriginRules: Set<String> = emptySet(),
    val installMode: InstallMode = InstallMode.Automatic,
    val inputMode: InputMode = InputMode.Hybrid,
    val timeouts: TimeoutConfig = TimeoutConfig(),
    val debug: DebugConfig = DebugConfig(),
    val productionMode: Boolean = false,
    val strictSelectors: Boolean = true,
    val allowEval: Boolean = true,
    val allowDomExtraction: Boolean = true,
    val enableTraceExport: Boolean = true,
    val viewport: Viewport? = null,
    val userAgent: String? = null,
    val iosSchemeInterception: IosSchemeInterceptionConfig? = null,
    val cdp: CdpConfig? = null
)

/**
 * CDP (Chrome DevTools Protocol) connection configuration.
 * Enables connecting to remote/cloud browsers via WebSocket.
 */
public data class CdpConfig(
    /** Direct WebSocket URL. If set, takes precedence over provider. */
    val websocketUrl: String? = null,
    /** Cloud provider to use. Provider requires corresponding API key env var. */
    val provider: CloudProvider? = null,
    /** Connection timeout in milliseconds. */
    val connectionTimeoutMs: Long = 30_000,
    /**
     * Proxy for KMPwright's own DevTools WebSocket to a remote or cloud browser. It does not
     * change how the browser loads pages; for that, launch with `launchBrowser(proxy = ...)`.
     */
    val connectionProxy: ProxyConfig? = null,
    /**
     * Hide native scrollbars in CDP screenshots for stable image output, like Playwright's
     * headless screenshots. [launchBrowser] turns this off for headed launches.
     */
    val hideScrollbars: Boolean = true,
    /** Remote CDP uploads use server-side paths. Opt in only when both machines share these paths. */
    val remoteBrowserSharesFileSystem: Boolean = false
)

/**
 * Supported cloud browser providers.
 */
public enum class CloudProvider {
    /** https://www.browserbase.com — env: BROWSERBASE_API_KEY */
    Browserbase,
    /** https://www.browserless.io — env: BROWSERLESS_API_KEY */
    Browserless,
    /** https://www.browser-use.com — env: BROWSER_USE_API_KEY */
    BrowserUse,
    /** https://www.onkernel.com — env: KERNEL_API_KEY */
    Kernel,
    /** Auto-discover a running Chrome instance on the local machine. */
    AutoConnect
}

/**
 * An HTTP or SOCKS proxy, shaped like Playwright's `proxy` option.
 *
 * @property server proxy URL such as `http://proxy.example.com:8080` or `socks5://proxy:1080`.
 * @property bypass hosts that skip the proxy, such as `localhost` or `*.internal.example.com`.
 *
 * Proxy authentication is not supported: Chromium does not accept credentials in its proxy flags.
 * Use an unauthenticated proxy, or a local forwarding proxy that adds credentials.
 */
public data class ProxyConfig @Throws(IllegalArgumentException::class) public constructor(
    val server: String,
    val bypass: List<String> = emptyList(),
) {
    init {
        require(server.isNotBlank()) { "Proxy server must not be blank" }
        require("@" !in server.substringAfter("://")) { "Proxy credentials in the server URL are not supported" }
        require(bypass.none { it.isBlank() || ';' in it || ',' in it }) { "Proxy bypass entries must be non-blank hosts" }
    }

    /** Host of [server]. */
    internal val host: String get() = server.substringAfter("://").substringBefore("/").substringBeforeLast(":")

    /** Port of [server]; 1080 for SOCKS and 8080 otherwise when absent. */
    internal val port: Int get() = server.substringAfter("://").substringBefore("/").substringAfterLast(":", "").toIntOrNull()
        ?: if (server.startsWith("socks")) 1080 else 8080

    /** Whether [host] skips this proxy, matching `*.example.com` against subdomains. */
    internal fun bypasses(host: String): Boolean = bypass.any { entry ->
        if (entry.startsWith("*.")) host == entry.removePrefix("*.") || host.endsWith(entry.removePrefix("*"))
        else host.equals(entry, ignoreCase = true)
    }
}

/** Custom URL scheme used to route iOS document requests. */
public data class IosSchemeInterceptionConfig(
    val scheme: String = "kmpwright",
    val fallbackScheme: String = "https"
)

/** Whether KMPwright may install its sealed in-page runtime (selector engines, actionability, refs). */
public enum class InstallMode {
    /** Installed at document start where the platform allows it, and on demand otherwise. */
    Automatic,
    /** Never installed: locators, `getBy*`, snapshots and other runtime-backed APIs throw. */
    Disabled
}

/** How actions are delivered: page JavaScript only, touch only, or native input where available. */
public enum class InputMode {
    JsOnly,
    TouchOnly,
    Hybrid
}

/**
 * Default timeouts. Per-call options left as null use these: [navigation] for goto/reload/back/
 * forward/setContent/waitForNavigation, [action] for locator actions, [waitForSelector] for
 * expectations, and [default] for script evaluation, screenshots, PDF and other page operations.
 *
 * [handler] bounds user callbacks that block the page, identically on every backend: a
 * [Page.onDialog] handler that does not answer in time dismisses the dialog, and a [Page.route]
 * handler that does not decide in time aborts the request with `timedout`. Playwright has no such
 * bound; KMPwright keeps one because a stuck handler would otherwise freeze the page (and, on
 * Android, a WebView network thread).
 */
public data class TimeoutConfig @Throws(IllegalArgumentException::class) public constructor(
    val default: Long = 30_000,
    val navigation: Long = 30_000,
    val action: Long = 30_000,
    val waitForSelector: Long = 30_000,
    val handler: Long = 30_000
) {
    init {
        require(handler > 0) { "TimeoutConfig.handler must be positive" }
    }
}

/** Debug switches. Only [enabled] is enforced by the core (it is rejected in production mode); the other flags are hints for UI integrations. */
public data class DebugConfig(
    val enabled: Boolean = false,
    val showHighlight: Boolean = false,
    val logJsCommands: Boolean = false,
    val exportTraceOnFailure: Boolean = true,
    val showDebugOverlay: Boolean = true
)

internal interface WebSettingsConfig {
    var javaScriptEnabled: Boolean
    var domStorageEnabled: Boolean
    var allowFileAccess: Boolean
    var allowContentAccess: Boolean
    var mixedContentMode: Int
    var blockNetworkLoads: Boolean
    var loadsImagesAutomatically: Boolean
    var cacheMode: Int
    var allowUniversalAccessFromFileURLs: Boolean
    var allowFileAccessFromFileURLs: Boolean
    var geolocationEnabled: Boolean
}

internal object DefaultWebSettings : WebSettingsConfig {
    override var javaScriptEnabled: Boolean = true
    override var domStorageEnabled: Boolean = true
    override var allowFileAccess: Boolean = false
    override var allowContentAccess: Boolean = false
    override var mixedContentMode: Int = 0
    override var blockNetworkLoads: Boolean = false
    override var loadsImagesAutomatically: Boolean = true
    override var cacheMode: Int = 0
    override var allowUniversalAccessFromFileURLs: Boolean = false
    override var allowFileAccessFromFileURLs: Boolean = false
    override var geolocationEnabled: Boolean = false
}
