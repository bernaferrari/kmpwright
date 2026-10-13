package com.bernaferrari.kmpwright.core

import kotlin.coroutines.cancellation.CancellationException

public actual object ChromeProfiles {
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public actual suspend fun discoverUserDataDirectory(): String? = throw KmpwrightUnsupportedException("Desktop Chrome profiles are unavailable on native WebViews")
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public actual suspend fun list(userDataDirectory: String): List<ChromeProfile> = throw KmpwrightUnsupportedException("Desktop Chrome profiles are unavailable on native WebViews")
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public actual suspend fun resolve(source: ChromeProfileSource): ChromeProfile = throw KmpwrightUnsupportedException("Desktop Chrome profiles are unavailable on native WebViews")
}
