package com.bernaferrari.kmpwright.core

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [WebViewController] implementation that uses CDP (Chrome DevTools Protocol)
 * over WebSocket. This enables connecting to remote/cloud browsers.
 */
internal class CdpWebViewController(
    private val connection: CdpEventTransport,
    private val sessionId: String? = null,
    private val hideScrollbarsForScreenshots: Boolean = false,
    private val allowedOriginRules: Set<String> = emptySet()
) : WebViewController, CdpInputBridge {
    override val inputState = CdpInputState()

    private val childNetworkLock = Any()
    private val networkSessionSubscriptions = mutableMapOf<String?, List<CdpEventSubscription>>()
    internal var childSessionReady: suspend (String) -> Unit = {}
    internal var childSessionGone: (String) -> Unit = {}
    internal fun observedChildSessions(): List<String> = synchronized(childNetworkLock) { networkSessionSubscriptions.keys.filterNotNull().filter { it != sessionId } }
    internal fun ownsFrame(frameId: String): Boolean = frameId == mainFrameId || webMcpRuntime.ownsFrame(frameId)
    internal suspend fun observeFrames() = webMcpRuntime.observeBeforeNavigation()
    private val webMcpRuntime = CdpWebMcp(connection, sessionId, allowedOriginRules,
        frameReady = { child ->
            ensureNetworkEventListeners(child)
            val enabled = connection.sendSession(child, "Network.enable")
            enabled.error?.let { throw KmpwrightException("Could not observe iframe network: ${it.message}") }
            ensureFetchPausedListener(child)
            refreshFetchPatterns()
            childSessionReady(child)
        }, frameGone = { child ->
            synchronized(childNetworkLock) { networkSessionSubscriptions.remove(child)?.forEach { it.cancel() } }
            pendingRequests.keys.filter { it.startsWith("frame:$child:") }.forEach { id ->
                pendingRequests.remove(id)?.let { request -> requestListeners.forEach { it.onRequestFailed(request, "Frame session detached") } }
            }
            synchronized(childNetworkLock) { childFetchSubscriptions.remove(child)?.cancel() }
            childSessionGone(child)
        })
    override val webMcp: WebMcp get() = webMcpRuntime

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val disposed = AtomicBoolean(false)
    internal val recordingCursor = RecordingCursorTimeline()
    private val scriptsClosing = AtomicBoolean(false)
    private val ownedInitScripts = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    @Volatile private var requestListeners = persistentListOf<RequestListener>()
    private val routeHandlers = java.util.concurrent.ConcurrentHashMap<String, suspend (Route) -> RouteHandler>()
    init {
        connection.connectionState?.let { state -> scope.launch {
            state.collect { connected ->
                if (!connected && !disposed.get()) navigationCallback?.invoke(
                    NavigationResult(_url.orEmpty(), 0, false, "CDP connection disconnected"))
            }
        } }
    }

    @Volatile private var navigationCallback: ((NavigationResult) -> Unit)? = null
    @Volatile private var dialogListener: DialogListener? = null

    @Volatile private var _url: String? = null
    @Volatile private var _title: String? = null
    @Volatile private var _cookies = persistentListOf<Cookie>()
    @Volatile private var extraHttpHeaders: Map<String, String> = emptyMap()
    @Volatile private var httpCredentials: Pair<String, String>? = null
    @Volatile private var credentialOrigin: String? = null
    private val pendingRequests = java.util.concurrent.ConcurrentHashMap<String, Request>()
    private val fetchRefreshMutex = Mutex()
    @Volatile private var fetchPausedListenerInstalled = false
    @Volatile private var domainFilter: DomainFilter? = null
    private var fetchPausedSubscription: CdpEventSubscription? = null
    private val childFetchSubscriptions = mutableMapOf<String, CdpEventSubscription>()
    private val navigationSubscriptions = mutableListOf<CdpEventSubscription>()
    @Volatile private var mainFrameId: String? = null
    @Volatile private var pendingNavigationUrl: String? = null
    @Volatile private var pendingNavigationStatus: Int = 200
    @Volatile private var rejectedCommittedNavigation = false
    private val navigationVersion = java.util.concurrent.atomic.AtomicLong()
    @Volatile private var navigationGuard: ((String) -> Boolean)? = null

    constructor(
        connection: CdpConnection,
        sessionId: String? = null,
        hideScrollbarsForScreenshots: Boolean = false,
        allowedOriginRules: Set<String> = emptySet()
    ) : this(connection as CdpEventTransport, sessionId, hideScrollbarsForScreenshots, allowedOriginRules)

    init {
        installNavigationListeners()
    }

    /** Sends a CDP command, choosing session-based or direct. */
    private suspend fun cdpSend(method: String, params: Map<String, Any?> = emptyMap()): CdpResponse {
        return if (sessionId != null) {
            connection.sendSession(sessionId, method, params)
        } else {
            connection.send(method, params)
        }
    }

    override suspend fun sendCdpCommand(method: String, params: Map<String, Any?>): CdpResponse {
        val response = cdpSend(method, params)
        if (method == "Input.dispatchMouseEvent" && response.error == null) recordingCursor.record(params)
        return response
    }

    /** Sends a CDP command and returns the raw result string. */
    private suspend fun cdpEval(method: String, params: Map<String, Any?> = emptyMap()): String {
        val response = cdpSend(method, params)
        response.error?.let {
            throw KmpwrightException("CDP error ${it.code}: ${it.message}")
        }
        return response.result.toString()
    }

    override suspend fun addInitScript(script: String): String {
        check(!disposed.get() && !scriptsClosing.get()) { "Controller is closed" }
        val response = cdpSend("Page.addScriptToEvaluateOnNewDocument", mapOf("source" to script))
        response.error?.let { throw KmpwrightException("Could not register init script: ${it.message}") }
        val id = response.result?.get("identifier")?.jsonPrimitive?.content
            ?: throw KmpwrightException("Browser did not return an init-script identifier")
        ownedInitScripts.add(id)
        if (disposed.get() || scriptsClosing.get()) {
            withContext(NonCancellable) { withTimeoutOrNull(1_000) { runCatching { removeInitScript(id) } } }
            throw KmpwrightException("Controller closed while registering an init script")
        }
        return id
    }

    override suspend fun removeInitScript(id: String) {
        val response = cdpSend("Page.removeScriptToEvaluateOnNewDocument", mapOf("identifier" to id))
        response.error?.let { throw KmpwrightException("Could not remove init script '$id': ${it.message}") }
        ownedInitScripts.remove(id)
    }

    // --- Navigation ---

    override fun loadUrl(url: String) {
        scope.launch {
            try {
                fetchConfiguration?.join()
                if (allowedOriginRules.isNotEmpty() && !allowedOriginRules.any { originRuleMatches(it, url) }) {
                    navigationCallback?.invoke(NavigationResult(url, 0, false, "Navigation origin is not allowed by KmpwrightConfig: $url"))
                    return@launch
                }
                if (navigationGuard?.invoke(url) == false) {
                    navigationCallback?.invoke(NavigationResult(url, 0, false, "Navigation denied by action policy: $url"))
                    return@launch
                }
                webMcpRuntime.observeBeforeNavigation()
                rejectedCommittedNavigation = false
                if (credentialOrigin == null) credentialOrigin = canonicalHttpOrigin(url)
                applyNetworkHeaders()
                if (mainFrameId == null) mainFrameId = cdpSend("Page.getFrameTree").result?.get("frameTree")?.jsonObject
                    ?.get("frame")?.jsonObject?.get("id")?.jsonPrimitive?.content
                pendingNavigationUrl = url
                pendingNavigationStatus = 200
                val result = cdpSend(CdpMethods.PAGE_NAVIGATE, mapOf("url" to url))
                result.error?.let {
                    navigationCallback?.invoke(NavigationResult(url, 0, false, "CDP error ${it.code}: ${it.message}"))
                    return@launch
                }
                result.result?.get("frameId")?.jsonPrimitive?.content?.let { mainFrameId = it }
            } catch (e: Exception) {
                navigationCallback?.invoke(NavigationResult(url, 0, false, error = e.message))
            }
        }
    }

    override fun loadHtml(content: String, baseUrl: String?) {
        scope.launch {
            loadUrl("data:text/html,${java.net.URLEncoder.encode(content, "UTF-8").replace("+", "%20")}")
        }
    }

    override suspend fun runAccessibilityAudit(options: AccessibilityAuditOptions): String =
        CdpAccessibilityAudit.run(connection, sessionId, options)

    override val nativeAccessibility: Boolean get() = true
    override suspend fun prepareAccessibilitySnapshot() = CdpAccessibility.prepare(this)

    override fun normalizeJavaScriptResult(raw: String?): String? = raw

    override fun evaluateJavaScript(script: String, callback: (String?) -> Unit) {
        if (disposed.get()) { callback(null); return }
        scope.launch {
            try {
                callback(evaluateJavaScriptAsync(script))
            } catch (e: Exception) {
                callback(null)
            }
        }
    }

    override suspend fun evaluateJavaScriptAsync(script: String): String? {
        if (disposed.get()) throw KmpwrightException("Page is closed")
        fetchConfiguration?.join()
        // Page delegates use JavaScript snippets with a leading `return`.
        // Runtime.evaluate accepts expressions, so execute those snippets as
        // a function body while preserving ordinary expressions/statements.
        val expression = if (script.trimStart().startsWith("return ")) {
            "(function() { $script })()"
        } else {
            script
        }
        val response = cdpSend(CdpMethods.RUNTIME_EVALUATE, mapOf(
            "expression" to expression,
            "returnByValue" to true,
            "awaitPromise" to true
        ))
        response.error?.let { throw KmpwrightException("CDP error ${it.code}: ${it.message}") }
        val outerResult = response.result
        outerResult?.get("exceptionDetails")?.let { details ->
            throw KmpwrightException("CDP JavaScript evaluation failed: $details")
        }
        val value = outerResult?.get("result")?.jsonObject
        return value?.get("value")?.let { v ->
            when (v) {
                kotlinx.serialization.json.JsonNull -> null
                is kotlinx.serialization.json.JsonPrimitive -> v.content
                else -> v.toString()
            }
        }
    }

    override fun goBack() {
        scope.launch {
            cdpSend(CdpMethods.RUNTIME_EVALUATE, mapOf(
                "expression" to "history.back()",
                "awaitPromise" to false
            ))
        }
    }

    override fun goForward() {
        scope.launch {
            cdpSend(CdpMethods.RUNTIME_EVALUATE, mapOf(
                "expression" to "history.forward()",
                "awaitPromise" to false
            ))
        }
    }
    override fun reload() { scope.launch { cdpSend(CdpMethods.PAGE_RELOAD, emptyMap()) } }

    override fun getUrl(): String? = _url

    override fun getTitle(): String? = _title

    // --- Screenshots ---

    override fun takeScreenshot(options: ScreenshotOptions, callback: (ByteArray?, Int, Int) -> Unit) {
        scope.launch {
            try {
                val shouldRestoreScrollbars = hideScrollbarsForScreenshots && installHiddenScrollbarStyle()
                val params = mutableMapOf<String, Any?>(
                    "format" to options.format.name.lowercase()
                )
                require(options.scale.isFinite() && options.scale > 0) { "Screenshot scale must be positive" }
                require(options.quality in 0..100) { "Screenshot quality must be between 0 and 100" }
                if (options.format != ScreenshotFormat.PNG) params["quality"] = options.quality
                if (options.omitBackground) {
                    val response = cdpSend("Emulation.setDefaultBackgroundColorOverride", mapOf("color" to mapOf("r" to 0, "g" to 0, "b" to 0, "a" to 0)))
                    response.error?.let { throw KmpwrightException(it.message) }
                }
                try {
                    val clip = options.clip ?: if (options.scale != 1.0) {
                        val metrics = cdpSend(CdpMethods.PAGE_GET_LAYOUT_METRICS)
                        metrics.error?.let { throw KmpwrightException(it.message) }
                        val dimensions = metrics.result?.get(if (options.fullPage) "cssContentSize" else "cssLayoutViewport")?.jsonObject
                            ?: throw KmpwrightException("Screenshot dimensions unavailable")
                        fun number(name: String) = dimensions[name]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0
                        if (options.fullPage) Rectangle(number("x"), number("y"), number("width"), number("height"))
                        else Rectangle(number("pageX"), number("pageY"), number("clientWidth"), number("clientHeight"))
                    } else null
                    if (clip != null) {
                        params["clip"] = mapOf(
                            "x" to clip.x,
                            "y" to clip.y,
                            "width" to clip.width,
                            "height" to clip.height,
                            "scale" to options.scale
                        )
                    } else {
                        params["captureBeyondViewport"] = options.fullPage
                        params["fromSurface"] = true
                    }
                    val result = cdpSend(CdpMethods.PAGE_CAPTURE_SCREENSHOT, params)
                    val data = result.result?.get("data")?.jsonPrimitive?.content
                    if (data != null) {
                        val bytes = java.util.Base64.getDecoder().decode(data)
                        val (width, height) = decodeImageSize(bytes)
                        callback(bytes, width, height)
                    } else {
                        callback(null, 0, 0)
                    }
                } finally {
                    if (options.omitBackground) cdpSend("Emulation.setDefaultBackgroundColorOverride")
                    if (shouldRestoreScrollbars) {
                        restoreHiddenScrollbarStyle()
                    }
                }
            } catch (e: Exception) {
                callback(null, 0, 0)
            }
        }
    }

    private suspend fun installHiddenScrollbarStyle(): Boolean =
        evaluateBoolean(HIDE_SCROLLBARS_SCRIPT)

    private suspend fun restoreHiddenScrollbarStyle() {
        runCatching { evaluateBoolean(RESTORE_SCROLLBARS_SCRIPT) }
    }

    private suspend fun evaluateBoolean(expression: String): Boolean {
        val result = cdpSend(CdpMethods.RUNTIME_EVALUATE, mapOf(
            "expression" to expression,
            "returnByValue" to true,
            "awaitPromise" to false
        ))
        return result.result
            ?.get("result")
            ?.jsonObject
            ?.get("value")
            ?.jsonPrimitive
            ?.booleanOrNull == true
    }

    // --- Viewport ---

    @Volatile
    private var cachedViewportSize: Pair<Int, Int> = 0 to 0
    @Volatile
    private var cachedDeviceScaleFactor: Double = 1.0

    override fun getViewportSize(): Pair<Int, Int> = cachedViewportSize

    override fun setViewportSize(width: Int, height: Int) {
        cachedViewportSize = width to height
        scope.launch {
            applyDeviceMetrics()
        }
    }

    override fun setDeviceScaleFactor(scale: Double) {
        cachedDeviceScaleFactor = scale
        scope.launch {
            applyDeviceMetrics()
        }
    }

    override suspend fun setViewportSizeAsync(width: Int, height: Int) {
        require(width > 0 && height > 0) { "Viewport dimensions must be positive" }
        cachedViewportSize = width to height
        applyDeviceMetrics()
    }

    override suspend fun setDeviceScaleFactorAsync(scale: Double) {
        require(scale.isFinite() && scale > 0) { "Device scale factor must be positive" }
        cachedDeviceScaleFactor = scale
        applyDeviceMetrics()
    }

    override suspend fun setUserAgentAsync(userAgent: String) {
        val response = cdpSend(CdpMethods.NETWORK_SET_USER_AGENT_OVERRIDE, mapOf("userAgent" to userAgent))
        response.error?.let { throw KmpwrightException("User agent configuration failed: ${it.message}") }
    }

    private suspend fun applyDeviceMetrics() {
        val (width, height) = cachedViewportSize
        val response = cdpSend(CdpMethods.EMULATION_SET_DEVICE_METRICS_OVERRIDE, mapOf(
            "width" to width.coerceAtLeast(1),
            "height" to height.coerceAtLeast(1),
            "deviceScaleFactor" to cachedDeviceScaleFactor,
            "mobile" to false
        ))
        response.error?.let { throw KmpwrightException("Viewport configuration failed: ${it.message}") }
    }

    init {
        // Fetch initial viewport size asynchronously
        scope.launch {
            try {
                val result = cdpSend(CdpMethods.PAGE_GET_LAYOUT_METRICS)
                val cssContentSize = result.result?.get("cssContentSize")?.jsonObject
                val width = cssContentSize?.get("width")?.jsonPrimitive?.longOrNull?.toInt() ?: 0
                val height = cssContentSize?.get("height")?.jsonPrimitive?.longOrNull?.toInt() ?: 0
                if (cachedViewportSize == 0 to 0) cachedViewportSize = width to height
            } catch (_: Exception) {}
        }
    }

    override fun setZoomScale(scale: Double) { /* Handled via device metrics */ }
    override fun getZoomScale(): Double = 1.0

    override fun setUserAgent(userAgent: String) {
        scope.launch {
            cdpSend(CdpMethods.NETWORK_SET_USER_AGENT_OVERRIDE, mapOf("userAgent" to userAgent))
        }
    }

    // --- JavaScript injection ---

    override fun injectJavaScript(script: String, callback: ((String?) -> Unit)?) {
        evaluateJavaScript(script, callback ?: {})
    }

    // --- Cookies ---

    override fun setCookie(cookie: Cookie) {
        scope.launch {
            runCatching { setCookieAsync(cookie) }
        }
    }

    override suspend fun setCookieAsync(cookie: Cookie) {
        val params = mutableMapOf<String, Any>(
            "name" to cookie.name,
            "value" to cookie.value,
            "path" to cookie.path,
            "secure" to cookie.secure,
            "httpOnly" to cookie.httpOnly
        )
        validateCookie(cookie)
        if (cookie.hostOnly) params["url"] = "${if (cookie.secure) "https" else "http"}://${cookie.domain.removePrefix(".")}${cookie.path}"
        else params["domain"] = cookie.domain
        cookie.sameSite?.let { params["sameSite"] = it.lowercase().replaceFirstChar(Char::uppercase) }
        cookie.expires?.let { params["expires"] = it }
        val response = cdpSend(CdpMethods.NETWORK_SET_COOKIE, params)
        response.error?.let { throw KmpwrightException("CDP error ${it.code}: ${it.message}") }
        val result = response.result
        if (result?.get("success")?.jsonPrimitive?.content == "false") {
            throw KmpwrightException("CDP failed to set cookie: ${result["errorMessage"] ?: "unknown error"}")
        }
        _cookies = _cookies.removingAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }.adding(cookie)
    }

    private suspend fun readCookies(url: String? = null): List<Cookie> {
        if (disposed.get()) throw KmpwrightException("Page is closed")
        val response = cdpSend(CdpMethods.NETWORK_GET_COOKIES,
            if (url != null) mapOf("urls" to listOf(url)) else emptyMap())
        response.error?.let { throw KmpwrightException("Cookie read failed: ${it.message}") }
        val cookies = response.result?.get("cookies")?.jsonArray ?: throw KmpwrightException("Cookie read returned no cookie data")
        return cookies.map { parseCdpCookie(it.jsonObject) }
    }

    override suspend fun getCookieAsync(name: String, domain: String): Cookie? =
        readCookies(domain.takeIf { it.contains("://") }).filter { it.name == name && cookieDomainMatches(it.domain, domain) }
            .maxByOrNull { it.path.length }

    override suspend fun getAllCookiesAsync(): List<Cookie> = readCookies()

    override fun getCookie(name: String, domain: String, callback: (Cookie?) -> Unit) {
        if (disposed.get()) { callback(null); return }
        scope.launch {
            try {
                val result = cdpSend(CdpMethods.NETWORK_GET_COOKIES)
                val cookies = result.result?.get("cookies")?.jsonArray.orEmpty()
                val found = cookies.firstOrNull { c ->
                    val cName = c.jsonObject["name"]?.jsonPrimitive?.content
                    val cDomain = c.jsonObject["domain"]?.jsonPrimitive?.content
                    cName == name && cDomain != null && cookieDomainMatches(cDomain, domain)
                }
                callback(found?.let { parseCdpCookie(it.jsonObject) })
            } catch (_: Exception) {
                callback(null)
            }
        }
    }

    override fun getAllCookies(callback: (List<Cookie>) -> Unit) {
        if (disposed.get()) { callback(emptyList()); return }
        scope.launch {
            try {
                val result = cdpSend(CdpMethods.NETWORK_GET_COOKIES)
                val cookies = result.result?.get("cookies")?.jsonArray.orEmpty()
                callback(cookies.map { parseCdpCookie(it.jsonObject) })
            } catch (_: Exception) {
                callback(emptyList())
            }
        }
    }

    override fun deleteCookie(name: String, domain: String) {
        scope.launch { runCatching { deleteCookieAsync(name, domain) } }
    }

    override suspend fun deleteCookieAsync(name: String, domain: String) {
        val response = cdpSend(CdpMethods.NETWORK_DELETE_COOKIE, mapOf("name" to name, "domain" to domain))
        response.error?.let { throw KmpwrightException("CDP error ${it.code}: ${it.message}") }
        _cookies = _cookies.removingAll { it.name == name && it.domain == domain }
    }

    override fun deleteAllCookies() {
        scope.launch { runCatching { deleteAllCookiesAsync() } }
    }

    override suspend fun deleteAllCookiesAsync() {
        val response = cdpSend(CdpMethods.NETWORK_CLEAR_COOKIES)
        response.error?.let { throw KmpwrightException("CDP error ${it.code}: ${it.message}") }
        _cookies = persistentListOf()
    }

    override fun setExtraHttpHeaders(headers: Map<String, String>): Boolean {
        extraHttpHeaders = headers.toMap()
        scope.launch { runCatching { applyNetworkHeaders() } }
        return true
    }

    override suspend fun setExtraHttpHeadersAsync(headers: Map<String, String>) {
        extraHttpHeaders = headers.toMap()
        applyNetworkHeaders()
    }

    override fun setHttpCredentials(username: String?, password: String?): Boolean {
        httpCredentials = if (username != null && password != null) {
            username to password
        } else {
            null
        }
        credentialOrigin = httpCredentials?.let { canonicalHttpOrigin(_url ?: pendingNavigationUrl) }
        // Fetch pauses every request once credentials are active; install the
        // handler before enabling the domain so a credentials-only update can
        // never leave requests paused without a continuation path.
        ensureFetchPausedListener()
        scope.launch { runCatching { applyNetworkHeaders(); refreshFetchPatterns() } }
        return true
    }

    override suspend fun setHttpCredentialsAsync(username: String?, password: String?) {
        httpCredentials = if (username != null && password != null) username to password else null
        credentialOrigin = httpCredentials?.let { canonicalHttpOrigin(_url ?: pendingNavigationUrl) }
        ensureFetchPausedListener()
        applyNetworkHeaders()
        refreshFetchPatterns()
    }

    private fun parseCdpCookie(obj: JsonObject): Cookie {
        return Cookie(
            name = obj["name"]?.jsonPrimitive?.content ?: "",
            value = obj["value"]?.jsonPrimitive?.content ?: "",
            domain = obj["domain"]?.jsonPrimitive?.content ?: "",
            path = obj["path"]?.jsonPrimitive?.content ?: "/",
            expires = obj["expires"]?.jsonPrimitive?.content?.toDoubleOrNull()?.takeIf { it >= 0 },
            secure = obj["secure"]?.jsonPrimitive?.booleanOrNull ?: false,
            httpOnly = obj["httpOnly"]?.jsonPrimitive?.booleanOrNull ?: false,
            sameSite = obj["sameSite"]?.jsonPrimitive?.content
        )
    }

    private suspend fun applyNetworkHeaders() {
        val enableResponse = cdpSend(CdpMethods.NETWORK_ENABLE)
        enableResponse.error?.let { throw KmpwrightException("CDP error ${it.code}: ${it.message}") }
        val headers = LinkedHashMap<String, String>(extraHttpHeaders)
        val headerResponse = cdpSend(CdpMethods.NETWORK_SET_EXTRA_HTTP_HEADERS, mapOf("headers" to headers))
        headerResponse.error?.let { throw KmpwrightException("CDP error ${it.code}: ${it.message}") }
    }

    // --- Route interception ---

    override fun addRouteHandler(urlPattern: String, handler: suspend (Route) -> RouteHandler) {
        routeHandlers[urlPattern] = handler
        ensureFetchPausedListener()
        scope.launch {
            refreshFetchPatterns()
        }
    }

    override fun removeRouteHandler(urlPattern: String) {
        routeHandlers.remove(urlPattern)
        scope.launch {
            refreshFetchPatterns()
        }
    }

    override fun removeAllRouteHandlers() {
        routeHandlers.clear()
        queueFetchRefresh()
    }

    @Volatile private var fetchConfiguration: kotlinx.coroutines.Job? = null
    private fun queueFetchRefresh() {
        val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) { refreshFetchPatterns() }
        fetchConfiguration = job
        job.start()
    }

    private suspend fun refreshFetchPatterns() {
        fetchRefreshMutex.withLock {
            val needsAllRequests = routeHandlers.isNotEmpty() || domainFilter != null || httpCredentials != null
            val needsDocumentRequests = navigationGuard != null || allowedOriginRules.isNotEmpty()
            if (needsAllRequests || needsDocumentRequests) {
                val pattern = if (needsAllRequests) {
                    mapOf("urlPattern" to "*")
                } else {
                    // Policy-only interception needs the document request,
                    // not every subresource. This keeps the pre-commit guard
                    // inexpensive for normal pages.
                    mapOf("urlPattern" to "*", "resourceType" to "Document")
                }
                (listOf(sessionId) + observedChildSessions()).forEach { target ->
                    ensureFetchPausedListener(target)
                    fetchSend(target, CdpMethods.FETCH_ENABLE, mapOf("patterns" to listOf(pattern)))
                }
            } else {
                (listOf(sessionId) + observedChildSessions()).forEach { target -> fetchSend(target, CdpMethods.FETCH_DISABLE) }
            }
        }
    }

    private suspend fun fetchSend(target: String?, method: String, params: Map<String, Any?> = emptyMap()): CdpResponse =
        if (target == null) connection.send(method, params) else connection.sendSession(target, method, params)

    private fun ensureFetchPausedListener(targetSession: String? = sessionId) {
        synchronized(childNetworkLock) {
        if (targetSession == sessionId && fetchPausedListenerInstalled) return
        if (targetSession != sessionId && childFetchSubscriptions.containsKey(targetSession)) return
        if (targetSession == sessionId) fetchPausedListenerInstalled = true
        val subscription = connection.onEvent("Fetch.requestPaused", targetSession) { params ->
            val requestId = params["requestId"]?.jsonPrimitive?.content ?: return@onEvent
            val requestObj = params["request"]?.jsonObject ?: return@onEvent
            val url = requestObj["url"]?.jsonPrimitive?.content ?: return@onEvent
            val method = requestObj["method"]?.jsonPrimitive?.content ?: "GET"
            val headers = requestObj["headers"]?.jsonObject?.mapValues { (_, value) -> value.jsonPrimitive.content } ?: emptyMap()
            val postData = requestObj["postData"]?.jsonPrimitive?.content
            // CDP reports resourceType beside request in Fetch.requestPaused;
            // retain the nested fallback for lightweight transport seams.
            val resourceType = params["resourceType"]?.jsonPrimitive?.content
                ?: requestObj["resourceType"]?.jsonPrimitive?.content
            if (resourceType == "Document" && !isMainFrameNavigationAllowed(url)) {
                scope.launch {
                    fetchSend(targetSession, CdpMethods.FETCH_FAIL_REQUEST, buildFetchFailParams(requestId, "BlockedByResponse"))
                }
                return@onEvent
            }
            val filter = domainFilter
            val hostname = canonicalHttpHost(url)
            if (filter != null && (hostname == null || !filter.isAllowed(hostname))) {
                scope.launch {
                    fetchSend(targetSession, CdpMethods.FETCH_FAIL_REQUEST, buildFetchFailParams(requestId, "BlockedByResponse"))
                }
                return@onEvent
            }
            val routeEntry = routeHandlers.entries.firstOrNull { (pattern, _) ->
                runCatching { Regex(pattern).matches(url) }.getOrDefault(false)
            }
            if (routeEntry == null) {
                scope.launch {
                    val continuationHeaders = credentialHeaders(url, headers)
                    val params = mutableMapOf<String, Any?>("requestId" to requestId)
                    if (continuationHeaders != null) {
                        params["headers"] = continuationHeaders.map { (name, value) -> mapOf("name" to name, "value" to value) }
                    }
                    fetchSend(targetSession, CdpMethods.FETCH_CONTINUE_REQUEST, params)
                }
                return@onEvent
            }

            val request = Request(url = url, method = method, headers = headers, postData = postData, id = requestId)
            val route = CdpPausedRoute(request)
            scope.launch {
                val handlerResult = try {
                    withTimeoutOrNull(5_000) { routeEntry.value(route) } ?: RouteHandler.Abort("TimedOut")
                } catch (_: Exception) {
                    RouteHandler.Fallthrough
                }
                val action = route.action ?: when (handlerResult) {
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
                dispatchRouteAction(requestId, action, url, headers, targetSession)
            }
        }
        if (targetSession == sessionId) fetchPausedSubscription = subscription
        else if (targetSession != null) childFetchSubscriptions[targetSession] = subscription
        }
    }

    override fun setDomainFilter(domains: Set<String>): Boolean {
        domainFilter = domains.takeIf { it.isNotEmpty() }?.let(::DomainFilter)
        ensureFetchPausedListener()
        return true
    }

    override suspend fun setDomainFilterAsync(domains: Set<String>) {
        setDomainFilter(domains)
        // Do not return until Fetch is enabled with the new interception
        // pattern; otherwise a request can beat the filter activation.
        refreshFetchPatterns()
    }

    private suspend fun dispatchRouteAction(
        requestId: String,
        action: RouteAction,
        originalUrl: String,
        originalHeaders: Map<String, String>,
        targetSession: String? = sessionId
    ) {
        when (action) {
            is RouteAction.Fulfill -> fetchSend(targetSession, CdpMethods.FETCH_FULFILL_REQUEST, buildFetchFulfillParams(requestId, action.toFulfillOptions()))
            is RouteAction.Abort -> fetchSend(targetSession, CdpMethods.FETCH_FAIL_REQUEST, buildFetchFailParams(requestId, action.errorCode))
            is RouteAction.Continue -> {
                val targetUrl = action.url ?: originalUrl
                val filter = domainFilter
                if (filter != null) {
                    val hostname = canonicalHttpHost(targetUrl)
                    if (hostname == null || !filter.isAllowed(hostname)) {
                        fetchSend(targetSession, CdpMethods.FETCH_FAIL_REQUEST, buildFetchFailParams(requestId, "BlockedByResponse"))
                        return
                    }
                }
                val headers = credentialHeaders(targetUrl, action.headers ?: originalHeaders) ?: action.headers
                val options = action.toContinueOptions().copy(headers = headers)
                fetchSend(targetSession, CdpMethods.FETCH_CONTINUE_REQUEST, buildFetchContinueParams(requestId, options))
            }
            is RouteAction.Fallthrough -> {
                val headers = credentialHeaders(originalUrl, originalHeaders)
                if (headers == null) {
                    fetchSend(targetSession, CdpMethods.FETCH_CONTINUE_REQUEST, mapOf("requestId" to requestId))
                } else {
                    fetchSend(targetSession,
                        CdpMethods.FETCH_CONTINUE_REQUEST,
                        mapOf(
                            "requestId" to requestId,
                            "headers" to headers.map { (name, value) -> mapOf("name" to name, "value" to value) }
                        )
                    )
                }
            }
        }
    }

    private fun credentialHeaders(url: String, requestHeaders: Map<String, String>): Map<String, String>? {
        val credentials = httpCredentials ?: return null
        val origin = credentialOrigin ?: return null
        val sanitized = LinkedHashMap(requestHeaders)
        sanitized.keys.removeAll { it.equals("Authorization", ignoreCase = true) }
        if (canonicalHttpOrigin(url) == origin) {
            val token = java.util.Base64.getEncoder()
                .encodeToString("${credentials.first}:${credentials.second}".toByteArray(Charsets.UTF_8))
            sanitized["Authorization"] = "Basic $token"
        }
        return sanitized
    }

    private inner class CdpPausedRoute(
        override val request: Request
    ) : Route {
        override val requestId: String = request.id
        var action: RouteAction? = null

        override fun continueRequest(options: ContinueOptions) {
            action = RouteAction.Continue(options.url, options.method, options.headers, options.postData)
        }

        override fun fulfill(options: FulfillOptions) {
            action = RouteAction.Fulfill(
                status = options.status,
                headers = options.headers,
                body = options.body ?: "",
                contentType = options.contentType ?: "application/json",
                bodyBytes = options.bodyBytes
            )
        }

        override fun abort(errorCode: String) {
            action = RouteAction.Abort(errorCode)
        }
    }

    override fun fulfillRoute(requestId: String, options: FulfillOptions) {
        scope.launch {
            cdpSend(CdpMethods.FETCH_FULFILL_REQUEST, buildFetchFulfillParams(requestId, options))
        }
    }

    override fun abortRoute(requestId: String, errorCode: String) {
        scope.launch {
            cdpSend(CdpMethods.FETCH_FAIL_REQUEST, buildFetchFailParams(requestId, errorCode))
        }
    }

    override fun continueRoute(requestId: String, options: ContinueOptions) {
        scope.launch {
            cdpSend(CdpMethods.FETCH_CONTINUE_REQUEST, buildFetchContinueParams(requestId, options))
        }
    }

    override fun interceptRequest(url: String, method: String, headers: Map<String, String>, onResult: (RouteAction?) -> Unit) {
        // Check route handlers
        for ((pattern, handler) in routeHandlers) {
            if (url.contains(pattern, ignoreCase = true)) {
                scope.launch {
                    // Simplified — real implementation would use Fetch events
                    onResult(RouteAction.Fallthrough)
                }
                return
            }
        }
        onResult(null)
    }

    // --- Navigation callback ---

    override fun setNavigationCallback(callback: ((NavigationResult) -> Unit)?) {
        navigationCallback = callback
    }

    override fun setNavigationGuard(guard: ((String) -> Boolean)?) {
        navigationGuard = guard
        if (guard != null) {
            ensureFetchPausedListener()
            scope.launch { runCatching { refreshFetchPatterns() } }
        }
    }

    private fun isMainFrameNavigationAllowed(url: String): Boolean {
        return url.isNotBlank() &&
            (allowedOriginRules.isEmpty() || allowedOriginRules.any { originRuleMatches(it, url) }) &&
            navigationGuard?.invoke(url) != false
    }

    private fun installNavigationListeners() {
        navigationSubscriptions += connection.onEvent("Page.navigatedWithinDocument", sessionId) { params ->
            if (mainFrameId == null || params["frameId"]?.jsonPrimitive?.content == mainFrameId) {
                params["url"]?.jsonPrimitive?.content?.let { _url = it }
            }
        }

        navigationSubscriptions += connection.onEvent("Page.javascriptDialogOpening", sessionId) { params ->
            val type = when (params["type"]?.jsonPrimitive?.content) {
                "prompt" -> DialogType.Prompt
                "confirm", "beforeunload" -> DialogType.Confirm
                else -> DialogType.Alert
            }
            scope.launch {
                var response: DialogResponse = DialogResponse.Dismiss
                try {
                    response = dialogListener?.onDialogAsync(type,
                        params["message"]?.jsonPrimitive?.content ?: "",
                        params["defaultPrompt"]?.jsonPrimitive?.content) ?: DialogResponse.Dismiss
                } finally {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                        kotlinx.coroutines.withTimeoutOrNull(1_000) {
                            cdpSend("Page.handleJavaScriptDialog", buildMap {
                                put("accept", response !is DialogResponse.Dismiss)
                                if (response is DialogResponse.Text) put("promptText", response.text)
                            })
                        }
                    }
                }
            }
        }
        navigationSubscriptions += connection.onEvent("Page.frameNavigated", sessionId) { params ->
            val frame = params["frame"]?.jsonObject ?: return@onEvent
            if (frame["parentId"] == null) {
                mainFrameId = frame["id"]?.jsonPrimitive?.content
                val version = navigationVersion.incrementAndGet()
                val url = frame["url"]?.jsonPrimitive?.content.orEmpty()
                if ((allowedOriginRules.isNotEmpty() &&
                    (url.isBlank() || allowedOriginRules.none { originRuleMatches(it, url) })
                ) || (url.isBlank() || navigationGuard?.invoke(url) == false)) {
                    rejectedCommittedNavigation = true
                    _url = null
                    scope.launch {
                        cdpSend(CdpMethods.PAGE_STOP_LOADING)
                        if (version != navigationVersion.get()) return@launch
                        navigationCallback?.invoke(NavigationResult(url, 0, false, "Committed navigation origin is not allowed by KmpwrightConfig: $url"))
                    }
                } else {
                    // A later allowed navigation must recover the controller
                    // after an earlier browser-initiated navigation was
                    // rejected. The following load event belongs to this
                    // committed frame, not the rejected one.
                    rejectedCommittedNavigation = false
                    _url = url.ifBlank { _url }
                }
            }
        }
        navigationSubscriptions += connection.onEvent("Page.loadEventFired", sessionId) {
            if (rejectedCommittedNavigation) return@onEvent
            val version = navigationVersion.get()
            val url = _url ?: pendingNavigationUrl ?: ""
            val status = pendingNavigationStatus
            scope.launch {
                runCatching {
                    val titleResult = cdpSend(
                        CdpMethods.RUNTIME_EVALUATE,
                        mapOf("expression" to "document.title", "returnByValue" to true)
                    )
                    _title = titleResult.result?.get("result")?.jsonObject
                        ?.get("value")?.jsonPrimitive?.content
                }
                if (version != navigationVersion.get() || rejectedCommittedNavigation) return@launch
                navigationCallback?.invoke(NavigationResult(url, status, status in 200..399))
            }
        }
    }

    // --- Request listeners ---

    override fun addRequestListener(listener: RequestListener) {
        requestListeners = requestListeners.adding(listener)
        ensureNetworkEventListeners()
        scope.launch { cdpSend(CdpMethods.NETWORK_ENABLE) }
    }

    override fun removeRequestListener(listener: RequestListener) {
        requestListeners = requestListeners.removing(listener)
    }

    override suspend fun networkResponseBody(requestId: String, maxBytes: Int): NetworkResponseBody {
        val parts = if (requestId.startsWith("frame:")) requestId.split(':', limit = 3) else emptyList()
        val reply = if (parts.size == 3) connection.sendSession(parts[1], "Network.getResponseBody", mapOf("requestId" to parts[2]))
            else cdpSend("Network.getResponseBody", mapOf("requestId" to requestId))
        if (reply.error != null) throw KmpwrightException("Response body is unavailable: ${reply.error}")
        val body = reply.result?.get("body")?.jsonPrimitive?.content
            ?: throw KmpwrightException("Response body is unavailable")
        val base64 = reply.result["base64Encoded"]?.jsonPrimitive?.booleanOrNull == true
        if (!base64) {
            val preview = boundedUtf8(body, maxBytes)
            return NetworkResponseBody(preview, false, preview != body)
        }
        val bytes = java.util.Base64.getDecoder().decode(body)
        return NetworkResponseBody(java.util.Base64.getEncoder().encodeToString(bytes.copyOf(minOf(bytes.size, maxBytes))),
            true, bytes.size > maxBytes)
    }

    private fun ensureNetworkEventListeners(observedSession: String? = sessionId): Unit = synchronized(childNetworkLock) {
        if (networkSessionSubscriptions.containsKey(observedSession)) return@synchronized
        val owned = mutableListOf<CdpEventSubscription>()
        fun requestKey(params: JsonObject): String? = params["requestId"]?.jsonPrimitive?.content?.let {
            if (observedSession == sessionId) it else "frame:$observedSession:$it"
        }
        owned += connection.onEvent("Network.requestWillBeSent", observedSession) { params ->
            val requestId = requestKey(params) ?: return@onEvent
            val requestObj = params["request"]?.jsonObject ?: return@onEvent
            params["redirectResponse"]?.jsonObject?.let { redirect ->
                pendingRequests.remove(requestId)?.let { previous ->
                    val response = Response(previous.url, redirect["status"]?.jsonPrimitive?.longOrNull?.toInt() ?: 0,
                        redirect["statusText"]?.jsonPrimitive?.content,
                        redirect["headers"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content }.orEmpty(), id = requestId)
                    requestListeners.forEach { it.onResponseReceived(response); it.onRequestFinished(previous) }
                }
            }
            val request = Request(
                resourceType = params["type"]?.jsonPrimitive?.content?.lowercase() ?: "other",
                url = requestObj["url"]?.jsonPrimitive?.content ?: "",
                method = requestObj["method"]?.jsonPrimitive?.content ?: "GET",
                headers = requestObj["headers"]?.jsonObject?.mapValues { (_, value) -> value.jsonPrimitive.content } ?: emptyMap(),
                postData = requestObj["postData"]?.jsonPrimitive?.content,
                id = requestId
            )
            pendingRequests[requestId] = request
            requestListeners.forEach { it.onRequestStarted(request) }
        }
        owned += connection.onEvent("Network.responseReceived", observedSession) { params ->
            val requestId = requestKey(params) ?: return@onEvent
            val responseObj = params["response"]?.jsonObject ?: return@onEvent
            val response = Response(
                url = responseObj["url"]?.jsonPrimitive?.content ?: pendingRequests[requestId]?.url.orEmpty(),
                status = responseObj["status"]?.jsonPrimitive?.longOrNull?.toInt() ?: 0,
                statusText = responseObj["statusText"]?.jsonPrimitive?.content,
                fromCache = responseObj["fromDiskCache"]?.jsonPrimitive?.booleanOrNull == true || responseObj["fromServiceWorker"]?.jsonPrimitive?.booleanOrNull == true,
                headers = responseObj["headers"]?.jsonObject?.mapValues { (_, value) -> value.jsonPrimitive.content } ?: emptyMap(),
                id = requestId
            )
            if (observedSession == sessionId && params["type"]?.jsonPrimitive?.content == "Document" &&
                ((mainFrameId != null && params["frameId"]?.jsonPrimitive?.content == mainFrameId) ||
                    (mainFrameId == null && response.url == pendingNavigationUrl))) {
                pendingNavigationStatus = response.status
                _url = response.url.ifEmpty { _url }
            }
            requestListeners.forEach { it.onResponseReceived(response) }
        }
        owned += connection.onEvent("Network.loadingFinished", observedSession) { params ->
            val requestId = requestKey(params) ?: return@onEvent
            val request = pendingRequests.remove(requestId) ?: return@onEvent
            requestListeners.forEach { it.onRequestFinished(request) }
        }
        owned += connection.onEvent("Network.loadingFailed", observedSession) { params ->
            val requestId = requestKey(params) ?: return@onEvent
            val error = params["errorText"]?.jsonPrimitive?.content ?: "failed"
            val request = pendingRequests.remove(requestId) ?: Request(url = "", id = requestId)
            requestListeners.forEach { listener ->
                listener.onRequestFailed(request, error)
                listener.onRequestFinished(request)
            }
        }
        networkSessionSubscriptions[observedSession] = owned
    }

    // Not used in CDP mode — network events are handled via CDP events
    override fun getPendingRequestCount(): Int = pendingRequests.size
    override fun resetRequestCount() {
        pendingRequests.clear()
    }
    override fun setRequestIdleTimeout(timeoutMs: Long) {}

    // --- Dialog ---

    override fun addDialogListener(listener: DialogListener) {
        dialogListener = listener
    }

    override fun removeDialogListener(listener: DialogListener) {
        if (dialogListener === listener) dialogListener = null
    }

    // --- PDF ---

    override fun validatePdfOptions(options: PdfOptions) {
        options.paperSizeInches()
        require(options.scale.isFinite() && options.scale in 0.1..2.0) { "PDF scale must be between 0.1 and 2.0" }
        listOf(options.margin.top, options.margin.bottom, options.margin.left, options.margin.right).forEach(::pdfLengthInches)
    }

    override fun generatePdf(options: PdfOptions, callback: (ByteArray?) -> Unit) {
        scope.launch {
            try {
                val size = options.copy(landscape = false).paperSizeInches()
                val params = mutableMapOf<String, Any>(
                    "landscape" to options.landscape,
                    "displayHeaderFooter" to options.displayHeaderFooter,
                    "printBackground" to options.printBackground,
                    "paperWidth" to size.first, "paperHeight" to size.second,
                    "headerTemplate" to options.headerTemplate, "footerTemplate" to options.footerTemplate,
                    "scale" to options.scale, "pageRanges" to options.pageRanges,
                    "marginTop" to pdfLengthInches(options.margin.top),
                    "marginBottom" to pdfLengthInches(options.margin.bottom),
                    "marginLeft" to pdfLengthInches(options.margin.left),
                    "marginRight" to pdfLengthInches(options.margin.right)
                )
                val result = cdpSend(CdpMethods.PAGE_PRINT_TO_PDF, params)
                val data = result.result?.get("data")?.jsonPrimitive?.content
                callback(data?.let { java.util.Base64.getDecoder().decode(it) })
            } catch (_: Exception) {
                callback(null)
            }
        }
    }

    // --- Dispose ---

    override suspend fun disposeAsync() {
        if (scriptsClosing.compareAndSet(false, true)) {
            withContext(NonCancellable) {
                withTimeoutOrNull(3_000) {
                    for (batch in ownedInitScripts.toList().chunked(16)) coroutineScope {
                        batch.map { id -> async { withTimeoutOrNull(1_000) { runCatching { removeInitScript(id) } } } }.awaitAll()
                    }
                }
            }
        }
        webMcpRuntime.disposeAsync()
        dispose()
    }

    override fun dispose() {
        if (!disposed.compareAndSet(false, true)) return
        recordingCursor.stop()
        scriptsClosing.set(true)
        ownedInitScripts.clear()
        webMcpRuntime.dispose()

        routeHandlers.clear()
        domainFilter = null
        requestListeners = persistentListOf()
        pendingRequests.clear()
        navigationCallback = null
        dialogListener = null
        fetchPausedListenerInstalled = false
        val fetchSubscription = fetchPausedSubscription
        fetchPausedSubscription = null
        synchronized(childNetworkLock) { childFetchSubscriptions.values.forEach { it.cancel() }; childFetchSubscriptions.clear() }
        try {
            fetchSubscription?.cancel()
        } catch (_: Throwable) {
            // Continue releasing every subscription even if one is faulty.
        }
        val network = synchronized(childNetworkLock) { networkSessionSubscriptions.values.flatten().also { networkSessionSubscriptions.clear() } }
        childSessionReady = {}
        childSessionGone = {}
        network.forEach {
            try { it.cancel() } catch (_: Throwable) {}
        }
        val navigation = navigationSubscriptions.toList()
        navigationSubscriptions.clear()
        navigation.forEach {
            try { it.cancel() } catch (_: Throwable) {}
        }
        try {
            scope.cancel()
        } catch (_: Throwable) {
            // Coroutine cancellation is best effort during shutdown.
        }
    }
}

