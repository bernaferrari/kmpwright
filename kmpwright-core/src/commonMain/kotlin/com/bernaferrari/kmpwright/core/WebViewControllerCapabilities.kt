package com.bernaferrari.kmpwright.core

import kotlin.coroutines.resume

/**
 * Navigation-only port implemented by every web view backend.
 *
 * These ports are deliberately separate from [WebViewController] so internal
 * delegates and backend tests can depend on the capability they use instead
 * of a controller with unrelated operations.
 */
interface WebViewNavigationController {
    fun loadUrl(url: String)
    fun loadHtml(content: String, baseUrl: String?)
    /** Wait for the new document, rather than the previous document's readyState. */
    suspend fun loadHtmlAsync(content: String, baseUrl: String?, timeoutMs: Long) {
        val result = kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
            kotlinx.coroutines.suspendCancellableCoroutine<NavigationResult> { continuation ->
                continuation.invokeOnCancellation { setNavigationCallback(null) }
                setNavigationCallback { navigation ->
                    setNavigationCallback(null)
                    if (continuation.isActive) continuation.resume(navigation)
                }
                loadHtml(content, baseUrl)
            }
        } ?: throw KmpwrightException("Timeout loading HTML content")
        if (!result.ok) throw KmpwrightException(result.error ?: "Failed to load HTML content")
    }
    fun goBack()
    fun goForward()
    fun reload()
    fun getUrl(): String?
    fun getTitle(): String?
    fun setNavigationCallback(callback: ((NavigationResult) -> Unit)?)
    /** Synchronous guard for committed and browser-initiated navigations. */
    fun setNavigationGuard(guard: ((String) -> Boolean)?) {}
}

/** Script evaluation and injection capability. */
interface WebViewScriptController {
    val nativeAccessibility: Boolean get() = false
    val webMcp: WebMcp get() = UnavailableWebMcp
    fun evaluateJavaScript(script: String, callback: (String?) -> Unit)
    fun injectJavaScript(script: String, callback: ((String?) -> Unit)?)

    /** Audit one document on WebViews; CDP can collect isolated frame reports. */
    suspend fun runAccessibilityAudit(options: AccessibilityAuditOptions): String =
        PageScriptTask(this).evaluate(AccessibilityAuditScripts.singleDocument(options), options.timeoutMs)
            ?: throw KmpwrightException("Accessibility audit returned no report")

    /** Refresh optional native accessibility metadata before a DOM snapshot. */
    suspend fun prepareAccessibilitySnapshot() {}

    /** Android returns JSON-encoded values; native and CDP backends may return decoded strings. */
    fun normalizeJavaScriptResult(raw: String?): String? = normalizeJsResult(raw)

    /** Register in every new document, including child frames; does not run in the current document. */
    suspend fun addInitScript(script: String): String =
        throw KmpwrightException("Document-start scripts are unavailable on this backend")

    /** Remove a registration without changing documents where it has already run. */
    suspend fun removeInitScript(id: String) {
        throw KmpwrightException("Document-start scripts are unavailable on this backend")
    }

    /** Completion-aware JavaScript evaluation used by suspend Page APIs. */
    suspend fun evaluateJavaScriptAsync(script: String): String? =
        kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
            evaluateJavaScript(script) { result ->
                if (continuation.isActive) continuation.resume(result) { _, _, _ -> }
            }
        }
}

/**
 * The capability required by locator implementations: evaluate page scripts
 * and capture element screenshots. A full controller is still accepted by
 * existing constructors because it extends this combined port.
 */
interface WebViewLocatorController : WebViewScriptController, WebViewCaptureController

/** Network events, routing, request interception, and transport policy. */
interface WebViewNetworkController {
    suspend fun networkResponseBody(requestId: String, maxBytes: Int): NetworkResponseBody =
        throw UnsupportedOperationException("Response body retrieval is unavailable on this backend")

    fun addRequestListener(listener: RequestListener)
    fun removeRequestListener(listener: RequestListener)
    fun getPendingRequestCount(): Int
    fun resetRequestCount()
    fun setRequestIdleTimeout(timeoutMs: Long)

    fun addDialogListener(listener: DialogListener)
    fun removeDialogListener(listener: DialogListener)

    fun addRouteHandler(urlPattern: String, handler: suspend (Route) -> RouteHandler)
    fun removeRouteHandler(urlPattern: String)
    fun removeAllRouteHandlers()

    fun fulfillRoute(requestId: String, options: FulfillOptions)
    fun abortRoute(requestId: String, errorCode: String)
    fun continueRoute(requestId: String, options: ContinueOptions)

    fun interceptRequest(
        url: String,
        method: String,
        headers: Map<String, String>,
        onResult: (RouteAction?) -> Unit
    )

    fun setExtraHttpHeaders(headers: Map<String, String>): Boolean = false
    suspend fun setExtraHttpHeadersAsync(headers: Map<String, String>) {
        if (!setExtraHttpHeaders(headers)) {
            throw KmpwrightException(
                "This backend cannot apply extra HTTP headers securely without exposing them to page JavaScript."
            )
        }
    }

    fun setHttpCredentials(username: String?, password: String?): Boolean = false
    suspend fun setHttpCredentialsAsync(username: String?, password: String?) {
        if (!setHttpCredentials(username, password)) {
            throw KmpwrightException(
                "This backend cannot apply HTTP credentials securely without exposing them to page JavaScript."
            )
        }
    }

    fun setDomainFilter(domains: Set<String>): Boolean = false
    suspend fun setDomainFilterAsync(domains: Set<String>) {
        if (!setDomainFilter(domains)) {
            throw KmpwrightException("This backend cannot apply a domain filter securely.")
        }
    }
}

/** Cookie and browser-state capability. */
interface WebViewCookieStateController {
    fun setCookie(cookie: Cookie)
    suspend fun setCookieAsync(cookie: Cookie) { setCookie(cookie) }
    fun getCookie(name: String, domain: String, callback: (Cookie?) -> Unit)
    fun getAllCookies(callback: (List<Cookie>) -> Unit)
    fun deleteCookie(name: String, domain: String)
    suspend fun deleteCookieAsync(name: String, domain: String) { deleteCookie(name, domain) }
    fun deleteAllCookies()
    suspend fun deleteAllCookiesAsync() { deleteAllCookies() }
}

/** Screenshot, viewport, scale, user-agent, and PDF capture capability. */
interface WebViewCaptureController {
    fun takeScreenshot(options: ScreenshotOptions, callback: (ByteArray?, Int, Int) -> Unit)
    fun getViewportSize(): Pair<Int, Int>
    fun setViewportSize(width: Int, height: Int)
    fun setZoomScale(scale: Double)
    fun getZoomScale(): Double
    fun setUserAgent(userAgent: String)
    fun setDeviceScaleFactor(scale: Double)
    fun generatePdf(options: PdfOptions, callback: (ByteArray?) -> Unit)
}

/** Lifecycle capability owned by a controller instance. */
interface WebViewLifecycleController {
    suspend fun disposeAsync() { dispose() }
    fun dispose()
}
