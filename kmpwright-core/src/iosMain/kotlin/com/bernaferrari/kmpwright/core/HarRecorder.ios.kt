package com.bernaferrari.kmpwright.core

import platform.Foundation.*

private const val ISO_8601_MILLIS = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"

/**
 * Fixed-format formatter: the POSIX locale keeps the Gregorian calendar and
 * ASCII digits regardless of the user's region settings (e.g. Buddhist or
 * Japanese calendars, 12-hour clocks), and UTC matches the literal `Z`.
 */
private fun iso8601Formatter(): NSDateFormatter = NSDateFormatter().apply {
    locale = NSLocale(localeIdentifier = "en_US_POSIX")
    calendar = NSCalendar(calendarIdentifier = NSCalendarIdentifierGregorian)
    timeZone = NSTimeZone.timeZoneForSecondsFromGMT(0)
    dateFormat = ISO_8601_MILLIS
}

internal actual fun formatIso8601(timestamp: Long): String {
    val date = NSDate(
        timeIntervalSinceReferenceDate = (timestamp / 1000.0) - APPLE_REFERENCE_TO_UNIX_EPOCH_SECONDS
    )
    return iso8601Formatter().stringFromDate(date)
}

/**
 * Parses timestamps produced by [formatIso8601]. Matching the JVM and Android
 * actuals, an unparseable value falls back to the current time; HAR timing
 * uses it only as a lower bound that is clamped to the measured entry time.
 */
internal actual fun parseIso8601ToMillis(iso: String): Long {
    val date = iso8601Formatter().dateFromString(iso) ?: return currentTimeMillis()
    return ((date.timeIntervalSinceReferenceDate + APPLE_REFERENCE_TO_UNIX_EPOCH_SECONDS) * 1000).toLong()
}
