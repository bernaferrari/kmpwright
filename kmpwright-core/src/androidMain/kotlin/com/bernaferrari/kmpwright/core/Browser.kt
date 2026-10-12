package com.bernaferrari.kmpwright.core

import android.content.Context
import android.net.Uri
import android.os.Looper
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

class AndroidWebViewController(
    private val webView: WebView,
    private val ownsWebView: Boolean = false,
    private val config: KmpwrightConfig = KmpwrightConfig(),
    private val delegateWebViewClient: android.webkit.WebViewClient? = null,
    private val delegateWebChromeClient: android.webkit.WebChromeClient? = null
) : WebViewController {
    override val supportsPromiseEvaluation: Boolean get() = false
    private val originalWebViewClient = webView.webViewClient
    private val originalWebChromeClient = webView.webChromeClient
    private val initScripts = mutableMapOf<String, androidx.webkit.ScriptHandler>()

    override suspend fun addInitScript(script: String): String = kotlinx.coroutines.withContext(Dispatchers.Main) {
        if (!androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT)) {
            throw KmpwrightException("This Android WebView does not support document-start scripts")
        }
        val id = java.util.UUID.randomUUID().toString()
        initScripts[id] = androidx.webkit.WebViewCompat.addDocumentStartJavaScript(webView, script, setOf("*"))
        id
    }

    override suspend fun removeInitScript(id: String) = kotlinx.coroutines.withContext(Dispatchers.Main) {
        val registration = initScripts.remove(id) ?: throw KmpwrightException("Unknown init-script identifier: $id")
        registration.remove()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val lock = Any()
    private var navigationCallback: ((NavigationResult) -> Unit)? = null
    @Volatile private var navigationGuard: ((String) -> Boolean)? = null
    private val requestListeners = Collections.synchronizedList(mutableListOf<RequestListener>())
    private val dialogListeners = Collections.synchronizedList(mutableListOf<DialogListener>())
    private val pendingRequests = ConcurrentHashMap<String, Request>()
    private val pendingRouteResults = ConcurrentHashMap<String, RouteAction>()
    private var requestIdleTimeoutMs = 500L
    private var requestIdleCheckJob: kotlinx.coroutines.Job? = null
    private val requestFinishJobs = ConcurrentHashMap<String, kotlinx.coroutines.Job>()
    private val routeHandlers = ConcurrentHashMap<Regex, suspend (Route) -> RouteHandler>()
    private val observedCookieUrls = Collections.synchronizedSet(mutableSetOf<String>())
    private val httpRequestPolicy = MobileHttpRequestPolicy()
    private var pendingNavigationUrl: String? = null
    @Volatile
    private var pendingNavigationHttpStatus: Int = 200

    init {
        webView.webViewClient = object : android.webkit.WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                if (config.allowedOriginRules.isNotEmpty() &&
                    (url.isNullOrBlank() || config.allowedOriginRules.none { originRuleMatches(it, url) })
                ) {
                    view?.stopLoading()
                    navigationCallback?.invoke(NavigationResult(url.orEmpty(), 0, false, "Navigation origin is not allowed by KmpwrightConfig: ${url.orEmpty()}"))
                    return
                }
                if (url.isNullOrBlank() || navigationGuard?.invoke(url) == false) {
                    view?.stopLoading()
                    navigationCallback?.invoke(NavigationResult(url.orEmpty(), 0, false, "Navigation denied by action policy: ${url.orEmpty()}"))
                    return
                }
                pendingNavigationUrl = url
                pendingNavigationHttpStatus = 200
                delegateWebViewClient?.onPageStarted(view, url, favicon)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                trackCookieUrl(url)
                val callback = synchronized(lock) { navigationCallback }
                val status = pendingNavigationHttpStatus
                val navUrl = url ?: pendingNavigationUrl ?: ""
                val isSuccess = status in 200..399 &&
                    (config.allowedOriginRules.isEmpty() || config.allowedOriginRules.any { originRuleMatches(it, navUrl) }) &&
                    navigationGuard?.invoke(navUrl) != false
                callback?.invoke(if (isSuccess) NavigationResult(navUrl, status, true) else NavigationResult(navUrl, status, false, "Committed navigation origin is not allowed by KmpwrightConfig: $navUrl"))
                delegateWebViewClient?.onPageFinished(view, url)
            }

            override fun onReceivedHttpError(view: WebView?, request: android.webkit.WebResourceRequest?, errorResponse: android.webkit.WebResourceResponse?) {
                super.onReceivedHttpError(view, request, errorResponse)
                val isMainFrame = request?.isForMainFrame == true
                if (isMainFrame) {
                    val statusCode = errorResponse?.statusCode ?: 0
                    pendingNavigationHttpStatus = statusCode
                }
                delegateWebViewClient?.onReceivedHttpError(view, request, errorResponse)
            }

            override fun onReceivedError(view: WebView?, request: android.webkit.WebResourceRequest?, error: android.webkit.WebResourceError?) {
                super.onReceivedError(view, request, error)
                val isMainFrame = request?.isForMainFrame == true
                if (isMainFrame) {
                    val errorMessage = error?.description?.toString() ?: "Unknown error"
                    val url = request.url.toString()
                    val callback = synchronized(lock) { navigationCallback }
                    callback?.invoke(NavigationResult(url, 0, false, errorMessage))
                }
                delegateWebViewClient?.onReceivedError(view, request, error)
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: android.webkit.WebResourceRequest?
            ): android.webkit.WebResourceResponse? {
                delegateWebViewClient?.shouldInterceptRequest(view, request)?.let { return it }
                request?.let {
                    val url = it.url.toString()
                    trackCookieUrl(url)
                    val method = it.method
                    val headers = it.requestHeaders.mapValues { (_, v) -> v }.toMap()

                    val req = Request(url = url, method = method, headers = headers)
                    onRequestStartedInternal(req)

                    val routeAction = waitForRouteAction(req)
                    when (routeAction) {
                        is RouteAction.Fulfill -> {
                            val bodyBytes = routeAction.bodyBytes ?: routeAction.body.toByteArray(Charsets.UTF_8)
                            val resolvedContentType = routeAction.contentType.ifEmpty { "text/plain; charset=UTF-8" }
                            val (mimeType, encoding) = parseWebResponseContentType(resolvedContentType)
                            val responseHeaders = LinkedHashMap(routeAction.headers)
                            responseHeaders.putIfAbsent("Content-Type", resolvedContentType)
                            completeRequest(
                                req,
                                Response(
                                    url = req.url,
                                    status = routeAction.status,
                                    statusText = defaultHttpStatusText(routeAction.status),
                                    headers = responseHeaders,
                                    id = req.id
                                )
                            )
                            val response = android.webkit.WebResourceResponse(
                                mimeType,
                                encoding,
                                routeAction.status,
                                defaultHttpStatusText(routeAction.status),
                                responseHeaders,
                                java.io.ByteArrayInputStream(bodyBytes)
                            )
                            return response
                        }
                        is RouteAction.Abort -> {
                            completeRequest(
                                req,
                                Response(
                                    url = req.url,
                                    status = 499,
                                    statusText = routeAction.errorCode,
                                    id = req.id
                                )
                            )
                            return android.webkit.WebResourceResponse(
                                "text/plain",
                                "UTF-8",
                                499,
                                routeAction.errorCode,
                                emptyMap(),
                                java.io.ByteArrayInputStream(ByteArray(0))
                            )
                        }
                        is RouteAction.Continue -> {
                            if (routeAction.hasOverrides()) {
                                performNetworkRequest(req, routeAction)?.let { return it }
                            }
                            finishRequestWithoutResponse(req.id)
                            return super.shouldInterceptRequest(view, request)
                        }
                        is RouteAction.Fallthrough -> {
                            scheduleRequestFinish(req.id)
                            return super.shouldInterceptRequest(view, request)
                        }
                        null -> {
                            scheduleRequestFinish(req.id)
                            return super.shouldInterceptRequest(view, request)
                        }
                    }
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: android.webkit.WebResourceRequest?
            ): Boolean {
                val url = request?.url?.toString()
                if (request?.isForMainFrame == true &&
                    (url.isNullOrBlank() ||
                        (config.allowedOriginRules.isNotEmpty() &&
                            config.allowedOriginRules.none { originRuleMatches(it, url) }) ||
                        navigationGuard?.invoke(url) == false)
                ) {
                    navigationCallback?.invoke(
                        NavigationResult(url.orEmpty(), 0, false, "Navigation origin is not allowed by policy: ${url.orEmpty()}")
                    )
                    return true
                }
                return delegateWebViewClient?.shouldOverrideUrlLoading(view, request)
                    ?: super.shouldOverrideUrlLoading(view, request)
            }
        }

        webView.webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onReceivedTitle(view: WebView?, title: String?) {
                super.onReceivedTitle(view, title)
                delegateWebChromeClient?.onReceivedTitle(view, title)
            }

            override fun onJsAlert(
                view: WebView?,
                url: String?,
                message: String?,
                result: android.webkit.JsResult?
            ): Boolean {
                if (delegateWebChromeClient?.onJsAlert(view, url, message, result) == true) {
                    return true
                }
                handleDialog(DialogType.Alert, message ?: "", null) { result?.confirm() }
                return true
            }

            override fun onJsConfirm(
                view: WebView?,
                url: String?,
                message: String?,
                result: android.webkit.JsResult?
            ): Boolean {
                if (delegateWebChromeClient?.onJsConfirm(view, url, message, result) == true) {
                    return true
                }
                handleDialog(DialogType.Confirm, message ?: "", null) { response ->
                    if (response is DialogResponse.Dismiss) result?.cancel() else result?.confirm()
                }
                return true
            }

            override fun onJsBeforeUnload(view: WebView?, url: String?, message: String?, result: android.webkit.JsResult?): Boolean {
                if (delegateWebChromeClient?.onJsBeforeUnload(view, url, message, result) == true) return true
                handleDialog(DialogType.Confirm, message.orEmpty(), null) { response ->
                    if (response is DialogResponse.Dismiss) result?.cancel() else result?.confirm()
                }
                return true
            }

            override fun onJsPrompt(
                view: WebView?,
                url: String?,
                message: String?,
                defaultValue: String?,
                result: android.webkit.JsPromptResult?
            ): Boolean {
                if (delegateWebChromeClient?.onJsPrompt(view, url, message, defaultValue, result) == true) {
                    return true
                }
                handleDialog(DialogType.Prompt, message ?: "", defaultValue) { response ->
                    when (response) {
                        is DialogResponse.Text -> result?.confirm(response.text)
                        is DialogResponse.Accept -> result?.confirm(defaultValue ?: "")
                        else -> result?.cancel()
                    }
                }
                return true
            }
        }
    }

    private fun waitForRouteAction(request: Request): RouteAction? {
        val requestId = request.id
        val timeoutMs = 5000L

        for ((pattern, handler) in routeHandlers) {
            if (pattern.matches(request.url)) {
                return kotlinx.coroutines.runBlocking(Dispatchers.Main.immediate) {
                    val route = AndroidRoute(request, requestId, this@AndroidWebViewController)
                    try {
                        val handlerResult = kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
                            handler(route)
                        } ?: RouteHandler.Fallthrough
                        pendingRouteResults.remove(requestId)?.let {
                            return@runBlocking it
                        }
                        when (handlerResult) {
                            is RouteHandler.Fulfill -> RouteAction.Fulfill(
                                status = handlerResult.options.status,
                                headers = handlerResult.options.headers,
                                body = handlerResult.options.body ?: "",
                                contentType = handlerResult.options.contentType ?: "application/json",
                                bodyBytes = handlerResult.options.bodyBytes
                            )
                            is RouteHandler.Abort -> RouteAction.Abort(handlerResult.errorCode)
                            is RouteHandler.Continue -> RouteAction.Continue(
                                url = handlerResult.options.url,
                                method = handlerResult.options.method,
                                headers = handlerResult.options.headers,
                                postData = handlerResult.options.postData
                            )
                            is RouteHandler.Fallthrough -> RouteAction.Fallthrough
                        }
                    } catch (_: Exception) {
                        RouteAction.Fallthrough
                    }
                }
            }
        }

        return RouteAction.Fallthrough
    }

    private fun handleDialog(type: DialogType, message: String, defaultPrompt: String?, complete: (DialogResponse) -> Unit) {
        scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            var response: DialogResponse = DialogResponse.Dismiss
            try {
                response = dialogListeners.firstOrNull()?.onDialogAsync(type, message, defaultPrompt) ?: DialogResponse.Dismiss
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (_: Exception) {
                // A caller's listener must not leave WebView waiting indefinitely.
            } finally { complete(response) }
        }
    }

    private fun onRequestStartedInternal(request: Request) {
        pendingRequests[request.id] = request
        requestListeners.forEach { it.onRequestStarted(request) }
        scheduleIdleCheck()
    }

    private fun scheduleRequestFinish(requestId: String) {
        requestFinishJobs[requestId]?.cancel()
        requestFinishJobs[requestId] = scope.launch(Dispatchers.Main) {
            delay(250)
            finishRequestWithoutResponse(requestId)
        }
    }

    private fun finishRequestWithoutResponse(requestId: String) {
        val request = pendingRequests.remove(requestId)
        requestFinishJobs.remove(requestId)?.cancel()
        request?.let { req ->
            requestListeners.forEach { listener ->
                listener.onRequestFinished(req)
            }
        }
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

    private fun performNetworkRequest(request: Request, action: RouteAction.Continue): android.webkit.WebResourceResponse? {
        return try {
            val targetUrl = action.url ?: request.url
            val conn = java.net.URI(targetUrl).toURL().openConnection() as java.net.HttpURLConnection
            conn.requestMethod = action.method ?: request.method
            val mergedHeaders = LinkedHashMap(request.headers)
            action.headers?.let { mergedHeaders.putAll(it) }
            mergedHeaders.forEach { (key, value) -> conn.setRequestProperty(key, value) }
            action.postData?.let { postData ->
                conn.doOutput = true
                conn.outputStream.use { it.write(postData.toByteArray(Charsets.UTF_8)) }
            }
            conn.connect()
            val status = conn.responseCode
            val statusText = conn.responseMessage ?: defaultHttpStatusText(status)
            val headers = conn.headerFields
                .filterKeys { it != null }
                .mapValues { (_, values) -> values.joinToString(",") }
                .mapKeys { (key, _) -> key.orEmpty() }
            val contentType = conn.contentType ?: headers["Content-Type"].orEmpty()
            val (mimeType, encoding) = parseWebResponseContentType(contentType)
            val stream = if (status >= 400) conn.errorStream else conn.inputStream
            completeRequest(
                request,
                Response(
                    url = targetUrl,
                    status = status,
                    statusText = statusText,
                    headers = headers,
                    id = request.id
                )
            )
            android.webkit.WebResourceResponse(
                mimeType.ifEmpty { "application/octet-stream" },
                encoding,
                status,
                statusText,
                headers,
                stream ?: java.io.ByteArrayInputStream(ByteArray(0))
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun RouteAction.Continue.hasOverrides(): Boolean =
        url != null || method != null || headers != null || postData != null

    private fun scheduleIdleCheck() {
        requestIdleCheckJob?.cancel()
        requestIdleCheckJob = scope.launch(Dispatchers.Main) {
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

    override fun setNavigationCallback(callback: ((NavigationResult) -> Unit)?) {
        synchronized(lock) {
            navigationCallback = callback
        }
    }

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

    override fun addRouteHandler(urlPattern: String, handler: suspend (Route) -> RouteHandler) {
        val regex = Regex(urlPattern)
        routeHandlers.keys.filter { it.pattern == urlPattern }.forEach { routeHandlers.remove(it) }
        routeHandlers[regex] = handler
    }

    override fun removeRouteHandler(urlPattern: String) {
        val regex = Regex(urlPattern)
        routeHandlers.keys.filter { it.pattern == urlPattern }.forEach { routeHandlers.remove(it) }
    }

    override fun removeAllRouteHandlers() {
        routeHandlers.clear()
    }

    override fun fulfillRoute(requestId: String, options: FulfillOptions) {
        pendingRouteResults[requestId] = RouteAction.Fulfill(
            status = options.status,
            headers = options.headers,
            body = options.body ?: "",
            contentType = options.contentType ?: "application/json",
            bodyBytes = options.bodyBytes
        )
    }

    override fun abortRoute(requestId: String, errorCode: String) {
        pendingRouteResults[requestId] = RouteAction.Abort(errorCode)
    }

    override fun continueRoute(requestId: String, options: ContinueOptions) {
        pendingRouteResults[requestId] = RouteAction.Continue(
            url = options.url,
            method = options.method,
            headers = options.headers,
            postData = options.postData
        )
    }

    override fun setCookie(cookie: Cookie) {
        val cookieManager = CookieManager.getInstance()
        val targetUrl = resolveCookieTargetUrl(cookie.domain, cookie.secure) ?: return
        cookieManager.setCookie(targetUrl, cookieHeader(cookie))
        cookieManager.flush()
        trackCookieUrl(targetUrl)
    }

    override suspend fun setCookieAsync(cookie: Cookie) = suspendCancellableCoroutine { continuation ->
        val cookieManager = CookieManager.getInstance()
        val targetUrl = resolveCookieTargetUrl(cookie.domain, cookie.secure)
            ?: run {
                continuation.resumeWith(Result.failure(KmpwrightException("Invalid cookie domain: ${cookie.domain}")))
                return@suspendCancellableCoroutine
            }
        cookieManager.setCookie(targetUrl, cookieHeader(cookie)) { accepted ->
            if (accepted == true) {
                cookieManager.flush()
                trackCookieUrl(targetUrl)
                continuation.resume(Unit)
            } else {
                continuation.resumeWith(Result.failure(KmpwrightException("Android WebView rejected cookie ${cookie.name}")))
            }
        }
    }

    private fun cookieHeader(cookie: Cookie): String = buildString {
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

    override fun getCookie(name: String, domain: String, callback: (Cookie?) -> Unit) {
        for (candidateUrl in candidateCookieUrls(domain)) {
            val cookie = readCookieMetadata(candidateUrl)
                .firstOrNull { it.name == name }
            if (cookie != null) {
                callback(cookie)
                return
            }
        }
        callback(null)
    }

    override fun getAllCookies(callback: (List<Cookie>) -> Unit) {
        val cookieManager = CookieManager.getInstance()
        val cookiesByKey = LinkedHashMap<String, Cookie>()
        for (candidateUrl in candidateCookieUrls(null)) {
            for (cookie in readCookieMetadata(candidateUrl)) {
                val key = "${cookie.domain}\u0000${cookie.path}\u0000${cookie.name}"
                cookiesByKey.putIfAbsent(key, cookie)
            }
        }
        callback(cookiesByKey.values.toList())
    }

    override fun deleteCookie(name: String, domain: String) {
        val cookieManager = CookieManager.getInstance()
        for (candidateUrl in candidateCookieUrls(domain)) {
            cookieManager.setCookie(candidateUrl, "$name=; Max-Age=0; path=/; domain=${Uri.parse(candidateUrl).host}")
        }
        cookieManager.flush()
    }

    override suspend fun deleteCookieAsync(name: String, domain: String) = suspendCancellableCoroutine { continuation ->
        val urls = candidateCookieUrls(domain)
        if (urls.isEmpty()) {
            continuation.resume(Unit)
            return@suspendCancellableCoroutine
        }
        var remaining = urls.size
        urls.forEach { candidateUrl ->
            CookieManager.getInstance().setCookie(candidateUrl, "$name=; Max-Age=0; path=/; domain=${Uri.parse(candidateUrl).host}") {
                // A false result means that no matching cookie existed; the
                // deletion command was still acknowledged successfully.
                remaining -= 1
                if (remaining == 0) {
                    CookieManager.getInstance().flush()
                    continuation.resume(Unit)
                }
            }
        }
    }

    override fun deleteAllCookies() {
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        observedCookieUrls.clear()
    }

    override suspend fun deleteAllCookiesAsync() = suspendCancellableCoroutine { continuation ->
        CookieManager.getInstance().removeAllCookies { accepted ->
            CookieManager.getInstance().flush()
            observedCookieUrls.clear()
            // removeAllCookies reports whether the store changed, not whether
            // the command failed (false is the normal empty-store result).
            continuation.resume(Unit)
        }
    }

    override fun setExtraHttpHeaders(headers: Map<String, String>): Boolean {
        // Android WebView has no backend-owned API that applies arbitrary
        // headers to all fetch/XHR subrequests. Returning false makes the
        // common API fail explicitly instead of exposing values to page JS.
        return httpRequestPolicy.supportsExtraHeaders(headers)
    }

    override fun setHttpCredentials(username: String?, password: String?): Boolean {
        httpRequestPolicy.setCredentials(username, password, webView.url)
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
        httpRequestPolicy.setCredentials(username, password, webView.url)
    }

    override fun interceptRequest(
        url: String,
        method: String,
        headers: Map<String, String>,
        onResult: (RouteAction?) -> Unit
    ) {
        val request = Request(url = url, method = method, headers = headers)
        onRequestStartedInternal(request)
        onResult(RouteAction.Fallthrough)
    }

    override fun loadUrl(url: String) {
        trackCookieUrl(url)
        val headers = buildRequestHeaders(url)
        if (headers.isEmpty()) {
            webView.loadUrl(url)
        } else {
            webView.loadUrl(url, headers)
        }
    }

    override fun loadHtml(content: String, baseUrl: String?) {
        trackCookieUrl(baseUrl)
        webView.loadDataWithBaseURL(baseUrl, content, "text/html", "UTF-8", null)
    }

    override fun evaluateJavaScript(script: String, callback: (String?) -> Unit) {
        webView.evaluateJavascript(script) { result ->
            callback(result)
        }
    }

    override fun goBack() = webView.goBack()
    override fun goForward() = webView.goForward()
    override fun reload() = webView.reload()

    override fun getUrl(): String? = webView.url
    override fun getTitle(): String? = webView.title

    private fun buildRequestHeaders(url: String): Map<String, String> {
        return httpRequestPolicy.requestHeaders(url) { credentials ->
            Base64.encodeToString(
                credentials.toByteArray(Charsets.UTF_8),
                Base64.NO_WRAP
            )
        }
    }

    private fun trackCookieUrl(url: String?) {
        val normalized = normalizeCookieUrl(url) ?: return
        observedCookieUrls.add(normalized)
    }

    private fun candidateCookieUrls(domainOrUrl: String?): List<String> {
        if (domainOrUrl != null) return listOfNotNull(normalizeCookieUrl(domainOrUrl))
        val candidates = LinkedHashSet<String>()
        normalizeCookieUrl(webView.url)?.let(candidates::add)
        normalizeCookieUrl(domainOrUrl)?.let(candidates::add)
        synchronized(observedCookieUrls) {
            candidates.addAll(observedCookieUrls)
        }
        return candidates.toList()
    }

    private fun resolveCookieTargetUrl(domainOrUrl: String, secure: Boolean): String? {
        val currentScheme = normalizeCookieUrl(webView.url)?.let { Uri.parse(it).scheme }
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
            throw UnsupportedOperationException("Cookie attribute reads require WebView GET_COOKIE_INFO support")
        }
        val requestUrl = url.toHttpUrl()
        return androidx.webkit.CookieManagerCompat.getCookieInfo(CookieManager.getInstance(), url).mapNotNull { header ->
            val parsed = okhttp3.Cookie.parse(requestUrl, header) ?: return@mapNotNull null
            val attributes = header.split(';').drop(1).associate { field ->
                val pair = field.trim().split('=', limit = 2)
                pair[0].lowercase() to pair.getOrElse(1) { "" }
            }
            if ("partitioned" in attributes) throw UnsupportedOperationException("Partitioned cookie state export is unavailable on Android")
            val domain = attributes["domain"] ?: parsed.domain
            Cookie(parsed.name, parsed.value, domain, parsed.path,
                expires = if (parsed.persistent) parsed.expiresAt / 1000.0 else null,
                secure = parsed.secure, httpOnly = parsed.httpOnly, sameSite = attributes["samesite"],
                hostOnly = !domain.startsWith("."))
        }
    }

    private fun parseCookieHeader(cookieHeader: String?, sourceUrl: String): List<Cookie> {
        if (cookieHeader.isNullOrBlank()) return emptyList()
        val host = Uri.parse(sourceUrl).host.orEmpty()
        return cookieHeader.split(";")
            .mapNotNull { part ->
                val pieces = part.trim().split("=", limit = 2)
                if (pieces.size != 2) {
                    null
                } else {
                    Cookie(
                        name = pieces[0].trim(),
                        value = pieces[1].trim(),
                        domain = host
                    )
                }
            }
    }

    private fun parseWebResponseContentType(contentType: String): Pair<String, String?> {
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

    override fun takeScreenshot(options: ScreenshotOptions, callback: (ByteArray?, Int, Int) -> Unit) {
        scope.launch {
            try {
                val screenshotController = AndroidScreenshotController(webView, config)
                val result = when {
                    options.fullPage -> screenshotController.captureFullPage(options.scale)
                    options.clip != null -> screenshotController.captureClip(
                        options.clip.x,
                        options.clip.y,
                        options.clip.width,
                        options.clip.height,
                        options.scale
                    )
                    else -> screenshotController.captureViewport(options.scale)
                }
                callback(result.bytes, result.width, result.height)
            } catch (_: Exception) {
                callback(null, 0, 0)
            }
        }
    }

    override fun getViewportSize(): Pair<Int, Int> = Pair(webView.width, webView.height)

    override fun setViewportSize(width: Int, height: Int) {
        webView.layoutParams = android.widget.FrameLayout.LayoutParams(width, height)
    }

    override fun setZoomScale(scale: Double) {
        webView.setInitialScale((scale * 100).toInt())
        val settings = webView.settings
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
    }

    override fun getZoomScale(): Double = 1.0

    fun getWebView(): WebView = webView

    override fun setUserAgent(userAgent: String) {
        webView.settings.userAgentString = userAgent
    }

    override fun setDeviceScaleFactor(scale: Double): Unit =
        throw UnsupportedOperationException("Android WebView device scale is determined by the host display")

    override fun injectJavaScript(script: String, callback: ((String?) -> Unit)?) {
        webView.evaluateJavascript(script) { result ->
            callback?.invoke(result)
        }
    }

    override fun dispose() {
        initScripts.values.forEach { it.remove() }
        initScripts.clear()
        requestFinishJobs.values.forEach { it.cancel() }
        requestFinishJobs.clear()
        requestIdleCheckJob?.cancel()
        routeHandlers.clear()
        pendingRequests.clear()
        pendingRouteResults.clear()

        val cleanup = {
            webView.stopLoading()
            webView.webChromeClient = originalWebChromeClient
            webView.webViewClient = originalWebViewClient
            if (ownsWebView) {
                webView.removeAllViews()
                webView.destroy()
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            cleanup()
        } else {
            webView.post(cleanup)
        }
        scope.cancel()
    }

    override fun validatePdfOptions(options: PdfOptions) = validateAndroidPdfOptions(options)

    override suspend fun generatePdfAsync(options: PdfOptions): ByteArray = captureAndroidPdf(webView, options)

    override fun generatePdf(options: PdfOptions, callback: (ByteArray?) -> Unit) {
        scope.launch {
            callback(runCatching { generatePdfAsync(options) }.getOrNull())
        }
    }

}

class AndroidPlatformContext(
    val context: Context,
    val webView: WebView? = null,
    val delegateWebViewClient: android.webkit.WebViewClient? = null,
    val delegateWebChromeClient: android.webkit.WebChromeClient? = null
) : PlatformContext

private const val ANDROID_CONTEXT_UNSUPPORTED_MESSAGE =
    "Android WebView cannot provide isolated BrowserContext semantics in-process. Use Browser pages directly or separate device/browser instances."

actual class Browser @JvmOverloads actual constructor(
    private val context: PlatformContext,
    private val config: KmpwrightConfig
) {
    private val platformContext = context as AndroidPlatformContext
    private val androidContext = platformContext.context
    private val browserScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val pages = mutableListOf<Page>()

    private val _isConnected = MutableStateFlow(true)
    actual val isConnected: StateFlow<Boolean> = _isConnected

    private val _connectionError = MutableStateFlow<String?>(null)
    actual val connectionError: StateFlow<String?> = _connectionError
    private val tabRuntime = object : PageTabRuntime {
        override suspend fun openTab(source: Page, url: String?): NavigationResult {
            val page = newPage()
            try { (source as? BasePage)?.inheritSetupTo(page) }
            catch (error: Throwable) { page.close(); throw error }
            return page.goto(url ?: "about:blank")
        }

        override suspend fun closeTab(page: Page): Boolean {
            if (!pages.remove(page)) return false
            page.close()
            return true
        }
    }

    init {
        createDefaultPage()
    }

    private fun createManagedWebView(): WebView {
        return WebView(androidContext).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                useWideViewPort = true
                loadWithOverviewMode = true
            }

            config.viewport?.let { viewport ->
                layoutParams = android.widget.FrameLayout.LayoutParams(viewport.width, viewport.height)
            }
            config.userAgent?.let { settings.userAgentString = it }
        }
    }

    private fun createPage(webView: WebView, ownsWebView: Boolean): Page {
        return AndroidPage(
            AndroidWebViewController(
                webView = webView,
                ownsWebView = ownsWebView,
                config = config,
                delegateWebViewClient = platformContext.delegateWebViewClient,
                delegateWebChromeClient = platformContext.delegateWebChromeClient
            ),
            config,
            tabRuntime
        )
    }

    private fun createDefaultPage() {
        val existing = platformContext.webView
        val webView = existing ?: createManagedWebView()
        val page = createPage(webView, ownsWebView = existing == null)
        pages.add(page)
    }

    internal actual suspend fun beginIndependentLease(): Unit = throw UnsupportedOperationException(
        "Independent browser-farm leases need isolated contexts. Android WebView requires a separate host process per owner.")
    internal actual suspend fun endIndependentLease() {}

    actual fun newPage(): Page {
        val page = createPage(createManagedWebView(), ownsWebView = true)
        pages.add(page)
        return page
    }

    suspend actual fun newPageAsync(): Page = newPage()

    suspend actual fun targetsAsync(): List<BrowserTarget> = throw UnsupportedOperationException("Browser target discovery requires CDP")
    suspend actual fun attachTargetAsync(targetId: String): Page = throw UnsupportedOperationException("Browser target attachment requires CDP")
    suspend actual fun activateTargetAsync(targetId: String): Unit = throw UnsupportedOperationException("Browser target activation requires CDP")
    suspend actual fun closeTargetAsync(targetId: String): Boolean = throw UnsupportedOperationException("Browser target closing requires CDP")
    suspend actual fun newWindowAsync(): Page = throw UnsupportedOperationException("Browser windows require CDP")

    actual fun newContext(): BrowserContext {
        throw UnsupportedOperationException(ANDROID_CONTEXT_UNSUPPORTED_MESSAGE)
    }

    actual fun pages(): List<Page> = pages.toList()

    actual fun page(): Page = pages.lastOrNull() ?: throw IllegalStateException("No page available")

    actual fun closePage(page: Page) {
        if (pages.remove(page)) {
            page.close()
        }
    }

    actual fun version(): BrowserVersion = BrowserVersion(
        product = "Kmpwright",
        version = "1.3.0",
        userAgent = "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36"
    )

    suspend actual fun versionAsync(): BrowserVersion = version()

    actual fun close() {
        pages.forEach { it.close() }
        pages.clear()
        _isConnected.value = false
        browserScope.cancel()
    }

    suspend actual fun closeAsync() {
        pages.toList().forEach { it.closeAsync() }
        close()
    }
}

actual class BrowserContext actual constructor(
    private val context: PlatformContext?,
    private val config: KmpwrightConfig
) {
    private val pages = mutableListOf<Page>()

    actual fun newPage(): Page {
        throw UnsupportedOperationException(ANDROID_CONTEXT_UNSUPPORTED_MESSAGE)
    }

    suspend actual fun newPageAsync(): Page {
        throw UnsupportedOperationException(ANDROID_CONTEXT_UNSUPPORTED_MESSAGE)
    }

    actual fun pages(): List<Page> = pages.toList()

    actual fun clearCookies() {
        throw UnsupportedOperationException(ANDROID_CONTEXT_UNSUPPORTED_MESSAGE)
    }

    suspend actual fun clearCookiesAsync() {
        throw UnsupportedOperationException(ANDROID_CONTEXT_UNSUPPORTED_MESSAGE)
    }

    actual fun clearData() {
        throw UnsupportedOperationException(ANDROID_CONTEXT_UNSUPPORTED_MESSAGE)
    }

    suspend actual fun clearDataAsync() {
        throw UnsupportedOperationException(ANDROID_CONTEXT_UNSUPPORTED_MESSAGE)
    }

    actual fun close() {
        pages.forEach { it.close() }
        pages.clear()
    }

    suspend actual fun closeAsync() {
        pages.toList().forEach { it.closeAsync() }
        close()
    }
}

internal class AndroidPage(
    controller: WebViewController,
    config: KmpwrightConfig = KmpwrightConfig(),
    tabRuntime: PageTabRuntime? = null
) : BasePage(controller, config, tabRuntime) {

    override fun capabilities(): PageCapabilities = androidPageCapabilities()

    override fun createPlatformLocator(selector: String): BaseLocator = AndroidLocator(controller, selector, config)

    override fun createTouch(): Touch {
        if (config.inputMode == InputMode.JsOnly) throw UnsupportedOperationException("Native touch is disabled by InputMode.JsOnly")
        return AndroidTouch(controller as AndroidWebViewController)
    }

    override fun createMouse(): Mouse {
        if (config.inputMode != InputMode.Hybrid) throw UnsupportedOperationException("Native mouse requires InputMode.Hybrid")
        return AndroidMouse(controller as AndroidWebViewController)
    }

    override fun createKeyboard(): Keyboard {
        if (config.inputMode != InputMode.Hybrid) throw UnsupportedOperationException("Native keyboard requires InputMode.Hybrid")
        return AndroidKeyboard(controller as AndroidWebViewController)
    }

    override fun createScreenshotController(): ScreenshotController {
        val webView = (controller as? AndroidWebViewController)?.getWebView()
            ?: throw IllegalStateException("ScreenshotController requires AndroidWebViewController")
        return AndroidScreenshotController(webView, config)
    }

    override suspend fun waitForDownload(urlPattern: String?, timeoutMs: Long): DownloadEvent {
        throw UnsupportedOperationException("Android WebView does not currently surface download events.")
    }

    override suspend fun waitForFileChooser(timeoutMs: Long): FileChooserEvent {
        throw UnsupportedOperationException("Android WebView does not currently surface file chooser events.")
    }

    override fun setDownloadPath(path: String) {
        throw UnsupportedOperationException("Android WebView does not currently support download path management.")
    }

    override fun setDownloadBehavior(behavior: DownloadBehavior) {
        throw UnsupportedOperationException("Android WebView does not currently support download behavior management.")
    }
}

/** Complete Android advertisement used by the backend and platform contracts. */
internal fun androidPageCapabilities(): PageCapabilities = PageCapabilities(
    backend = BackendKind.AndroidWebView,
    routeInterception = SupportLevel.Partial,
    touchInput = SupportLevel.Supported,
    mouseInput = SupportLevel.Partial,
    keyboardInput = SupportLevel.Supported,
    pdf = SupportLevel.Partial,
    browserContexts = SupportLevel.Unsupported,
    serviceWorkers = SupportLevel.Unsupported,
    domainFiltering = SupportLevel.Partial,
    networkEmulation = SupportLevel.Partial,
    harRecording = SupportLevel.Partial,
    downloads = SupportLevel.Unsupported,
    fileChooser = SupportLevel.Unsupported,
    tracing = SupportLevel.Unsupported,
    profiling = SupportLevel.Unsupported,
    tabs = SupportLevel.Supported,
    cdp = SupportLevel.Unsupported,
    initScripts = SupportLevel.Partial,
    webMcp = SupportLevel.Unsupported,
    accessibilityAudit = SupportLevel.Partial,
    webVitals = SupportLevel.Partial,
    reactInspection = SupportLevel.Supported,
    httpRead = SupportLevel.Supported,
    networkHistory = SupportLevel.Partial,
    videoRecording = SupportLevel.Unsupported,
    screencast = SupportLevel.Unsupported,
    notes = listOf(
        "Android touch and keyboard automation dispatch native WebView events.",
        "Mouse automation is still partial and may fall back to hover/tap approximations.",
        "Routing works at the WebView interception layer. Fulfill and abort are supported; continue overrides are applied only for requests WebView exposes to shouldInterceptRequest.",
        "Network emulation uses JS-level blocking/throttling helpers, not device-wide network controls.",
        "Cookie enumeration is limited to origins observed by this WebView and does not expose full browser-cookie metadata.",
        "Download and file chooser events are not currently surfaced from Android WebView."
    )
)

class AndroidLocator(
    controller: WebViewLocatorController,
    selector: String,
    config: KmpwrightConfig,
    options: FilterOptions? = null
) : BaseLocator(controller, selector, config, options) {

    constructor(
        controller: WebViewController,
        selector: String,
        config: KmpwrightConfig,
        options: FilterOptions? = null
    ) : this(controller as WebViewLocatorController, selector, config, options)

    override fun createLocator(newSelector: String, options: FilterOptions?): Locator =
        AndroidLocator(controller, newSelector, config, options)

    override suspend fun evaluateJavaScriptAsync(script: String): String? = suspendCancellableCoroutine { cont ->
        controller.evaluateJavaScript(script) { result ->
            cont.resume(normalizeJsResult(result))
        }
    }
}

class AndroidRoute(
    override val request: Request,
    override val requestId: String,
    private val controller: WebViewController
) : Route {
    override fun continueRequest(options: ContinueOptions) {
        controller.continueRoute(requestId, options)
    }

    override fun fulfill(options: FulfillOptions) {
        controller.fulfillRoute(requestId, options)
    }

    override fun abort(errorCode: String) {
        controller.abortRoute(requestId, errorCode)
    }
}
