package com.bernaferrari.kmpwright.core

data class KmpwrightConfig(
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
data class CdpConfig(
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
    /** Stealth mode (anti-detection). */
    val stealth: Boolean = false
)

/**
 * Supported cloud browser providers.
 */
enum class CloudProvider {
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
data class ProxyConfig(
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
}

data class IosSchemeInterceptionConfig(
    val scheme: String = "kmpwright",
    val fallbackScheme: String = "https"
)

enum class InstallMode {
    DocumentStartPreferred,
    AlwaysEvaluate,
    Disabled
}

enum class InputMode {
    JsOnly,
    TouchOnly,
    Hybrid
}

data class TimeoutConfig(
    val default: Long = 30_000,
    val navigation: Long = 30_000,
    val action: Long = 30_000,
    val waitForSelector: Long = 30_000
)

data class DebugConfig(
    val enabled: Boolean = false,
    val showHighlight: Boolean = false,
    val logJsCommands: Boolean = false,
    val exportTraceOnFailure: Boolean = true,
    val showDebugOverlay: Boolean = true
)

interface WebSettingsConfig {
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

object DefaultWebSettings : WebSettingsConfig {
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
