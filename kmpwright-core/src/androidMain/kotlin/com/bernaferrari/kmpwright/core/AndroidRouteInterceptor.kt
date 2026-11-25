package com.bernaferrari.kmpwright.core

import android.net.Uri
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebResourceResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.util.concurrent.CopyOnWriteArrayList

private const val TAG = "Kmpwright"
private const val ROUTE_CONTINUE_CONNECT_TIMEOUT_MS = 15_000
private const val ROUTE_CONTINUE_READ_TIMEOUT_MS = 30_000

/** Owns route registrations and translates route decisions into synchronous Chromium responses. */
internal class AndroidRouteInterceptor(
    private val timeoutMs: Long,
    private val completeRequest: (Request, Response) -> Unit,
    private val failRequest: (Request, String) -> Unit,
) {
    private class RouteRegistration(val pattern: String, val regex: Regex, val handler: suspend (Route) -> Unit)

    private val routeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val routeHandlers = CopyOnWriteArrayList<RouteRegistration>()

    fun register(pattern: String, handler: suspend (Route) -> Unit) {
        val regex = Regex(pattern)
        unregister(pattern)
        routeHandlers.add(RouteRegistration(pattern, regex, handler))
    }

    fun unregister(pattern: String) { routeHandlers.removeAll { it.pattern == pattern } }
    fun clear() { routeHandlers.clear() }
    fun close() {
        clear()
        routeScope.cancel()
    }

    fun intercept(req: Request, passThrough: () -> Unit): WebResourceResponse? {
        return try {
            when (val routeAction = resolveRouteAction(req)) {
                is RouteAction.Fulfill -> fulfillResponse(req, routeAction)
                is RouteAction.Abort -> abortResponse(req, routeAction.errorCode)
                is RouteAction.Continue -> if (routeAction.hasOverrides()) {
                    performNetworkRequest(req, routeAction)
                } else {
                    passThrough()
                    null
                }
                RouteAction.Fallback -> {
                    passThrough()
                    null
                }
            }
        } catch (error: Throwable) {
            Log.w(TAG, "Route interception failed; aborting the request", error)
            abortResponse(req, "failed")
        }
    }

    /**
     * Runs matching handlers newest-first on [routeScope] (Playwright order:
     * the last registered handler wins and `route.fallback()` falls
     * back to the next one). The Chromium IO thread waits for a bounded time;
     * a timed-out or failing handler aborts the request instead of silently
     * letting it through.
     */
    private fun resolveRouteAction(request: Request): RouteAction {
        val matching = routeHandlers.reversed().filter { it.regex.matches(request.url) }
        if (matching.isEmpty()) return RouteAction.Fallback
        val decision = routeScope.async {
            resolveRoute(request, matching.map { it.handler }).also { action ->
                if (action is RouteAction.Fulfill) validateFulfillStatus(action.status)
            }
        }
        return try {
            runBlocking { withTimeout(timeoutMs) { decision.await() } }
        } catch (_: TimeoutCancellationException) {
            decision.cancel()
            Log.w(TAG, "Route handler did not finish within ${timeoutMs}ms; aborting the request")
            RouteAction.Abort("timedout")
        } catch (error: Throwable) {
            Log.w(TAG, "Route handler threw; aborting the request", error)
            RouteAction.Abort("failed")
        }
    }

    private fun fulfillResponse(request: Request, action: RouteAction.Fulfill): WebResourceResponse {
        validateFulfillStatus(action.status)
        val bodyBytes = action.bytes
        // No content type (Playwright sends none either): a null MIME type lets Chromium sniff the body.
        val (mimeType, encoding) = action.contentType?.takeIf { it.isNotBlank() }
            ?.let(::parseWebResponseContentType) ?: (null to null)
        val responseHeaders = action.headers
        val reason = httpReasonPhrase(action.status)
        // WebView derives Content-Type from mimeType; passing the header too would duplicate it.
        val webViewHeaders = responseHeaders.filterKeys { !it.equals("content-type", ignoreCase = true) }
        val response = WebResourceResponse(
            mimeType, encoding, action.status, reason, webViewHeaders, java.io.ByteArrayInputStream(bodyBytes)
        )
        completeRequest(request, Response(url = request.url, status = action.status, statusText = reason, headers = responseHeaders, id = request.id))
        return response
    }

    /**
     * A stream that fails before any byte is produced. WebView probes `available()` before it commits
     * response headers, so the failure surfaces as a network error (fetch rejects, images/iframes fail)
     * rather than as a 200 response with a broken body.
     */
    fun abortResponse(request: Request, errorCode: String): WebResourceResponse {
        failRequest(request, errorCode)
        return WebResourceResponse("text/plain", "UTF-8", AbortedInputStream(errorCode))
    }

    private class AbortedInputStream(private val errorCode: String) : InputStream() {
        override fun available(): Int = throw IOException("Request aborted by Kmpwright: $errorCode")
        override fun read(): Int = throw IOException("Request aborted by Kmpwright: $errorCode")
        override fun read(b: ByteArray, off: Int, len: Int): Int = read()
    }

    private class DisconnectingInputStream(
        private val source: InputStream,
        private val connection: HttpURLConnection
    ) : java.io.FilterInputStream(source) {
        override fun close() {
            try { source.close() } finally { connection.disconnect() }
        }
    }

    private fun performNetworkRequest(request: Request, action: RouteAction.Continue): WebResourceResponse {
        val targetUrl = action.url ?: request.url
        val scheme = Uri.parse(targetUrl).scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            throw KmpwrightException("Route continue overrides support only http(s) URLs: $targetUrl")
        }
        val connection = java.net.URI(targetUrl).toURL().openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = ROUTE_CONTINUE_CONNECT_TIMEOUT_MS
            connection.readTimeout = ROUTE_CONTINUE_READ_TIMEOUT_MS
            connection.instanceFollowRedirects = true
            connection.requestMethod = action.method ?: request.method
            val headers = LinkedHashMap(action.headers ?: request.headers)
            // A 304 cannot be relayed through WebResourceResponse, and
            // HttpURLConnection only decodes gzip transparently when it owns
            // Accept-Encoding.
            headers.keys.removeAll {
                it.equals("If-None-Match", true) || it.equals("If-Modified-Since", true) || it.equals("Accept-Encoding", true)
            }
            val cookieManager = CookieManager.getInstance()
            if (headers.keys.none { it.equals("Cookie", ignoreCase = true) }) {
                cookieManager.getCookie(targetUrl)?.takeIf { it.isNotBlank() }?.let { headers["Cookie"] = it }
            }
            headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
            action.postData?.let { postData ->
                connection.doOutput = true
                connection.outputStream.use { it.write(postData.toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val responseUrl = connection.url.toString()
            connection.headerFields.forEach { (key, values) ->
                if (key != null && key.equals("Set-Cookie", ignoreCase = true)) {
                    values.forEach { cookieManager.setCookie(responseUrl, it) }
                }
            }
            cookieManager.flush()
            if (status in 300..399) {
                throw KmpwrightException("Route continue received an unfollowable redirect ($status) from $responseUrl")
            }
            if (status !in 100..599) throw KmpwrightException("Route continue received an invalid HTTP status $status")
            val statusText = connection.responseMessage?.takeIf { it.isNotBlank() } ?: httpReasonPhrase(status)
            val responseHeaders = connection.headerFields
                .filterKeys { it != null && !it.equals("Set-Cookie", ignoreCase = true) }
                .mapKeys { (key, _) -> key!! }
                .mapValues { (_, values) -> values.joinToString(", ") }
            val contentType = connection.contentType ?: responseHeaders.entries
                .firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }?.value.orEmpty()
            val (mimeType, encoding) = parseWebResponseContentType(contentType.ifEmpty { "application/octet-stream" })
            val body = (if (status >= 400) connection.errorStream else connection.inputStream)
                ?: java.io.ByteArrayInputStream(ByteArray(0))
            val response = WebResourceResponse(
                mimeType, encoding, status, statusText, responseHeaders, DisconnectingInputStream(body, connection)
            )
            completeRequest(request, Response(url = responseUrl, status = status, statusText = statusText, headers = responseHeaders, id = request.id))
            return response
        } catch (error: Throwable) {
            connection.disconnect()
            throw error
        }
    }

    private fun RouteAction.Continue.hasOverrides(): Boolean =
        url != null || method != null || headers != null || postData != null

}

