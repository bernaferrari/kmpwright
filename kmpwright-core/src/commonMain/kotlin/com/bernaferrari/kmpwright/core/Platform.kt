package com.bernaferrari.kmpwright.core

expect fun currentTimeMillis(): Long
internal expect fun secureRandomBytes(size: Int): ByteArray
