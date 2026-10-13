package com.bernaferrari.kmpwright.core

import kotlin.coroutines.cancellation.CancellationException

/** Host Chromium process ownership. Native WebViews cannot launch a desktop browser. */
/** Options for [launchBrowser], which starts a local Chromium (JVM only) and connects over CDP. */
public data class BrowserLaunchOptions @Throws(IllegalArgumentException::class) public constructor(val executablePath: String, val headless: Boolean = true,
    val userDataDirectory: String? = null, val arguments: List<String> = emptyList(),
    val webGpu: Boolean = false, val webMcp: Boolean = true, val useSystemKeychain: Boolean = false,
    val ignoreHttpsErrors: Boolean = false, val extensionDirectories: List<String> = emptyList(),
    val startupTimeoutMs: Long = 30_000, val profileSnapshot: ChromeProfileSource? = null, val caCertificatePath: String? = null) {
    init {
        require(caCertificatePath == null || caCertificatePath.isNotBlank()) { "CA certificate path must not be blank" }
        require(caCertificatePath == null || (!ignoreHttpsErrors && profileSnapshot == null && userDataDirectory == null)) { "Isolated CA trust requires a temporary profile and certificate verification" }
        require(profileSnapshot == null || userDataDirectory == null) { "A profile snapshot and a writable profile directory are mutually exclusive" }
        require(profileSnapshot == null || arguments.none { it.startsWith("--profile-directory") }) { "The snapshot owns the selected profile directory" }
        require(extensionDirectories.none { it.isBlank() || it.contains(',') }) { "Extension paths must be non-blank and must not contain commas" }
        require(executablePath.isNotBlank()) { "Browser executable must not be blank" }
        require(userDataDirectory == null || userDataDirectory.isNotBlank()) { "Profile directory must not be blank" }
        require(startupTimeoutMs in 1_000..120_000) { "Startup timeout must be between one and 120 seconds" }
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

/** Starts a local Chromium and connects to it, like Playwright's `chromium.launch()` (JVM only). */
@Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
public expect suspend fun launchBrowser(options: BrowserLaunchOptions, config: KmpwrightConfig = KmpwrightConfig()): LaunchedBrowser