internal fun buildFetchFulfillParams(requestId: String, options: FulfillOptions): Map<String, Any?> {
    val headers = LinkedHashMap(options.headers)
    options.contentType?.let { headers.putIfAbsent("Content-Type", it) }
    val bodyBytes = options.bodyBytes ?: options.body?.encodeToByteArray()
    return buildMap {
        put("requestId", requestId)
        put("responseCode", options.status)
        if (headers.isNotEmpty()) {
            put("responseHeaders", headers.map { (name, value) -> mapOf("name" to name, "value" to value) })
        }
        if (bodyBytes != null) {
            put("body", java.util.Base64.getEncoder().encodeToString(bodyBytes))
        }
    }
}

internal fun buildFetchContinueParams(requestId: String, options: ContinueOptions): Map<String, Any?> =
    buildMap {
        put("requestId", requestId)
        options.url?.let { put("url", it) }
        options.method?.let { put("method", it) }
        options.headers?.let { headers ->
            put("headers", headers.map { (name, value) -> mapOf("name" to name, "value" to value) })
        }
        options.postData?.let {
            put("postData", java.util.Base64.getEncoder().encodeToString(it.encodeToByteArray()))
        }
    }

internal fun buildFetchFailParams(requestId: String, errorCode: String): Map<String, Any?> =
    mapOf("requestId" to requestId, "errorReason" to normalizeFetchErrorReason(errorCode))

