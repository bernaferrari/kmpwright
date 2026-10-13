package com.bernaferrari.kmpwright.core.ios

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.create
import platform.posix.memcpy

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal fun NSData.toByteArrayPinned(): ByteArray {
    val size = length.toInt()
    if (size <= 0) return ByteArray(0)
    val out = ByteArray(size)
    val src = bytes ?: return ByteArray(0)

    out.usePinned { pinned ->
        memcpy(pinned.addressOf(0), src, length)
    }
    return out
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal fun ByteArray.toNSData(): NSData {
    if (isEmpty()) return NSData()
    return usePinned { pinned ->
        NSData.create(bytes = pinned.addressOf(0), length = size.toULong())
    }
}
