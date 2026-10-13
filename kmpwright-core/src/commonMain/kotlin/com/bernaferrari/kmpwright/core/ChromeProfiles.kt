package com.bernaferrari.kmpwright.core

import kotlin.coroutines.cancellation.CancellationException

/** An existing Chrome profile. Launching a snapshot never writes to this source. */
public data class ChromeProfile(val directory: String, val name: String)
/** A Chrome profile to snapshot for [BrowserLaunchOptions.profileSnapshot]. */
public data class ChromeProfileSource @Throws(IllegalArgumentException::class) public constructor(val userDataDirectory: String, val profile: String) {
    init { require(userDataDirectory.isNotBlank() && profile.isNotBlank()) { "Profile source and name must not be blank" } }
}

/** Discovers local Chrome profiles (JVM only). */
public expect object ChromeProfiles {
    /** The default Chrome user data directory, or null. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun discoverUserDataDirectory(): String?
    /** Profiles in [userDataDirectory]. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun list(userDataDirectory: String): List<ChromeProfile>
    /** The profile described by [source]. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun resolve(source: ChromeProfileSource): ChromeProfile
}
