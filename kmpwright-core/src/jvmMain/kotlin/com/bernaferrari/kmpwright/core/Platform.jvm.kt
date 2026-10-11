package com.bernaferrari.kmpwright.core

actual fun currentTimeMillis(): Long = System.currentTimeMillis()

internal actual fun secureRandomBytes(size: Int): ByteArray =
    ByteArray(size).also(java.security.SecureRandom()::nextBytes)
