package com.bernaferrari.kmpwright.core

/** An existing Chrome profile. Launching a snapshot never writes to this source. */
data class ChromeProfile(val directory: String, val name: String)
data class ChromeProfileSource(val userDataDirectory: String, val profile: String) {
    init { require(userDataDirectory.isNotBlank() && profile.isNotBlank()) { "Profile source and name must not be blank" } }
}

expect object ChromeProfiles {
    suspend fun discoverUserDataDirectory(): String?
    suspend fun list(userDataDirectory: String): List<ChromeProfile>
    suspend fun resolve(source: ChromeProfileSource): ChromeProfile
}
