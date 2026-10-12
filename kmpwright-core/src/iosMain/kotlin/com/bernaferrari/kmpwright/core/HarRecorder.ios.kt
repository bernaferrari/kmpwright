package com.bernaferrari.kmpwright.core

import platform.Foundation.*

internal actual fun formatIso8601(timestamp: Long): String {
    val date = NSDate(
        timeIntervalSinceReferenceDate = (timestamp / 1000.0) - APPLE_REFERENCE_TO_UNIX_EPOCH_SECONDS
    )
    val formatter = NSDateFormatter()
    formatter.dateFormat = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"
    formatter.timeZone = NSTimeZone.timeZoneWithAbbreviation("UTC") ?: NSTimeZone.systemTimeZone
    return formatter.stringFromDate(date)
}

internal actual fun parseIso8601ToMillis(iso: String): Long {
    val formatter = NSDateFormatter()
    formatter.dateFormat = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"
    formatter.timeZone = NSTimeZone.timeZoneWithAbbreviation("UTC") ?: NSTimeZone.systemTimeZone
    val date = formatter.dateFromString(iso)
    return date?.let {
        ((it.timeIntervalSinceReferenceDate + APPLE_REFERENCE_TO_UNIX_EPOCH_SECONDS) * 1000).toLong()
    } ?: currentTimeMillis()
}
