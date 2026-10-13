package com.bernaferrari.kmpwright.core

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
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
    private val allowedOriginRules: Set<String> = emptySet(),
    /** [TimeoutConfig.handler]: bounds dialog listeners (then dismissed) and route handlers (then aborted). */
    private val handlerTimeoutMs: Long = TimeoutConfig().handler,
    private val onOriginObserved: (String) -> Unit = {}
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
            observeRuntimeDiagnostics(child)
            connection.sendSession(child, "Page.enable").error?.let {
                throw KmpwrightException("Could not observe iframe origins: ${it.message}")
            }
            connection.sendSession(child, "Page.getFrameTree").result?.get("frameTree")?.jsonObject?.let(::observeFrameTree)
            connection.sendSession(child, "Runtime.enable").error?.let {
                kmpwrightLogWarning("Could not observe iframe console: ${it.message}", null)
            }
            val enabled = connection.sendSession(child, "Network.enable")
            enabled.error?.let { throw KmpwrightException("Could not observe iframe network: ${it.message}") }
            ensureFetchPausedListener(child)
            refreshFetchPatterns()
            applyEmulationTo(child)
            childSessionReady(child)
        }, frameGone = { child ->
            synchronized(childNetworkLock) { networkSessionSubscriptions.remove(child)?.forEach { it.cancel() } }
            synchronized(childNetworkLock) { runtimeChildSubscriptions.remove(child)?.forEach { it.cancel() } }
            pendingRequests.keys.filter { id ->
                id.startsWith("frame:$child:") || synchronized(responseBodySources) { responseBodySources[id]?.first == child }
            }.forEach { id ->
                pendingRequests.remove(id)?.let { request ->
                    networkActivity.finished(id)
                    deliver { requestListeners.forEach { it.onRequestFailed(request, "Frame session detached") } }
                }
            }
            synchronized(childNetworkLock) { childFetchSubscriptions.remove(child)?.cancel() }
            childSessionGone(child)
        })
    override val webMcp: WebMcp get() = webMcpRuntime

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, error ->
        // Fire-and-forget protocol work (navigation, cookies, routing) has no
        // caller to rethrow to; never let a failure disappear silently.
        kmpwrightLogWarning("CDP page background task failed", error)
    })
    private val disposed = AtomicBoolean(false)

    /**
     * Serial, ordered delivery of user-facing callbacks (request listeners,
     * navigation guards) off the CDP event pump. A slow or blocking callback
     * then stalls only later callbacks, never protocol replies, so it cannot
     * deadlock a command it is waiting for.
     */
    private val callbackQueue = kotlinx.coroutines.channels.Channel<() -> Unit>(kotlinx.coroutines.channels.Channel.UNLIMITED)
    init {
        scope.launch {
            for (task in callbackQueue) {
                try { task() } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Throwable) { kmpwrightLogWarning("CDP page callback failed", error) }
            }
        }
    }
    private fun deliver(task: () -> Unit) { callbackQueue.trySend(task) }
    internal val recordingCursor = RecordingCursorTimeline()
    private val scriptsClosing = AtomicBoolean(false)
    private val ownedInitScripts = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    @Volatile private var requestListeners = persistentListOf<RequestListener>()
    private class RouteEntry(val pattern: String, val regex: Regex, val handler: suspend (Route) -> Unit)
    /** Registration order; like Playwright the most recently added matching route wins. */
    private val routeHandlers = java.util.concurrent.CopyOnWriteArrayList<RouteEntry>()
    init {
        connection.connectionState?.let { state -> scope.launch {
            state.collect { connected ->
                if (!connected && !disposed.get()) deliverNavigation(
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
    private val networkActivity = CdpNetworkActivity()
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
    private val navigationCommandVersion = java.util.concurrent.atomic.AtomicLong()
    private val navigationCommandLock = Any()
    @Volatile private var navigationGuard: ((String) -> Boolean)? = null
    /** Set while [loadHtml] waits for its base document; receives that navigation's result. */
    @Volatile private var htmlNavigation: CompletableDeferred<NavigationResult>? = null
    /** Page.setDocumentContent replaced the document; ignore its load event until the next commit. */
    @Volatile private var contentReplaced = false

    constructor(
        connection: CdpConnection,
        sessionId: String? = null,
        hideScrollbarsForScreenshots: Boolean = false,
        allowedOriginRules: Set<String> = emptySet(),
        handlerTimeoutMs: Long = TimeoutConfig().handler
    ) : this(connection as CdpEventTransport, sessionId, hideScrollbarsForScreenshots, allowedOriginRules, handlerTimeoutMs)

    private fun deliverNavigation(result: NavigationResult) {
        htmlNavigation?.let { if (it.complete(result)) return }
        navigationCallback?.invoke(result)
    }

    @Volatile private var diagnosticsListeners = persistentListOf<PageDiagnosticsListener>()
    /** Runtime console/exception subscriptions of out-of-process iframe sessions, guarded by [childNetworkLock]. */
    private val runtimeChildSubscriptions = mutableMapOf<String, List<CdpEventSubscription>>()

    init {
        installNavigationListeners()
        observeRuntimeDiagnostics(sessionId)
    }

    /**
     * Console API calls and uncaught exceptions of one session (the page, or an OOPIF child; same-process
     * iframes report through the page session). Delivered directly on the event pump, so they stay
     * ordered with the main frame's `Page.frameNavigated` commit signal.
     */
    private fun observeRuntimeDiagnostics(observedSession: String?) {
        val subscriptions = listOfNotNull(
            if (observedSession != sessionId) connection.onEvent("Page.frameNavigated", observedSession) { params ->
                params["frame"]?.jsonObject?.let(::observeFrameOrigin)
            } else null,
            connection.onEvent("Runtime.consoleAPICalled", observedSession) { params ->
                val message = cdpConsoleMessage(params) ?: return@onEvent
                diagnosticsListeners.forEach { it.onConsoleMessage(message) }
            },
            connection.onEvent("Runtime.exceptionThrown", observedSession) { params ->
                val error = cdpPageError(params) ?: return@onEvent
                diagnosticsListeners.forEach { it.onPageError(error) }
            }
        )
        if (observedSession == sessionId) {
            navigationSubscriptions += subscriptions
        } else synchronized(childNetworkLock) {
            runtimeChildSubscriptions.put(observedSession!!, subscriptions)?.forEach { it.cancel() }
        }
    }

    private fun observeFrameOrigin(frame: JsonObject) {
        val origin = frame["securityOrigin"]?.jsonPrimitive?.contentOrNull
            ?: frame["url"]?.jsonPrimitive?.contentOrNull?.let { url ->
                runCatching { io.ktor.http.Url(url) }.getOrNull()?.takeIf { it.protocol.name in setOf("http", "https") }
                    ?.let { "${it.protocol.name}://${it.host}:${it.port}" }
            }
        if (origin != null && origin.contains("://") && origin != "://") onOriginObserved(origin)
    }

    private fun observeFrameTree(tree: JsonObject) {
        tree["frame"]?.jsonObject?.let(::observeFrameOrigin)
        tree["childFrames"]?.jsonArray?.forEach { observeFrameTree(it.jsonObject) }
    }

    override fun addDiagnosticsListener(listener: PageDiagnosticsListener) {
        diagnosticsListeners = diagnosticsListeners.adding(listener)
    }

    override fun removeDiagnosticsListener(listener: PageDiagnosticsListener) {
        diagnosticsListeners = diagnosticsListeners.removing(listener)
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
        launchNavigation(url)
    }

    private fun advanceNavigationCommand(): Long = synchronized(navigationCommandLock) {
        val version = navigationCommandVersion.incrementAndGet()
        htmlNavigation?.complete(NavigationResult(_url.orEmpty(), 0, false, "Navigation superseded by a newer request"))
        htmlNavigation = null
        version
    }

    private fun isCurrentNavigationCommand(version: Long): Boolean =
        version == navigationCommandVersion.get() && !disposed.get()

    internal fun launchNavigation(url: String): Job {
        val version = advanceNavigationCommand()
        return scope.launch { navigateTo(url, version) }
    }

    private suspend fun navigateTo(url: String, commandVersion: Long) {
        try {
            fetchConfiguration?.join()
            if (!isCurrentNavigationCommand(commandVersion)) return
            if (allowedOriginRules.isNotEmpty() && !allowedOriginRules.any { originRuleMatches(it, url) }) {
                deliverNavigation(NavigationResult(url, 0, false, "Navigation origin is not allowed by KmpwrightConfig: $url"))
                return
            }
            if (navigationGuard?.invoke(url) == false) {
                deliverNavigation(NavigationResult(url, 0, false, "Navigation denied by action policy: $url"))
                return
            }
            webMcpRuntime.observeBeforeNavigation()
            if (!isCurrentNavigationCommand(commandVersion)) return
            rejectedCommittedNavigation = false
            if (credentialOrigin == null) credentialOrigin = canonicalHttpOrigin(url)
            applyNetworkHeaders()
            if (mainFrameId == null) mainFrameId = cdpSend("Page.getFrameTree").result?.get("frameTree")?.jsonObject
                ?.get("frame")?.jsonObject?.get("id")?.jsonPrimitive?.content
            if (!isCurrentNavigationCommand(commandVersion)) return
            pendingNavigationUrl = url
            pendingNavigationStatus = 200
            val result = cdpSend(CdpMethods.PAGE_NAVIGATE, mapOf("url" to url))
            if (!isCurrentNavigationCommand(commandVersion)) return
            result.error?.let {
                deliverNavigation(NavigationResult(url, 0, false, "CDP error ${it.code}: ${it.message}"))
                return
            }
            val payload = result.result ?: return
            payload["frameId"]?.jsonPrimitive?.content?.let { mainFrameId = it }
            // Network failures (DNS, TLS, refused connections) are reported in
            // errorText of an otherwise successful command reply.
            payload["errorText"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { errorText ->
                deliverNavigation(NavigationResult(url, 0, false, errorText))
                return
            }
            // No loaderId means a same-document navigation (fragment or history
            // entry): there is no new document, so no load event will follow.
            if (payload["loaderId"] == null || payload["loaderId"] is kotlinx.serialization.json.JsonNull) {
                deliverNavigation(committedNavigation(url, pendingNavigationStatus))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            if (isCurrentNavigationCommand(commandVersion)) deliverNavigation(NavigationResult(url, 0, false, error = e.message))
        }
    }

    /**
     * Commits [baseUrl] (or about:blank) as the document origin, then replaces
     * the main frame's document with [content] via Page.setDocumentContent, so
     * relative URLs, cookies and storage resolve against the base URL.
     */
    override fun loadHtml(content: String, baseUrl: String?) {
        val commandVersion = advanceNavigationCommand()
        scope.launch {
            val target = baseUrl?.takeIf { it.isNotBlank() } ?: "about:blank"
            val waiter = CompletableDeferred<NavigationResult>()
            val registered = synchronized(navigationCommandLock) {
                if (!isCurrentNavigationCommand(commandVersion)) false
                else { htmlNavigation = waiter; true }
            }
            if (!registered) return@launch
            val loaded = try {
                navigateTo(target, commandVersion)
                if (!isCurrentNavigationCommand(commandVersion)) return@launch
                withTimeoutOrNull(HTML_BASE_NAVIGATION_TIMEOUT_MS) { waiter.await() }
                    ?: NavigationResult(target, 0, false, "Timed out loading base URL $target")
            } finally {
                synchronized(navigationCommandLock) { if (htmlNavigation === waiter) htmlNavigation = null }
            }
            if (!isCurrentNavigationCommand(commandVersion)) return@launch
            if (!loaded.ok) { deliverNavigation(loaded); return@launch }
            try {
                val frameId = mainFrameId ?: cdpSend("Page.getFrameTree").result?.get("frameTree")?.jsonObject
                    ?.get("frame")?.jsonObject?.get("id")?.jsonPrimitive?.content
                    ?: throw KmpwrightException("Main frame is unavailable")
                if (!isCurrentNavigationCommand(commandVersion)) return@launch
                val response = cdpSend("Page.setDocumentContent", mapOf("frameId" to frameId, "html" to content))
                response.error?.let { throw KmpwrightException("Could not set document content: ${it.message}") }
                if (!isCurrentNavigationCommand(commandVersion)) return@launch
                contentReplaced = true
                _title = runCatching {
                    cdpSend(CdpMethods.RUNTIME_EVALUATE, mapOf("expression" to "document.title", "returnByValue" to true))
                        .result?.get("result")?.jsonObject?.get("value")?.jsonPrimitive?.contentOrNull
                }.getOrNull()
                if (isCurrentNavigationCommand(commandVersion)) deliverNavigation(NavigationResult(_url ?: target, loaded.status, true))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (isCurrentNavigationCommand(commandVersion)) deliverNavigation(NavigationResult(target, 0, false, error.message))
            }
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
        // The body runs in an async function so `return await ...` works; the
        // newline keeps a trailing `// comment` from swallowing the closing brace.
        val expression = if (script.trimStart().startsWith("return ")) {
            "(async function() {\n$script\n})()"
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
            val result = try {
                captureScreenshot(options)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                kmpwrightLogWarning("CDP screenshot failed", error)
                null
            }
            if (result == null) callback(null, 0, 0) else callback(result.bytes, result.width, result.height)
        }
    }

    /** Captures a screenshot, throwing with the protocol's reason on failure. */
    override suspend fun captureScreenshot(options: ScreenshotOptions): ScreenshotResult {
        require(options.scale.isFinite() && options.scale > 0) { "Screenshot scale must be positive" }
        require(options.quality in 0..100) { "Screenshot quality must be between 0 and 100" }
        if (disposed.get()) throw KmpwrightException("Page is closed")
        val shouldRestoreScrollbars = hideScrollbarsForScreenshots && installHiddenScrollbarStyle()
        val params = mutableMapOf<String, Any?>(
            "format" to options.format.name.lowercase()
        )
        if (options.format != ScreenshotFormat.Png) params["quality"] = options.quality
        var backgroundOverridden = false
        try {
            if (options.omitBackground) {
                val response = cdpSend("Emulation.setDefaultBackgroundColorOverride", mapOf("color" to mapOf("r" to 0, "g" to 0, "b" to 0, "a" to 0)))
                response.error?.let { throw KmpwrightException("Could not make the background transparent: ${it.message}") }
                backgroundOverridden = true
            }
            val clip = if (options.clip != null || options.scale != 1.0) {
                val metrics = cdpSend(CdpMethods.PAGE_GET_LAYOUT_METRICS)
                metrics.error?.let { throw KmpwrightException("Could not read layout metrics: ${it.message}") }
                fun dimensions(vararg names: String) = names.firstNotNullOfOrNull { metrics.result?.get(it)?.jsonObject }
                    ?: throw KmpwrightException("Screenshot dimensions unavailable")
                fun JsonObject.number(name: String) = this[name]?.jsonPrimitive?.doubleOrNull ?: 0.0
                val viewport by lazy { dimensions("cssVisualViewport", "cssLayoutViewport") }
                when {
                    options.fullPage -> dimensions("cssContentSize").let {
                        Rectangle(it.number("x"), it.number("y"), it.number("width"), it.number("height"))
                    }
                    // CDP clips are in document coordinates; ScreenshotOptions.clip is viewport-relative.
                    options.clip != null -> trimScreenshotClip(options.clip, viewport.number("clientWidth"), viewport.number("clientHeight"))
                        .let { Rectangle(it.x + viewport.number("pageX"), it.y + viewport.number("pageY"), it.width, it.height) }
                    else -> Rectangle(viewport.number("pageX"), viewport.number("pageY"), viewport.number("clientWidth"), viewport.number("clientHeight"))
                }
            } else null
            if (clip != null) {
                params["clip"] = mapOf(
                    "x" to clip.x,
                    "y" to clip.y,
                    "width" to clip.width,
                    "height" to clip.height,
                    "scale" to options.scale
                )
                // A full-page clip extends below the fold; without this flag
                // Chromium only paints the viewport and the rest is blank.
                if (options.fullPage) params["captureBeyondViewport"] = true
            } else {
                params["captureBeyondViewport"] = options.fullPage
                params["fromSurface"] = true
            }
            val result = cdpSend(CdpMethods.PAGE_CAPTURE_SCREENSHOT, params)
            result.error?.let { throw KmpwrightException("Screenshot failed: ${it.message}") }
            val data = result.result?.get("data")?.jsonPrimitive?.contentOrNull
                ?: throw KmpwrightException("Screenshot returned no image data")
            val bytes = java.util.Base64.getDecoder().decode(data)
            val (width, height) = decodeImageSize(bytes)
            return ScreenshotResult(bytes, width, height)
        } finally {
            withContext(NonCancellable) {
                if (backgroundOverridden) runCatching { cdpSend("Emulation.setDefaultBackgroundColorOverride") }
                if (shouldRestoreScrollbars) restoreHiddenScrollbarStyle()
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
    @Volatile
    private var cachedMobile: Boolean = false
    private var cachedLandscape: Boolean? = null

    /** True once the caller emulates a size; the browser window no longer dictates it. */
    @Volatile
    private var viewportOverridden = false

    /** CSS layout viewport (not document) size, kept fresh on resize. */
    override fun getViewportSize(): Pair<Int, Int> = cachedViewportSize

    override fun setViewportSize(width: Int, height: Int) {
        viewportOverridden = true
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
        viewportOverridden = true
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
        val params = mutableMapOf<String, Any?>(
            "width" to width.coerceAtLeast(1),
            "height" to height.coerceAtLeast(1),
            "deviceScaleFactor" to cachedDeviceScaleFactor,
            "mobile" to cachedMobile
        )
        // Like Playwright, only mobile emulation reports an orientation.
        cachedLandscape?.takeIf { cachedMobile }?.let { landscape ->
            params["screenOrientation"] = if (landscape) mapOf("angle" to 90, "type" to "landscapePrimary")
            else mapOf("angle" to 0, "type" to "portraitPrimary")
        }
        val response = cdpSend(CdpMethods.EMULATION_SET_DEVICE_METRICS_OVERRIDE, params)
        response.error?.let { throw KmpwrightException("Viewport configuration failed: ${it.message}") }
    }

    /** Playwright's viewport emulation: one device-metrics override plus touch emulation. */
    override suspend fun emulateViewportAsync(viewport: Viewport) {
        require(viewport.width > 0 && viewport.height > 0) { "Viewport dimensions must be positive" }
        require(viewport.deviceScaleFactor.isFinite() && viewport.deviceScaleFactor > 0) { "Device scale factor must be positive" }
        viewportOverridden = true
        cachedViewportSize = viewport.width to viewport.height
        cachedDeviceScaleFactor = viewport.deviceScaleFactor
        cachedMobile = viewport.isMobile
        cachedLandscape = viewport.isLandscape
        applyDeviceMetrics()
        val touchPoints = if (viewport.hasTouch) 5 else 0
        val touch = cdpSend(CdpMethods.EMULATION_SET_TOUCH_EMULATION_ENABLED,
            if (viewport.hasTouch) mapOf("enabled" to true, "maxTouchPoints" to touchPoints) else mapOf("enabled" to false))
        touch.error?.let { throw KmpwrightException("Touch emulation failed: ${it.message}") }
        inputState.configuredTouchPoints = touchPoints
    }

    init {
        // Fetch the initial viewport size asynchronously and track window
        // resizes until the caller emulates an explicit viewport.
        scope.launch { refreshViewportSize() }
        navigationSubscriptions += connection.onEvent("Page.frameResized", sessionId) {
            if (!viewportOverridden && !disposed.get()) scope.launch { refreshViewportSize() }
        }
    }

    private suspend fun refreshViewportSize() {
        val result = cdpSend(CdpMethods.PAGE_GET_LAYOUT_METRICS)
        if (result.error != null || viewportOverridden) return
        val viewport = result.result?.get("cssLayoutViewport")?.jsonObject ?: return
        val width = viewport["clientWidth"]?.jsonPrimitive?.doubleOrNull?.toInt() ?: return
        val height = viewport["clientHeight"]?.jsonPrimitive?.doubleOrNull?.toInt() ?: return
        if (!viewportOverridden && width > 0 && height > 0) cachedViewportSize = width to height
    }

    // --- Emulation ---

    /** Native emulation applied to this page's target and re-applied to out-of-process iframe sessions. */
    @Volatile private var emulatedColorScheme: String? = null
    @Volatile private var emulatedGeolocation: Geolocation? = null
    @Volatile private var emulatedNetwork: Map<String, Any?>? = null

    private suspend fun emulationTargets(): List<String?> = listOf(sessionId) + observedChildSessions()

    private suspend fun sendEmulation(target: String?, method: String, params: Map<String, Any?>, what: String) {
        val response = if (target == null) connection.send(method, params) else connection.sendSession(target, method, params)
        response.error?.let { throw KmpwrightException("$what failed: ${it.message}") }
    }

    private suspend fun applyColorScheme(target: String?) {
        sendEmulation(target, CdpMethods.EMULATION_SET_EMULATED_MEDIA, mapOf(
            "features" to listOf(mapOf("name" to "prefers-color-scheme", "value" to (emulatedColorScheme ?: "")))
        ), "Color scheme emulation")
    }

    private suspend fun applyGeolocation(target: String?) {
        val geo = emulatedGeolocation
        if (geo == null) sendEmulation(target, CdpMethods.EMULATION_CLEAR_GEOLOCATION_OVERRIDE, emptyMap(), "Geolocation reset")
        else sendEmulation(target, CdpMethods.EMULATION_SET_GEOLATION_OVERRIDE,
            mapOf("latitude" to geo.latitude, "longitude" to geo.longitude, "accuracy" to geo.accuracy), "Geolocation emulation")
    }

    private suspend fun applyNetworkConditions(target: String?) {
        val conditions = emulatedNetwork ?: mapOf("offline" to false, "latency" to 0, "downloadThroughput" to -1, "uploadThroughput" to -1)
        sendEmulation(target, CdpMethods.NETWORK_EMULATE_NETWORK_CONDITIONS, conditions, "Network emulation")
    }

    /** Re-applies active overrides to an iframe target that attached after they were set. */
    private suspend fun applyEmulationTo(child: String) {
        if (emulatedColorScheme != null) applyColorScheme(child)
        if (emulatedGeolocation != null) applyGeolocation(child)
        if (emulatedNetwork != null) applyNetworkConditions(child)
    }

    override suspend fun setColorSchemeOverride(scheme: ColorScheme?): Boolean {
        emulatedColorScheme = when (scheme) {
            null -> null
            ColorScheme.Light -> "light"
            ColorScheme.Dark -> "dark"
            ColorScheme.NoPreference -> "no-preference"
        }
        emulationTargets().forEach { applyColorScheme(it) }
        return true
    }

    override suspend fun setGeolocationOverride(geolocation: Geolocation?): Boolean {
        emulatedGeolocation = geolocation
        if (emulatedGeolocation != null) grantGeolocationPermission()
        emulationTargets().forEach { applyGeolocation(it) }
        return true
    }

    /**
     * Emulated coordinates are only observable once the page may use geolocation; grant it for this
     * page's browser context (all origins), like Playwright's `permissions: ["geolocation"]`.
     */
    private suspend fun grantGeolocationPermission() {
        val contextId = cdpSend(CdpMethods.TARGET_GET_TARGET_INFO).result?.get("targetInfo")?.jsonObject
            ?.get("browserContextId")?.jsonPrimitive?.contentOrNull
        val params = buildMap<String, Any?> {
            put("permissions", listOf("geolocation"))
            if (contextId != null) put("browserContextId", contextId)
        }
        // Browser-domain commands are browser-wide; a page-level connection forwards them to the browser.
        val response = connection.send(CdpMethods.BROWSER_GRANT_PERMISSIONS, params)
        response.error?.let { throw KmpwrightException("Could not grant geolocation permission: ${it.message}") }
    }

    override suspend fun setNetworkConditions(offline: Boolean, latencyMs: Long, downloadBytesPerSecond: Long, uploadBytesPerSecond: Long): Boolean {
        val throttled = offline || latencyMs > 0 || downloadBytesPerSecond > 0 || uploadBytesPerSecond > 0
        emulatedNetwork = if (!throttled) null else mapOf(
            "offline" to offline,
            "latency" to if (offline) 0 else latencyMs,
            // CDP: -1 disables throttling for a direction.
            "downloadThroughput" to if (offline) 0 else downloadBytesPerSecond.takeIf { it > 0 } ?: -1,
            "uploadThroughput" to if (offline) 0 else uploadBytesPerSecond.takeIf { it > 0 } ?: -1
        )
        emulationTargets().forEach { applyNetworkConditions(it) }
        return true
    }

    override suspend fun canTraverseHistory(delta: Int): Boolean? {
        val response = runCatching { cdpSend(CdpMethods.PAGE_GET_NAVIGATION_HISTORY) }.getOrNull() ?: return null
        if (response.error != null) return null
        val result = response.result ?: return null
        val current = result["currentIndex"]?.jsonPrimitive?.longOrNull ?: return null
        val count = result["entries"]?.jsonArray?.size ?: return null
        return (current + delta) in 0 until count
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
        val response = cdpSend(CdpMethods.NETWORK_DELETE_COOKIES, mapOf("name" to name, "domain" to domain))
        response.error?.let { throw KmpwrightException("CDP error ${it.code}: ${it.message}") }
        _cookies = _cookies.removingAll { it.name == name && it.domain == domain }
    }

    override suspend fun deleteCookieAsync(cookie: Cookie) {
        val response = cdpSend(CdpMethods.NETWORK_DELETE_COOKIES,
            mapOf("name" to cookie.name, "domain" to cookie.domain, "path" to cookie.path))
        response.error?.let { throw KmpwrightException("CDP error ${it.code}: ${it.message}") }
        _cookies = _cookies.removingAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
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

    override fun addRouteHandler(urlPattern: String, handler: suspend (Route) -> Unit) {
        val regex = Regex(urlPattern) // Compile once; an invalid pattern fails at registration.
        // Re-registering a pattern moves it to the end, making it the newest route.
        routeHandlers.removeIf { it.pattern == urlPattern }
        routeHandlers.add(RouteEntry(urlPattern, regex, handler))
        ensureFetchPausedListener()
        scope.launch {
            refreshFetchPatterns()
        }
    }

    override fun removeRouteHandler(urlPattern: String) {
        routeHandlers.removeIf { it.pattern == urlPattern }
        scope.launch {
            refreshFetchPatterns()
        }
    }

    override fun removeAllRouteHandlers() {
        routeHandlers.clear()
        queueFetchRefresh()
    }

    override suspend fun syncRouteHandlers() {
        if (!disposed.get()) refreshFetchPatterns()
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
            // Guards and route handlers are user code: evaluate them off the
            // event pump so they can never stall protocol replies.
            scope.launch { handlePausedRequest(params, targetSession) }
        }
        if (targetSession == sessionId) fetchPausedSubscription = subscription
        else if (targetSession != null) childFetchSubscriptions[targetSession] = subscription
        }
    }

    private suspend fun handlePausedRequest(params: JsonObject, targetSession: String?) {
        val requestId = params["requestId"]?.jsonPrimitive?.content ?: return
        val requestObj = params["request"]?.jsonObject ?: return
        val url = requestObj["url"]?.jsonPrimitive?.content ?: return
        val method = requestObj["method"]?.jsonPrimitive?.content ?: "GET"
        val headers = requestObj["headers"]?.jsonObject?.mapValues { (_, value) -> value.jsonPrimitive.content } ?: emptyMap()
        val postData = requestObj["postData"]?.jsonPrimitive?.content
        // CDP reports resourceType beside request in Fetch.requestPaused;
        // retain the nested fallback for lightweight transport seams.
        val resourceType = params["resourceType"]?.jsonPrimitive?.content
            ?: requestObj["resourceType"]?.jsonPrimitive?.content
        if (resourceType == "Document" && !isMainFrameNavigationAllowed(url)) {
            fetchSend(targetSession, CdpMethods.FETCH_FAIL_REQUEST, buildFetchFailParams(requestId, "BlockedByResponse"))
            return
        }
        val filter = domainFilter
        val hostname = canonicalHttpHost(url)
        if (filter != null && (hostname == null || !filter.isAllowed(hostname))) {
            fetchSend(targetSession, CdpMethods.FETCH_FAIL_REQUEST, buildFetchFailParams(requestId, "BlockedByResponse"))
            return
        }
        // Playwright order: newest matching route first; a handler that falls back (or
        // returns without deciding) passes the request to the next older matching route.
        val matching = routeHandlers.filter { it.regex.matches(url) }.asReversed()
        if (matching.isEmpty()) {
            val continuationHeaders = credentialHeaders(url, headers)
            val continueParams = mutableMapOf<String, Any?>("requestId" to requestId)
            if (continuationHeaders != null) {
                continueParams["headers"] = continuationHeaders.map { (name, value) -> mapOf("name" to name, "value" to value) }
            }
            fetchSend(targetSession, CdpMethods.FETCH_CONTINUE_REQUEST, continueParams)
            return
        }

        // Fetch reports fetch() as "XHR"; the Network domain (and Playwright) distinguish "fetch". Prefer
        // the Network record of the same request, and its id, so route and event Requests agree.
        val networkId = params["networkId"]?.jsonPrimitive?.content
        val observed = networkId?.let { pendingRequests[logicalNetworkRequestKey(it, targetSession)] }
        val request = Request(url = url, method = method, headers = headers, postData = postData,
            resourceType = observed?.resourceType ?: resourceType?.lowercase() ?: "other", id = observed?.id ?: networkId ?: requestId)
        val action = try {
                withTimeoutOrNull(handlerTimeoutMs) {
                    resolveRoute(request, matching.map { it.handler })
                } ?: run {
                    kmpwrightLogWarning("Route handler for $url did not finish within ${handlerTimeoutMs}ms; aborting the request", null)
                    RouteAction.Abort("TimedOut")
                }
            } catch (cancelled: CancellationException) {
                if (disposed.get()) throw cancelled
                kmpwrightLogWarning("Route handler for $url was cancelled; aborting the request", cancelled)
                RouteAction.Abort("Aborted")
            } catch (error: Exception) {
                // A failing handler must not silently let the request through.
                kmpwrightLogWarning("Route handler for $url threw; aborting the request", error)
                RouteAction.Abort("Failed")
            }
        dispatchRouteAction(requestId, action, url, headers, targetSession)
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
            is RouteAction.Fulfill -> fetchSend(targetSession, CdpMethods.FETCH_FULFILL_REQUEST, buildFetchFulfillParams(requestId, action))
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
                fetchSend(targetSession, CdpMethods.FETCH_CONTINUE_REQUEST, buildFetchContinueParams(requestId, action.copy(headers = headers)))
            }
            is RouteAction.Fallback -> {
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

    // --- Navigation callback ---

    override fun setNavigationCallback(callback: ((NavigationResult) -> Unit)?) {
        navigationCallback = callback
    }

    @Volatile private var lifecycleListener: ((PageLifecycleEvent) -> Unit)? = null

    override fun setLifecycleListener(listener: ((PageLifecycleEvent) -> Unit)?) {
        lifecycleListener = listener
    }

    /** Page.frameNavigated / Page.domContentEventFired are reported for every main-frame document. */
    override val reportsDomContentLoaded: Boolean get() = true

    /** Ordered with navigation callbacks on the serial callback queue. */
    private fun emitLifecycle(event: PageLifecycleEvent) {
        deliver { lifecycleListener?.invoke(event) }
    }

    private suspend fun readTitle(): String? = runCatching {
        cdpSend(CdpMethods.RUNTIME_EVALUATE, mapOf("expression" to "document.title", "returnByValue" to true))
            .result?.get("result")?.jsonObject?.get("value")?.jsonPrimitive?.contentOrNull
    }.getOrNull()

    private fun refreshTitle() {
        if (disposed.get()) return
        scope.launch {
            val title = readTitle() ?: return@launch
            if (title != _title) {
                _title = title
                emitLifecycle(PageLifecycleEvent.TitleChanged(title))
            }
        }
    }

    private val titleBindingName = "__kmpwright_title_" + PageHelpers.key.takeLast(10)
    private val titleObserverStarted = AtomicBoolean(false)

    /**
     * Pushes `document.title` changes (no navigation needed) to [PageLifecycleEvent.TitleChanged]: a
     * `Runtime.addBinding` called by a document-start MutationObserver in the top frame. The binding only
     * triggers a re-read of `document.title`, so a page calling it cannot forge a title.
     */
    internal suspend fun startTitleObserver() {
        if (!titleObserverStarted.compareAndSet(false, true) || disposed.get()) return
        navigationSubscriptions += connection.onEvent("Runtime.bindingCalled", sessionId) { params ->
            if (params["name"]?.jsonPrimitive?.content == titleBindingName) refreshTitle()
        }
        val script = """
            (function(){
              if (window.top !== window) return;
              var send = window[${jsStringLiteral(titleBindingName)}];
              if (typeof send !== 'function') return;
              var last = null;
              function check() { var t = document.title; if (t !== last) { last = t; try { send(''); } catch (e) {} } }
              try { new MutationObserver(check).observe(document, {subtree: true, childList: true, characterData: true}); } catch (e) {}
              document.addEventListener('DOMContentLoaded', check);
              check();
            })();
        """.trimIndent()
        runCatching {
            if (cdpSend("Runtime.addBinding", mapOf("name" to titleBindingName)).error != null) return
            addInitScript(script)
            cdpSend(CdpMethods.RUNTIME_EVALUATE, mapOf("expression" to script, "returnByValue" to true))
        }.onFailure { if (it is CancellationException) throw it }
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
                params["url"]?.jsonPrimitive?.content?.let { url ->
                    _url = url
                    emitLifecycle(PageLifecycleEvent.SameDocument(url))
                    refreshTitle()
                }
            }
        }
        navigationSubscriptions += connection.onEvent("Page.domContentEventFired", sessionId) {
            val status = pendingNavigationStatus
            deliver {
                // loadHtml's base document is not the content the caller waits for.
                if (rejectedCommittedNavigation || contentReplaced || htmlNavigation != null) return@deliver
                val url = _url ?: pendingNavigationUrl ?: return@deliver
                lifecycleListener?.invoke(PageLifecycleEvent.DomContentLoaded(url, status))
            }
        }

        navigationSubscriptions += connection.onEvent("Page.javascriptDialogOpening", sessionId) { params ->
            val type = when (params["type"]?.jsonPrimitive?.content) {
                "prompt" -> DialogType.Prompt
                "confirm" -> DialogType.Confirm
                "beforeunload" -> DialogType.BeforeUnload
                else -> DialogType.Alert
            }
            scope.launch {
                var response: DialogResponse = DialogResponse.Dismiss
                try {
                    val listener = dialogListener
                    if (listener != null) {
                        // A dialog blocks the page; never let a stuck listener hold it open.
                        response = withTimeoutOrNull(handlerTimeoutMs) {
                            listener.onDialogAsync(type,
                                params["message"]?.jsonPrimitive?.content ?: "",
                                params["defaultPrompt"]?.jsonPrimitive?.content)
                        } ?: run {
                            kmpwrightLogWarning("Dialog listener did not answer within ${handlerTimeoutMs}ms; dismissing", null)
                            DialogResponse.Dismiss
                        }
                    }
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
            observeFrameOrigin(frame)
            if (frame["parentId"] != null) return@onEvent
            diagnosticsListeners.forEach { it.onMainFrameDocumentCommitted() }
            mainFrameId = frame["id"]?.jsonPrimitive?.content
            contentReplaced = false
            val version = navigationVersion.incrementAndGet()
            val url = frame["url"]?.jsonPrimitive?.content.orEmpty()
            // Chromium commits an error page (with unreachableUrl) for failed loads.
            val errorPage = frame["unreachableUrl"]?.jsonPrimitive?.contentOrNull != null
            val originAllowed = url.isNotBlank() &&
                (allowedOriginRules.isEmpty() || allowedOriginRules.any { originRuleMatches(it, url) })
            val guard = navigationGuard
            if (guard == null || !originAllowed) {
                commitNavigation(version, url, originAllowed, errorPage)
            } else {
                // The guard is user code: run it off the pump, ordered before
                // the load event that is queued behind it.
                deliver { commitNavigation(version, url, guard(url), errorPage) }
            }
        }
        navigationSubscriptions += connection.onEvent("Page.loadEventFired", sessionId) {
            val status = pendingNavigationStatus
            deliver {
                if (rejectedCommittedNavigation || contentReplaced) return@deliver
                val version = navigationVersion.get()
                val url = _url ?: pendingNavigationUrl ?: ""
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
                    deliverNavigation(committedNavigation(url, status))
                }
            }
        }
    }

    private fun commitNavigation(version: Long, url: String, allowed: Boolean, errorPage: Boolean) {
        if (!allowed) {
            rejectedCommittedNavigation = true
            _url = null
            scope.launch {
                cdpSend(CdpMethods.PAGE_STOP_LOADING)
                if (version != navigationVersion.get()) return@launch
                deliverNavigation(NavigationResult(url, 0, false, "Committed navigation origin is not allowed by KmpwrightConfig: $url"))
            }
        } else if (errorPage) {
            // The failure itself is reported by Page.navigate's errorText; the
            // error page's own load event must not be reported as success.
            rejectedCommittedNavigation = true
        } else {
            // A later allowed navigation must recover the controller
            // after an earlier browser-initiated navigation was
            // rejected. The following load event belongs to this
            // committed frame, not the rejected one.
            rejectedCommittedNavigation = false
            _url = url.ifBlank { _url }
            emitLifecycle(PageLifecycleEvent.Committed(url))
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

    /** Decoded body bytes seen per request (Network.dataReceived), bounded LRU. */
    private val responseBodySizes = object : LinkedHashMap<String, Long>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean = size > MAX_TRACKED_REQUESTS
    }

    /** A document request may start in its parent and finish in a new OOPIF renderer. */
    private fun logicalNetworkRequestKey(rawId: String, observedSession: String?): String {
        val scoped = if (observedSession == sessionId) rawId else "frame:$observedSession:$rawId"
        if (pendingRequests.containsKey(scoped)) return scoped
        if (pendingRequests.containsKey(rawId)) return rawId
        return synchronized(responseBodySources) {
            responseBodySources.entries.firstOrNull { it.value.second == rawId && pendingRequests.containsKey(it.key) }?.key
        } ?: scoped
    }

    private val responseBodySources = object : LinkedHashMap<String, Pair<String?, String>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<String?, String>>?): Boolean = size > MAX_TRACKED_REQUESTS
    }

    override suspend fun networkResponseBody(requestId: String, maxBytes: Int): NetworkResponseBody {
        // Network.getResponseBody has no range support and returns the whole
        // body in one protocol message. Refuse bodies that cannot fit in a
        // single message instead of failing the command after transferring it.
        val knownSize = synchronized(responseBodySizes) { responseBodySizes[requestId] }
        if (knownSize != null && knownSize > MAX_RESPONSE_BODY_BYTES) {
            throw KmpwrightException("Response body is too large to read ($knownSize bytes; limit $MAX_RESPONSE_BODY_BYTES)")
        }
        val parts = if (requestId.startsWith("frame:")) requestId.split(':', limit = 3) else emptyList()
        val source = synchronized(responseBodySources) { responseBodySources[requestId] }
            ?: if (parts.size == 3) parts[1] to parts[2] else sessionId to requestId
        val reply = if (source.first != null) connection.sendSession(requireNotNull(source.first), "Network.getResponseBody", mapOf("requestId" to source.second))
            else connection.send("Network.getResponseBody", mapOf("requestId" to source.second))
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

    internal fun ensureNetworkEventListeners(observedSession: String? = sessionId): Unit = synchronized(childNetworkLock) {
        if (networkSessionSubscriptions.containsKey(observedSession)) return@synchronized
        val owned = mutableListOf<CdpEventSubscription>()
        owned += connection.onEvent("Page.frameNavigated", observedSession) { params ->
            val frame = params["frame"]?.jsonObject ?: return@onEvent
            networkActivity.documentCommitted(frame["id"]?.jsonPrimitive?.content, frame["loaderId"]?.jsonPrimitive?.content)
        }
        owned += connection.onEvent("Page.frameDetached", observedSession) { params ->
            // A process swap keeps the frame alive; its request may finish on the new renderer.
            if (params["reason"]?.jsonPrimitive?.content != "swap") networkActivity.frameDetached(params["frameId"]?.jsonPrimitive?.content)
        }
        fun requestKey(params: JsonObject): String? = params["requestId"]?.jsonPrimitive?.content?.let {
            logicalNetworkRequestKey(it, observedSession)
        }
        owned += connection.onEvent("Network.requestWillBeSent", observedSession) { params ->
            val requestId = requestKey(params) ?: return@onEvent
            val requestObj = params["request"]?.jsonObject ?: return@onEvent
            params["redirectResponse"]?.jsonObject?.let { redirect ->
                pendingRequests.remove(requestId)?.let { previous ->
                    val response = Response(previous.url, redirect["status"]?.jsonPrimitive?.longOrNull?.toInt() ?: 0,
                        redirect["statusText"]?.jsonPrimitive?.content,
                        redirect["headers"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content }.orEmpty(), id = requestId)
                    deliver { requestListeners.forEach { it.onResponseReceived(response); it.onRequestFinished(previous) } }
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
            // Bound tracking: requests whose completion is never reported
            // (lost targets, long-lived streams) must not grow without limit.
            var firstObservation = true
            if (pendingRequests.size < MAX_TRACKED_REQUESTS || pendingRequests.containsKey(requestId)) {
                firstObservation = pendingRequests.put(requestId, request) == null
                networkActivity.started(request, params["frameId"]?.jsonPrimitive?.content, params["loaderId"]?.jsonPrimitive?.content)
                synchronized(responseBodySources) {
                    responseBodySources[requestId] = observedSession to params.getValue("requestId").jsonPrimitive.content
                }
            }
            if (firstObservation) deliver { requestListeners.forEach { it.onRequestStarted(request) } }
        }
        owned += connection.onEvent("Network.responseReceived", observedSession) { params ->
            val requestId = requestKey(params) ?: return@onEvent
            val responseObj = params["response"]?.jsonObject ?: return@onEvent
            synchronized(responseBodySources) {
                responseBodySources[requestId] = observedSession to params.getValue("requestId").jsonPrimitive.content
            }
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
            deliver { requestListeners.forEach { it.onResponseReceived(response) } }
        }
        owned += connection.onEvent("Network.dataReceived", observedSession) { params ->
            val requestId = requestKey(params) ?: return@onEvent
            val length = params["dataLength"]?.jsonPrimitive?.longOrNull ?: return@onEvent
            synchronized(responseBodySizes) { responseBodySizes[requestId] = (responseBodySizes[requestId] ?: 0L) + length }
        }
        owned += connection.onEvent("Network.loadingFinished", observedSession) { params ->
            val requestId = requestKey(params) ?: return@onEvent
            networkActivity.finished(requestId)
            val request = pendingRequests.remove(requestId) ?: return@onEvent
            deliver { requestListeners.forEach { it.onRequestFinished(request) } }
        }
        owned += connection.onEvent("Network.loadingFailed", observedSession) { params ->
            val requestId = requestKey(params) ?: return@onEvent
            networkActivity.finished(requestId)
            val error = params["errorText"]?.jsonPrimitive?.content ?: "failed"
            val request = pendingRequests.remove(requestId) ?: Request(url = "", id = requestId)
            deliver {
                requestListeners.forEach { listener ->
                    listener.onRequestFailed(request, error)
                    listener.onRequestFinished(request)
                }
            }
        }
        networkSessionSubscriptions[observedSession] = owned
    }

    // Not used in CDP mode — network events are handled via CDP events
    override fun getPendingRequestCount(): Int = networkActivity.count()
    override fun resetRequestCount() {
        pendingRequests.clear()
        networkActivity.clear()
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
            val bytes = try {
                generatePdfAsync(options)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                kmpwrightLogWarning("CDP PDF generation failed", error)
                null
            }
            callback(bytes)
        }
    }

    override suspend fun generatePdfAsync(options: PdfOptions): ByteArray {
        if (disposed.get()) throw KmpwrightException("Page is closed")
        validatePdfOptions(options)
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
            "marginRight" to pdfLengthInches(options.margin.right),
            // Large documents would not fit in one protocol message; stream them.
            "transferMode" to "ReturnAsStream"
        )
        val result = cdpSend(CdpMethods.PAGE_PRINT_TO_PDF, params)
        result.error?.let { throw KmpwrightException("PDF generation failed: ${it.message}") }
        val payload = result.result ?: throw KmpwrightException("PDF generation returned no result")
        payload["stream"]?.jsonPrimitive?.contentOrNull?.let { handle ->
            try {
                return readCdpTraceStream(pageTransport, handle, maxBytes = MAX_PDF_BYTES, timeoutMs = 60_000, label = "PDF")
            } finally {
                withContext(NonCancellable) { withTimeoutOrNull(1_000) { runCatching { cdpSend(CdpMethods.IO_CLOSE, mapOf("handle" to handle)) } } }
            }
        }
        // Endpoints that ignore transferMode still return inline data.
        val data = payload["data"]?.jsonPrimitive?.contentOrNull
            ?: throw KmpwrightException("PDF generation returned no data")
        return java.util.Base64.getDecoder().decode(data)
    }

    /** Routes stream reads (IO.read) through this page's session. */
    private val pageTransport = object : CdpSessionTransport {
        override suspend fun send(method: String, params: Map<String, Any?>): CdpResponse = cdpSend(method, params)
        override suspend fun sendSession(sessionId: String, method: String, params: Map<String, Any?>): CdpResponse =
            connection.sendSession(sessionId, method, params)
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
        networkActivity.clear()
        synchronized(responseBodySizes) { responseBodySizes.clear() }
        callbackQueue.close()
        htmlNavigation?.cancel()
        htmlNavigation = null
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
        diagnosticsListeners = persistentListOf()
        synchronized(childNetworkLock) { runtimeChildSubscriptions.values.flatten().also { runtimeChildSubscriptions.clear() } }.forEach { it.cancel() }
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

internal fun buildFetchFulfillParams(requestId: String, options: RouteAction.Fulfill): Map<String, Any?> {
    val headers = options.headers
    val bodyBytes = options.bytes
    return buildMap {
        put("requestId", requestId)
        put("responseCode", options.status)
        if (headers.isNotEmpty()) {
            put("responseHeaders", headers.map { (name, value) -> mapOf("name" to name, "value" to value) })
        }
        put("body", java.util.Base64.getEncoder().encodeToString(bodyBytes))
    }
}

internal fun buildFetchContinueParams(requestId: String, options: RouteAction.Continue): Map<String, Any?> =
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

private const val HTML_BASE_NAVIGATION_TIMEOUT_MS = 30_000L
private const val MAX_TRACKED_REQUESTS = 10_000
/** Base64 inflates by 4/3; keep getResponseBody replies well inside the connection's message budget. */
private const val MAX_RESPONSE_BODY_BYTES = (DEFAULT_MAX_MESSAGE_CHARS / 4) * 3 - 1024 * 1024L
private const val MAX_PDF_BYTES = 512 * 1024 * 1024

private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

/** Reads image dimensions from headers only (PNG IHDR fast path), never decoding pixels. */
internal fun decodeImageSize(bytes: ByteArray): Pair<Int, Int> {
    if (bytes.size >= 24 && bytes.copyOfRange(0, 8).contentEquals(PNG_SIGNATURE) &&
        bytes[12] == 'I'.code.toByte() && bytes[13] == 'H'.code.toByte() &&
        bytes[14] == 'D'.code.toByte() && bytes[15] == 'R'.code.toByte()) {
        fun int(offset: Int) = ((bytes[offset].toInt() and 0xFF) shl 24) or ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or (bytes[offset + 3].toInt() and 0xFF)
        return int(16) to int(20)
    }
    return runCatching {
        ImageIO.createImageInputStream(ByteArrayInputStream(bytes))?.use { input ->
            val reader = ImageIO.getImageReaders(input).asSequence().firstOrNull() ?: return@use null
            try {
                reader.input = input
                reader.getWidth(0) to reader.getHeight(0)
            } finally {
                reader.dispose()
            }
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
