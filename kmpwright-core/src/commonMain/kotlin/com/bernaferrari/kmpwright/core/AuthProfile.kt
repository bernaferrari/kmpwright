package com.bernaferrari.kmpwright.core

import kotlinx.serialization.Serializable

/**
 * A saved authentication profile with credentials and optional auto-login selectors.
 */
@Serializable
data class AuthProfile(
    val name: String,
    val url: String,
    val username: String,
    val password: String,
    val usernameSelector: String? = null,
    val passwordSelector: String? = null,
    val submitSelector: String? = null,
    val createdAt: Long = currentTimeMillis(),
    val lastUsedAt: Long? = null
)

/**
 * Manages persistent encrypted auth profiles on disk.
 * Platform-specific file I/O is delegated to expect/actual functions.
 */
object AuthManager {

    private val json = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    fun save(profile: AuthProfile, encrypt: Boolean = true, password: String? = null) {
        val dir = authProfileDir()
        mkdirs(dir)
        val serialized = json.encodeToString(AuthProfile.serializer(), profile)
        val content = if (encrypt) SessionManager.encrypt(serialized, password) else serialized
        val safeName = sanitizeFileName(profile.name)
        fileWrite("$dir/$safeName.json", content)
    }

    fun load(name: String, password: String? = null): AuthProfile? {
        val dir = authProfileDir()
        val safeName = sanitizeFileName(name)
        val raw = fileRead("$dir/$safeName.json") ?: return null
        val content = if (password != null) {
            // When a password is provided, require successful decryption
            SessionManager.decrypt(raw, password) ?: return null
        } else {
            raw
        }
        return try {
            json.decodeFromString(AuthProfile.serializer(), content)
        } catch (_: Exception) {
            null
        }
    }

    fun list(): List<String> {
        val dir = authProfileDir()
        return fileList(dir).filter { it.endsWith(".json") }.map { it.removeSuffix(".json") }
    }

    fun delete(name: String): Boolean {
        val dir = authProfileDir()
        val safeName = sanitizeFileName(name)
        return fileDelete("$dir/$safeName.json")
    }
}

/**
 * Returns the directory where auth profiles are stored. Platform-specific.
 */
internal expect fun authProfileDir(): String

/**
 * Sanitizes a file name to prevent path traversal attacks.
 */
private fun sanitizeFileName(name: String): String {
    return name.replace("\\", "").replace("/", "").replace("..", "").ifEmpty { "unnamed" }
}
