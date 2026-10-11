package com.bernaferrari.kmpwright.core

import platform.Foundation.NSDate
import com.bernaferrari.kmpwright.core.ios.toByteArrayPinned
import com.bernaferrari.kmpwright.core.nativeinterop.KmpwrightSnapshot
import kotlinx.cinterop.ExperimentalForeignApi

private const val APPLE_REFERENCE_TO_UNIX_EPOCH_SECONDS = 978307200.0

actual fun currentTimeMillis(): Long {
    val date = NSDate()
    return ((date.timeIntervalSinceReferenceDate + APPLE_REFERENCE_TO_UNIX_EPOCH_SECONDS) * 1000).toLong()
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun secureRandomBytes(size: Int): ByteArray =
    KmpwrightSnapshot.randomDataOfLength(size.toULong())?.toByteArrayPinned()
        ?: throw KmpwrightException("Unable to obtain secure random bytes")
