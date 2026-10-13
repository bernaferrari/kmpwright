package com.bernaferrari.kmpwright.core

import kotlin.coroutines.resume

/**
 * Navigation-only port implemented by every web view backend.
 *
 * These ports are deliberately separate from [WebViewController] so internal
 * delegates and backend tests can depend on the capability they use instead
 * of a controller with unrelated operations.
 */
internal interface WebViewNavigationController {
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
        if (!result.committed) throw KmpwrightException(result.error ?: "Failed to load HTML content")
    }
    fun goBack()
    fun goForward()
    fun reload()
    fun getUrl(): String?
    fun getTitle(): String?
    fun setNavigationCallback(callback: ((NavigationResult) -> Unit)?)
    /** Synchronous guard for committed and browser-initiated navigations. */
    fun setNavigationGuard(guard: ((String) -> Boolean)?) {}

    /**
     * Whether a history traversal by [delta] (-1 = back, +1 = forward) is possible, or null when the
     * backend cannot tell (the page then probes the Navigation API). Android: `WebView.canGoBack()` /
     * `canGoForward()`; iOS: `WKWebView.canGoBack` / `canGoForward`; CDP: `Page.getNavigationHistory`.
     */
    suspend fun canTraverseHistory(delta: Int): Boolean? = null

    /**
     * Receives document lifecycle signals that are not navigation completions: commits, DOMContentLoaded,
     * same-document navigations (pushState/replaceState/hash/history traversal) and title changes. Backends
     * report what they observe natively; the page fills the gaps (see [reportsDomContentLoaded]).
     */
    fun setLifecycleListener(listener: ((PageLifecycleEvent) -> Unit)?) {}

    /**
     * True when [setLifecycleListener] receives [PageLifecycleEvent.Committed] and
     * [PageLifecycleEvent.DomContentLoaded] for every main-frame document (CDP). Otherwise the page detects
     * DOMContentLoaded by polling the new document's `readyState`.
     */
    val reportsDomContentLoaded: Boolean get() = false

    /**
     * False when navigation callbacks cannot report the real HTTP status of successful loads (Android
     * WebView reports only errors >= 400); the page then reads the Navigation Timing `responseStatus`.
     */
    val reportsHttpStatus: Boolean get() = true

    /** HTTP status of the main-frame response of the navigation in progress, when the backend knows it (iOS). */
    fun pendingNavigationStatus(): Int? = null
}

/** Lifecycle signals of the main frame, see [WebViewNavigationController.setLifecycleListener]. */
internal sealed class PageLifecycleEvent {
    /** A new document was committed for [url]. */
    data class Committed(val url: String) : PageLifecycleEvent()

    /** The current document fired DOMContentLoaded; [status] is its HTTP status (0 when unknown). */
    data class DomContentLoaded(val url: String, val status: Int) : PageLifecycleEvent()

    /** The URL changed without a new document (pushState, replaceState, fragment, same-document history). */
    data class SameDocument(val url: String) : PageLifecycleEvent()

    /** `document.title` changed. */
    data class TitleChanged(val title: String?) : PageLifecycleEvent()
}

/** Script evaluation and injection capability. */
internal interface WebViewScriptController {
    /** Whether the native evaluate callback awaits JavaScript Promises. */
    val supportsPromiseEvaluation: Boolean get() = true
    val nativeAccessibility: Boolean get() = false
    val webMcp: WebMcp get() = UnavailableWebMcp
    fun evaluateJavaScript(script: String, callback: (String?) -> Unit)
    fun injectJavaScript(script: String, callback: ((String?) -> Unit)?)

    /** Audit one document on WebViews; CDP can collect isolated frame reports. */
    suspend fun runAccessibilityAudit(options: AccessibilityAuditOptions): String =
        PageScriptTask(this).evaluate(AccessibilityAuditScripts.singleDocument(options), options.timeoutMs ?: TimeoutConfig().default)
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
internal interface WebViewLocatorController : WebViewScriptController, WebViewCaptureController

/** Network events, routing, request interception, and transport policy. */
internal interface WebViewNetworkController {
    suspend fun networkResponseBody(requestId: String, maxBytes: Int): NetworkResponseBody =
        throw KmpwrightUnsupportedException("Response body retrieval is unavailable on this backend")

    fun addRequestListener(listener: RequestListener)
    fun removeRequestListener(listener: RequestListener)
    fun getPendingRequestCount(): Int
    fun resetRequestCount()
    fun setRequestIdleTimeout(timeoutMs: Long)

    fun addDialogListener(listener: DialogListener)
    fun removeDialogListener(listener: DialogListener)

    /** Registers [handler] for request URLs matching the regular expression [urlPattern]; see [resolveRoute]. */
    fun addRouteHandler(urlPattern: String, handler: suspend (Route) -> Unit)
    fun removeRouteHandler(urlPattern: String)
    fun removeAllRouteHandlers()

    /** Suspends until route registrations made so far are active for new requests (CDP enables Fetch). */
    suspend fun syncRouteHandlers() {}

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

