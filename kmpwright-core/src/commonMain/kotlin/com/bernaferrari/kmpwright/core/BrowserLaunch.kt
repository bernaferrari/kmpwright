package com.bernaferrari.kmpwright.core

import kotlin.coroutines.cancellation.CancellationException

/** Parameters of [launchBrowser] passed to the platform launcher. */
internal data class BrowserLaunchOptions(val executablePath: String, val headless: Boolean = true,
    val userDataDirectory: String? = null, val arguments: List<String> = emptyList(),
    val webGpu: Boolean = false, val webMcp: Boolean = true, val useSystemKeychain: Boolean = false,
    val ignoreHttpsErrors: Boolean = false, val extensionDirectories: List<String> = emptyList(),
    val startupTimeoutMs: Long = 30_000, val profileSnapshot: ChromeProfileSource? = null, val caCertificatePath: String? = null,
    val proxy: ProxyConfig? = null) {
    init {
        require(caCertificatePath == null || caCertificatePath.isNotBlank()) { "CA certificate path must not be blank" }
        require(caCertificatePath == null || (!ignoreHttpsErrors && profileSnapshot == null && userDataDirectory == null)) { "Isolated CA trust requires a temporary profile and certificate verification" }
        require(profileSnapshot == null || userDataDirectory == null) { "A profile snapshot and a writable profile directory are mutually exclusive" }
        require(profileSnapshot == null || arguments.none { it.startsWith("--profile-directory") }) { "The snapshot owns the selected profile directory" }
        require(extensionDirectories.none { it.isBlank() || it.contains(',') }) { "Extension paths must be non-blank and must not contain commas" }
        require(executablePath.isNotBlank()) { "Browser executable must not be blank" }
        require(userDataDirectory == null || userDataDirectory.isNotBlank()) { "Profile directory must not be blank" }
        require(startupTimeoutMs in 1_000..120_000) { "Startup timeout must be between one and 120 seconds" }
        require(proxy == null || arguments.none { it.startsWith("--proxy-server") || it.startsWith("--proxy-bypass-list") }) {
            "Set the proxy with the proxy parameter or launch arguments, not both"
        }
        require(arguments.none { it.contains('\u0000') || it.startsWith("--remote-debugging-") || it.startsWith("--user-data-dir") }) {
            "Launch arguments must preserve the owned debugging endpoint and profile directory"
        }
    }
}

/** A Chromium process started by [launchBrowser] and the [Browser] connected to it. */
public interface LaunchedBrowser {
    /** The browser connected to the launched process. */
    public val browser: Browser

    /** Closes owned pages and the process; caller-provided profile directories are preserved. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun close()
}

/**
 * Starts a local Chromium and connects to it over CDP, like Playwright's `chromium.launch()` (JVM only;
 * Android and iOS throw [KmpwrightUnsupportedException] because a WebView cannot launch a desktop browser).
 *
 * ```kotlin
 * launchBrowser(executablePath = chrome, headless = true, proxy = ProxyConfig("http://proxy:8080")).use { launched ->
 *     launched.browser.newPage().goto("https://example.com")
 * }
 * ```
 *
 * @param executablePath the Chrome or Chromium binary.
 * @param headless run without a window.
 * @param userDataDirectory a writable profile directory to use and keep; null uses a temporary profile
 *   that is deleted on close.
 * @param arguments extra Chromium command-line switches (the debugging endpoint and profile directory
 *   are owned by KMPwright).
 * @param webGpu enable WebGPU.
 * @param webMcp enable the WebMCP API (agent-browser `webmcp`).
 * @param useSystemKeychain use the OS keychain instead of a mock one (needed to read real profile cookies).
 * @param ignoreHttpsErrors accept invalid TLS certificates.
 * @param extensionDirectories unpacked extensions to load (paths must not contain commas).
 * @param startupTimeoutMs how long to wait for the browser to start (one to 120 seconds).
 * @param profileSnapshot a Chrome profile to copy into the temporary profile (exclusive with [userDataDirectory]).
 * @param caCertificatePath a PEM certificate trusted only by this browser (needs a temporary profile
 *   and certificate verification).
 * @param proxy routes the browser's traffic through a proxy, like Playwright's `launch({ proxy })`;
 *   exclusive with `--proxy-server` in [arguments].
 * @param config the configuration of the connected [Browser].
 * @throws IllegalArgumentException when the parameters conflict or are out of range.
 */
@Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
public suspend fun launchBrowser(
    executablePath: String,
    headless: Boolean = true,
    userDataDirectory: String? = null,
    arguments: List<String> = emptyList(),
    webGpu: Boolean = false,
    webMcp: Boolean = true,
    useSystemKeychain: Boolean = false,
    ignoreHttpsErrors: Boolean = false,
    extensionDirectories: List<String> = emptyList(),
    startupTimeoutMs: Long = 30_000,
    profileSnapshot: ChromeProfileSource? = null,
    caCertificatePath: String? = null,
    proxy: ProxyConfig? = null,
    config: KmpwrightConfig = KmpwrightConfig()
): LaunchedBrowser = launchPlatformBrowser(
    BrowserLaunchOptions(executablePath, headless, userDataDirectory, arguments, webGpu, webMcp, useSystemKeychain,
        ignoreHttpsErrors, extensionDirectories, startupTimeoutMs, profileSnapshot, caCertificatePath, proxy),
    config
)

internal expect suspend fun launchPlatformBrowser(options: BrowserLaunchOptions, config: KmpwrightConfig): LaunchedBrowser
