package com.bernaferrari.kmpwright.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Session state that can be encrypted for persistence.
 * Mirrors agent-browser's named session feature with AES-256 encryption.
 */
@Serializable
internal data class SessionState(
    val name: String,
    val url: String? = null,
    val cookies: List<SessionCookie> = emptyList(),
    val localStorage: Map<String, String> = emptyMap(),
    val sessionStorage: Map<String, String> = emptyMap(),
    val timestamp: Long = currentTimeMillis()
)

@Serializable
internal data class SessionCookie(
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
 * Header of the one encrypted file format shared by every platform: `WW2:` followed by Base64 of
 * salt(16) | iv(16) | AES-256-CBC/PKCS7 ciphertext | HMAC-SHA256 tag(32), with the AES and HMAC keys
 * derived by PBKDF2-HMAC-SHA256 (210,000 rounds, 64 bytes) from the password and salt. A file written
 * on JVM, Android or iOS decrypts on all of them.
 */
internal const val ENCRYPTION_FORMAT_PREFIX: String = "WW2:"

/**
 * Platform-specific encryption/decryption of the [ENCRYPTION_FORMAT_PREFIX] format.
 */
internal expect fun platformEncrypt(plainText: String, password: String): String
internal expect fun platformDecrypt(cipherText: String, password: String): String?

/**
 * Utilities for saving and loading named browser sessions with optional encryption.
 *
 * [StorageState.save] and [StorageState.load] build on these helpers.
 */
internal object SessionManager {

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

    /**
     * Decrypts [cipherText] read from [description], explaining why it fails.
     * @throws KmpwrightException for a non-WW2 file (plaintext, or a pre-release format) or a wrong password.
     */
    fun decryptOrThrow(cipherText: String, password: String, description: String): String {
        if (!cipherText.trimStart().startsWith(ENCRYPTION_FORMAT_PREFIX)) {
            throw KmpwrightException(
                "$description is not in the KMPwright encrypted format (no $ENCRYPTION_FORMAT_PREFIX header): pass password = null " +
                    "for a plaintext file; files written by pre-release versions must be saved again"
            )
        }
        return platformDecrypt(cipherText.trim(), password)
            ?: throw KmpwrightException("Could not decrypt $description (wrong password?)")
    }
}
