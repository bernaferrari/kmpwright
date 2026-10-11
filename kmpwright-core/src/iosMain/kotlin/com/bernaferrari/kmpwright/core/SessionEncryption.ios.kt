package com.bernaferrari.kmpwright.core

import com.bernaferrari.kmpwright.core.nativeinterop.KmpwrightSnapshot
import kotlinx.cinterop.ExperimentalForeignApi

@OptIn(ExperimentalForeignApi::class)
internal actual fun platformEncrypt(plainText: String, password: String): String {
    return KmpwrightSnapshot.encryptString(plainText, password)
        ?: throw KmpwrightException("iOS session encryption failed")
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun platformDecrypt(cipherText: String, password: String): String? =
    KmpwrightSnapshot.decryptString(cipherText, password)