internal fun validateFulfillStatus(status: Int) {
    if (status !in 100..599) {
        throw KmpwrightException("Route fulfill status must be within 100..599, was $status")
    }
    if (status in 300..399) {
        throw KmpwrightException(
            "Android WebView cannot fulfill a route with redirect status $status. " +
                "Fulfill the final response or use route.resume(url = ...)."
        )
    }
}

internal fun httpReasonPhrase(status: Int): String = defaultHttpStatusText(status).ifBlank { "Status $status" }

internal fun parseWebResponseContentType(contentType: String): Pair<String, String?> {
    val parts = contentType.split(";").map { it.trim() }.filter { it.isNotEmpty() }
    val mimeType = parts.firstOrNull().orEmpty().ifEmpty { "text/plain" }
    val charset = parts
        .drop(1)
        .firstOrNull { it.startsWith("charset=", ignoreCase = true) }
        ?.substringAfter("=")
        ?.trim()
    val encoding = charset ?: when {
        mimeType.startsWith("text/") -> "UTF-8"
        mimeType.contains("json", ignoreCase = true) -> "UTF-8"
        mimeType.contains("xml", ignoreCase = true) -> "UTF-8"
        mimeType.contains("javascript", ignoreCase = true) -> "UTF-8"
        else -> null
    }
    return mimeType to encoding
}
