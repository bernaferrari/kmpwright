package com.bernaferrari.kmpwright.core

import com.bernaferrari.kmpwright.core.ios.toByteArrayPinned
import com.bernaferrari.kmpwright.core.ios.toNSData
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.cinterop.CValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import platform.WebKit.WKContentWorld
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.useContents
import kotlinx.cinterop.toKString
import kotlin.coroutines.resume
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSNumber
import platform.Foundation.NSDate
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSURLResponse
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionDataTask
import platform.Foundation.NSURLErrorDomain
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.Foundation.HTTPBody
import platform.Foundation.HTTPMethod
import platform.Foundation.allHTTPHeaderFields
import platform.Foundation.base64EncodedStringWithOptions
import platform.Foundation.dataTaskWithRequest
import platform.Foundation.setValue
import platform.UIKit.UIImage
import platform.UIKit.UIImagePNGRepresentation
import platform.WebKit.WKFrameInfo
import platform.WebKit.WKHTTPCookieStore
import platform.WebKit.WKNavigation
import platform.WebKit.WKNavigationAction
import platform.WebKit.WKNavigationActionPolicy
import platform.WebKit.WKNavigationDelegateProtocol
import platform.WebKit.WKNavigationResponse
import platform.WebKit.WKNavigationResponsePolicy
import platform.WebKit.WKPDFConfiguration
import platform.WebKit.WKPreferences
import platform.WebKit.WKScriptMessage
import platform.WebKit.WKScriptMessageHandlerProtocol
import platform.WebKit.WKUIDelegateProtocol
import platform.WebKit.WKUserContentController
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.WebKit.WKSnapshotConfiguration
import platform.WebKit.WKURLSchemeHandlerProtocol
import platform.WebKit.WKURLSchemeTaskProtocol
import platform.WebKit.WKWebsiteDataStore

@OptIn(ExperimentalForeignApi::class)
class IOSPlatformContext(
    val webView: WKWebView? = null,
    val websiteDataStore: WKWebsiteDataStore? = null
) : PlatformContext

