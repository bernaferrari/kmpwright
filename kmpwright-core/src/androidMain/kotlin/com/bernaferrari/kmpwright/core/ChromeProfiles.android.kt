package com.bernaferrari.kmpwright.core

actual object ChromeProfiles {
    actual suspend fun discoverUserDataDirectory(): String? = throw UnsupportedOperationException("Desktop Chrome profiles are unavailable on native WebViews")
    actual suspend fun list(userDataDirectory: String): List<ChromeProfile> = throw UnsupportedOperationException("Desktop Chrome profiles are unavailable on native WebViews")
    actual suspend fun resolve(source: ChromeProfileSource): ChromeProfile = throw UnsupportedOperationException("Desktop Chrome profiles are unavailable on native WebViews")
}
