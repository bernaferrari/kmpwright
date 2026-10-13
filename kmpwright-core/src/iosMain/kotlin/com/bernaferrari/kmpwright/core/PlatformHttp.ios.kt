package com.bernaferrari.kmpwright.core

import com.bernaferrari.kmpwright.core.ios.toByteArrayPinned
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.HTTPMethod
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSURL
import platform.Foundation.NSURLResponse
import platform.Foundation.NSURLSession
import platform.Foundation.dataTaskWithRequest
import platform.Foundation.setValue
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Same contract as the JVM actual: non-2xx responses throw instead of returning the error body. */
internal actual suspend fun platformHttpGetText(
    url: String,
    headers: Map<String, String>
): String = suspendCancellableCoroutine { continuation ->
    val target = NSURL.URLWithString(url)
    if (target == null) {
        continuation.resumeWithException(KmpwrightException("HTTP GET $url failed: invalid URL"))
        return@suspendCancellableCoroutine
    }
    val request = NSMutableURLRequest(uRL = target).apply {
        HTTPMethod = "GET"
        headers.forEach { (name, value) -> setValue(value, forHTTPHeaderField = name) }
    }

    val task = NSURLSession.sharedSession.dataTaskWithRequest(request) { data: NSData?, response: NSURLResponse?, error: NSError? ->
        if (!continuation.isActive) return@dataTaskWithRequest
        val status = (response as? NSHTTPURLResponse)?.statusCode?.toInt()
        when {
            error != null -> continuation.resumeWithException(KmpwrightException("HTTP GET $url failed: ${error.localizedDescription}"))
            status != null && status !in 200..299 ->
                continuation.resumeWithException(KmpwrightException("HTTP GET $url failed with status $status ${defaultHttpStatusText(status)}"))
            else -> continuation.resume(data?.toByteArrayPinned()?.decodeToString() ?: "")
        }
    }

    continuation.invokeOnCancellation {
        task.cancel()
    }

    task.resume()
}
