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
    val installMode: InstallMode = InstallMode.DocumentStartPreferred,
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
    val cdp: CdpConfig? = null,
    val proxy: ProxyConfig? = null
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
    /** Browser launch arguments (Chrome flags). */
    val browserArgs: List<String> = emptyList(),
    /** Headless mode. */
    val headless: Boolean = true,
    /** Hide native scrollbars in headless CDP screenshots for stable image output. */
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
 * HTTP/HTTPS proxy configuration for CDP connections.
 */
public data class ProxyConfig(
    /** Proxy URL, e.g. "http://proxy.example.com:8080" or "socks5://proxy:1080" */
    val url: String,
    /** Bypass list — domains that should not use the proxy. */
    val bypass: List<String> = emptyList(),
    /** Optional username for proxy authentication. */
    val username: String? = null,
    /** Optional password for proxy authentication. */
    val password: String? = null
) {
    /** Extracts the host portion of the proxy URL. Returns the full URL if no scheme found. */
    val host: String get() = if ("://" in url) url.substringAfter("://").substringBefore(":") else url.substringBefore(":")

    /** Extracts the port portion of the proxy URL, defaulting to 8080. */
    val port: Int get() {
        val afterScheme = if ("://" in url) url.substringAfter("://") else url
        val withPort = afterScheme.substringBefore("/")
        return withPort.substringAfter(":").toIntOrNull() ?: 8080
    }

    /** Extracts the scheme portion of the proxy URL (e.g. "http", "socks5"). Returns "http" if no scheme found. */
    val scheme: String get() = if ("://" in url) url.substringBefore("://") else "http"

    /** Never prints the password (or credentials embedded in the URL). */
    override fun toString(): String {
        val safeUrl = if ("@" in url.substringAfter("://")) url.substringBefore("://") + "://***@" + url.substringAfter("@") else url
        return "ProxyConfig(url=$safeUrl, bypass=$bypass, username=$username, password=${if (password == null) "null" else "***"})"
    }
}

/** Custom URL scheme used to route iOS document requests. */
public data class IosSchemeInterceptionConfig(
    val scheme: String = "kmpwright",
    val fallbackScheme: String = "https"
)

/** When the in-page runtime is installed. */
public enum class InstallMode {
    DocumentStartPreferred,
    AlwaysEvaluate,
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
