package com.bernaferrari.kmpwright.core

public actual fun currentTimeMillis(): Long = java.lang.System.currentTimeMillis()

internal actual fun secureRandomBytes(size: Int): ByteArray =
    ByteArray(size).also(java.security.SecureRandom()::nextBytes)

internal actual fun controlOrMetaModifier(): String = KeyboardModifiers.CONTROL