    /**
     * Enforces a host allowlist at the request layer (every request, including navigations and
     * subresources). Return false when the backend has no such hook; the page then falls back to a
     * best-effort document-start JavaScript patch and reports [SupportLevel.Partial].
     */
    fun setDomainFilter(domains: Set<String>): Boolean = false
    suspend fun setDomainFilterAsync(domains: Set<String>) {
        if (!setDomainFilter(domains)) {
            throw KmpwrightException("This backend cannot apply a domain filter securely.")
        }
    }
}

/** Cookie and browser-state capability. */
internal interface WebViewCookieStateController {
    fun setCookie(cookie: Cookie)
    suspend fun setCookieAsync(cookie: Cookie) { setCookie(cookie) }
    fun getCookie(name: String, domain: String, callback: (Cookie?) -> Unit)
    suspend fun getCookieAsync(name: String, domain: String): Cookie? = kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
        getCookie(name, domain) { cookie -> if (continuation.isActive) continuation.resume(cookie) }
    }
    fun getAllCookies(callback: (List<Cookie>) -> Unit)
    suspend fun getAllCookiesAsync(): List<Cookie> = kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
        getAllCookies { cookies -> if (continuation.isActive) continuation.resume(cookies) }
    }
    fun deleteCookie(name: String, domain: String)
    suspend fun deleteCookieAsync(name: String, domain: String) { deleteCookie(name, domain) }
    /** Deletes exactly [cookie]: its name, domain (host-only or not) and path. */
    suspend fun deleteCookieAsync(cookie: Cookie) { deleteCookieAsync(cookie.name, cookie.domain) }
    fun deleteAllCookies()
    suspend fun deleteAllCookiesAsync() { deleteAllCookies() }
}

/** Screenshot, viewport, scale, user-agent, and PDF capture capability. */
internal interface WebViewCaptureController {
    fun takeScreenshot(options: ScreenshotOptions, callback: (ByteArray?, Int, Int) -> Unit)
    /** Like [takeScreenshot] but reports the backend's failure reason; backends override it. */
    suspend fun captureScreenshot(options: ScreenshotOptions): ScreenshotResult = kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
        takeScreenshot(options) { bytes, width, height ->
            if (!continuation.isActive) return@takeScreenshot
            if (bytes == null) continuation.resumeWith(Result.failure(KmpwrightException("Screenshot returned no data")))
            else continuation.resumeWith(Result.success(ScreenshotResult(bytes, width, height)))
        }
    }
    fun getViewportSize(): Pair<Int, Int>
    fun setViewportSize(width: Int, height: Int)
    suspend fun setViewportSizeAsync(width: Int, height: Int) { setViewportSize(width, height) }
    fun setZoomScale(scale: Double)
    fun getZoomScale(): Double
    fun setUserAgent(userAgent: String)
    suspend fun setUserAgentAsync(userAgent: String) { setUserAgent(userAgent) }
    fun setDeviceScaleFactor(scale: Double)
    /**
     * Applies a whole [Viewport] (size, device scale factor, mobile and touch). Native WebViews can
     * only resize: their scale, mobile layout and touch support come from the device.
     */
    suspend fun emulateViewportAsync(viewport: Viewport) { setViewportSizeAsync(viewport.width, viewport.height) }
    suspend fun setDeviceScaleFactorAsync(scale: Double) { setDeviceScaleFactor(scale) }
    fun validatePdfOptions(options: PdfOptions) {}
    fun generatePdf(options: PdfOptions, callback: (ByteArray?) -> Unit)
    suspend fun generatePdfAsync(options: PdfOptions): ByteArray = kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
        generatePdf(options) { bytes ->
            if (continuation.isActive) {
                if (bytes == null || bytes.isEmpty()) continuation.resumeWith(Result.failure(KmpwrightException("PDF capture returned no data")))
                else continuation.resume(bytes)
            }
        }
    }
}

/**
 * Native emulation hooks. Each returns true when the backend applied the override natively (for every
 * document of the page), or false to let the page use its JavaScript fallback.
 */
internal interface WebViewEmulationController {
    /**
     * `prefers-color-scheme` override; null resets. CDP: `Emulation.setEmulatedMedia`; iOS:
     * `overrideUserInterfaceStyle` on the WKWebView (light/dark, only once WebKit reports it, i.e. when the
     * view is in a window). Android WebView cannot change the media feature at runtime (it follows the
     * host theme with targetSdk 33+), so it returns false and the page uses its JavaScript fallback.
     */
    suspend fun setColorSchemeOverride(scheme: ColorScheme?): Boolean = false

    /** Geolocation override; null resets. CDP: `Emulation.setGeolocationOverride` (+ `Browser.grantPermissions`). */
    suspend fun setGeolocationOverride(geolocation: Geolocation?): Boolean = false

    /** Network conditions (latency ms, throughput bytes/s; 0 = unthrottled). CDP: `Network.emulateNetworkConditions`. */
    suspend fun setNetworkConditions(offline: Boolean, latencyMs: Long, downloadBytesPerSecond: Long, uploadBytesPerSecond: Long): Boolean = false
}

/** Lifecycle capability owned by a controller instance. */
internal interface WebViewLifecycleController {
    suspend fun disposeAsync() { dispose() }
    fun dispose()
}
