package com.bernaferrari.kmpwright.core

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSDate
import platform.Foundation.distantPast
import platform.WebKit.WKHTTPCookieStore
import platform.WebKit.WKWebsiteDataStore
import kotlin.coroutines.resume

@OptIn(ExperimentalForeignApi::class)
internal fun Cookie.toNSHTTPCookie(): platform.Foundation.NSHTTPCookie? {
    val props = mutableMapOf<Any?, Any?>()
    props[platform.Foundation.NSHTTPCookieName] = name
    props[platform.Foundation.NSHTTPCookieValue] = value
    props[platform.Foundation.NSHTTPCookieDomain] = if (hostOnly) domain.removePrefix(".") else ".${domain.removePrefix(".")}"
    props[platform.Foundation.NSHTTPCookiePath] = path
    if (secure) props[platform.Foundation.NSHTTPCookieSecure] = "TRUE"
    if (httpOnly) props["HttpOnly"] = "TRUE"
    expires?.let { props[platform.Foundation.NSHTTPCookieExpires] = NSDate(timeIntervalSinceReferenceDate = it - APPLE_REFERENCE_TO_UNIX_EPOCH_SECONDS) }
    sameSite?.let { props[platform.Foundation.NSHTTPCookieSameSitePolicy] = it.lowercase() }
    return platform.Foundation.NSHTTPCookie.cookieWithProperties(props)
}

internal fun List<*>?.toNSHTTPCookies(): List<platform.Foundation.NSHTTPCookie> =
    this?.mapNotNull { it as? platform.Foundation.NSHTTPCookie } ?: emptyList()

/** Deletes every cookie accepted by [filter] and waits for WebKit to confirm. Main thread only. */
internal suspend fun WKHTTPCookieStore.deleteCookiesMatching(filter: (platform.Foundation.NSHTTPCookie) -> Boolean) {
    val cookies = suspendCancellableCoroutine<List<platform.Foundation.NSHTTPCookie>> { continuation ->
        getAllCookies { all -> if (continuation.isActive) continuation.resume(all.toNSHTTPCookies().filter(filter)) }
    }
    if (cookies.isEmpty()) return
    suspendCancellableCoroutine<Unit> { continuation ->
        var remaining = cookies.size
        cookies.forEach { cookie ->
            deleteCookie(cookie) {
                remaining -= 1
                if (remaining == 0 && continuation.isActive) continuation.resume(Unit)
            }
        }
    }
}

/** Removes all website data of [store] and waits for completion. Main thread only. */
internal suspend fun WKWebsiteDataStore.removeAllData() = suspendCancellableCoroutine<Unit> { continuation ->
    removeDataOfTypes(WKWebsiteDataStore.allWebsiteDataTypes(), modifiedSince = NSDate.distantPast) {
        if (continuation.isActive) continuation.resume(Unit)
    }
}

@OptIn(ExperimentalForeignApi::class)
internal fun platform.Foundation.NSHTTPCookie.toKmpwrightCookie(): Cookie {
    return Cookie(
        name = name,
        value = value,
        domain = domain,
        path = path,
        expires = expiresDate?.let { it.timeIntervalSinceReferenceDate + APPLE_REFERENCE_TO_UNIX_EPOCH_SECONDS },
        secure = isSecure(),
        httpOnly = isHTTPOnly(),
        sameSite = sameSitePolicy
    )
}