internal fun normalizeFetchErrorReason(errorCode: String): String {
    val normalized = errorCode.trim()
    val allowed = setOf(
        "Failed", "Aborted", "TimedOut", "AccessDenied", "ConnectionClosed",
        "ConnectionReset", "ConnectionRefused", "ConnectionAborted", "ConnectionFailed",
        "NameNotResolved", "InternetDisconnected", "AddressUnreachable", "BlockedByClient",
        "BlockedByResponse"
    )
    return when {
        normalized in allowed -> normalized
        normalized.equals("aborted", ignoreCase = true) -> "Aborted"
        normalized.equals("timedout", ignoreCase = true) || normalized.equals("timeout", ignoreCase = true) -> "TimedOut"
        normalized.equals("accessdenied", ignoreCase = true) || normalized.equals("access_denied", ignoreCase = true) -> "AccessDenied"
        normalized.equals("blockedbyclient", ignoreCase = true) || normalized.equals("blocked_by_client", ignoreCase = true) -> "BlockedByClient"
        normalized.equals("blockedbyresponse", ignoreCase = true) || normalized.equals("blocked_by_response", ignoreCase = true) -> "BlockedByResponse"
        else -> "Failed"
    }
}

private fun RouteAction.Fulfill.toFulfillOptions(): FulfillOptions =
    FulfillOptions(status = status, headers = headers, body = body, bodyBytes = bodyBytes, contentType = contentType)

