package com.bernaferrari.kmpwright.core

import com.bernaferrari.kmpwright.core.ios.toByteArrayPinned
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.HTTPMethod
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSURL
import platform.Foundation.NSURLSession
import platform.Foundation.dataTaskWithRequest
import platform.Foundation.setValue
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal actual suspend fun platformHttpGetText(
    url: String,
    headers: Map<String, String>
): String = suspendCancellableCoroutine { continuation ->
    val request = NSMutableURLRequest(uRL = NSURL(string = url)).apply {
        HTTPMethod = "GET"
        headers.forEach { (name, value) -> setValue(value, forHTTPHeaderField = name) }
    }

    val task = NSURLSession.sharedSession.dataTaskWithRequest(request) { data: NSData?, _, error: NSError? ->
        when {
            error != null -> continuation.resumeWithException(KmpwrightException(error.localizedDescription))
            data != null -> continuation.resume(data.toByteArrayPinned().decodeToString())
            else -> continuation.resume("")
        }
    }

    continuation.invokeOnCancellation {
        task.cancel()
    }

    task.resume()
}
