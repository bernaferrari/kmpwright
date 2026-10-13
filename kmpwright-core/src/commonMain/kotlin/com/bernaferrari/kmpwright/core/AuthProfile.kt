package com.bernaferrari.kmpwright.core

import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlin.coroutines.cancellation.CancellationException

/**
 * A saved authentication profile with credentials and optional auto-login selectors.
 */
@Serializable
public data class AuthProfile(
    val name: String,
    val url: String,
    val username: String,
    val password: String,
    val usernameSelector: String? = null,
    val passwordSelector: String? = null,
    val submitSelector: String? = null,
    val createdAt: Long = currentTimeMillis(),
    val lastUsedAt: Long? = null
) {
    /** Never prints the password. */
    override fun toString(): String =
        "AuthProfile(name=$name, url=$url, username=$username, password=***, usernameSelector=$usernameSelector, " +
            "passwordSelector=$passwordSelector, submitSelector=$submitSelector, createdAt=$createdAt, lastUsedAt=$lastUsedAt)"
}

/**
 * Manages persistent auth profiles on disk with the same rules as [StorageState]: every call is
 * suspending and runs on the IO dispatcher, [save] takes an explicit password (null writes
 * plaintext on purpose), and [load] throws instead of returning null.
 */
public object AuthManager {

    private val json = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    /** Test seam: replaces the platform profile directory. */
    internal var directoryOverride: String? = null
    private fun directory(): String = directoryOverride ?: authProfileDir()
    private fun profilePath(name: String): String = "${directory()}/${sanitizeStateFileName(name)}.json"

    /**
     * Saves [profile]. Profiles contain credentials, so they are encrypted with [password]; pass
     * `password = null` explicitly to write plaintext (the same rule as [StorageState.save]).
     *
     * @throws IllegalArgumentException when [password] is blank.
     */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun save(profile: AuthProfile, password: String?) {
        require(password == null || password.isNotBlank()) { "Password must not be blank; pass null to write plaintext" }
        withContext(fileDispatcher) {
            mkdirs(directory())
            val serialized = json.encodeToString(AuthProfile.serializer(), profile)
            fileWrite(profilePath(profile.name), if (password != null) SessionManager.encrypt(serialized, password) else serialized)
        }
    }

    /**
     * Loads the profile [name]. A [password] makes this an authenticated decryption that never falls
     * back to plaintext; pass null to read a plaintext profile.
     *
     * @throws KmpwrightException when the profile is missing, cannot be decrypted (wrong password) or is malformed.
     */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun load(name: String, password: String?): AuthProfile = withContext(fileDispatcher) {
        val raw = fileRead(profilePath(name)) ?: throw KmpwrightException("Auth profile not found: $name")
        val content = if (password == null) raw else SessionManager.decryptOrThrow(raw, password, "Auth profile $name")
        try {
            json.decodeFromString(AuthProfile.serializer(), content)
        } catch (error: Exception) {
            throw KmpwrightException("Auth profile $name is not valid${if (password == null) " plaintext; pass its password" else ""}", error)
        }
    }

    /** Names of the saved profiles. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun list(): List<String> = withContext(fileDispatcher) {
        fileList(directory()).filter { it.endsWith(".json") }.map { it.removeSuffix(".json") }
    }

    /** Deletes the profile [name]; true when it existed. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun delete(name: String): Boolean = withContext(fileDispatcher) { fileDelete(profilePath(name)) }
}

/**
 * Returns the directory where auth profiles are stored. Platform-specific.
 */
internal expect fun authProfileDir(): String
