package com.bernaferrari.kmpwright.core

/** Host Chromium process ownership. Native WebViews cannot launch a desktop browser. */
data class BrowserLaunchOptions(val executablePath: String, val headless: Boolean = true,
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

interface LaunchedBrowser {
    val browser: Browser
    /** Close owned pages and the process; preserve caller-provided profile directories. */
    suspend fun closeAsync()
}

expect suspend fun launchBrowser(options: BrowserLaunchOptions, config: KmpwrightConfig = KmpwrightConfig()): LaunchedBrowser
