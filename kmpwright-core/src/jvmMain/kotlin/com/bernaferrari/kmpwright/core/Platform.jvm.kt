package com.bernaferrari.kmpwright.core

public actual fun currentTimeMillis(): Long = System.currentTimeMillis()

internal actual fun secureRandomBytes(size: Int): ByteArray =
    ByteArray(size).also(java.security.SecureRandom()::nextBytes)

internal actual fun controlOrMetaModifier(): String =
    if (System.getProperty("os.name").orEmpty().contains("Mac", ignoreCase = true)) KeyboardModifiers.META else KeyboardModifiers.CONTROL
