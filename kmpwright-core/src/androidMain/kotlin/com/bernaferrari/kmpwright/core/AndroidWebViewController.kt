package com.bernaferrari.kmpwright.core

import android.net.Uri
import android.util.Base64
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.HttpAuthHandler
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.view.updateLayoutParams
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt

private const val TAG = "Kmpwright"
private const val ROUTE_CONTINUE_CONNECT_TIMEOUT_MS = 15_000
private const val ROUTE_CONTINUE_READ_TIMEOUT_MS = 30_000

/** Observable WebView state, updated on the main thread from WebView client callbacks. */
public data class AndroidWebViewState(
    val url: String? = null,
    val title: String? = null,
    val isLoading: Boolean = false,
    val progress: Int = 0,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    /** The renderer crashed or was killed; this WebView can no longer be used. */
    val rendererGone: Boolean = false
)

/**
 * Drives one Android [WebView]. Must be constructed on the main thread; every
 * other member may be called from any thread and is confined to the main
 * looper internally.
 *
 * Callbacks are forwarded to [delegateWebViewClient]/[delegateWebChromeClient]
 * or, when those are null, to the clients that were installed on the WebView
 * before Kmpwright attached. The originals are restored on [dispose].
 *
 * When the renderer process dies, pending navigations and evaluations fail
 * with a "page crashed" error and the host app is kept alive. A WebView owned
 * by Kmpwright is destroyed; a host-owned WebView must be removed and
 * destroyed by the host (observe [state]'s `rendererGone`).
 */
