package com.bernaferrari.kmpwright.core

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

private const val FORMAT_PREFIX = "WW2:"
private const val SALT_SIZE = 16
private const val IV_SIZE = 16
private const val TAG_SIZE = 32
private const val PBKDF2_ITERATIONS = 210_000

internal actual fun platformEncrypt(plainText: String, password: String): String {
    val salt = ByteArray(SALT_SIZE).also(SecureRandom()::nextBytes)
    val iv = ByteArray(IV_SIZE).also(SecureRandom()::nextBytes)
    val keys = deriveKeys(password, salt)
    val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
    cipher.init(Cipher.ENCRYPT_MODE, keys.first, IvParameterSpec(iv))
    val encrypted = cipher.doFinal(plainText.toByteArray(StandardCharsets.UTF_8))
    val authenticated = salt + iv + encrypted
    return FORMAT_PREFIX + android.util.Base64.encodeToString(authenticated + hmac(keys.second, authenticated), android.util.Base64.NO_WRAP)
}

internal actual fun platformDecrypt(cipherText: String, password: String): String? = runCatching {
    if (cipherText.startsWith(FORMAT_PREFIX)) {
        val combined = android.util.Base64.decode(cipherText.removePrefix(FORMAT_PREFIX), android.util.Base64.NO_WRAP)
        require(combined.size > SALT_SIZE + IV_SIZE + TAG_SIZE)
        val salt = combined.copyOfRange(0, SALT_SIZE)
        val iv = combined.copyOfRange(SALT_SIZE, SALT_SIZE + IV_SIZE)
        val encrypted = combined.copyOfRange(SALT_SIZE + IV_SIZE, combined.size - TAG_SIZE)
        val authenticated = combined.copyOfRange(0, combined.size - TAG_SIZE)
        val suppliedTag = combined.copyOfRange(combined.size - TAG_SIZE, combined.size)
        val keys = deriveKeys(password, salt)
        require(MessageDigest.isEqual(suppliedTag, hmac(keys.second, authenticated)))
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, keys.first, IvParameterSpec(iv))
        String(cipher.doFinal(encrypted), StandardCharsets.UTF_8)
    } else {
        decryptLegacy(cipherText, password)
    }
}.getOrNull()

private fun deriveKeys(password: String, salt: ByteArray): Pair<SecretKeySpec, SecretKeySpec> {
    val spec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, 512)
    val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    spec.clearPassword()
    return SecretKeySpec(bytes.copyOfRange(0, 32), "AES") to SecretKeySpec(bytes.copyOfRange(32, 64), "HmacSHA256")
}

private fun hmac(key: SecretKeySpec, data: ByteArray): ByteArray =
    Mac.getInstance("HmacSHA256").run { init(key); doFinal(data) }

private fun decryptLegacy(cipherText: String, password: String): String {
    val combined = android.util.Base64.decode(cipherText, android.util.Base64.NO_WRAP)
    require(combined.size > 12 + 16)
    val key = SecretKeySpec(
        MessageDigest.getInstance("SHA-256").digest(password.toByteArray(StandardCharsets.UTF_8)),
        "AES"
    )
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, key, javax.crypto.spec.GCMParameterSpec(128, combined.copyOfRange(0, 12)))
    return String(cipher.doFinal(combined.copyOfRange(12, combined.size)), StandardCharsets.UTF_8)
}