private fun RouteAction.Continue.toContinueOptions(): ContinueOptions =
    ContinueOptions(url = url, method = method, headers = headers, postData = postData)

private fun decodeImageSize(bytes: ByteArray): Pair<Int, Int> {
    return runCatching {
        ImageIO.read(ByteArrayInputStream(bytes))?.let { image ->
            image.width to image.height
        }
    }.getOrNull() ?: (0 to 0)
}

private const val HIDE_SCROLLBARS_SCRIPT = """
(() => {
    const key = '__kmpwrightHideScrollbarsCount';
    const styleId = '__kmpwright_hide_scrollbars_style__';
    window[key] = (window[key] || 0) + 1;
    if (!document.getElementById(styleId)) {
        const style = document.createElement('style');
        style.id = styleId;
        style.textContent = `
            *, *::before, *::after {
                -ms-overflow-style: none !important;
                scrollbar-width: none !important;
            }
            ::-webkit-scrollbar {
                display: none !important;
                width: 0 !important;
                height: 0 !important;
            }
        `;
        (document.head || document.documentElement).appendChild(style);
    }
    return true;
})()
"""

private const val RESTORE_SCROLLBARS_SCRIPT = """
(() => {
    const key = '__kmpwrightHideScrollbarsCount';
    const styleId = '__kmpwright_hide_scrollbars_style__';
    window[key] = Math.max((window[key] || 1) - 1, 0);
    if (window[key] === 0) {
        const style = document.getElementById(styleId);
        if (style) style.remove();
    }
    return true;
})()
"""
