package com.bernaferrari.kmpwright.core

import kotlin.coroutines.cancellation.CancellationException

/**
 * An intercepted request passed to a [Page.route] handler, like Playwright's `Route`.
 *
 * A handler decides once: [fulfill], [abort], [resume] (Playwright JS `route.continue()`, named after
 * Playwright-Java because `continue` is a Kotlin keyword) or [fallback]. A handler that returns without
 * deciding falls back to the next older matching route, and finally to the network.
 */
public interface Route {
    /** The request being routed. */
    public val request: Request

    /**
     * Answers the request without contacting the network, with Playwright's `route.fulfill` semantics:
     *
     * - [json] is an already-serialized JSON body; it cannot be combined with [body] or [bodyBytes].
     * - The `Content-Type` header is [contentType] when set (it replaces any `Content-Type` in
     *   [headers]); otherwise `application/json` when [json] is set; otherwise the `Content-Type`
     *   from [headers]; otherwise none is sent and the browser sniffs the body (WebKit's fetch
     *   shim on iOS reports `text/plain;charset=UTF-8` for string bodies, as `new Response(text)` does).
     * - Header names are lower-cased, and `content-length` is added when the body is non-empty and
     *   [headers] has none.
     * - [bodyBytes] takes precedence over [body] for binary payloads.
     *
     * @throws IllegalArgumentException when [json] is combined with [body] or [bodyBytes].
     */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun fulfill(
        status: Int = 200,
        headers: Map<String, String> = emptyMap(),
        body: String? = null,
        bodyBytes: ByteArray? = null,
        contentType: String? = null,
        json: String? = null
    )

    /** Fails the request with a network error such as `"failed"`, `"aborted"` or `"timedout"`. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun abort(errorCode: String = "failed")

    /** Sends the request to the network, optionally overriding parts of it (Playwright `route.continue()`). */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun resume(
        url: String? = null,
        method: String? = null,
        headers: Map<String, String>? = null,
        postData: String? = null
    )

    /** Passes the request to the next older matching route handler, or to the network. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun fallback()
}

/** The single internal routing decision model shared by every backend. */
internal sealed class RouteAction {
    /** [headers] are final (lower-case names, content type and length already resolved). */
    data class Fulfill(
        val status: Int,
        val headers: Map<String, String>,
        val body: String,
        val bodyBytes: ByteArray? = null
    ) : RouteAction() {
        /** The resolved `content-type` header, or null when the browser should sniff the body. */
        val contentType: String? get() = headers.entries.firstOrNull { it.key.equals("content-type", ignoreCase = true) }?.value
        /** The response body bytes. */
        val bytes: ByteArray get() = bodyBytes ?: body.encodeToByteArray()
    }
    data class Abort(val errorCode: String) : RouteAction()
    data class Continue(val url: String?, val method: String?, val headers: Map<String, String>?, val postData: String?) : RouteAction()
    data object Fallback : RouteAction()
}

/** Route handed to user handlers; records the handler's single decision. */
internal class RecordingRoute(override val request: Request) : Route {
    var action: RouteAction? = null
        private set

    private fun decide(decision: RouteAction) {
        if (action != null) throw KmpwrightException("Route is already handled")
        action = decision
    }

    override suspend fun fulfill(
        status: Int,
        headers: Map<String, String>,
        body: String?,
        bodyBytes: ByteArray?,
        contentType: String?,
        json: String?
    ) {
        decide(buildFulfillAction(status, headers, body, bodyBytes, contentType, json))
    }

    override suspend fun abort(errorCode: String) = decide(RouteAction.Abort(errorCode))

    override suspend fun resume(url: String?, method: String?, headers: Map<String, String>?, postData: String?) =
        decide(RouteAction.Continue(url, method, headers, postData))

    override suspend fun fallback() = decide(RouteAction.Fallback)
}

/** Resolves `route.fulfill` options exactly like Playwright's client (`Route._innerFulfill`). */
internal fun buildFulfillAction(
    status: Int,
    headers: Map<String, String>,
    body: String?,
    bodyBytes: ByteArray?,
    contentType: String?,
    json: String?
): RouteAction.Fulfill {
    require(json == null || (body == null && bodyBytes == null)) { "Can specify either body or json parameters" }
    val text = json ?: body ?: ""
    val bytes = if (json != null) null else bodyBytes
    val length = bytes?.size ?: text.encodeToByteArray().size
    val resolved = LinkedHashMap<String, String>()
    headers.forEach { (name, value) -> resolved[name.lowercase()] = value }
    when {
        !contentType.isNullOrEmpty() -> resolved["content-type"] = contentType
        json != null -> resolved["content-type"] = "application/json"
    }
    if (length > 0 && "content-length" !in resolved) resolved["content-length"] = length.toString()
    return RouteAction.Fulfill(status = status, headers = resolved, body = text, bodyBytes = bytes)
}

/**
 * Runs matching [handlers] (already ordered newest first) until one decides. Returns
 * [RouteAction.Fallback] when every handler falls back or returns without deciding.
 */
internal suspend fun resolveRoute(request: Request, handlers: List<suspend (Route) -> Unit>): RouteAction {
    for (handler in handlers) {
        val route = RecordingRoute(request)
        handler(route)
        val decision = route.action
        if (decision != null && decision != RouteAction.Fallback) return decision
    }
    return RouteAction.Fallback
}