internal class AndroidWebViewController(
    private val webView: WebView,
    private val ownsWebView: Boolean = false,
    private val config: KmpwrightConfig = KmpwrightConfig(),
    delegateWebViewClient: WebViewClient? = null,
    delegateWebChromeClient: WebChromeClient? = null
) : WebViewController {
    override val supportsPromiseEvaluation: Boolean get() = false

    init {
        checkMainThread("AndroidWebViewController")
    }

    private val originalWebViewClient: WebViewClient = webView.webViewClient
    private val originalWebChromeClient: WebChromeClient? = webView.webChromeClient
    private val clientDelegate: WebViewClient =
        delegateWebViewClient ?: (originalWebViewClient as? KmpwrightWebViewClient)?.delegate ?: originalWebViewClient
    private val chromeDelegate: WebChromeClient? =
        delegateWebChromeClient ?: (originalWebChromeClient as? KmpwrightWebChromeClient)?.delegate ?: originalWebChromeClient

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    /** Route handlers run off the main thread so a busy UI cannot stall network interception. */
    private val routeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()
    private var navigationCallback: ((NavigationResult) -> Unit)? = null
    @Volatile private var navigationGuard: ((String) -> Boolean)? = null
    private val requestListeners = CopyOnWriteArrayList<RequestListener>()
    private val dialogListeners = CopyOnWriteArrayList<DialogListener>()
    private val diagnosticsListeners = CopyOnWriteArrayList<PageDiagnosticsListener>()
    /** Per-controller secret prefixing the diagnostics hook's console sentinel messages. */
    private val diagnosticsNonce = java.util.UUID.randomUUID().toString().replace("-", "")
    private val consoleRouter = AndroidConsoleRouter(diagnosticsNonce)
    /** Main-thread confined: the document-start error hook, null when WebView lacks DOCUMENT_START_SCRIPT. */
    private var diagnosticsScript: androidx.webkit.ScriptHandler? = null
    private val pendingRequests = ConcurrentHashMap<String, Request>()
    @Volatile private var requestIdleTimeoutMs = 500L
    /** Main-thread confined. */
    private var requestIdleCheckJob: Job? = null
    private val requestFinishJobs = ConcurrentHashMap<String, Job>()
    private val routeHandlers = CopyOnWriteArrayList<RouteRegistration>()
    private val observedCookieUrls: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val httpRequestPolicy = MobileHttpRequestPolicy()
    private val initScripts = mutableMapOf<String, androidx.webkit.ScriptHandler>()
    private val pendingEvaluations: MutableSet<PendingEvaluation> = ConcurrentHashMap.newKeySet()

    /**
     * Requests WebView loaded itself (no route answered them), newest last, keyed by URL. WebView
     * reports no response for these, so a response is attached best-effort: the main-frame document
     * gets its status when the page finishes, any request gets status and headers from
     * `onReceivedHttpError`, and network errors fail the record. Other subresources finish without
     * a response (documented in the Android PageCapabilities notes).
     */
    private val passThroughRequests = object : LinkedHashMap<String, Request>(64, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Request>?): Boolean = size > 256
    }
    /** The pass-through main-frame document request still waiting for its page to finish. */
    @Volatile private var pendingMainFrameRequest: Request? = null

    // Main-thread confined navigation bookkeeping.
    /** Between onPageStarted and onPageFinished: URL changes belong to the loading document. */
    private var documentLoading = false
    @Volatile private var lifecycleListener: ((PageLifecycleEvent) -> Unit)? = null
    private var mainFrameHttpError: Pair<String, Int>? = null
    private var failedNavigationUrl: String? = null
    private var loadHtmlBaseUrl: String? = null
    /** Latest main-frame request seen by shouldInterceptRequest: the URL a server redirect started from. */
    @Volatile private var mainFrameRequestUrl: String? = null
    private val httpAuthAttempts = mutableSetOf<String>()

    /** Request-layer host allowlist (null = unrestricted); read on WebView IO threads. */
    @Volatile private var domainFilter: DomainFilter? = null
    @Volatile private var httpCredentials: Pair<String, String>? = null
    @Volatile private var httpCredentialHost: String? = null
    @Volatile private var zoomScale: Double = 1.0
    @Volatile private var disposed = false
    @Volatile private var rendererGone = false
    @Volatile private var destroyed = false
    @Volatile private var cachedUrl: String? = webView.url
    @Volatile private var cachedTitle: String? = webView.title
    @Volatile private var cachedViewportPx: Pair<Int, Int> = webView.width to webView.height


    private val _state = MutableStateFlow(AndroidWebViewState(url = cachedUrl, title = cachedTitle))
    /** Observable navigation state for UI layers (for example kmpwright-compose). */
    val state: StateFlow<AndroidWebViewState> = _state.asStateFlow()

    private val layoutListener = View.OnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
        cachedViewportPx = (right - left) to (bottom - top)
    }

    init {
        // Every locator, wait and assertion runs page JavaScript; a host WebView may have it off.
        webView.settings.javaScriptEnabled = true
        config.userAgent?.let { webView.settings.userAgentString = it }
        webView.webViewClient = KmpwrightWebViewClient(clientDelegate)
        webView.webChromeClient = KmpwrightWebChromeClient(chromeDelegate)
        webView.addOnLayoutChangeListener(layoutListener)
        installDiagnosticsHook()
    }

    /**
     * Registers the uncaught-error hook in every frame. It reports through `console.debug` with
     * [diagnosticsNonce] (no JavaScript interface is exposed; see [AndroidConsoleRouter]). Without
     * DOCUMENT_START_SCRIPT, page errors fall back to Chromium's "Uncaught ..." console messages.
     */
    private fun installDiagnosticsHook() {
        if (!androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT)) return
        val post = "(function(log, prefix) { return function(s) { log(prefix + s); }; })(console.debug.bind(console), " +
            jsStringLiteral(diagnosticsNonce) + ")"
        val origins = config.allowedOriginRules.ifEmpty { setOf("*") }
        diagnosticsScript = runCatching {
            androidx.webkit.WebViewCompat.addDocumentStartJavaScript(webView,
                buildPageDiagnosticsScript(post, diagnosticsNonce, wrapConsole = false), origins)
        }.getOrNull()
    }

    private class RouteRegistration(val pattern: String, val regex: Regex, val handler: suspend (Route) -> Unit)

    private inner class PendingEvaluation(
        private val onResult: (String?) -> Unit,
        private val onFailure: (Throwable) -> Unit
    ) {
        private val done = AtomicBoolean(false)
        fun complete(value: String?) {
            if (done.compareAndSet(false, true)) { pendingEvaluations.remove(this); onResult(value) }
        }
        fun fail(error: Throwable) {
            if (done.compareAndSet(false, true)) { pendingEvaluations.remove(this); onFailure(error) }
        }
    }

    // ---------------------------------------------------------------- clients

    internal inner class KmpwrightWebViewClient(delegate: WebViewClient) : ForwardingWebViewClient(delegate) {
        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
            if (url != null && !domainFilterAllows(url)) {
                // Server redirects of loadUrl navigations bypass both request hooks; never commit them.
                rejectNavigation(view, url, domainBlockedMessage(url))
                return
            }
            if (!isNavigationAllowedByConfig(url)) {
                rejectNavigation(view, url, "Navigation origin is not allowed by KmpwrightConfig: ${url.orEmpty()}")
                return
            }
            if (url.isNullOrBlank() || !guardAllows(url)) {
                rejectNavigation(view, url, "Navigation denied by action policy: ${url.orEmpty()}")
                return
            }
            if (failedNavigationUrl != null && failedNavigationUrl != url) failedNavigationUrl = null
            documentLoading = true
            refreshState(loading = true)
            // With the hook, the document's own sentinel marks the commit in console order instead.
            if (diagnosticsScript == null) diagnosticsListeners.forEach { it.onMainFrameDocumentCommitted() }
            super.onPageStarted(view, url, favicon)
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            documentLoading = false
            trackCookieUrl(url)
            httpAuthAttempts.clear()
            refreshState(loading = false)
            super.onPageFinished(view, url)
            if (failedNavigationUrl != null) {
                // The failure was already reported by onReceivedError or the
                // origin policy; this callback belongs to the error page.
                failedNavigationUrl = null
                mainFrameHttpError = null
                return
            }
            val navUrl = url ?: cachedUrl.orEmpty()
            val status = mainFrameHttpError?.takeIf { sameDocumentUrl(it.first, navUrl) }?.second ?: 200
            mainFrameHttpError = null
            completeMainFrameRequest(navUrl, status)
            val result = if (!domainFilterAllows(navUrl)) {
                // A server redirect reached a disallowed host (WebView offers no pre-request hook for it):
                // report the navigation as failed and stop the document from loading further.
                // Report it against the requested URL so the waiting navigation observes the failure.
                view?.stopLoading()
                NavigationResult(mainFrameRequestUrl ?: navUrl, 0, false, "${domainBlockedMessage(navUrl)} (redirected to $navUrl)")
            } else if (isNavigationAllowedByConfig(navUrl) && guardAllows(navUrl)) {
                committedNavigation(navUrl, status)
            } else {
                NavigationResult(navUrl, status, false, "Committed navigation origin is not allowed by KmpwrightConfig: $navUrl")
            }
            deliverNavigation(result)
        }

        override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
            refreshState()
            // Outside a document load this is a same-document navigation (pushState, replaceState,
            // fragment change or same-document history traversal).
            if (!documentLoading && !url.isNullOrBlank() && !isReload) {
                lifecycleListener?.invoke(PageLifecycleEvent.SameDocument(url))
            }
            super.doUpdateVisitedHistory(view, url, isReload)
        }

        override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
            if (request?.isForMainFrame == true) {
                mainFrameHttpError = request.url.toString() to (errorResponse?.statusCode ?: 0)
            }
            if (request != null && errorResponse != null) attachPassThroughResponse(request.url.toString(), errorResponse)
            super.onReceivedHttpError(view, request, errorResponse)
        }

        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
            if (request != null) failPassThroughRequest(request.url.toString(), error?.description?.toString() ?: "net::ERR_FAILED")
            if (request?.isForMainFrame == true) {
                val url = request.url.toString()
                if (failedNavigationUrl == null) {
                    failedNavigationUrl = url
                    deliverNavigation(NavigationResult(url, 0, false, error?.description?.toString() ?: "Unknown error"))
                }
            }
            super.onReceivedError(view, request, error)
        }

        override fun onReceivedHttpAuthRequest(view: WebView?, handler: HttpAuthHandler?, host: String?, realm: String?) {
            val credentials = httpCredentials
            val attemptKey = "${host.orEmpty()}\u0000${realm.orEmpty()}"
            if (handler != null && credentials != null && credentialHostMatches(host) && httpAuthAttempts.add(attemptKey)) {
                handler.proceed(credentials.first, credentials.second)
                return
            }
            super.onReceivedHttpAuthRequest(view, handler, host, realm)
        }

        override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
            val handledByDelegate = super.onRenderProcessGone(view, detail)
            handleRendererGone(didCrash = detail?.didCrash() == true, handledByDelegate = handledByDelegate)
            // Returning false would kill the host app together with the renderer.
            return true
        }

        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
            // The domain filter is a security boundary: it runs before the host client and routes.
            if (request?.isForMainFrame == true) mainFrameRequestUrl = request.url.toString()
            if (request != null && !domainFilterAllows(request.url.toString())) return blockByDomainFilter(request)
            super.shouldInterceptRequest(view, request)?.let { return it }
            if (request == null) return null
            return interceptWithRoutes(request)
        }

        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            val url = request?.url?.toString()
            if (url != null && !domainFilterAllows(url)) {
                // Covers renderer-initiated navigations and server redirects (shouldInterceptRequest never sees redirects).
                if (request.isForMainFrame) {
                    if (request.isRedirect) {
                        // Report against the URL the navigation started from, and ignore the cancelled load's finish.
                        failedNavigationUrl = url
                        deliverNavigation(NavigationResult(mainFrameRequestUrl ?: url, 0, false, "${domainBlockedMessage(url)} (redirected to $url)"))
                    } else {
                        deliverNavigation(NavigationResult(url, 0, false, domainBlockedMessage(url)))
                    }
                }
                return true
            }
            if (request?.isForMainFrame == true &&
                (url.isNullOrBlank() || !isNavigationAllowedByConfig(url) || !guardAllows(url))
            ) {
                deliverNavigation(NavigationResult(url.orEmpty(), 0, false, "Navigation origin is not allowed by policy: ${url.orEmpty()}"))
                return true
            }
            return super.shouldOverrideUrlLoading(view, request)
        }
    }

    internal inner class KmpwrightWebChromeClient(delegate: WebChromeClient?) : ForwardingWebChromeClient(delegate) {
        override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage?): Boolean {
            if (consoleMessage == null) return super.onConsoleMessage(consoleMessage)
            if (disposed) return consoleMessage.message().orEmpty().startsWith(diagnosticsNonce) || super.onConsoleMessage(consoleMessage)
            val route = consoleRouter.route(androidConsoleType(consoleMessage.messageLevel()?.name),
                consoleMessage.message().orEmpty(), consoleMessage.sourceId(), consoleMessage.lineNumber())
            when (route) {
                // Sentinels are KMPwright's own channel: never forwarded to the host or logcat.
                AndroidConsoleRouter.Route.Internal -> return true
                AndroidConsoleRouter.Route.DocumentStarted -> {
                    diagnosticsListeners.forEach { it.onMainFrameDocumentCommitted() }
                    return true
                }
                is AndroidConsoleRouter.Route.Error -> {
                    diagnosticsListeners.forEach { it.onPageError(route.error) }
                    if (route.internal) return true
                }
                is AndroidConsoleRouter.Route.Console -> diagnosticsListeners.forEach { it.onConsoleMessage(route.message) }
                AndroidConsoleRouter.Route.HostOnly -> Unit
            }
            return super.onConsoleMessage(consoleMessage)
        }

        override fun onReceivedTitle(view: WebView?, title: String?) {
            refreshState()
            // Also fired when the page assigns document.title.
            lifecycleListener?.invoke(PageLifecycleEvent.TitleChanged(title ?: webView.title))
            super.onReceivedTitle(view, title)
        }

        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            refreshState(progress = newProgress)
            super.onProgressChanged(view, newProgress)
        }

        override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
            // An explicit Page.onDialog handler wins; otherwise the host client or native UI handles it.
            if (!automationHandlesDialogs()) {
                dialogListeners.forEach { it.onDialogObserved(DialogType.Alert, message.orEmpty(), null) }
                return super.onJsAlert(view, url, message, result)
            }
            handleDialog(DialogType.Alert, message.orEmpty(), null) { result?.confirm() }
            return true
        }

        override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
            // An explicit Page.onDialog handler wins; otherwise the host client or native UI handles it.
            if (!automationHandlesDialogs()) {
                dialogListeners.forEach { it.onDialogObserved(DialogType.Confirm, message.orEmpty(), null) }
                return super.onJsConfirm(view, url, message, result)
            }
            handleDialog(DialogType.Confirm, message.orEmpty(), null) { response ->
                if (response is DialogResponse.Dismiss) result?.cancel() else result?.confirm()
            }
            return true
        }

        override fun onJsBeforeUnload(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
            // An explicit Page.onDialog handler wins; otherwise the host client or native UI handles it.
            if (!automationHandlesDialogs()) {
                dialogListeners.forEach { it.onDialogObserved(DialogType.BeforeUnload, message.orEmpty(), null) }
                return super.onJsBeforeUnload(view, url, message, result)
            }
            handleDialog(DialogType.BeforeUnload, message.orEmpty(), null) { response ->
                if (response is DialogResponse.Dismiss) result?.cancel() else result?.confirm()
            }
            return true
        }

        override fun onJsPrompt(
            view: WebView?,
            url: String?,
            message: String?,
            defaultValue: String?,
            result: JsPromptResult?
        ): Boolean {
            // An explicit Page.onDialog handler wins; otherwise the host client or native UI handles it.
            if (!automationHandlesDialogs()) {
                dialogListeners.forEach { it.onDialogObserved(DialogType.Prompt, message.orEmpty(), defaultValue) }
                return super.onJsPrompt(view, url, message, defaultValue, result)
            }
            handleDialog(DialogType.Prompt, message.orEmpty(), defaultValue) { response ->
                when (response) {
                    is DialogResponse.Text -> result?.confirm(response.text)
                    is DialogResponse.Accept -> result?.confirm(defaultValue.orEmpty())
                    else -> result?.cancel()
                }
            }
            return true
        }
    }

    // ------------------------------------------------------- navigation state

    private fun isUsable(): Boolean = !disposed && !rendererGone && !destroyed

    private fun unusableError(): KmpwrightException = KmpwrightException(
        when {
            rendererGone -> "Page crashed: the Android WebView renderer process is gone"
            else -> "Page is closed"
        }
    )

    /** Main thread only. */
    private fun refreshState(loading: Boolean? = null, progress: Int? = null) {
        if (destroyed) return
        cachedUrl = webView.url
        cachedTitle = webView.title
        val previous = _state.value
        _state.value = previous.copy(
            url = cachedUrl,
            title = cachedTitle,
            isLoading = loading ?: previous.isLoading,
            progress = progress ?: previous.progress,
            canGoBack = webView.canGoBack(),
            canGoForward = webView.canGoForward(),
            rendererGone = rendererGone
        )
    }

    private fun deliverNavigation(result: NavigationResult) {
        synchronized(lock) { navigationCallback }?.invoke(result)
    }

    private fun rejectNavigation(view: WebView?, url: String?, message: String) {
        view?.stopLoading()
        failedNavigationUrl = url.orEmpty()
        deliverNavigation(NavigationResult(url.orEmpty(), 0, false, message))
    }

    private fun guardAllows(url: String): Boolean =
        try {
            navigationGuard?.invoke(url) != false
        } catch (error: Exception) {
            // A guard must never throw through a WebView callback (that would crash the app).
            Log.w(TAG, "Navigation guard failed for $url; denying", error)
            false
        }

    /** about:blank and the base URL of [loadHtml] are Kmpwright's own documents, not remote origins. */
    private fun isNavigationAllowedByConfig(url: String?): Boolean {
        if (config.allowedOriginRules.isEmpty()) return true
        if (url.isNullOrBlank()) return false
        if (url == "about:blank" || url == loadHtmlBaseUrl) return true
        return config.allowedOriginRules.any { originRuleMatches(it, url) }
    }

    private fun sameDocumentUrl(first: String, second: String): Boolean =
        first.substringBefore('#') == second.substringBefore('#')

    /** Main thread only. Starts tracking a navigation initiated through this controller. */
    private fun beginNavigation() {
        mainFrameHttpError = null
        failedNavigationUrl = null
    }

    /** Without a `Page.onDialog` handler, dialogs fall through to the WebView's native UI. */
    private fun automationHandlesDialogs(): Boolean = dialogListeners.any { it.hasDialogHandlers } && isUsable()

    private fun handleRendererGone(didCrash: Boolean, handledByDelegate: Boolean) {
        rendererGone = true
        val error = KmpwrightException(
            "Page crashed: the Android WebView renderer process " + if (didCrash) "crashed" else "was killed by the system"
        )
        Log.e(TAG, error.message.orEmpty())
        _state.value = _state.value.copy(isLoading = false, rendererGone = true)
        pendingEvaluations.toList().forEach { it.fail(error) }
        deliverNavigation(NavigationResult(cachedUrl.orEmpty(), 0, false, error.message))
        if (ownsWebView && !handledByDelegate && !destroyed) {
            destroyed = true
            webView.removeOnLayoutChangeListener(layoutListener)
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        }
    }

    // --------------------------------------------------------- domain filter

    override fun setDomainFilter(domains: Set<String>): Boolean {
        domainFilter = domains.takeIf { it.isNotEmpty() }?.let(::DomainFilter)
        return true
    }

    override suspend fun setDomainFilterAsync(domains: Set<String>) {
        setDomainFilter(domains)
    }

    /** Network URLs (http, https, ws, wss) must target an allowed host; local schemes (data:, blob:, about:) pass. */
    private fun domainFilterAllows(url: String): Boolean {
        val filter = domainFilter ?: return true
        val scheme = url.substringBefore(':', "").lowercase()
        val httpUrl = when (scheme) {
            "http", "https" -> url
            "ws" -> "http" + url.substring(2)
            "wss" -> "https" + url.substring(3)
            else -> return true
        }
        val host = canonicalHttpHost(httpUrl) ?: return false
        return filter.isAllowed(host)
    }

    private fun domainBlockedMessage(url: String): String =
        "Blocked by KMPwright domain filter: ${canonicalHttpHost(url) ?: url}"

    /** Fails the request as a network error, exactly like a route abort. */
    private fun blockByDomainFilter(request: WebResourceRequest): WebResourceResponse {
        val url = request.url.toString()
        val req = Request(url = url, method = request.method, headers = request.requestHeaders.orEmpty().toMap(),
            resourceType = if (request.isForMainFrame) "document" else "other")
        onRequestStartedInternal(req)
        return abortResponse(req, "blockedbyclient")
    }

    // ------------------------------------------------------------- routing

    private fun interceptWithRoutes(request: WebResourceRequest): WebResourceResponse? {
        val url = request.url.toString()
        trackCookieUrl(url)
        // WebView exposes no initiator type: the main-frame document is "document", everything else "other".
        val req = Request(url = url, method = request.method, headers = request.requestHeaders.orEmpty().toMap(),
            resourceType = if (request.isForMainFrame) "document" else "other")
        onRequestStartedInternal(req)
        return try {
            when (val routeAction = resolveRouteAction(req)) {
                is RouteAction.Fulfill -> fulfillResponse(req, routeAction)
                is RouteAction.Abort -> abortResponse(req, routeAction.errorCode)
                is RouteAction.Continue -> if (routeAction.hasOverrides()) {
                    performNetworkRequest(req, routeAction)
                } else {
                    passThrough(req, request.isForMainFrame)
                    null
                }
                RouteAction.Fallback -> {
                    passThrough(req, request.isForMainFrame)
                    null
                }
            }
        } catch (error: Throwable) {
            Log.w(TAG, "Route for $url failed; aborting the request", error)
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
            runBlocking { withTimeout(config.timeouts.handler) { decision.await() } }
        } catch (_: TimeoutCancellationException) {
            decision.cancel()
            Log.w(TAG, "Route handler for ${request.url} did not finish within ${config.timeouts.handler}ms; aborting the request")
            RouteAction.Abort("timedout")
        } catch (error: Throwable) {
            Log.w(TAG, "Route handler for ${request.url} threw; aborting the request", error)
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
    private fun abortResponse(request: Request, errorCode: String): WebResourceResponse {
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

    // ------------------------------------------------------- request events

    private fun onRequestStartedInternal(request: Request) {
        pendingRequests[request.id] = request
        requestListeners.forEach { it.onRequestStarted(request) }
        scheduleIdleCheck()
    }

    /**
     * WebView loads [request] itself. The main-frame document stays pending until its page
     * finishes (or fails, or another document replaces it); subresources finish shortly after.
     */
    private fun passThrough(request: Request, isMainFrame: Boolean) {
        synchronized(passThroughRequests) {
            passThroughRequests.remove(request.url)
            passThroughRequests[request.url] = request
        }
        if (isMainFrame) {
            pendingMainFrameRequest?.let { previous -> finishRequestWithoutResponse(previous.id) }
            pendingMainFrameRequest = request
            // Never let a document that never finishes keep the page out of network idle.
            requestFinishJobs.remove(request.id)?.cancel()
            requestFinishJobs[request.id] = scope.launch {
                delay(config.timeouts.navigation)
                if (pendingMainFrameRequest?.id == request.id) pendingMainFrameRequest = null
                finishRequestWithoutResponse(request.id)
            }
        } else {
            scheduleRequestFinish(request.id)
        }
    }

    private fun passThroughRequest(url: String): Request? = synchronized(passThroughRequests) { passThroughRequests[url] }

    /** Main thread. Completes the pending main-frame document with the status WebView reported. */
    private fun completeMainFrameRequest(url: String, status: Int) {
        val request = pendingMainFrameRequest ?: return
        pendingMainFrameRequest = null
        val redirected = mainFrameRequestUrl?.let { sameDocumentUrl(it, request.url) } == true
        if (!sameDocumentUrl(request.url, url) && !redirected) {
            finishRequestWithoutResponse(request.id)
            return
        }
        completeRequest(request, Response(url = url, status = status, statusText = httpReasonPhrase(status), id = request.id))
    }

    /** `onReceivedHttpError` is the only WebView callback carrying status and headers of a page-loaded request. */
    private fun attachPassThroughResponse(url: String, errorResponse: WebResourceResponse) {
        val request = passThroughRequest(url) ?: return
        val status = errorResponse.statusCode
        val response = Response(
            url = url,
            status = status,
            statusText = errorResponse.reasonPhrase?.takeIf { it.isNotBlank() } ?: httpReasonPhrase(status),
            headers = errorResponse.responseHeaders.orEmpty(),
            id = request.id
        )
        if (pendingMainFrameRequest?.id == request.id) pendingMainFrameRequest = null
        if (pendingRequests.containsKey(request.id)) {
            completeRequest(request, response)
        } else {
            // Already finished by the short subresource timer: attach the late response to its record.
            requestListeners.forEach { it.onResponseReceived(response) }
        }
    }

    private fun failPassThroughRequest(url: String, error: String) {
        val request = synchronized(passThroughRequests) { passThroughRequests.remove(url) } ?: return
        if (pendingMainFrameRequest?.id == request.id) pendingMainFrameRequest = null
        failRequest(request, error)
    }

    private fun scheduleRequestFinish(requestId: String) {
        requestFinishJobs.remove(requestId)?.cancel()
        requestFinishJobs[requestId] = scope.launch {
            delay(250)
            finishRequestWithoutResponse(requestId)
        }
    }

    private fun finishRequestWithoutResponse(requestId: String) {
        val request = pendingRequests.remove(requestId)
        requestFinishJobs.remove(requestId)?.cancel()
        request?.let { req -> requestListeners.forEach { it.onRequestFinished(req) } }
        scheduleIdleCheck()
    }

    private fun completeRequest(request: Request, response: Response) {
        pendingRequests.remove(request.id)
        requestFinishJobs.remove(request.id)?.cancel()
        requestListeners.forEach { listener ->
            listener.onResponseReceived(response)
            listener.onRequestFinished(request)
        }
        scheduleIdleCheck()
    }

    private fun failRequest(request: Request, error: String) {
        pendingRequests.remove(request.id)
        requestFinishJobs.remove(request.id)?.cancel()
        requestListeners.forEach { it.onRequestFailed(request, error) }
        scheduleIdleCheck()
    }

    private fun scheduleIdleCheck() = runOnMain {
        requestIdleCheckJob?.cancel()
        requestIdleCheckJob = scope.launch {
            delay(requestIdleTimeoutMs)
            checkNetworkIdle()
        }
    }

    private fun checkNetworkIdle() {
        if (pendingRequests.isEmpty()) {
            requestListeners.forEach { listener ->
                listener.onResponseReceived(Response(url = "", status = 0, statusText = "idle"))
            }
        }
    }

    private fun handleDialog(type: DialogType, message: String, defaultPrompt: String?, complete: (DialogResponse) -> Unit) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            var response: DialogResponse = DialogResponse.Dismiss
            try {
                response = dialogListeners.firstOrNull { it.hasDialogHandlers }
                    ?.onDialogAsync(type, message, defaultPrompt) ?: DialogResponse.Dismiss
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // A caller's listener must not leave WebView waiting indefinitely.
                Log.w(TAG, "Dialog handler failed; dismissing the dialog", error)
            } finally {
                complete(response)
            }
        }
    }

    // ------------------------------------------------------------- listeners

    override fun setNavigationCallback(callback: ((NavigationResult) -> Unit)?) {
        synchronized(lock) { navigationCallback = callback }
    }

    /** Same-document navigations (doUpdateVisitedHistory outside a load) and titles (onReceivedTitle). */
    override fun setLifecycleListener(listener: ((PageLifecycleEvent) -> Unit)?) {
        lifecycleListener = listener
    }

    /** onReceivedHttpError reports only statuses >= 400; successful loads are reported as 200. */
    override val reportsHttpStatus: Boolean get() = false

    override fun setNavigationGuard(guard: ((String) -> Boolean)?) {
        navigationGuard = guard
    }

    override fun addRequestListener(listener: RequestListener) {
        requestListeners.add(listener)
    }

    override fun removeRequestListener(listener: RequestListener) {
        requestListeners.remove(listener)
    }

    override fun getPendingRequestCount(): Int = pendingRequests.size

    override fun resetRequestCount() {
        pendingRequests.clear()
    }

    override fun setRequestIdleTimeout(timeoutMs: Long) {
        requestIdleTimeoutMs = timeoutMs
    }

    override fun addDialogListener(listener: DialogListener) {
        dialogListeners.add(listener)
    }

    override fun removeDialogListener(listener: DialogListener) {
        dialogListeners.remove(listener)
    }

    override fun addDiagnosticsListener(listener: PageDiagnosticsListener) {
        diagnosticsListeners.add(listener)
    }

    override fun removeDiagnosticsListener(listener: PageDiagnosticsListener) {
        diagnosticsListeners.remove(listener)
    }

    override fun addRouteHandler(urlPattern: String, handler: suspend (Route) -> Unit) {
        routeHandlers.removeAll { it.pattern == urlPattern }
        routeHandlers.add(RouteRegistration(urlPattern, Regex(urlPattern), handler))
    }

    override fun removeRouteHandler(urlPattern: String) {
        routeHandlers.removeAll { it.pattern == urlPattern }
    }

    override fun removeAllRouteHandlers() {
        routeHandlers.clear()
    }

    // --------------------------------------------------------------- cookies

    override fun setCookie(cookie: Cookie) {
        val cookieManager = CookieManager.getInstance()
        val targetUrl = resolveCookieTargetUrl(cookie.domain, cookie.secure) ?: return
        cookieManager.setCookie(targetUrl, cookieHeader(cookie))
        cookieManager.flush()
        trackCookieUrl(targetUrl)
        trackCookieUrl(cookiePathUrl(targetUrl, cookie))
    }

    // CookieManager's callback forms need a Looper thread: run them on main.
    override suspend fun setCookieAsync(cookie: Cookie) = onMain {
        val header = cookieHeader(cookie)
        val targetUrl = resolveCookieTargetUrl(cookie.domain, cookie.secure)
            ?: throw KmpwrightException("Invalid cookie domain: ${cookie.domain}")
        suspendCancellableCoroutine { continuation ->
            val cookieManager = CookieManager.getInstance()
            cookieManager.setCookie(targetUrl, header) { accepted ->
                if (accepted == true) {
                    cookieManager.flush()
                    trackCookieUrl(targetUrl)
                    // getCookieInfo only lists cookies visible at a URL: remember the cookie's path too.
                    trackCookieUrl(cookiePathUrl(targetUrl, cookie))
                    if (continuation.isActive) continuation.resume(Unit)
                } else if (continuation.isActive) {
                    continuation.resumeWithException(KmpwrightException("Android WebView rejected cookie ${cookie.name}"))
                }
            }
        }
    }

    private fun cookieHeader(cookie: Cookie): String {
        fun requireCookieToken(label: String, value: String) {
            if (value.any { it == ';' || it == '\r' || it == '\n' }) {
                throw KmpwrightException("Cookie $label must not contain ';', CR or LF: ${cookie.name}")
            }
        }
        if (cookie.name.isBlank() || cookie.name.any { it == '=' || it.isWhitespace() }) {
            throw KmpwrightException("Invalid cookie name: '${cookie.name}'")
        }
        requireCookieToken("name", cookie.name)
        requireCookieToken("value", cookie.value)
        requireCookieToken("path", cookie.path)
        requireCookieToken("domain", cookie.domain)
        cookie.sameSite?.let { requireCookieToken("SameSite", it) }
        return buildString {
            append("${cookie.name}=${cookie.value}")
            append("; path=${cookie.path}")
            if (!cookie.hostOnly) append("; domain=${cookie.domain.removePrefix(".")}")
            cookie.expires?.let {
                val date = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", java.util.Locale.US)
                    .apply { timeZone = java.util.TimeZone.getTimeZone("GMT") }
                    .format(java.util.Date((it * 1000).toLong()))
                append("; expires=$date")
            }
            if (cookie.secure) append("; secure")
            if (cookie.httpOnly) append("; HttpOnly")
            cookie.sameSite?.let { append("; SameSite=$it") }
        }
    }

    override fun getCookie(name: String, domain: String, callback: (Cookie?) -> Unit) {
        for (candidateUrl in candidateCookieUrls(domain)) {
            val cookie = readCookieMetadata(candidateUrl).firstOrNull { it.name == name }
            if (cookie != null) {
                callback(cookie)
                return
            }
        }
        callback(null)
    }

    override fun getAllCookies(callback: (List<Cookie>) -> Unit) {
        val cookiesByKey = LinkedHashMap<String, Cookie>()
        for (candidateUrl in candidateCookieUrls(null)) {
            for (cookie in readCookieMetadata(candidateUrl)) {
                val key = "${cookie.domain}\u0000${cookie.path}\u0000${cookie.name}"
                cookiesByKey.putIfAbsent(key, cookie)
            }
        }
        callback(cookiesByKey.values.toList())
    }

    /**
     * CookieManager has no delete-by-key API: a cookie is removed by overwriting its exact key
     * (name, host-only or `.domain`, path) with an expired one. A fixed `path=/; domain=host`
     * would miss host-only cookies and cookies scoped to a sub-path.
     */
    private fun expiredCookieWrite(cookie: Cookie): Pair<String, String> {
        val host = cookie.domain.removePrefix(".")
        val path = cookie.path.ifEmpty { "/" }
        val header = buildString {
            append("${cookie.name}=; Max-Age=0; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=$path")
            if (!cookie.hostOnly) append("; domain=$host")
            // Only a secure origin may overwrite a Secure cookie.
            if (cookie.secure) append("; Secure")
            cookie.sameSite?.let { append("; SameSite=$it") }
        }
        return "https://$host$path" to header
    }

    /** Every stored cookie named [name] whose domain matches [domain] (any path, host-only or not). */
    private fun matchingCookies(name: String, domain: String): List<Cookie> {
        val wanted = domain.removePrefix(".")
        val urls = candidateCookieUrls(null) + listOfNotNull(normalizeCookieUrl(domain))
        return urls.distinct().flatMap { url -> runCatching { readCookieMetadata(url) }.getOrDefault(emptyList()) }
            .filter { it.name == name && it.domain.removePrefix(".") == wanted }
            .distinctBy { Triple(it.domain, it.path, it.hostOnly) }
            .ifEmpty { listOf(Cookie(name, "", wanted, "/"), Cookie(name, "", ".$wanted", "/", hostOnly = false)) }
    }

    override fun deleteCookie(name: String, domain: String) {
        val cookieManager = CookieManager.getInstance()
        for (cookie in matchingCookies(name, domain)) {
            val (url, header) = expiredCookieWrite(cookie)
            cookieManager.setCookie(url, header)
        }
        cookieManager.flush()
    }

    override suspend fun deleteCookieAsync(name: String, domain: String) {
        val cookies = onMain { matchingCookies(name, domain) }
        cookies.forEach { deleteCookieAsync(it) }
    }

    override suspend fun deleteCookieAsync(cookie: Cookie) = onMain {
        val (url, header) = expiredCookieWrite(cookie)
        suspendCancellableCoroutine { continuation ->
            CookieManager.getInstance().setCookie(url, header) {
                // A false result means that no matching cookie existed; the
                // deletion command was still acknowledged successfully.
                CookieManager.getInstance().flush()
                if (continuation.isActive) continuation.resume(Unit)
            }
        }
    }

    override fun deleteAllCookies() {
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        observedCookieUrls.clear()
    }

    override suspend fun deleteAllCookiesAsync() = onMain {
        suspendCancellableCoroutine { continuation ->
            CookieManager.getInstance().removeAllCookies { _ ->
                CookieManager.getInstance().flush()
                observedCookieUrls.clear()
                // removeAllCookies reports whether the store changed, not whether
                // the command failed (false is the normal empty-store result).
                if (continuation.isActive) continuation.resume(Unit)
            }
        }
    }

    private fun cookiePathUrl(targetUrl: String, cookie: Cookie): String =
        targetUrl.trimEnd('/') + cookie.path.ifEmpty { "/" }.let { if (it.startsWith('/')) it else "/$it" }

    private fun trackCookieUrl(url: String?) {
        val normalized = normalizeCookieUrl(url) ?: return
        observedCookieUrls.add(normalized)
    }

    private fun candidateCookieUrls(domainOrUrl: String?): List<String> {
        if (domainOrUrl != null) return listOfNotNull(normalizeCookieUrl(domainOrUrl))
        val candidates = LinkedHashSet<String>()
        normalizeCookieUrl(getUrl())?.let(candidates::add)
        candidates.addAll(observedCookieUrls)
        return candidates.toList()
    }

    private fun resolveCookieTargetUrl(domainOrUrl: String, secure: Boolean): String? {
        val currentScheme = normalizeCookieUrl(getUrl())?.let { Uri.parse(it).scheme }
        return normalizeCookieUrl(domainOrUrl, if (secure) "https" else currentScheme ?: "http")
    }

    private fun normalizeCookieUrl(urlOrDomain: String?, fallbackScheme: String = "https"): String? {
        val trimmed = urlOrDomain?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        val parsed = Uri.parse(trimmed)
        if (parsed.scheme != null && parsed.scheme !in setOf("http", "https")) return null
        if (!parsed.scheme.isNullOrBlank() && !parsed.host.isNullOrBlank()) {
            return parsed.buildUpon().path(parsed.path ?: "/").build().toString()
        }

        val host = trimmed.removePrefix(".").substringBefore("/").trim()
        if (host.isEmpty()) return null
        return "$fallbackScheme://$host/"
    }

    private fun readCookieMetadata(url: String): List<Cookie> {
        if (!androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.GET_COOKIE_INFO)) {
            throw KmpwrightUnsupportedException("Cookie attribute reads require WebView GET_COOKIE_INFO support")
        }
        val requestUrl = url.toHttpUrl()
        return androidx.webkit.CookieManagerCompat.getCookieInfo(CookieManager.getInstance(), url).mapNotNull { header ->
            val parsed = okhttp3.Cookie.parse(requestUrl, header) ?: return@mapNotNull null
            val attributes = header.split(';').drop(1).associate { field ->
                val pair = field.trim().split('=', limit = 2)
                pair[0].lowercase() to pair.getOrElse(1) { "" }
            }
            if ("partitioned" in attributes) {
                // Partitioned (CHIPS) state cannot be round-tripped through the
                // unpartitioned Cookie model; skip it rather than failing the whole export.
                Log.w(TAG, "Skipping partitioned cookie ${parsed.name} for $url")
                return@mapNotNull null
            }
            // Chromium serializes the stored domain: host-only cookies keep
            // the bare host, domain cookies carry a leading dot.
            val storedDomain = attributes["domain"]?.trim()?.takeIf { it.isNotEmpty() }
            Cookie(
                parsed.name, parsed.value,
                domain = storedDomain ?: parsed.domain,
                path = parsed.path,
                expires = if (parsed.persistent) parsed.expiresAt / 1000.0 else null,
                secure = parsed.secure, httpOnly = parsed.httpOnly, sameSite = attributes["samesite"],
                hostOnly = storedDomain?.startsWith(".") != true
            )
        }
    }

    // ------------------------------------------------------- HTTP transport

    override fun setExtraHttpHeaders(headers: Map<String, String>): Boolean {
        // Android WebView has no backend-owned API that applies arbitrary
        // headers to all fetch/XHR subrequests. Returning false makes the
        // common API fail explicitly instead of exposing values to page JS.
        return httpRequestPolicy.supportsExtraHeaders(headers)
    }

    override fun setHttpCredentials(username: String?, password: String?): Boolean {
        val currentUrl = getUrl()
        httpRequestPolicy.setCredentials(username, password, currentUrl)
        httpCredentials = if (username != null && password != null) username to password else null
        httpCredentialHost = if (httpCredentials == null) null else httpHost(currentUrl)
        return true
    }

    override suspend fun setExtraHttpHeadersAsync(headers: Map<String, String>) {
        if (!httpRequestPolicy.supportsExtraHeaders(headers)) {
            throw KmpwrightException(
                "This backend cannot apply extra HTTP headers securely without exposing them to page JavaScript."
            )
        }
    }

    override suspend fun setHttpCredentialsAsync(username: String?, password: String?) {
        setHttpCredentials(username, password)
    }

    private fun httpHost(url: String?): String? {
        val parsed = url?.let(Uri::parse) ?: return null
        if (parsed.scheme != "http" && parsed.scheme != "https") return null
        return parsed.host?.lowercase()
    }

    /** Credentials are bound to one host, mirroring [MobileHttpRequestPolicy]'s origin scope. */
    private fun credentialHostMatches(challengeHost: String?): Boolean {
        val host = challengeHost?.let { Uri.parse("http://$it").host?.lowercase() } ?: return false
        val bound = httpCredentialHost ?: httpHost(cachedUrl)?.also { httpCredentialHost = it } ?: return false
        return bound == host
    }

    private fun buildRequestHeaders(url: String): Map<String, String> {
        if (httpCredentials != null && httpCredentialHost == null) httpCredentialHost = httpHost(url)
        return httpRequestPolicy.requestHeaders(url) { credentials ->
            Base64.encodeToString(credentials.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        }
    }

    // ----------------------------------------------------------- navigation

    override fun loadUrl(url: String) {
        if (!domainFilterAllows(url)) {
            runOnMain { deliverNavigation(NavigationResult(url, 0, false, domainBlockedMessage(url))) }
            return
        }
        trackCookieUrl(url)
        val headers = buildRequestHeaders(url)
        runOnMain {
            if (!isUsable()) {
                deliverNavigation(NavigationResult(url, 0, false, unusableError().message))
                return@runOnMain
            }
            beginNavigation()
            if (headers.isEmpty()) webView.loadUrl(url) else webView.loadUrl(url, headers)
        }
    }

    override fun loadHtml(content: String, baseUrl: String?) {
        trackCookieUrl(baseUrl)
        runOnMain {
            if (!isUsable()) {
                deliverNavigation(NavigationResult(baseUrl ?: "about:blank", 0, false, unusableError().message))
                return@runOnMain
            }
            beginNavigation()
            loadHtmlBaseUrl = baseUrl
            webView.loadDataWithBaseURL(baseUrl, content, "text/html", "UTF-8", null)
        }
    }

    override suspend fun canTraverseHistory(delta: Int): Boolean? = onMain {
        if (!isUsable()) null else webView.canGoBackOrForward(delta)
    }

    /**
     * Not applied natively: with targetSdk 33+ WebView derives `prefers-color-scheme` from the host
     * context's theme (`isLightTheme`), which cannot change for an existing WebView, and
     * `WebSettingsCompat.setAlgorithmicDarkeningAllowed` only darkens pages when that theme is already
     * dark. The page therefore keeps its JavaScript (`matchMedia` + `color-scheme`) fallback.
     */
    override suspend fun setColorSchemeOverride(scheme: ColorScheme?): Boolean = false

    override fun goBack() = runOnMain { if (isUsable()) { beginNavigation(); webView.goBack() } }
    override fun goForward() = runOnMain { if (isUsable()) { beginNavigation(); webView.goForward() } }
    override fun reload() = runOnMain { if (isUsable()) { beginNavigation(); webView.reload() } }

    /** Live value on the main thread; otherwise the value last observed by the WebView callbacks. */
    override fun getUrl(): String? {
        if (isMainThread() && isUsable()) cachedUrl = webView.url
        return cachedUrl
    }

    override fun getTitle(): String? {
        if (isMainThread() && isUsable()) cachedTitle = webView.title
        return cachedTitle
    }

    // ------------------------------------------------------------ scripting

    override suspend fun addInitScript(script: String): String = onMain {
        if (!androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT)) {
            throw KmpwrightException("This Android WebView does not support document-start scripts")
        }
        if (!isUsable()) throw unusableError()
        // Scripts must not run in origins the configuration does not allow.
        val origins = config.allowedOriginRules.ifEmpty { setOf("*") }
        val registration = try {
            androidx.webkit.WebViewCompat.addDocumentStartJavaScript(webView, script, origins)
        } catch (error: IllegalArgumentException) {
            throw KmpwrightException("Android WebView rejected allowedOriginRules $origins for init scripts: ${error.message}")
        }
        val id = java.util.UUID.randomUUID().toString()
        initScripts[id] = registration
        id
    }

    override suspend fun removeInitScript(id: String) = onMain {
        val registration = initScripts.remove(id) ?: throw KmpwrightException("Unknown init-script identifier: $id")
        registration.remove()
    }

    private fun evaluate(script: String, onResult: (String?) -> Unit, onFailure: (Throwable) -> Unit) {
        val pending = PendingEvaluation(onResult, onFailure)
        pendingEvaluations.add(pending)
        runOnMain {
            if (!isUsable()) {
                pending.fail(unusableError())
                return@runOnMain
            }
            try {
                webView.evaluateJavascript(script) { pending.complete(it) }
            } catch (error: Exception) {
                pending.fail(error)
            }
        }
    }

    override fun evaluateJavaScript(script: String, callback: (String?) -> Unit) =
        evaluate(script, callback) { callback(null) }

    override fun injectJavaScript(script: String, callback: ((String?) -> Unit)?) =
        evaluate(script, { callback?.invoke(it) }) { callback?.invoke(null) }

    /** Fails (instead of hanging) when the page is closed or its renderer is gone. */
    override suspend fun evaluateJavaScriptAsync(script: String): String? = suspendCancellableCoroutine { continuation ->
        evaluate(
            script,
            { result -> if (continuation.isActive) continuation.resume(result) },
            { error -> if (continuation.isActive) continuation.resumeWithException(error) }
        )
    }

    // ------------------------------------------------------ capture/viewport

    override suspend fun captureScreenshot(options: ScreenshotOptions): ScreenshotResult =
        AndroidScreenshotController(webView, config).capture(options)

    override fun takeScreenshot(options: ScreenshotOptions, callback: (ByteArray?, Int, Int) -> Unit) {
        val delivered = AtomicBoolean(false)
        val deliver = { bytes: ByteArray?, width: Int, height: Int ->
            if (delivered.compareAndSet(false, true)) callback(bytes, width, height)
        }
        val job = scope.launch {
            try {
                val result = captureScreenshot(options)
                deliver(result.bytes, result.width, result.height)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "Screenshot failed", error)
                deliver(null, 0, 0)
            }
        }
        // A disposed controller's scope never runs the job; still answer the caller.
        job.invokeOnCompletion { deliver(null, 0, 0) }
    }

    /** CSS pixels. Live on the main thread; otherwise the last laid-out size. */
    override fun getViewportSize(): Pair<Int, Int> {
        if (isMainThread() && !destroyed) cachedViewportPx = webView.width to webView.height
        val density = webView.displayDensity()
        val (width, height) = cachedViewportPx
        return (width / density).roundToInt() to (height / density).roundToInt()
    }

    /** CSS pixels; converted to device pixels with the display density. */
    override fun setViewportSize(width: Int, height: Int) {
        require(width > 0 && height > 0) { "Viewport size must be positive: ${width}x$height" }
        runOnMain { if (!destroyed) webView.applyCssViewportSize(width, height) }
    }

    override suspend fun setViewportSizeAsync(width: Int, height: Int) {
        require(width > 0 && height > 0) { "Viewport size must be positive: ${width}x$height" }
        onMain {
            if (destroyed) throw unusableError()
            webView.applyCssViewportSize(width, height)
        }
    }

    /**
     * Sets the initial page scale (1.0 = 100% CSS zoom) used for documents
     * loaded after this call; the current document keeps its scale. Host
     * WebSettings are not modified.
     */
    override fun setZoomScale(scale: Double) {
        require(scale > 0) { "Zoom scale must be positive: $scale" }
        zoomScale = scale
        runOnMain {
            if (!destroyed) webView.setInitialScale((scale * webView.displayDensity() * 100).roundToInt())
        }
    }

    override fun getZoomScale(): Double = zoomScale

    fun getWebView(): WebView = webView

    override fun setUserAgent(userAgent: String) = runOnMain {
        if (!destroyed) webView.settings.userAgentString = userAgent
    }

    override suspend fun setUserAgentAsync(userAgent: String) = onMain {
        if (destroyed) throw unusableError()
        webView.settings.userAgentString = userAgent
    }

    override fun setDeviceScaleFactor(scale: Double): Unit =
        throw KmpwrightUnsupportedException("Android WebView device scale is determined by the host display")

    override fun validatePdfOptions(options: PdfOptions) = validateAndroidPdfOptions(options)

    override suspend fun generatePdfAsync(options: PdfOptions): ByteArray {
        if (!isUsable()) throw unusableError()
        return captureAndroidPdf(webView, options)
    }

    override fun generatePdf(options: PdfOptions, callback: (ByteArray?) -> Unit) {
        val delivered = AtomicBoolean(false)
        val deliver = { bytes: ByteArray? -> if (delivered.compareAndSet(false, true)) callback(bytes) }
        val job = scope.launch {
            try {
                deliver(generatePdfAsync(options))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "PDF capture failed", error)
                deliver(null)
            }
        }
        job.invokeOnCompletion { deliver(null) }
    }

    // -------------------------------------------------------------- dispose

    override fun dispose() {
        if (disposed) return
        disposed = true
        diagnosticsListeners.clear()
        routeHandlers.clear()
        routeScope.cancel()
        requestFinishJobs.values.forEach { it.cancel() }
        requestFinishJobs.clear()
        pendingRequests.clear()
        pendingEvaluations.toList().forEach { it.fail(KmpwrightException("Page is closed")) }
        // Handler posts run even for detached WebViews (View.post would not).
        runOnMain { releaseOnMain() }
    }

    override suspend fun disposeAsync() = withContext(NonCancellable + Dispatchers.Main.immediate) { dispose() }

    private fun releaseOnMain() {
        requestIdleCheckJob?.cancel()
        scope.cancel()
        if (destroyed) return
        initScripts.values.forEach { runCatching { it.remove() } }
        initScripts.clear()
        diagnosticsScript?.let { runCatching { it.remove() } }
        diagnosticsScript = null
        webView.removeOnLayoutChangeListener(layoutListener)
        webView.stopLoading()
        webView.webChromeClient = originalWebChromeClient
        webView.webViewClient = originalWebViewClient
        if (ownsWebView) {
            destroyed = true
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.removeAllViews()
            webView.destroy()
        }
    }
}

/** Main thread only. Applies a CSS-pixel size without replacing the parent's LayoutParams type. */
internal fun WebView.applyCssViewportSize(width: Int, height: Int) {
    val density = displayDensity()
    val widthPx = (width * density).roundToInt()
    val heightPx = (height * density).roundToInt()
    if (layoutParams == null) {
        layoutParams = ViewGroup.LayoutParams(widthPx, heightPx)
    } else {
        updateLayoutParams {
            this.width = widthPx
            this.height = heightPx
        }
    }
    if (parent == null) {
        // Detached WebViews never receive a layout pass; size them directly.
        measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY)
        )
        layout(0, 0, widthPx, heightPx)
    }
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
