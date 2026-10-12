package com.bernaferrari.kmpwright.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Session state that can be encrypted for persistence.
 * Mirrors agent-browser's named session feature with AES-256 encryption.
 */
@Serializable
data class SessionState(
    val name: String,
    val url: String? = null,
    val cookies: List<SessionCookie> = emptyList(),
    val localStorage: Map<String, String> = emptyMap(),
    val sessionStorage: Map<String, String> = emptyMap(),
    val timestamp: Long = currentTimeMillis()
)

@Serializable
data class SessionCookie(
    val name: String,
    val value: String,
    val domain: String,
    val path: String = "/",
    /** Unix epoch seconds; null denotes a session cookie. */
    val expires: Double? = null,
    val secure: Boolean = false,
    val httpOnly: Boolean = false,
    val sameSite: String? = null,
    val hostOnly: Boolean = !domain.startsWith(".")
)

/**
 * Platform-specific encryption/decryption.
 * All platforms use a versioned, password-derived authenticated-encryption format.
 */
internal expect fun platformEncrypt(plainText: String, password: String): String
internal expect fun platformDecrypt(cipherText: String, password: String): String?

/**
 * Utilities for saving and loading named browser sessions with optional encryption.
 *
 * Usage:
 * ```kotlin
 * val state = page.captureSession("login-session")
 * SessionManager.save(state)
 *
 * val loaded = SessionManager.load("login-session")
 * page.restoreSession(loaded)
 * ```
 */
object SessionManager {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    /**
     * Captures the current page state into a [SessionState].
     */
    fun create(name: String, url: String? = null): SessionState {
        return SessionState(name = name, url = url)
    }

    /**
     * Serializes a session state to JSON.
     */
    fun serialize(state: SessionState): String {
        return json.encodeToString(SessionState.serializer(), state)
    }

    /**
     * Deserializes a session state from JSON.
     */
    fun deserialize(jsonStr: String): SessionState? {
        return try {
            json.decodeFromString(SessionState.serializer(), jsonStr)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Encrypts a JSON string using password-derived authenticated encryption.
     *
     * @param plainText The JSON string to encrypt
     * @param password The required encryption password
     * @return Base64-encoded encrypted string
     */
    fun encrypt(plainText: String, password: String? = null): String {
        require(!password.isNullOrBlank()) { "An explicit non-blank password is required for encryption" }
        return platformEncrypt(plainText, password)
    }

    /**
     * Decrypts a string encrypted with [encrypt].
     *
     * @param cipherText The Base64-encoded encrypted string
     * @param password The encryption password
     * @return The original plaintext JSON string, or null on failure
     */
    fun decrypt(cipherText: String, password: String? = null): String? {
        if (password.isNullOrBlank()) return null
        return platformDecrypt(cipherText, password)
    }
}