@OptIn(ExperimentalForeignApi::class)
class IOSWebViewController(
    private val config: KmpwrightConfig = KmpwrightConfig(),
    existingWebView: WKWebView? = null,
    websiteDataStore: WKWebsiteDataStore? = null
) : WebViewController {
    private val initScripts = mutableMapOf<String, platform.WebKit.WKUserScript>()
    private var initScriptCounter = 0L

    override suspend fun addInitScript(script: String): String = kotlinx.coroutines.withContext(Dispatchers.Main) {
        val id = "init-${++initScriptCounter}"
        val registration = platform.WebKit.WKUserScript(script,
            platform.WebKit.WKUserScriptInjectionTime.WKUserScriptInjectionTimeAtDocumentStart, false)
        webView.configuration.userContentController.addUserScript(registration)
        initScripts[id] = registration
        id
    }

    override suspend fun removeInitScript(id: String) = kotlinx.coroutines.withContext(Dispatchers.Main) {
        val registration = initScripts.remove(id) ?: throw KmpwrightException("Unknown init-script identifier: $id")
        val content = webView.configuration.userContentController
        val retained = content.userScripts.filterIsInstance<platform.WebKit.WKUserScript>().filter { it != registration }
        content.removeAllUserScripts()
        retained.forEach { content.addUserScript(it) }
    }

    private fun handleDialog(type: DialogType, message: String, defaultPrompt: String?, complete: (DialogResponse) -> Unit) {
        scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            var response: DialogResponse = DialogResponse.Dismiss
            try {
                response = dialogListeners.firstOrNull()?.onDialogAsync(type, message, defaultPrompt) ?: DialogResponse.Dismiss
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (_: Exception) {
                // Complete WebKit's callback even if a caller's listener throws.
            } finally { complete(response) }
        }
    }

    private val disposed = kotlinx.atomicfu.atomic(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var webView: WKWebView
    private val previousNavigationDelegate = existingWebView?.navigationDelegate
    private val previousUiDelegate = existingWebView?.UIDelegate
    private var networkUserScript: platform.WebKit.WKUserScript? = null
    private var navigationCallback: ((NavigationResult) -> Unit)? = null
    private var navigationGuard: ((String) -> Boolean)? = null
    private val requestListeners = mutableListOf<RequestListener>()
    private val dialogListeners = mutableListOf<DialogListener>()
    private val pendingRequests = mutableMapOf<String, Request>()
    private val routeHandlers = mutableMapOf<Regex, suspend (Route) -> RouteHandler>()
    private val pendingRouteResults = mutableMapOf<String, RouteAction>()
    private var schemeHandler: IOSSchemeHandler? = null
    private var schemeRequestCounter = 0L
    private val delegate = IOSWebViewDelegate()
    private val httpRequestPolicy = MobileHttpRequestPolicy()
    private val bridgeNonce = createBridgeNonce()
    private val bridgeChannel = "kmpwright_$bridgeNonce"
    private val bridgeLimits = WebKitNetworkBridgeLimits()
    private val bridgeRateLimiter = WebKitNetworkBridgeRateLimiter()
    private var pendingNavigationHttpStatus: Int = 200

    init {
        val preferences = WKPreferences()
        val configuration = WKWebViewConfiguration()
        configuration.preferences = preferences
        configuration.userContentController = WKUserContentController()
        websiteDataStore?.let { configuration.websiteDataStore = it }
        config.iosSchemeInterception?.let { schemeConfig ->
            val handler = IOSSchemeHandler(schemeConfig, this)
            configuration.setURLSchemeHandler(handler, schemeConfig.scheme)
            schemeHandler = handler
        }

        val viewport = config.viewport
        val width = viewport?.width ?: 1280
        val height = viewport?.height ?: 800

        webView = existingWebView ?: WKWebView(CGRectMake(0.0, 0.0, width.toDouble(), height.toDouble()), configuration)
        webView.navigationDelegate = delegate
        webView.UIDelegate = delegate

        config.userAgent?.let { webView.customUserAgent = it }

        setupNetworkInterception(webView.configuration.userContentController)
    }

    override fun setNavigationCallback(callback: ((NavigationResult) -> Unit)?) {
        navigationCallback = callback
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
    override fun setRequestIdleTimeout(timeoutMs: Long) {}

    override fun addDialogListener(listener: DialogListener) {
        dialogListeners.add(listener)
    }
    override fun removeDialogListener(listener: DialogListener) {
        dialogListeners.remove(listener)
    }

    override fun loadUrl(url: String) {
        if (config.allowedOriginRules.isNotEmpty() && !config.allowedOriginRules.any { originRuleMatches(it, url) }) {
            navigationCallback?.invoke(NavigationResult(url, 0, false, "Navigation origin is not allowed by KmpwrightConfig: $url"))
            return
        }
        if (navigationGuard?.invoke(url) == false) {
            navigationCallback?.invoke(NavigationResult(url, 0, false, "Navigation denied by action policy: $url"))
            return
        }
        val request = NSMutableURLRequest(NSURL(string = url))
        buildRequestHeaders(url).forEach { (key, value) ->
            request.setValue(value, forHTTPHeaderField = key)
        }
        webView.loadRequest(request)
    }

    override fun loadHtml(content: String, baseUrl: String?) {
        webView.loadHTMLString(content, baseURL = baseUrl?.let { NSURL(string = it) })
    }

    override val supportsPromiseEvaluation: Boolean get() = true
    override fun normalizeJavaScriptResult(raw: String?): String? = raw
    private fun scriptResult(result: Any?): String? = when {
        result is platform.Foundation.NSNull || result == null -> null
        result is Boolean -> result.toString()
        result is NSNumber && result.objCType?.toKString() == "c" -> result.boolValue.toString()
        result is NSNumber -> numberResult(result.doubleValue)
        result is Number -> numberResult(result.toDouble())
        else -> result.toString()
    }

    private fun numberResult(value: Double): String =
        if (value.isFinite() && value.toLong().toDouble() == value) value.toLong().toString() else value.toString()

    override fun evaluateJavaScript(script: String, callback: (String?) -> Unit) {
        webView.evaluateJavaScript(script) { result, _ ->
            callback(scriptResult(result))
        }
    }

    override suspend fun evaluateJavaScriptAsync(script: String): String? = withContext(Dispatchers.Main) {
        if (disposed.value) throw KmpwrightException("Page is closed")
        suspendCancellableCoroutine { continuation ->
            val expression = script.trim().removeSuffix(";").removePrefix("return ")
            fun run(asProgram: Boolean) {
                val source = if (asProgram) "(async function() { $script\n })()" else "($expression)"
                val body = """
                    try {
                        var result = await $source;
                        return JSON.stringify({value: result});
                    } catch (error) {
                        return JSON.stringify({error: String(error && error.message || error)});
                    }
                """.trimIndent()
                webView.callAsyncJavaScript(body, arguments = emptyMap<Any?, Any?>(), inFrame = null,
                    inContentWorld = WKContentWorld.pageWorld) { result, error ->
                    if (!continuation.isActive) return@callAsyncJavaScript
                    if (error != null) {
                        val message = error.userInfo["WKJavaScriptExceptionMessage"]?.toString() ?: error.localizedDescription
                        // Runtime errors are serialized by the wrapper. Only a
                        // parse failure can reach this expression-to-program fallback.
                        if (!asProgram && message.contains("SyntaxError")) run(true)
                        else continuation.resumeWith(Result.failure(KmpwrightException("WebKit JavaScript evaluation failed: $message")))
                    } else try {
                        val decoded = Json.parseToJsonElement(result?.toString() ?: "{}").let { it as? JsonObject }
                            ?: throw KmpwrightException("WebKit returned an invalid evaluation result")
                        (decoded["error"] as? JsonPrimitive)?.let { throw KmpwrightException("JavaScript evaluation failed: ${it.content}") }
                        val value = decoded["value"]
                        continuation.resume(when (value) {
                            null, JsonNull -> null
                            is JsonPrimitive -> value.content
                            else -> value.toString()
                        })
                    } catch (failure: Exception) { continuation.resumeWith(Result.failure(failure)) }
                }
            }
            run(false)
        }
    }

    override fun goBack() { webView.goBack() }
    override fun goForward() { webView.goForward() }
    override fun reload() { webView.reload() }

    override fun getUrl(): String? = webView.URL?.absoluteString
    override fun getTitle(): String? = webView.title

    override fun setExtraHttpHeaders(headers: Map<String, String>): Boolean {
        // WKWebView has no backend-owned API that applies arbitrary headers to
        // all fetch/XHR subrequests. Fail explicitly rather than expose them in
        // the page content world.
        return httpRequestPolicy.supportsExtraHeaders(headers)
    }

    override suspend fun setExtraHttpHeadersAsync(headers: Map<String, String>) {
        if (!httpRequestPolicy.supportsExtraHeaders(headers)) {
            throw KmpwrightException(
                "This backend cannot apply extra HTTP headers securely without exposing them to page JavaScript."
            )
        }
    }

    override fun setHttpCredentials(username: String?, password: String?): Boolean {
        httpRequestPolicy.setCredentials(username, password, webView.URL?.absoluteString)
        return true
    }

    override suspend fun setHttpCredentialsAsync(username: String?, password: String?) {
        httpRequestPolicy.setCredentials(username, password, webView.URL?.absoluteString)
    }

    private fun buildRequestHeaders(url: String): Map<String, String> {
        return httpRequestPolicy.requestHeaders(url) { credentials ->
            credentials.encodeToByteArray()
                .toNSData()
                .base64EncodedStringWithOptions(0u)
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    override fun takeScreenshot(options: ScreenshotOptions, callback: (ByteArray?, Int, Int) -> Unit) {
        scope.launch(Dispatchers.Main) {
            try {
                val scale = options.scale
                val rect = when {
                    options.clip != null -> CGRectMake(
                        options.clip.x,
                        options.clip.y,
                        options.clip.width,
                        options.clip.height
                    )
                    options.fullPage -> {
                        val contentSize = webView.scrollView.contentSize
                        val width = contentSize.useContents { width }
                        val height = contentSize.useContents { height }
                        CGRectMake(0.0, 0.0, width, height)
                    }
                    else -> {
                        val bounds = webView.bounds
                        CGRectMake(0.0, 0.0, bounds.useContents { size.width }, bounds.useContents { size.height })
                    }
                }

                webView.takePngSnapshotBytes(rect, scale) { bytes, error ->
                    if (bytes != null && error == null) {
                        val width = (rect.useContents { size.width } * scale).toInt()
                        val height = (rect.useContents { size.height } * scale).toInt()
                        callback(bytes, width, height)
                    } else {
                        callback(null, 0, 0)
                    }
                }
            } catch (e: Exception) {
                callback(null, 0, 0)
            }
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun WKWebView.takePngSnapshotBytes(
        rect: kotlinx.cinterop.CValue<platform.CoreGraphics.CGRect>,
        scale: Double,
        completion: (bytes: ByteArray?, error: String?) -> Unit
    ) {
        val config = WKSnapshotConfiguration().apply {
            this.rect = rect
            if (scale != 1.0) {
                val width = rect.useContents { size.width } * scale
                snapshotWidth = NSNumber(width)
            }
        }

        takeSnapshotWithConfiguration(config) { image: UIImage?, error: NSError? ->
            if (error != null) {
                completion(null, error.localizedDescription)
                return@takeSnapshotWithConfiguration
            }
            if (image == null) {
                completion(null, "Snapshot returned null image")
                return@takeSnapshotWithConfiguration
            }

            val pngData: NSData? = UIImagePNGRepresentation(image)
            if (pngData == null) {
                completion(null, "UIImagePNGRepresentation returned null")
                return@takeSnapshotWithConfiguration
            }

            completion(pngData.toByteArrayPinned(), null)
        }
    }

    override fun getViewportSize(): Pair<Int, Int> {
        val bounds = webView.bounds
        val width = bounds.useContents { size.width }.toInt()
        val height = bounds.useContents { size.height }.toInt()
        return Pair(width, height)
    }

    override fun setViewportSize(width: Int, height: Int) {
        val frame = CGRectMake(0.0, 0.0, width.toDouble(), height.toDouble())
        webView.setFrame(frame)
    }

    override fun setZoomScale(scale: Double) {
        webView.scrollView.setZoomScale(scale, animated = true)
    }

    override fun getZoomScale(): Double = webView.scrollView.zoomScale

    override fun setUserAgent(userAgent: String) {
        webView.customUserAgent = userAgent
    }

    override fun setDeviceScaleFactor(scale: Double) {
        throw UnsupportedOperationException("WKWebView device scale is determined by the host screen")
    }

    override fun injectJavaScript(script: String, callback: ((String?) -> Unit)?) {
        webView.evaluateJavaScript(script) { result, _ ->
            callback?.invoke(result?.toString())
        }
    }

    override suspend fun disposeAsync() = withContext(Dispatchers.Main) {
        if (disposed.value) return@withContext
        withTimeoutOrNull(500) { suspendCancellableCoroutine<Unit> { continuation ->
            webView.evaluateJavaScript("if (window.__kmpwrightNetworkDispose) window.__kmpwrightNetworkDispose();") { _, _ ->
                if (continuation.isActive) continuation.resume(Unit)
            }
        } }
        dispose()
    }

    override fun dispose() {
        if (!disposed.compareAndSet(false, true)) return
        webView.evaluateJavaScript("if (window.__kmpwrightNetworkDispose) window.__kmpwrightNetworkDispose();") { _, _ -> }
        webView.stopLoading()
        webView.navigationDelegate = previousNavigationDelegate
        webView.UIDelegate = previousUiDelegate
        val content = webView.configuration.userContentController
        content.removeScriptMessageHandlerForName(bridgeChannel)
        val owned = initScripts.values.toSet() + listOfNotNull(networkUserScript)
        val retained = content.userScripts.filterIsInstance<platform.WebKit.WKUserScript>().filter { it !in owned }
        content.removeAllUserScripts()
        retained.forEach { content.addUserScript(it) }
        initScripts.clear()
        networkUserScript = null
        requestListeners.clear()
        dialogListeners.clear()
        pendingRequests.clear()
        routeHandlers.clear()
        pendingRouteResults.clear()
        schemeHandler?.dispose()
        schemeHandler = null
        scope.cancel()
    }

    override fun validatePdfOptions(options: PdfOptions) {
        if (options.format != PdfFormat.Content || options.landscape || options.width != null || options.height != null ||
            options.displayHeaderFooter || options.headerTemplate.isNotEmpty() || options.footerTemplate.isNotEmpty() ||
            !options.printBackground || options.scale != 1.0 || options.pageRanges.isNotEmpty() ||
            listOf(options.margin.top, options.margin.bottom, options.margin.left, options.margin.right).any { pdfLengthInches(it) != 0.0 }) {
            throw UnsupportedOperationException("WKWebView PDF export supports Content format with zero margins only; print pagination/options are unavailable")
        }
    }

    override fun generatePdf(options: PdfOptions, callback: (ByteArray?) -> Unit) {
        scope.launch(Dispatchers.Main) {
            try {
                val config = WKPDFConfiguration()
                webView.createPDFWithConfiguration(config) { data, error ->
                    if (error != null || data == null) {
                        callback(null)
                    } else {
                        callback(data.toByteArrayPinned())
                    }
                }
            } catch (e: Exception) {
                callback(null)
            }
        }
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

    override fun removeAllRouteHandlers() { routeHandlers.clear() }
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
        val store = webView.configuration.websiteDataStore.httpCookieStore
        val props = mutableMapOf<Any?, Any?>()
        props[platform.Foundation.NSHTTPCookieName] = cookie.name
        props[platform.Foundation.NSHTTPCookieValue] = cookie.value
        props[platform.Foundation.NSHTTPCookieDomain] = if (cookie.hostOnly) cookie.domain.removePrefix(".") else ".${cookie.domain.removePrefix(".")}"
        props[platform.Foundation.NSHTTPCookiePath] = cookie.path
        if (cookie.secure) props[platform.Foundation.NSHTTPCookieSecure] = "TRUE"
        if (cookie.httpOnly) props["HttpOnly"] = "TRUE"
        cookie.expires?.let { props[platform.Foundation.NSHTTPCookieExpires] = NSDate(timeIntervalSinceReferenceDate = it - APPLE_REFERENCE_TO_UNIX_EPOCH_SECONDS) }
        cookie.sameSite?.let { props[platform.Foundation.NSHTTPCookieSameSitePolicy] = it.lowercase() }
        val nsCookie = platform.Foundation.NSHTTPCookie.cookieWithProperties(props)
        if (nsCookie != null) {
            store.setCookie(nsCookie, completionHandler = {})
        }
    }

    override suspend fun setCookieAsync(cookie: Cookie) = suspendCancellableCoroutine { continuation ->
        val store = webView.configuration.websiteDataStore.httpCookieStore
        val props = mutableMapOf<Any?, Any?>()
        props[platform.Foundation.NSHTTPCookieName] = cookie.name
        props[platform.Foundation.NSHTTPCookieValue] = cookie.value
        props[platform.Foundation.NSHTTPCookieDomain] = if (cookie.hostOnly) cookie.domain.removePrefix(".") else ".${cookie.domain.removePrefix(".")}"
        props[platform.Foundation.NSHTTPCookiePath] = cookie.path
        if (cookie.secure) props[platform.Foundation.NSHTTPCookieSecure] = "TRUE"
        if (cookie.httpOnly) props["HttpOnly"] = "TRUE"
        cookie.expires?.let { props[platform.Foundation.NSHTTPCookieExpires] = NSDate(timeIntervalSinceReferenceDate = it - APPLE_REFERENCE_TO_UNIX_EPOCH_SECONDS) }
        cookie.sameSite?.let { props[platform.Foundation.NSHTTPCookieSameSitePolicy] = it.lowercase() }
        val nsCookie = platform.Foundation.NSHTTPCookie.cookieWithProperties(props)
        if (nsCookie == null) {
            continuation.resumeWith(Result.failure(KmpwrightException("Invalid cookie ${cookie.name}")))
        } else {
            store.setCookie(nsCookie) { continuation.resume(Unit) }
        }
    }

    override fun getCookie(name: String, domain: String, callback: (Cookie?) -> Unit) {
        val store = webView.configuration.websiteDataStore.httpCookieStore
        store.getAllCookies { cookies ->
            val list = cookies?.mapNotNull { it as? platform.Foundation.NSHTTPCookie }
                ?: emptyList()
            val match = list.firstOrNull { it.name == name && (domain.isEmpty() || cookieDomainMatches(it.domain, domain)) }
            callback(match?.toKmpwrightCookie())
        }
    }

    override fun getAllCookies(callback: (List<Cookie>) -> Unit) {
        val store = webView.configuration.websiteDataStore.httpCookieStore
        store.getAllCookies { cookies ->
            val list = cookies?.mapNotNull { it as? platform.Foundation.NSHTTPCookie }
                ?: emptyList()
            callback(list.map { it.toKmpwrightCookie() })
        }
    }

    override fun deleteCookie(name: String, domain: String) {
        val store = webView.configuration.websiteDataStore.httpCookieStore
        store.getAllCookies { cookies ->
            val list = cookies?.mapNotNull { it as? platform.Foundation.NSHTTPCookie }
                ?: emptyList()
            list.filter { it.name == name && (domain.isEmpty() || cookieDomainMatches(it.domain, domain)) }
                .forEach { store.deleteCookie(it, completionHandler = {}) }
        }
    }

    override suspend fun deleteCookieAsync(name: String, domain: String) = suspendCancellableCoroutine { continuation ->
        val store = webView.configuration.websiteDataStore.httpCookieStore
        store.getAllCookies { cookies ->
            val list = cookies?.mapNotNull { it as? platform.Foundation.NSHTTPCookie }
                ?.filter { it.name == name && (domain.isEmpty() || cookieDomainMatches(it.domain, domain)) }
                ?: emptyList()
            if (list.isEmpty()) {
                continuation.resume(Unit)
            } else {
                var remaining = list.size
                list.forEach { cookie ->
                    store.deleteCookie(cookie) {
                        remaining -= 1
                        if (remaining == 0 && continuation.isActive) continuation.resume(Unit)
                    }
                }
            }
        }
    }

    override fun deleteAllCookies() {
        val store = webView.configuration.websiteDataStore.httpCookieStore
        store.getAllCookies { cookies ->
            val list = cookies?.mapNotNull { it as? platform.Foundation.NSHTTPCookie }
                ?: emptyList()
            list.forEach { store.deleteCookie(it, completionHandler = {}) }
        }
    }

    override suspend fun deleteAllCookiesAsync() = suspendCancellableCoroutine { continuation ->
        val store = webView.configuration.websiteDataStore.httpCookieStore
        store.getAllCookies { cookies ->
            val list = cookies?.mapNotNull { it as? platform.Foundation.NSHTTPCookie } ?: emptyList()
            if (list.isEmpty()) {
                continuation.resume(Unit)
            } else {
                var remaining = list.size
                list.forEach { cookie ->
                    store.deleteCookie(cookie) {
                        remaining -= 1
                        if (remaining == 0 && continuation.isActive) continuation.resume(Unit)
                    }
                }
            }
        }
    }

    override fun interceptRequest(url: String, method: String, headers: Map<String, String>, onResult: (RouteAction?) -> Unit) {
        onResult(RouteAction.Fallthrough)
    }

    internal fun createPage(tabRuntime: PageTabRuntime? = null): IOSPage = IOSPage(this, config, tabRuntime)

    internal fun getWebView(): WKWebView = webView

    private fun setupNetworkInterception(userContentController: WKUserContentController) {
        // The network shim must run in the page world: fetch/XHR prototypes are
        // world-local and cannot be replaced from a WKContentWorld. The native
        // endpoint is therefore treated as hostile and protected by a per-view
        // nonce plus frame/origin and bounded-message checks below.
        userContentController.addScriptMessageHandler(delegate, bridgeChannel)
        val script = buildWebKitNetworkInterceptionScript(bridgeChannel, bridgeNonce)
        val userScript = platform.WebKit.WKUserScript(
            script,
            platform.WebKit.WKUserScriptInjectionTime.WKUserScriptInjectionTimeAtDocumentStart,
            true
        )
        userContentController.addUserScript(userScript)
        networkUserScript = userScript
    }

    private fun handleNetworkMessage(json: String, scriptMessage: WKScriptMessage) {

        if (scriptMessage.name != bridgeChannel) return
        if (json.length > bridgeLimits.maxPayloadChars || !acceptBridgeMessage()) return
        if (!isTrustedBridgeFrame(scriptMessage)) return
        try {
            when (val message = parseWebKitNetworkBridgeMessage(json, bridgeNonce, bridgeLimits)) {
                is WebKitNetworkBridgeMessage.RequestStarted -> handleNetworkRequestStarted(message)
                is WebKitNetworkBridgeMessage.ResponseReceived -> completePendingRequest(
                    requestId = message.id,
                    url = message.url,
                    status = message.status,
                    statusText = message.statusText,
                    headers = message.headers
                )
                is WebKitNetworkBridgeMessage.RequestFailed -> failPendingRequest(message.id, message.error, message.url)
                null -> Unit
            }
        } catch (_: Exception) {
        }
    }

    private fun handleNetworkRequestStarted(message: WebKitNetworkBridgeMessage.RequestStarted) {
        if (pendingRequests.size >= bridgeLimits.maxInFlightRequests || pendingRequests.containsKey(message.id)) return
        val request = Request(
            url = message.url,
            method = message.method,
            headers = message.headers,
            postData = message.body,
            id = message.id
        )
        pendingRequests[request.id] = request
        requestListeners.forEach { it.onRequestStarted(request) }

        scope.launch {
            respondToScriptRequest(request, resolveRouteAction(request))
        }
    }

    private fun completePendingRequest(
        requestId: String,
        url: String? = null,
        status: Int,
        statusText: String? = null,
        headers: Map<String, String> = emptyMap()
    ) {
        val request = pendingRequests.remove(requestId) ?: return
        val response = Response(
            url = url?.takeIf { it.isNotBlank() } ?: request.url,
            status = status,
            statusText = statusText,
            headers = headers,
            id = request.id
        )
        requestListeners.forEach { listener ->
            listener.onResponseReceived(response)
            listener.onRequestFinished(request)
        }
    }

    private fun failPendingRequest(requestId: String, error: String, url: String? = null) {
        val request = pendingRequests.remove(requestId) ?: return
        requestListeners.forEach { listener ->
            listener.onRequestFailed(request, error)
            listener.onRequestFinished(request)
        }
    }

    private fun acceptBridgeMessage(): Boolean {
        return bridgeRateLimiter.allow(currentTimeMillis())
    }

    private fun isTrustedBridgeFrame(message: WKScriptMessage): Boolean {

        if (message.webView?.isEqual(webView) != true) return false
        val frame = message.frameInfo
        val expectedUrl = webView.URL ?: frame.request.URL ?: return false
        val expectedOrigin = expectedUrl.origin() ?: return false
        val securityOrigin = frame.securityOrigin
        val actualOrigin = if (expectedOrigin.protocol in setOf("about", "file") &&
            securityOrigin.host.isEmpty() && securityOrigin.protocol.isEmpty()) expectedOrigin else IOSBridgeOrigin(
            securityOrigin.protocol.lowercase(),
            securityOrigin.host.lowercase(),
            securityOrigin.port.toInt().takeIf { it != 0 } ?: when (securityOrigin.protocol.lowercase()) {
                "https" -> 443
                "http" -> 80
                else -> 0
            }
        )

        return isTrustedWebKitNetworkBridgeFrame(
            WebKitNetworkBridgeFrameMetadata(
                isMainFrame = frame.isMainFrame(),
                isExpectedWebView = message.webView?.isEqual(webView) == true,
                frameOrigin = actualOrigin.serialized,
                expectedOrigin = expectedOrigin.serialized
            )
        )
    }

    private suspend fun resolveRouteAction(request: Request): RouteAction {
        val timeoutMs = 5000L
        for ((pattern, handler) in routeHandlers) {
            if (pattern.matches(request.url)) {
                val route = IOSRoute(request, request.id, this)
                return try {
                    val handlerResult = withTimeoutOrNull(timeoutMs) {
                        handler(route)
                    } ?: RouteHandler.Fallthrough
                    pendingRouteResults.remove(request.id) ?: when (handlerResult) {
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
        return RouteAction.Fallthrough
    }

    private fun respondToScriptRequest(request: Request, response: RouteAction?) {
        when (response) {
            is RouteAction.Fulfill -> {
                sendNetworkResponse(
                    mapOf(
                        "id" to request.id,
                        "action" to "fulfill",
                        "status" to response.status,
                        "statusText" to defaultHttpStatusText(response.status),
                        "headers" to response.headers,
                        "body" to response.body,
                        "contentType" to response.contentType,
                        "bodyBase64" to response.bodyBytes?.toNSData()?.base64EncodedStringWithOptions(0u)
                    )
                )
            }
            is RouteAction.Abort -> {
                sendNetworkResponse(mapOf("id" to request.id, "action" to "abort", "error" to response.errorCode))
            }
            is RouteAction.Continue -> {
                val requestOverride = request.applyContinueOptions(response)
                pendingRequests[request.id] = request.copy(
                    url = requestOverride.url,
                    method = requestOverride.method,
                    headers = requestOverride.headers,
                    postData = requestOverride.postData
                )
                sendNetworkResponse(
                    mapOf(
                        "id" to request.id,
                        "action" to "continue",
                        "url" to requestOverride.url,
                        "method" to requestOverride.method,
                        "headers" to requestOverride.headers
                    ) + if (response.postData != null) mapOf("body" to response.postData) else emptyMap()
                )
            }
            is RouteAction.Fallthrough, null -> {
                sendNetworkResponse(mapOf("id" to request.id, "action" to "continue"))
            }
        }
    }

    private fun sendNetworkResponse(payload: Map<String, Any?>) {
        val obj = buildJsonObject {
            payload.forEach { (k, v) ->
                when (v) {
                    is String -> put(k, JsonPrimitive(v))
                    is Number -> put(k, JsonPrimitive(v))
                    is Boolean -> put(k, JsonPrimitive(v))
                    is Map<*, *> -> {
                        val inner = buildJsonObject {
                            v.forEach { (hk, hv) ->
                                if (hk is String && hv is String) put(hk, JsonPrimitive(hv))
                            }
                        }
                        put(k, inner)
                    }
                    else -> put(k, JsonNull)
                }
            }
        }
        val json = Json.encodeToString(JsonObject.serializer(), obj)
        webView.evaluateJavaScript("window.__kmpwrightNetworkRespond($json)") { _, _ -> }
    }

    private fun schemeTaskKey(task: WKURLSchemeTaskProtocol): Long = task.hashCode().toLong()

    private fun nextSchemeRequestId(): String {
        schemeRequestCounter += 1
        return "scheme-$schemeRequestCounter"
    }

    private fun mapSchemeUrl(originalUrl: NSURL, config: IosSchemeInterceptionConfig): NSURL {
        val absolute = originalUrl.absoluteString ?: return originalUrl
        val prefix = "${config.scheme}://"
        if (!absolute.startsWith(prefix)) return originalUrl
        val mapped = config.fallbackScheme + "://" + absolute.removePrefix(prefix)
        return NSURL(string = mapped)
    }

    internal fun handleSchemeTask(
        task: WKURLSchemeTaskProtocol,
        schemeConfig: IosSchemeInterceptionConfig,
        taskStore: MutableMap<Long, NSURLSessionDataTask>,
        isActive: () -> Boolean,
        completed: () -> Unit
    ) {
        val originalUrl = task.request.URL ?: return
        val mappedUrl = mapSchemeUrl(originalUrl, schemeConfig)
        val mappedString = mappedUrl.absoluteString ?: originalUrl.absoluteString ?: ""
        val method = task.request.HTTPMethod ?: "GET"
        val headers = task.request.allHTTPHeaderFields?.mapNotNull { (key, value) ->
            if (key is String && value is String) key to value else null
        }?.toMap() ?: emptyMap()
        val bodyData = task.request.HTTPBody
        val body = bodyData?.toByteArrayPinned()?.decodeToString()

        val requestId = nextSchemeRequestId()
        val request = Request(
            url = mappedString,
            method = method,
            headers = headers,
            postData = body,
            id = requestId
        )
        pendingRequests[requestId] = request
        requestListeners.forEach { it.onRequestStarted(request) }

        scope.launch {
            val response = pendingRouteResults.remove(requestId) ?: resolveRouteAction(request)
            if (!isActive()) { failPendingRequest(requestId, "Scheme task cancelled"); return@launch }
            when (response) {
                is RouteAction.Fulfill -> {
                val responseHeaders = response.headers.toMutableMap()
                responseHeaders["Content-Type"] = response.contentType
                val data = response.bodyBytes?.toNSData() ?: response.body.encodeToByteArray().toNSData()
                val headerFields: Map<Any?, *> = responseHeaders.entries.associate { it.key as Any? to it.value as Any? }
                val httpResponse = NSHTTPURLResponse(
                    uRL = mappedUrl,
                    statusCode = response.status.toLong(),
                    HTTPVersion = "HTTP/1.1",
                    headerFields = headerFields
                )
                task.didReceiveResponse(httpResponse)
                task.didReceiveData(data)
                task.didFinish()
                completed()
                completePendingRequest(
                    requestId = requestId,
                    url = mappedString,
                    status = response.status,
                    statusText = defaultHttpStatusText(response.status),
                    headers = responseHeaders
                )
                }
                is RouteAction.Abort -> {
                val error = NSError(domain = NSURLErrorDomain, code = -999, userInfo = null)
                task.didFailWithError(error)
                completed()
                failPendingRequest(requestId, response.errorCode)
                }
                is RouteAction.Continue -> {
                val requestOverride = request.applyContinueOptions(response)
                pendingRequests[requestId] = request.copy(
                    url = requestOverride.url,
                    method = requestOverride.method,
                    headers = requestOverride.headers,
                    postData = requestOverride.postData
                )
                val targetUrl = NSURL(string = requestOverride.url)
                val mutableRequest = NSMutableURLRequest(uRL = targetUrl)
                mutableRequest.HTTPMethod = requestOverride.method
                requestOverride.headers.forEach { (key, value) -> mutableRequest.setValue(value, forHTTPHeaderField = key) }
                requestOverride.postData?.let { mutableRequest.HTTPBody = it.encodeToByteArray().toNSData() }
                val dataTask = NSURLSession.sharedSession.dataTaskWithRequest(mutableRequest) { data, responseObj, error ->
                    dispatch_async(dispatch_get_main_queue()) {
                        taskStore.remove(schemeTaskKey(task))
                        if (!isActive()) { failPendingRequest(requestId, "Scheme task cancelled"); return@dispatch_async }
                        if (error != null) {
                            task.didFailWithError(error)
                            completed()
                            failPendingRequest(requestId, error.localizedDescription)
                            return@dispatch_async
                        }
                        if (responseObj != null) {
                            task.didReceiveResponse(responseObj)
                        }
                        if (data != null) {
                            task.didReceiveData(data)
                        }
                        task.didFinish()
                        completed()
                        val httpResponse = responseObj as? NSHTTPURLResponse
                        if (httpResponse != null) {
                            completePendingRequest(
                                requestId = requestId,
                                url = httpResponse.URL?.absoluteString ?: requestOverride.url,
                                status = httpResponse.statusCode.toInt(),
                                statusText = defaultHttpStatusText(httpResponse.statusCode.toInt()),
                                headers = httpResponse.allHeaderFields.toStringMap()
                            )
                        } else {
                            failPendingRequest(requestId, "No HTTP response")
                        }
                    }
                }
                taskStore[schemeTaskKey(task)] = dataTask
                dataTask.resume()
                }
                is RouteAction.Fallthrough -> {
                val mutableRequest = NSMutableURLRequest(uRL = mappedUrl)
                mutableRequest.HTTPMethod = method
                headers.forEach { (key, value) -> mutableRequest.setValue(value, forHTTPHeaderField = key) }
                if (bodyData != null) mutableRequest.HTTPBody = bodyData
                val dataTask = NSURLSession.sharedSession.dataTaskWithRequest(mutableRequest) { data, responseObj, error ->
                    dispatch_async(dispatch_get_main_queue()) {
                        taskStore.remove(schemeTaskKey(task))
                        if (!isActive()) { failPendingRequest(requestId, "Scheme task cancelled"); return@dispatch_async }
                        if (error != null) {
                            task.didFailWithError(error)
                            completed()
                            failPendingRequest(requestId, error.localizedDescription)
                            return@dispatch_async
                        }
                        if (responseObj != null) {
                            task.didReceiveResponse(responseObj)
                        }
                        if (data != null) {
                            task.didReceiveData(data)
                        }
                        task.didFinish()
                        completed()
                        val httpResponse = responseObj as? NSHTTPURLResponse
                        if (httpResponse != null) {
                            completePendingRequest(
                                requestId = requestId,
                                url = httpResponse.URL?.absoluteString ?: mappedString,
                                status = httpResponse.statusCode.toInt(),
                                statusText = defaultHttpStatusText(httpResponse.statusCode.toInt()),
                                headers = httpResponse.allHeaderFields.toStringMap()
                            )
                        } else {
                            failPendingRequest(requestId, "No HTTP response")
                        }
                    }
                }
                taskStore[schemeTaskKey(task)] = dataTask
                dataTask.resume()
                }
            }
        }
    }

    private inner class IOSWebViewDelegate : NSObject(), WKNavigationDelegateProtocol, WKUIDelegateProtocol, WKScriptMessageHandlerProtocol {
        override fun webView(
            webView: WKWebView,
            decidePolicyForNavigationAction: WKNavigationAction,
            decisionHandler: (WKNavigationActionPolicy) -> Unit
        ) {
            val url = decidePolicyForNavigationAction.request.URL?.absoluteString.orEmpty()
            if (!isNavigationAllowed(url)) {
                navigationCallback?.invoke(
                    NavigationResult(url, 0, false, "Navigation origin is not allowed by policy: $url")
                )
                decisionHandler(WKNavigationActionPolicy.WKNavigationActionPolicyCancel)
            } else {
                decisionHandler(WKNavigationActionPolicy.WKNavigationActionPolicyAllow)
            }
        }

        override fun webView(webView: WKWebView, decidePolicyForNavigationResponse: WKNavigationResponse, decisionHandler: (WKNavigationResponsePolicy) -> Unit) {
            val response = decidePolicyForNavigationResponse.response as? platform.Foundation.NSHTTPURLResponse
            val responseUrl = decidePolicyForNavigationResponse.response.URL?.absoluteString.orEmpty()
            if (decidePolicyForNavigationResponse.forMainFrame && !isNavigationAllowed(responseUrl)) {
                navigationCallback?.invoke(
                    NavigationResult(responseUrl, 0, false, "Navigation origin is not allowed by policy: $responseUrl")
                )
                decisionHandler(WKNavigationResponsePolicy.WKNavigationResponsePolicyCancel)
                return
            }
            if (response != null && decidePolicyForNavigationResponse.forMainFrame) {
                pendingNavigationHttpStatus = response.statusCode.toInt()
            }
            decisionHandler(WKNavigationResponsePolicy.WKNavigationResponsePolicyAllow)
        }

        override fun webView(webView: WKWebView, didFinishNavigation: WKNavigation?) {
            val url = webView.URL?.absoluteString.orEmpty()
            val originAllowed = config.allowedOriginRules.isEmpty() ||
                (url.isNotBlank() && config.allowedOriginRules.any { originRuleMatches(it, url) })
            val policyAllowed = url.isNotBlank() && navigationGuard?.invoke(url) != false
            if (!originAllowed || !policyAllowed) webView.stopLoading()
            navigationCallback?.invoke(
                if (originAllowed && policyAllowed) {
                    NavigationResult(url, pendingNavigationHttpStatus, pendingNavigationHttpStatus in 200..399)
                } else {
                    NavigationResult(url, pendingNavigationHttpStatus, false, "Committed navigation origin is not allowed by KmpwrightConfig: $url")
                }
            )
            pendingNavigationHttpStatus = 200
        }

        private fun isNavigationAllowed(url: String): Boolean {
            return url.isNotBlank() &&
                (config.allowedOriginRules.isEmpty() || config.allowedOriginRules.any { originRuleMatches(it, url) }) &&
                navigationGuard?.invoke(url) != false
        }

        @ObjCSignatureOverride
        override fun webView(webView: WKWebView, didFailNavigation: WKNavigation?, withError: NSError) {
            navigationCallback?.invoke(
                NavigationResult(webView.URL?.absoluteString ?: "", 0, false, withError.localizedDescription)
            )
            pendingNavigationHttpStatus = 200
        }

        @ObjCSignatureOverride
        override fun webView(webView: WKWebView, didFailProvisionalNavigation: WKNavigation?, withError: NSError) {
            navigationCallback?.invoke(
                NavigationResult(webView.URL?.absoluteString ?: "", 0, false, withError.localizedDescription)
            )
            pendingNavigationHttpStatus = 200
        }

        override fun webView(
            webView: WKWebView,
            runJavaScriptAlertPanelWithMessage: String,
            initiatedByFrame: WKFrameInfo,
            completionHandler: () -> Unit
        ) {
            handleDialog(DialogType.Alert, runJavaScriptAlertPanelWithMessage, null) { completionHandler() }
        }

        override fun webView(
            webView: WKWebView,
            runJavaScriptConfirmPanelWithMessage: String,
            initiatedByFrame: WKFrameInfo,
            completionHandler: (Boolean) -> Unit
        ) {
            handleDialog(DialogType.Confirm, runJavaScriptConfirmPanelWithMessage, null) { response ->
                completionHandler(response !is DialogResponse.Dismiss)
            }
        }

        override fun webView(
            webView: WKWebView,
            runJavaScriptTextInputPanelWithPrompt: String,
            defaultText: String?,
            initiatedByFrame: WKFrameInfo,
            completionHandler: (String?) -> Unit
        ) {
            handleDialog(DialogType.Prompt, runJavaScriptTextInputPanelWithPrompt, defaultText) { response ->
                when (response) {
                    is DialogResponse.Text -> completionHandler(response.text)
                    is DialogResponse.Accept -> completionHandler(defaultText)
                    else -> completionHandler(null)
                }
            }
        }

        override fun userContentController(
            userContentController: WKUserContentController,
            didReceiveScriptMessage: WKScriptMessage
        ) {

            val body = didReceiveScriptMessage.body as? String ?: return
            this@IOSWebViewController.handleNetworkMessage(body, didReceiveScriptMessage)
        }
    }
}

private data class IOSBridgeOrigin(val protocol: String, val host: String, val port: Int) {
    val serialized: String get() = "$protocol://$host:$port"
}

private fun NSURL.origin(): IOSBridgeOrigin? {
    val protocol = scheme?.lowercase() ?: return null
    // about:blank and file URLs have opaque/empty hosts but are still valid
    // origins for an explicitly loaded document.
    val host = host?.lowercase().orEmpty()
    val port = this.port?.intValue ?: when (protocol) {
        "https" -> 443
        "http" -> 80
        else -> 0
    }
    return IOSBridgeOrigin(protocol, host, port)
}

private fun createBridgeNonce(): String {
    // secureRandomBytes delegates to SecRandomCopyBytes on iOS. Keep the
    // 256-bit value controller-local; it is never logged or persisted.
    val hex = "0123456789abcdef"
    return buildString(64) {
        secureRandomBytes(32).forEach { byte ->
            val value = byte.toInt() and 0xff
            append(hex[value ushr 4])
            append(hex[value and 0x0f])
        }
    }
}

private class IOSSchemeHandler(
    private val config: IosSchemeInterceptionConfig,
    private val controller: IOSWebViewController
) : NSObject(), WKURLSchemeHandlerProtocol {
    private val tasks = mutableMapOf<Long, NSURLSessionDataTask>()
    private val active = mutableSetOf<Long>()

    fun dispose() {
        active.clear()
        tasks.values.forEach { it.cancel() }
        tasks.clear()
    }

    @ObjCSignatureOverride
    override fun webView(webView: WKWebView, startURLSchemeTask: WKURLSchemeTaskProtocol) {
        val key = startURLSchemeTask.hashCode().toLong()
        active.add(key)
        controller.handleSchemeTask(startURLSchemeTask, config, tasks,
            isActive = { key in active }, completed = { active.remove(key); tasks.remove(key) })
    }

    @ObjCSignatureOverride
    override fun webView(webView: WKWebView, stopURLSchemeTask: WKURLSchemeTaskProtocol) {
        val key = stopURLSchemeTask.hashCode().toLong()
        active.remove(key)
        tasks.remove(key)?.cancel()
    }
}

@OptIn(ExperimentalForeignApi::class)
actual class Browser actual constructor(
    private val context: PlatformContext,
    private val config: KmpwrightConfig
) {
    private val pages = mutableListOf<Page>()
    private val iosContext = context as? IOSPlatformContext
    private val backingStore = selectBackingStore(
        embeddedStore = iosContext?.webView?.configuration?.websiteDataStore,
        providedStore = iosContext?.websiteDataStore
    ) { WKWebsiteDataStore.nonPersistentDataStore() }
    private val websiteDataStore: WKWebsiteDataStore = backingStore.store
    private val ownsWebsiteDataStore: Boolean = backingStore.owned
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

    private val ownedContexts = mutableListOf<BrowserContext>()
    private var leaseContext: BrowserContext? = null

    internal actual suspend fun beginIndependentLease() {
        check(leaseContext == null) { "Browser already has an active lease" }
        endIndependentLease()
        leaseContext = newContext()
    }

    internal actual suspend fun endIndependentLease() {
        ownedContexts.toList().forEach { it.closeAsync() }
        ownedContexts.clear()
        leaseContext = null
        pages.toList().forEach { it.closeAsync() }
        pages.clear()
    }

    private val _isConnected = kotlinx.coroutines.flow.MutableStateFlow(true)
    actual val isConnected: kotlinx.coroutines.flow.StateFlow<Boolean> = _isConnected

    private val _connectionError = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    actual val connectionError: kotlinx.coroutines.flow.StateFlow<String?> = _connectionError

    init {
        val controller = IOSWebViewController(config, iosContext?.webView, websiteDataStore)
        pages.add(controller.createPage(tabRuntime))
    }

    actual fun newPage(): Page {
        leaseContext?.let { return it.newPage() }
        val controller = IOSWebViewController(config, websiteDataStore = websiteDataStore)
        return controller.createPage(tabRuntime).also { pages.add(it) }
    }

    suspend actual fun newPageAsync(): Page = newPage()

    suspend actual fun targetsAsync(): List<BrowserTarget> = throw UnsupportedOperationException("Browser target discovery requires CDP")
    suspend actual fun attachTargetAsync(targetId: String): Page = throw UnsupportedOperationException("Browser target attachment requires CDP")
    suspend actual fun activateTargetAsync(targetId: String): Unit = throw UnsupportedOperationException("Browser target activation requires CDP")
    suspend actual fun closeTargetAsync(targetId: String): Boolean = throw UnsupportedOperationException("Browser target closing requires CDP")
    suspend actual fun newWindowAsync(): Page = throw UnsupportedOperationException("Browser windows require CDP")

    actual fun newContext(): BrowserContext = BrowserContext(
        IOSPlatformContext(websiteDataStore = WKWebsiteDataStore.nonPersistentDataStore()),
        config
    ).also { ownedContexts.add(it) }
    actual fun pages(): List<Page> = pages.toList() + ownedContexts.flatMap { it.pages() }
    actual fun page(): Page = leaseContext?.let { it.pages().lastOrNull() ?: it.newPage() } ?: pages.lastOrNull() ?: throw IllegalStateException("No page available")

    actual fun closePage(page: Page) {
        if (pages.remove(page)) {
            page.close()
        }
    }

    actual fun version(): BrowserVersion = BrowserVersion("Kmpwright", "1.3.0", "Mozilla/5.0 (iPhone; CPU iPhone OS) AppleWebKit/605.1.15")

    suspend actual fun versionAsync(): BrowserVersion = version()

    actual fun close() {
        _isConnected.value = false
        ownedContexts.toList().forEach { it.close() }
        ownedContexts.clear()
        leaseContext = null
        pages.forEach { it.close() }
        pages.clear()
        if (ownsWebsiteDataStore) {
            clearOwnedData()
        }
    }

    suspend actual fun closeAsync() {
        ownedContexts.toList().forEach { it.closeAsync() }
        ownedContexts.clear()
        leaseContext = null
        pages.toList().forEach { it.closeAsync() }
        close()
    }

    private fun clearOwnedData() {
        websiteDataStore.removeDataOfTypes(
            WKWebsiteDataStore.allWebsiteDataTypes(),
            modifiedSince = NSDate(timeIntervalSinceReferenceDate = -978307200.0)
        ) {}
    }
}

@OptIn(ExperimentalForeignApi::class)
actual class BrowserContext actual constructor(
    private val context: PlatformContext?,
    private val config: KmpwrightConfig
) {
    private val pages = mutableListOf<Page>()
    private var consumedProvidedWebView = false
    private val iosContext = context as? IOSPlatformContext
    private val backingStore = selectBackingStore(
        embeddedStore = iosContext?.webView?.configuration?.websiteDataStore,
        providedStore = iosContext?.websiteDataStore
    ) { WKWebsiteDataStore.nonPersistentDataStore() }
    private val websiteDataStore: WKWebsiteDataStore = backingStore.store
    private val ownsWebsiteDataStore: Boolean = backingStore.owned
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

    actual fun newPage(): Page {
        val existingWebView = if (!consumedProvidedWebView) {
            iosContext?.webView?.also { consumedProvidedWebView = true }
        } else {
            null
        }
        val controller = IOSWebViewController(config, existingWebView, websiteDataStore)
        return controller.createPage(tabRuntime).also { pages.add(it) }
    }

    suspend actual fun newPageAsync(): Page = newPage()

    actual fun pages(): List<Page> = pages.toList()

    actual fun clearCookies() {
        val store = websiteDataStore.httpCookieStore
        store.getAllCookies { cookies ->
            val list = cookies?.mapNotNull { it as? platform.Foundation.NSHTTPCookie } ?: emptyList()
            list.forEach { store.deleteCookie(it, completionHandler = {}) }
        }
    }

    suspend actual fun clearCookiesAsync() = suspendCancellableCoroutine<Unit> { continuation ->
        val store = websiteDataStore.httpCookieStore
        store.getAllCookies { cookies ->
            val list = cookies?.mapNotNull { it as? platform.Foundation.NSHTTPCookie }.orEmpty()
            var remaining = list.size
            if (remaining == 0 && continuation.isActive) continuation.resume(Unit)
            list.forEach { cookie -> store.deleteCookie(cookie) {
                remaining -= 1
                if (remaining == 0 && continuation.isActive) continuation.resume(Unit)
            } }
        }
    }

    actual fun clearData() {
        clearCookies()
        websiteDataStore.removeDataOfTypes(
            WKWebsiteDataStore.allWebsiteDataTypes(),
            modifiedSince = NSDate(timeIntervalSinceReferenceDate = -978307200.0)
        ) {}
    }

    suspend actual fun clearDataAsync() = suspendCancellableCoroutine<Unit> { continuation ->
        websiteDataStore.removeDataOfTypes(WKWebsiteDataStore.allWebsiteDataTypes(),
            modifiedSince = NSDate(timeIntervalSinceReferenceDate = -978307200.0)) {
            if (continuation.isActive) continuation.resume(Unit)
        }
    }

    actual fun close() {
        pages.forEach { it.close() }
        pages.clear()
        if (ownsWebsiteDataStore) {
            clearData()
        }
    }

    suspend actual fun closeAsync() {
        pages.toList().forEach { it.closeAsync() }
        close()
    }
}

@OptIn(ExperimentalForeignApi::class)
internal class IOSPage(
    controller: WebViewController,
    config: KmpwrightConfig = KmpwrightConfig(),
    tabRuntime: PageTabRuntime? = null,
    private val screenshotControllerFactory: (() -> ScreenshotController)? = null
) : BasePage(controller, config, tabRuntime) {

    override fun capabilities(): PageCapabilities = iosPageCapabilities()

    override fun createPlatformLocator(selector: String): BaseLocator = IOSLocator(controller, selector, config)
    override fun createTouch(): Touch {
        if (config.inputMode == InputMode.JsOnly) throw UnsupportedOperationException("Native touch is disabled by InputMode.JsOnly")
        return IOSTouch(controller)
    }

    override fun createMouse(): Mouse {
        if (config.inputMode != InputMode.Hybrid) throw UnsupportedOperationException("Native mouse requires InputMode.Hybrid")
        return IOSMouse(controller)
    }

    override fun createKeyboard(): Keyboard {
        if (config.inputMode != InputMode.Hybrid) throw UnsupportedOperationException("Native keyboard requires InputMode.Hybrid")
        return IOSKeyboard(controller)
    }

    override fun createScreenshotController(): ScreenshotController {
        screenshotControllerFactory?.let { return it() }
        val webView = (controller as? IOSWebViewController)?.getWebView()
            ?: throw IllegalStateException("ScreenshotController requires IOSWebViewController")
        return IOSScreenshotController(webView)
    }

    override suspend fun waitForDownload(urlPattern: String?, timeoutMs: Long): DownloadEvent {
        throw UnsupportedOperationException("WKWebView does not currently surface download events.")
    }

    override suspend fun waitForFileChooser(timeoutMs: Long): FileChooserEvent {
        throw UnsupportedOperationException("WKWebView does not currently surface file chooser events.")
    }

    override fun setDownloadPath(path: String) {
        throw UnsupportedOperationException("WKWebView does not currently support download path management.")
    }

    override fun setDownloadBehavior(behavior: DownloadBehavior) {
        throw UnsupportedOperationException("WKWebView does not currently support download behavior management.")
    }
}

class IOSLocator(
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
        IOSLocator(controller, newSelector, config, options)

    override suspend fun evaluateJavaScriptAsync(script: String): String? = suspendCancellableCoroutine { cont ->
        controller.evaluateJavaScript(script) { result ->
            cont.resume(controller.normalizeJavaScriptResult(result))
        }
    }
}

class IOSRoute(
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

/** Complete iOS advertisement used by the backend and platform contracts. */
internal fun iosPageCapabilities(): PageCapabilities = PageCapabilities(
    backend = BackendKind.IOSWebView,
    routeInterception = SupportLevel.Partial,
    touchInput = SupportLevel.Partial,
    mouseInput = SupportLevel.Partial,
    keyboardInput = SupportLevel.Partial,
    pdf = SupportLevel.Partial,
    browserContexts = SupportLevel.Partial,
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
    initScripts = SupportLevel.Supported,
    webMcp = SupportLevel.Unsupported,
    accessibilityAudit = SupportLevel.Partial,
    webVitals = SupportLevel.Partial,
    reactInspection = SupportLevel.Supported,
    httpRead = SupportLevel.Supported,
    networkHistory = SupportLevel.Partial,
    videoRecording = SupportLevel.Unsupported,
    screencast = SupportLevel.Unsupported,
    notes = listOf(
        "WKWebView routing covers fetch, XHR, and optional custom-scheme interception, but full browser-network parity remains partial.",
        "iOS input uses touch-first event synthesis with modifier-aware printable keys and DOM text-input fallbacks; hover, drag-and-drop, and low-level key semantics remain partial.",
        "Network emulation uses JS-level blocking/throttling helpers, not device-wide network controls.",
        "Browser contexts use WKWebsiteDataStore isolation, not independent browser processes.",
        "Download and file chooser events are not currently surfaced from WKWebView."
    )
)

@OptIn(ExperimentalForeignApi::class)
private fun platform.Foundation.NSHTTPCookie.toKmpwrightCookie(): Cookie {
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

@OptIn(ExperimentalForeignApi::class)
private fun Map<Any?, *>.toStringMap(): Map<String, String> {
    return entries.mapNotNull { (key, value) ->
        val stringKey = key as? String ?: return@mapNotNull null
        val stringValue = value?.toString() ?: return@mapNotNull null
        stringKey to stringValue
    }.toMap()
}
