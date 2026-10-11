package com.bernaferrari.kmpwright.core

import kotlinx.atomicfu.atomic
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

abstract class BasePage protected constructor(
    protected val controller: WebViewController,
    protected val config: KmpwrightConfig,
    private val tabRuntime: PageTabRuntime? = null,
    dispatcher: CoroutineDispatcher = Dispatchers.Main
) : Page {
    protected val pageScope = CoroutineScope(SupervisorJob() + dispatcher)

    /**
     * Closing a page is an ownership boundary.  Keep this state on the common
     * page implementation so all backends get the same idempotent teardown,
     * including pages whose controller's dispose implementation is best effort.
     */
    private val closed = atomic(false)
    private val inheritedSetup = PageSetupRegistry()

    protected fun rememberSetup(key: String, apply: suspend (Page) -> Unit) = inheritedSetup.remember(key, apply)
    private fun forgetSetup(key: String) = inheritedSetup.forget(key)
    internal suspend fun inheritSetupTo(page: Page) = inheritedSetup.applyTo(page)

    private val _url = MutableStateFlow<String?>(null)
    override val url: StateFlow<String?> = _url.asStateFlow()

    private val _title = MutableStateFlow<String?>(null)
    override val title: StateFlow<String?> = _title.asStateFlow()

    override val webMcp: WebMcp = GuardedWebMcp({ controller.webMcp }, config) {
        checkPolicy(ActionType.Evaluate, currentPageUrl())
    }

    override val touch: Touch
        get() = touchDevice

    override val mouse: Mouse
        get() = mouseDevice

    override val keyboard: Keyboard
        get() = keyboardDevice

    private val touchDevice by lazy(LazyThreadSafetyMode.NONE) { guardedTouch(createTouch()) }
    private val mouseDevice by lazy(LazyThreadSafetyMode.NONE) { guardedMouse(createMouse()) }
    private val keyboardDevice by lazy(LazyThreadSafetyMode.NONE) { guardedKeyboard(createKeyboard()) }

    protected abstract fun createTouch(): Touch
    protected abstract fun createMouse(): Mouse
    protected abstract fun createKeyboard(): Keyboard

    private fun guardedTouch(delegate: Touch): Touch = object : Touch {
        override suspend fun tap(x: Double, y: Double, options: TapOptions) { checkPolicy(ActionType.Click, currentPageUrl()); delegate.tap(x, y, options) }
        override suspend fun longTap(x: Double, y: Double, options: LongTapOptions) { checkPolicy(ActionType.Click, currentPageUrl()); delegate.longTap(x, y, options) }
        override suspend fun scroll(x: Double, y: Double, deltaX: Double, deltaY: Double, options: ScrollOptions) { checkPolicy(ActionType.Scroll, currentPageUrl()); delegate.scroll(x, y, deltaX, deltaY, options) }
        override suspend fun swipe(x1: Double, y1: Double, x2: Double, y2: Double, options: SwipeOptions) { checkPolicy(ActionType.Drag, currentPageUrl()); delegate.swipe(x1, y1, x2, y2, options) }
        override suspend fun pinch(x: Double, y: Double, scale: Double, options: PinchOptions) { checkPolicy(ActionType.Scroll, currentPageUrl()); delegate.pinch(x, y, scale, options) }
    }

    private fun guardedMouse(delegate: Mouse): Mouse = object : Mouse {
        override suspend fun move(x: Double, y: Double, options: MoveOptions) { checkPolicy(ActionType.Hover, currentPageUrl()); delegate.move(x, y, options) }
        override suspend fun click(x: Double, y: Double, options: MouseClickOptions) { checkPolicy(ActionType.Click, currentPageUrl()); delegate.click(x, y, options) }
        override suspend fun dblclick(x: Double, y: Double, options: MouseClickOptions) { checkPolicy(ActionType.Click, currentPageUrl()); delegate.dblclick(x, y, options) }
        override suspend fun tripleclick(x: Double, y: Double, options: MouseClickOptions) { checkPolicy(ActionType.Click, currentPageUrl()); delegate.tripleclick(x, y, options) }
        override suspend fun down(options: MouseDownOptions) { checkPolicy(ActionType.Click, currentPageUrl()); delegate.down(options) }
        override suspend fun up(options: MouseUpOptions) { checkPolicy(ActionType.Click, currentPageUrl()); delegate.up(options) }
        override suspend fun wheel(deltaX: Double, deltaY: Double, options: WheelOptions) { checkPolicy(ActionType.Scroll, currentPageUrl()); delegate.wheel(deltaX, deltaY, options) }
        override suspend fun drag(startX: Double, startY: Double, endX: Double, endY: Double, options: DragOptions) { checkPolicy(ActionType.Drag, currentPageUrl()); delegate.drag(startX, startY, endX, endY, options) }
    }

    private fun guardedKeyboard(delegate: Keyboard): Keyboard = object : Keyboard {
        override suspend fun insertText(text: String) { checkPolicy(ActionType.Type, currentPageUrl()); delegate.insertText(text) }
        override suspend fun type(text: String, options: TypeOptions) { checkPolicy(ActionType.Type, currentPageUrl()); delegate.type(text, options) }
        override suspend fun down(key: String) { checkPolicy(ActionType.Press, currentPageUrl()); delegate.down(key) }
        override suspend fun up(key: String) { checkPolicy(ActionType.Press, currentPageUrl()); delegate.up(key) }
        override suspend fun press(key: String, options: PressOptions) { checkPolicy(ActionType.Press, currentPageUrl()); delegate.press(key, options) }
        override suspend fun downAndUp(key: String, options: TypeOptions) { checkPolicy(ActionType.Press, currentPageUrl()); delegate.downAndUp(key, options) }
        override fun isModifierPressed(key: String): Boolean = delegate.isModifierPressed(key)
        override fun modifiers(): Set<String> = delegate.modifiers()
    }

    override fun capabilities(): PageCapabilities = PageCapabilities()

    override val events: PageEvents = object : PageEvents {
        override val console = MutableSharedFlow<ConsoleMessage>(extraBufferCapacity = 64)
        override val navigation = MutableSharedFlow<NavigationEvent>(extraBufferCapacity = 64)
        override val loadState = MutableSharedFlow<LoadState>(extraBufferCapacity = 4)
        override val dialog = MutableSharedFlow<DialogEvent>(extraBufferCapacity = 8)
        override val request = MutableSharedFlow<RequestEvent>(extraBufferCapacity = 64)
        override val response = MutableSharedFlow<ResponseEvent>(extraBufferCapacity = 64)
        override val download = MutableSharedFlow<DownloadEvent>(extraBufferCapacity = 16)
        override val fileChooser = MutableSharedFlow<FileChooserEvent>(extraBufferCapacity = 8)
    }

    private val routeHandlers = mutableMapOf<Regex, suspend (Route) -> RouteHandler>()
    private val requestFlow = MutableSharedFlow<Request>(extraBufferCapacity = 64)
    private val responseFlow = MutableSharedFlow<Response>(extraBufferCapacity = 64)
    private val navigationResultFlow = MutableSharedFlow<NavigationResult>(extraBufferCapacity = 8)
    private var networkIdleSince: Long = 0

    private fun <T> emitFromCallback(flow: MutableSharedFlow<T>, value: T) {
        if (!flow.tryEmit(value)) {
            pageScope.launch { flow.emit(value) }
        }
    }

    // Feature 2: Element Reference System
    internal var currentSnapshotRefMap: Map<String, RefEntry> = emptyMap()

    // Console/error message accumulation
    private var accumulatedConsoleMessages = emptyList<ConsoleMessage>()

    // Feature 3: Action Policy System
    private var actionPolicy: ActionPolicy? = null
    private var policyConfirmCallback: (suspend (ActionType, String?) -> Boolean)? = null
    private var approvedNavigationUrl: String? = null

    // Delegates for focused concerns
    private val storageDelegate by lazy { PageStorageDelegate { script -> evalString(script) } }
    private val emulationDelegate by lazy {
        PageEmulationDelegate({ script -> evalString(script) }, controller)
    }
    private val httpDelegate by lazy { PageHttpDelegate(controller) }
    private val scrollDelegate by lazy { PageScrollDelegate { script -> evalString(script) } }
    private val stateDelegate by lazy {
        PageStateDelegate(
            currentPageUrl = { currentPageUrl() },
            getAllCookies = { getAllCookies() },
            getAllLocalStorage = { getAllLocalStorage() },
            getAllSessionStorage = { getAllSessionStorage() },
            restoreState = { stateUrl, cookies, localStorage, sessionStorage ->
                restoreStateInternal(stateUrl, cookies, localStorage, sessionStorage)
            }
        )
    }

    private val networkHistory = PageNetworkHistory(controller::networkResponseBody, {
        if (!config.allowDomExtraction) throw KmpwrightException("Response body access is disabled by KmpwrightConfig.allowDomExtraction")
        checkPolicy(ActionType.Evaluate, currentPageUrl())
    })
    override val network: NetworkHistory get() = networkHistory

    private val harCapture = PageHarCapture(controller, pageScope)
    override suspend fun startHarCapture() {
        check(!closed.value) { "Page is closed" }
        if (!config.allowDomExtraction) throw KmpwrightException("HAR capture is disabled by KmpwrightConfig.allowDomExtraction")
        checkPolicy(ActionType.Evaluate, currentPageUrl())
        harCapture.start()
    }
    override suspend fun stopHarCapture(): String = harCapture.stop()

    private val requestListener = object : RequestListener {
        override fun onRequestStarted(request: Request) {
            networkHistory.started(request)
            harCapture.started(request)
            networkIdleSince = 0
            emitFromCallback(requestFlow, request)
            emitFromCallback(
                events.request,
                RequestEvent(
                    url = request.url,
                    method = request.method,
                    headers = request.headers,
                    timestamp = request.timestamp
                )
            )
        }

        override fun onResponseReceived(response: Response) {
            if (response.status == 0 && response.statusText == "idle") {
                networkIdleSince = currentTimeMillis()
                emitFromCallback(events.loadState, LoadState.NetworkIdle)
            } else {
                networkHistory.received(response)
                harCapture.received(response)
                emitFromCallback(responseFlow, response)
                emitFromCallback(
                    events.response,
                    ResponseEvent(
                        url = response.url,
                        status = response.status,
                        statusText = response.statusText,
                        headers = response.headers,
                        timestamp = response.timestamp
                    )
                )
            }
        }

        override fun onRequestFinished(request: Request) {
            networkHistory.finished(request)
            harCapture.finished(request)
            if (controller.getPendingRequestCount() == 0) {
                networkIdleSince = currentTimeMillis()
                emitFromCallback(events.loadState, LoadState.NetworkIdle)
            }
        }

        override fun onRequestFailed(request: Request, error: String) {
            networkHistory.failed(request, error)
            harCapture.failed(request, error)
            emitFromCallback(
                events.response,
                ResponseEvent(
                    url = request.url,
                    status = 0,
                    statusText = error,
                    headers = emptyMap(),
                    timestamp = currentTimeMillis()
                )
            )
        }
    }

    private val dialogListener = object : DialogListener {
        private var currentHandler: (suspend (DialogEvent) -> DialogResponse)? = null

        override fun onDialog(type: DialogType, message: String, defaultPrompt: String?): DialogResponse =
            kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withTimeoutOrNull(100) { onDialogAsync(type, message, defaultPrompt) }
                    ?: DialogResponse.Dismiss
            }

        override suspend fun onDialogAsync(type: DialogType, message: String, defaultPrompt: String?): DialogResponse {
            val event = DialogEvent(type, message, defaultPrompt)
            emitFromCallback(events.dialog, event)
            val handler = currentHandler ?: return DialogResponse.Dismiss
            return try {
                kotlinx.coroutines.withTimeoutOrNull(config.timeouts.default) { handler(event) }
                    ?: DialogResponse.Dismiss
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (_: Exception) {
                DialogResponse.Dismiss
            }
        }

        fun setHandler(handler: suspend (DialogEvent) -> DialogResponse) {
            currentHandler = handler
        }

        fun clearHandler() {
            currentHandler = null
        }
    }

    init {
        require(!config.productionMode || !config.debug.enabled) {
            "Debug mode cannot be enabled when KmpwrightConfig.productionMode is true"
        }
        controller.addRequestListener(requestListener)
        controller.addDialogListener(dialogListener)
        controller.setRequestIdleTimeout(500)
    }

    override suspend fun goto(url: String, options: GotoOptions): NavigationResult {
        checkPolicy(ActionType.Navigate, url)
        if (config.allowedOriginRules.isNotEmpty() && config.allowedOriginRules.none { originRuleMatches(it, url) }) {
            throw KmpwrightException("Navigation origin is not allowed by KmpwrightConfig: $url")
        }
        approvedNavigationUrl = url
        _url.value = url
        networkIdleSince = 0
        invalidateOnNavigation()

        val result = suspendCancellableCoroutine { continuation ->
            var timeoutJob: kotlinx.coroutines.Job? = null

            continuation.invokeOnCancellation {
                timeoutJob?.cancel()
                controller.setNavigationCallback(null)
                approvedNavigationUrl = null
            }

            controller.setNavigationCallback { result ->
                timeoutJob?.cancel()
                controller.setNavigationCallback(null)
                val committedOriginAllowed = result.url.isNotBlank() &&
                    (config.allowedOriginRules.isEmpty() || config.allowedOriginRules.any { originRuleMatches(it, result.url) }) &&
                    navigationGuardAllows(result.url)
                val validatedResult = if (committedOriginAllowed) result else result.copy(
                    ok = false,
                    error = "Committed navigation origin is not allowed by KmpwrightConfig: ${result.url}"
                )
                _url.value = validatedResult.url
                _title.value = controller.getTitle()
                approvedNavigationUrl = null
                if (continuation.isActive) {
                    navigationResultFlow.tryEmit(validatedResult)
                    events.navigation.tryEmit(NavigationEvent(validatedResult.url, validatedResult.status, NavigationType.NewDocument))
                    if (validatedResult.ok) {
                        events.loadState.tryEmit(LoadState.DOMContentLoaded)
                        events.loadState.tryEmit(LoadState.Load)
                    }
                    continuation.resume(validatedResult)
                }
            }

            controller.loadUrl(url)

            timeoutJob = pageScope.launch {
                delay(options.timeoutMs)
                if (continuation.isActive) {
                    controller.setNavigationCallback(null)
                    approvedNavigationUrl = null
                    continuation.resume(NavigationResult(url, 0, false, "Navigation timeout"))
                }
            }
        }
        // Platform navigation callbacks fire at the completed-load boundary. NetworkIdle
        // needs the additional request-quiescence wait; DOMContentLoaded/Load are already met.
        if (result.ok && options.waitUntil == LoadState.NetworkIdle) {
            waitForLoadState(LoadState.NetworkIdle, options.timeoutMs)
        }
        return result
    }

    private suspend fun evalString(script: String): String? =
        controller.normalizeJavaScriptResult(controller.evaluateJavaScriptAsync(script))

    override suspend fun reload(options: ReloadOptions) {
        checkPolicy(ActionType.Navigate, currentPageUrl())
        invalidateOnNavigation()
        controller.reload()
    }

    override suspend fun back() {
        checkPolicy(ActionType.Navigate, currentPageUrl())
        invalidateOnNavigation()
        controller.goBack()
    }

    override suspend fun forward() {
        checkPolicy(ActionType.Navigate, currentPageUrl())
        invalidateOnNavigation()
        controller.goForward()
    }

    override suspend fun setContent(html: String, options: SetContentOptions) {
        checkPolicy(ActionType.SetContent, currentPageUrl())
        _url.value = "about:blank"
        networkIdleSince = 0
        invalidateOnNavigation()
        controller.loadHtmlAsync(html, null, options.timeoutMs)
        waitForLoadState(options.waitUntil, options.timeoutMs)
    }

    internal fun invalidateOnNavigation() {
        BaseLocator.invalidateHelpers(controller)
        currentSnapshotRefMap = emptyMap()
        accumulatedConsoleMessages = emptyList()
    }

    final override fun locator(selector: String): Locator {
        val loc = createPlatformLocator(selector)
        val policy = actionPolicy
        if (policy != null) {
            loc.policyChecker = { actionType ->
                checkPolicy(actionType, currentPageUrl())
            }
        }
        return loc
    }

    protected abstract fun createPlatformLocator(selector: String): BaseLocator

    override suspend fun evaluate(expression: String): JsResult {
        checkPolicy(ActionType.Evaluate, currentPageUrl())
        if (!config.allowEval) throw KmpwrightException("JavaScript evaluation is disabled by KmpwrightConfig.allowEval")
        val result = controller.evaluateJavaScriptAsync(expression)
        return JsResult(controller.normalizeJavaScriptResult(result) ?: "null", "text")
    }


    override suspend fun content(): String {
        if (!config.allowDomExtraction) throw KmpwrightException("DOM extraction is disabled by KmpwrightConfig.allowDomExtraction")
        return evalString("document.documentElement.outerHTML") ?: ""
    }

    override suspend fun screenshot(options: ScreenshotOptions): ScreenshotResult {
        checkPolicy(ActionType.Screenshot, currentPageUrl())
        val screenshotController = createScreenshotController()

        return try {
            if (options.fullPage) {
                screenshotController.captureFullPage(options.scale)
            } else if (options.clip != null) {
                screenshotController.captureClip(
                    x = options.clip.x,
                    y = options.clip.y,
                    width = options.clip.width,
                    height = options.clip.height,
                    scale = options.scale
                )
            } else {
                screenshotController.captureViewport(options.scale)
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            throw KmpwrightException("Screenshot failed: ${e.message}")
        }
    }

    protected open fun createScreenshotController(): ScreenshotController {
        throw IllegalStateException("createScreenshotController must be implemented by platform")
    }

    override suspend fun waitForLoadState(state: LoadState, timeoutMs: Long) {
        val startTime = currentTimeMillis()

        when (state) {
            LoadState.DOMContentLoaded -> {
                val ready = evalString("document.readyState")
                if (ready == "interactive" || ready == "complete") return
                if (waitForReadyStateOrLoadEvent(
                        startTime,
                        timeoutMs,
                        accepts = { it == LoadState.DOMContentLoaded || it == LoadState.Load },
                        isReady = { it == "interactive" || it == "complete" }
                    )) return
                throw KmpwrightException("Timeout waiting for DOMContentLoaded")
            }
            LoadState.Load -> {
                val ready = evalString("document.readyState")
                if (ready == "complete") return
                if (waitForReadyStateOrLoadEvent(
                        startTime,
                        timeoutMs,
                        accepts = { it == LoadState.Load },
                        isReady = { it == "complete" }
                    )) return
                throw KmpwrightException("Timeout waiting for load")
            }
            LoadState.NetworkIdle -> {
                if (controller.getPendingRequestCount() == 0) {
                    networkIdleSince = currentTimeMillis()
                } else {
                    val remaining = (timeoutMs - (currentTimeMillis() - startTime)).coerceAtLeast(0)
                    if (withTimeoutOrNull(remaining) {
                            events.loadState.first { it == LoadState.NetworkIdle }
                        } == null) {
                        throw KmpwrightException("Timeout waiting for network idle")
                    }
                }
                val idleFor = currentTimeMillis() - networkIdleSince
                if (idleFor < 500) {
                    val remaining = (timeoutMs - (currentTimeMillis() - startTime)).coerceAtLeast(0)
                    if (remaining < 500 - idleFor) {
                        throw KmpwrightException("Timeout waiting for network idle")
                    }
                    delay(500 - idleFor)
                }
                if (controller.getPendingRequestCount() == 0) {
                    return
                }
                throw KmpwrightException("Timeout waiting for network idle")
            }
        }
    }

    /**
     * Waits in event-sized windows and probes readyState only between windows.
     * This keeps normal navigation bridge-free while preserving a fallback for
     * controllers that do not surface load-state events.
     */
    private suspend fun waitForReadyStateOrLoadEvent(
        startTime: Long,
        timeoutMs: Long,
        accepts: (LoadState) -> Boolean,
        isReady: (String?) -> Boolean
    ): Boolean {
        while (currentTimeMillis() - startTime < timeoutMs) {
            val remaining = (timeoutMs - (currentTimeMillis() - startTime)).coerceAtLeast(0)
            if (withTimeoutOrNull(minOf(500L, remaining)) {
                    events.loadState.first(accepts)
                } != null) return true
            if (isReady(evalString("document.readyState"))) return true
        }
        return false
    }

    override suspend fun waitForTimeout(ms: Long) = delay(ms)

    override suspend fun waitForNavigation(options: WaitForNavigationOptions): NavigationResult? {
        val result = kotlinx.coroutines.withTimeoutOrNull(options.timeoutMs) {
            navigationResultFlow.first { navigation ->
                options.urlPattern == null || waitUrlPatternMatches(options.urlPattern, navigation.url)
            }
        } ?: return null
        options.waitUntil?.let { waitForLoadState(it, options.timeoutMs) }
        return result
    }

    override suspend fun waitForEvent(event: String, options: WaitForEventOptions): Any? {
        return kotlinx.coroutines.withTimeoutOrNull(options.timeoutMs) {
            when (event.lowercase()) {
                "console" -> events.console.first { options.predicate == null || options.predicate(it) }
                "navigation" -> events.navigation.first { options.predicate == null || options.predicate(it) }
                "domcontentloaded" -> events.loadState.first { it == LoadState.DOMContentLoaded }
                "load" -> events.loadState.first { it == LoadState.Load }
                "networkidle" -> events.loadState.first { it == LoadState.NetworkIdle }
                else -> throw KmpwrightException("Unsupported page event: $event")
            }
        }
    }

    override suspend fun waitForRequest(urlPattern: String, timeoutMs: Long): RequestEvent {
        return try {
            kotlinx.coroutines.withTimeout(timeoutMs) {
                val result = requestFlow.first { request ->
                    waitUrlPatternMatches(urlPattern, request.url)
                }
                RequestEvent(
                    url = result.url,
                    method = result.method,
                    headers = result.headers,
                    timestamp = result.timestamp
                )
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw KmpwrightException("Timeout waiting for request matching $urlPattern")
        }
    }

    override suspend fun waitForResponse(urlPattern: String, timeoutMs: Long): ResponseEvent {
        return try {
            kotlinx.coroutines.withTimeout(timeoutMs) {
                val result = responseFlow.first { response ->
                    waitUrlPatternMatches(urlPattern, response.url)
                }
                ResponseEvent(
                    url = result.url,
                    status = result.status,
                    statusText = result.statusText,
                    headers = result.headers,
                    timestamp = result.timestamp
                )
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw KmpwrightException("Timeout waiting for response matching $urlPattern")
        }
    }

    override suspend fun waitForRequestUrl(url: String, timeoutMs: Long): Request {
        return try {
            kotlinx.coroutines.withTimeout(timeoutMs) {
                requestFlow.first { it.url == url }
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw KmpwrightException("Timeout waiting for request to $url")
        }
    }

    override suspend fun waitForResponseUrl(url: String, timeoutMs: Long): Response {
        return try {
            kotlinx.coroutines.withTimeout(timeoutMs) {
                responseFlow.first { it.url == url }
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw KmpwrightException("Timeout waiting for response from $url")
        }
    }

    override fun route(urlPattern: String, handler: suspend (Route) -> RouteHandler) {
        val regex = routeUrlPatternToRegex(urlPattern)
        routeHandlers[regex] = handler
        rememberSetup("route:$urlPattern") { it.route(urlPattern, handler) }
        syncRoutesToController()
    }

    override fun unroute(urlPattern: String?) {
        if (urlPattern != null) {
            val regex = routeUrlPatternToRegex(urlPattern)
            routeHandlers.remove(regex)
            forgetSetup("route:$urlPattern")
        } else {
            routeHandlers.clear()
            inheritedSetup.forgetPrefix("route:")
        }
        syncRoutesToController()
    }

    private fun syncRoutesToController() {
        controller.removeAllRouteHandlers()
        routeHandlers.forEach { (pattern, handler) ->
            controller.addRouteHandler(pattern.pattern) { route ->
                handler(route)
            }
        }
    }

    private var readAllowedDomains: Set<String> = emptySet()
    private val httpReader by lazy { HttpReader(config) }
    override suspend fun read(url: String?, options: ReadOptions): ReadResult {
        if (!config.allowDomExtraction) throw KmpwrightException("DOM extraction is disabled")
        if (url == null) return httpReader.activeHtml(currentPageUrl().orEmpty(), content(), options)
        val domains = readAllowedDomains
        return httpReader.readChecked(url, options) { target ->
            checkPolicy(ActionType.Navigate, target)
            if (!DomainFilter(domains).isAllowed(canonicalHttpHost(target).orEmpty())) throw KmpwrightException("HTTP read domain is not allowed")
        }
    }

    private val reactDelegate = lazy {
        PageReactInspector(this, controller) { extraction ->
            if (!config.allowEval) throw KmpwrightException("JavaScript evaluation is disabled")
            if (extraction && !config.allowDomExtraction) throw KmpwrightException("DOM extraction is disabled")
            checkPolicy(ActionType.Evaluate, currentPageUrl())
        }
    }
    override val react: ReactInspector get() = reactDelegate.value

    private val vitalsDelegate by lazy { PageVitalsDelegate(controller) }

    private val scriptTask by lazy { PageScriptTask(controller) }

    override suspend fun pushState(url: String, timeoutMs: Long): NavigationResult {
        require(timeoutMs > 0) { "Navigation timeout must be positive" }
        if (!config.allowEval) throw KmpwrightException("JavaScript evaluation is disabled")
        val target = evalString("new URL(${jsStringLiteral(url)}, location.href).href")
            ?: throw KmpwrightException("Could not resolve navigation URL")
        checkPolicy(ActionType.Navigate, target)
        if (config.allowedOriginRules.isNotEmpty() && config.allowedOriginRules.none { originRuleMatches(it, target) }) {
            throw KmpwrightException("Navigation origin is not allowed by KmpwrightConfig: $target")
        }
        try { kotlinx.coroutines.withTimeout(timeoutMs) {
            scriptTask.evaluate("""
                (async function(){
                  var absolute = ${jsStringLiteral(target)};
                  if (absolute === location.href) return location.href;
                  var router = window.next && window.next.router;
                  if (router && typeof router.push === 'function') {
                    var result = await router.push(${jsStringLiteral(url)});
                    if (result === false) throw new Error('Router canceled navigation');
                  } else {
                    history.pushState(null, '', absolute);
                    dispatchEvent(new PopStateEvent('popstate', {state:null}));
                    dispatchEvent(new Event('navigate'));
                  }
                  return location.href;
                })()
            """.trimIndent(), timeoutMs)
            if (!awaitPageCondition(controller, "location.href === ${jsStringLiteral(target)}", timeoutMs)) {
                throw KmpwrightException("Timeout waiting for router navigation: $target")
            }
        }
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            throw KmpwrightException("Timeout waiting for router navigation: $target")
        }
        _url.value = target
        emitFromCallback(events.navigation, NavigationEvent(target, 200, NavigationType.SameDocument))
        return NavigationResult(target, 200, true)
    }

    override suspend fun webVitals(options: WebVitalsOptions): WebVitalsReport {
        if (!config.allowEval) throw KmpwrightException("JavaScript evaluation is disabled")
        checkPolicy(ActionType.Evaluate, currentPageUrl())
        return vitalsDelegate.capture(this, options)
    }

    override suspend fun metrics(): PageMetrics {
        return PageMetrics(
            documents = 1,
            frames = 1,
            jsHeapUsedSize = 0,
            jsHeapTotalSize = 0,
            resources = requestFlow.subscriptionCount.value,
            networkIdle = controller.getPendingRequestCount() == 0
        )
    }

    override fun serviceWorkers(): List<ServiceWorker> = emptyList()
    override fun expect(locator: Locator): KmpwrightExpect = KmpwrightExpect(locator, config)

    private inner class PageRoute(
        override val request: Request,
        private val controller: WebViewController
    ) : Route {
        override val requestId: String = request.id

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

    override fun onDialog(handler: suspend (DialogEvent) -> DialogResponse): DialogHandler {
        dialogListener.setHandler(handler)
        return object : DialogHandler {
            override fun close() {
                dialogListener.clearHandler()
            }
        }
    }

    override suspend fun pdf(options: PdfOptions): PdfResult {
        return suspendCancellableCoroutine { cont ->
            controller.generatePdf(options) { bytes ->
                cont.resume(PdfResult(bytes ?: ByteArray(0)))
            }
        }
    }

    override suspend fun addInitScript(script: String): String {
        if (!config.allowEval) throw KmpwrightException("JavaScript evaluation is disabled")
        checkPolicy(ActionType.Evaluate, currentPageUrl())
        val id = controller.addInitScript(script)
        rememberSetup("init:$id") { it.addInitScript(script) }
        return id
    }

    override suspend fun removeInitScript(id: String) {
        checkPolicy(ActionType.Evaluate, currentPageUrl())
        controller.removeInitScript(id)
        forgetSetup("init:$id")
    }

    override suspend fun addScriptTag(options: ScriptTagOptions): String {
        checkPolicy(ActionType.AddScriptTag, currentPageUrl())
        val scriptId = options.id.ifEmpty { "script-${currentTimeMillis()}" }
        val url = options.url?.let { escapeJsString(it) }
        val content = options.content?.let { escapeJsString(it) }
        val type = escapeJsString(options.type)
        val asyncAttr = options.async
        val deferAttr = options.defer

        return suspendCancellableCoroutine { cont ->
            val script = """
                (function() {
                    try {
                        var node = document.createElement('script');
                        node.type = '$type';
                        node.id = '$scriptId';
                        node.async = ${asyncAttr.toString()};
                        node.defer = ${deferAttr.toString()};
                        if (${url != null}) { node.src = '${url ?: ""}'; }
                        if (${content != null}) { node.text = '${content ?: ""}'; }
                        document.head.appendChild(node);
                        return '$scriptId';
                    } catch (e) {
                        return 'error: ' + e.message;
                    }
                })()
            """.trimIndent()

            controller.evaluateJavaScript(script) { result ->
                val normalized = controller.normalizeJavaScriptResult(result) ?: scriptId
                if (normalized.startsWith("error:")) {
                    cont.resumeWith(Result.failure(KmpwrightException(normalized.substring(6))))
                } else {
                    cont.resume(normalized)
                }
            }
        }
    }

    override suspend fun addStyleTag(options: StyleTagOptions): String {
        checkPolicy(ActionType.AddStyleTag, currentPageUrl())
        val styleId = options.id.ifEmpty { "style-${currentTimeMillis()}" }
        val url = options.url?.let { escapeJsString(it) }
        val content = options.content?.let { escapeJsString(it) }

        return suspendCancellableCoroutine { cont ->
            val style = """
                (function() {
                    try {
                        if (${url != null}) {
                            var link = document.createElement('link');
                            link.rel = 'stylesheet';
                            link.href = '${url ?: ""}';
                            link.id = '$styleId';
                            document.head.appendChild(link);
                            return '$styleId';
                        }
                        if (${content != null}) {
                            var style = document.createElement('style');
                            style.id = '$styleId';
                            style.textContent = '${content ?: ""}';
                            document.head.appendChild(style);
                            return '$styleId';
                        }
                        return 'error: Either url or content must be provided for addStyleTag';
                    } catch (e) {
                        return 'error: ' + e.message;
                    }
                })()
            """.trimIndent()

            controller.evaluateJavaScript(style) { result ->
                val normalized = controller.normalizeJavaScriptResult(result) ?: styleId
                if (normalized.startsWith("error:")) {
                    cont.resumeWith(Result.failure(KmpwrightException(normalized.substring(6))))
                } else {
                    cont.resume(normalized)
                }
            }
        }
    }

    override suspend fun setCookie(cookie: Cookie) {
        checkPolicy(ActionType.SetCookie, currentPageUrl())
        controller.setCookieAsync(cookie)
    }

    override suspend fun getCookie(name: String): Cookie? {
        return suspendCancellableCoroutine { cont ->
            controller.getCookie(name, _url.value ?: "") { cookie ->
                cont.resume(cookie)
            }
        }
    }

    override suspend fun getAllCookies(): List<Cookie> {
        return suspendCancellableCoroutine { cont ->
            controller.getAllCookies { cookies ->
                cont.resume(cookies)
            }
        }
    }

    override suspend fun deleteCookie(name: String, domain: String) {
        checkPolicy(ActionType.DeleteCookie, currentPageUrl())
        controller.deleteCookieAsync(name, domain)
    }

    override suspend fun deleteAllCookies() {
        checkPolicy(ActionType.DeleteCookie, currentPageUrl())
        controller.deleteAllCookiesAsync()
    }

    // Scrolling
    override suspend fun scroll(x: Double, y: Double) {
        checkPolicy(ActionType.Scroll, currentPageUrl())
        scrollDelegate.scroll(x, y)
    }
    override suspend fun scrollDown(distance: Double) {
        checkPolicy(ActionType.Scroll, currentPageUrl())
        scrollDelegate.scrollDown(distance)
    }
    override suspend fun scrollUp(distance: Double) {
        checkPolicy(ActionType.Scroll, currentPageUrl())
        scrollDelegate.scrollUp(distance)
    }
    override suspend fun scrollToLeft(distance: Double) {
        checkPolicy(ActionType.Scroll, currentPageUrl())
        scrollDelegate.scrollToLeft(distance)
    }
    override suspend fun scrollToTop() {
        checkPolicy(ActionType.Scroll, currentPageUrl())
        scrollDelegate.scrollToTop()
    }
    override suspend fun scrollToBottom() {
        checkPolicy(ActionType.Scroll, currentPageUrl())
        scrollDelegate.scrollToBottom()
    }

    // Feature 1: Accessibility Tree Snapshot
    private var snapshotHistory = mapOf<String, String>()

    private val auditDelegate by lazy { PageAccessibilityAudit(controller) }

    override suspend fun accessibilityAudit(options: AccessibilityAuditOptions): AccessibilityAuditReport {
        if (!config.allowDomExtraction) throw KmpwrightException("DOM extraction is disabled")
        if (!config.allowEval) throw KmpwrightException("JavaScript evaluation is disabled")
        checkPolicy(ActionType.Evaluate, currentPageUrl())
        return auditDelegate.run(options)
    }

    override suspend fun accessibilitySnapshot(options: A11ySnapshotOptions): A11ySnapshot {
        if (!config.allowDomExtraction) throw KmpwrightException("DOM extraction is disabled by KmpwrightConfig.allowDomExtraction")
        controller.prepareAccessibilitySnapshot()
        val script = AccessibilitySnapshotEngine.snapshotJs(options)
        val raw = suspendCancellableCoroutine<String?> { cont ->
            controller.evaluateJavaScript(script) { result ->
                cont.resume(controller.normalizeJavaScriptResult(result))
            }
        }
        if (raw != null) {
            val error = Json.parseToJsonElement(raw).jsonObject["error"]?.jsonPrimitive?.content
            if (error != null) throw KmpwrightException(error)
        }
        val snapshot = AccessibilitySnapshotEngine.parseSnapshotJson(raw ?: "null")
        currentSnapshotRefMap = snapshot.refMap
        // Auto-save as baseline for diffing
        snapshotHistory = snapshotHistory + ("__baseline__" to snapshot.textTree)
        return snapshot
    }

    // Feature 2: Element Reference System
    override suspend fun locatorByRef(ref: String): Locator {
        val refId = parseRef(ref) ?: throw KmpwrightException("Invalid element reference: $ref")
        var entry = currentSnapshotRefMap[refId]
        if (entry == null) {
            // Re-snapshot regardless — map might be stale after DOM mutations
            accessibilitySnapshot()
            entry = currentSnapshotRefMap[refId]
        }
        entry
            ?: throw KmpwrightException("Element reference not found in snapshot: $refId. Run accessibilitySnapshot() first.")
        // Resolve the captured DOM identity rather than a CSS path that may now
        // point at a replacement element after a mutation.
        return locator("ref=$refId")
    }

    override suspend fun snapshotAndInteract(ref: String, action: suspend (Locator) -> Unit) {
        action(locatorByRef(ref))
    }

    internal fun parseRef(input: String): String? = com.bernaferrari.kmpwright.core.parseRef(input)

    // Feature 3: Action Policy System
    override fun setActionPolicy(policy: ActionPolicy) {
        actionPolicy = policy
        // Keep the backend's synchronous navigation boundary in step with
        // policy changes so redirects and browser-initiated navigations are
        // checked before they can become the committed page.
        controller.setNavigationGuard { target -> navigationGuardAllows(target) }
    }

    override fun setPolicyConfirmCallback(callback: (suspend (ActionType, String?) -> Boolean)?) {
        policyConfirmCallback = callback
    }

    internal suspend fun checkPolicy(action: ActionType, url: String? = null) {
        val policy = actionPolicy ?: return
        val result = policy.check(action, url)
        when (result) {
            PolicyResult.Allow -> { /* ok */ }
            PolicyResult.Deny -> throw KmpwrightException("Action denied by policy: $action")
            PolicyResult.Confirm -> {
                val callback = policyConfirmCallback
                    ?: throw KmpwrightException("Action $action requires confirmation but no confirm callback is set")
                if (!callback(action, url)) {
                    throw KmpwrightException("Action denied by user confirmation: $action")
                }
            }
        }
    }

    override suspend fun setLocalStorage(key: String, value: String) = storageDelegate.setLocalStorage(key, value)
    override suspend fun getLocalStorage(key: String): String? = storageDelegate.getLocalStorage(key)
    override suspend fun removeLocalStorage(key: String) = storageDelegate.removeLocalStorage(key)
    override suspend fun getAllLocalStorage(): Map<String, String> = storageDelegate.getAllLocalStorage()
    override suspend fun clearLocalStorage() = storageDelegate.clearLocalStorage()

    override suspend fun setSessionStorage(key: String, value: String) = storageDelegate.setSessionStorage(key, value)
    override suspend fun getSessionStorage(key: String): String? = storageDelegate.getSessionStorage(key)
    override suspend fun removeSessionStorage(key: String) = storageDelegate.removeSessionStorage(key)
    override suspend fun getAllSessionStorage(): Map<String, String> = storageDelegate.getAllSessionStorage()
    override suspend fun clearSessionStorage() = storageDelegate.clearSessionStorage()

    override suspend fun emulateColorScheme(scheme: ColorScheme) {
        emulationDelegate.emulateColorScheme(scheme)
        rememberSetup("color") { it.emulateColorScheme(scheme) }
    }
    override suspend fun resetColorScheme() {
        emulationDelegate.resetColorScheme()
        rememberSetup("color") { it.resetColorScheme() }
    }

    override suspend fun emulateGeolocation(latitude: Double, longitude: Double) {
        emulationDelegate.emulateGeolocation(latitude, longitude)
        rememberSetup("geo") { it.emulateGeolocation(latitude, longitude) }
    }
    override suspend fun resetGeolocation() {
        emulationDelegate.resetGeolocation()
        rememberSetup("geo") { it.resetGeolocation() }
    }

    override suspend fun emulateOffline(offline: Boolean) {
        emulationDelegate.emulateOffline(offline)
        rememberSetup("network") { it.emulateOffline(offline) }
    }
    override suspend fun emulateNetwork(offline: Boolean, latency: Long, downloadThroughput: Long, uploadThroughput: Long) {
        emulationDelegate.emulateNetwork(offline, latency, downloadThroughput, uploadThroughput)
        rememberSetup("network") { it.emulateNetwork(offline, latency, downloadThroughput, uploadThroughput) }
    }

    override suspend fun setUserAgent(userAgent: String) {
        emulationDelegate.setUserAgent(userAgent)
        rememberSetup("userAgent") { it.setUserAgent(userAgent) }
    }
    override suspend fun emulateDevice(device: DeviceDescriptor) {
        emulationDelegate.emulateDevice(device)
        rememberSetup("device") { it.emulateDevice(device) }
    }
    override suspend fun setViewportSize(width: Int, height: Int) {
        emulationDelegate.setViewportSize(width, height)
        rememberSetup("viewport") { it.setViewportSize(width, height) }
    }
    override fun viewportSize(): Pair<Int, Int> = emulationDelegate.viewportSize()
    override suspend fun setDeviceScaleFactor(scale: Double) {
        emulationDelegate.setDeviceScaleFactor(scale)
        rememberSetup("scale") { it.setDeviceScaleFactor(scale) }
    }

    override suspend fun setExtraHTTPHeaders(headers: Map<String, String>) {
        val copied = headers.toMap()
        httpDelegate.setExtraHTTPHeaders(copied)
        rememberSetup("headers") { it.setExtraHTTPHeaders(copied) }
    }
    override suspend fun setHTTPCredentials(username: String, password: String) {
        httpDelegate.setHTTPCredentials(username, password)
        rememberSetup("credentials") { it.setHTTPCredentials(username, password) }
    }
    override suspend fun clearHTTPCredentials() {
        httpDelegate.clearHTTPCredentials()
        rememberSetup("credentials") { it.clearHTTPCredentials() }
    }
    override suspend fun waitForText(text: String, timeoutMs: Long): Boolean {
        val escapedText = escapeJsString(text)
        val conditionJs = "(function() { return document.body && document.body.innerText.indexOf('$escapedText') !== -1; })()"
        return awaitPageCondition(controller, conditionJs, timeoutMs)
    }

    override suspend fun waitForFunction(expression: String, timeoutMs: Long): JsResult {
        checkPolicy(ActionType.Evaluate, currentPageUrl())
        if (!config.allowEval) throw KmpwrightException("JavaScript evaluation is disabled by KmpwrightConfig.allowEval")
        val conditionJs = "(function() { return !!($expression); })()"
        if (!awaitPageCondition(controller, conditionJs, timeoutMs)) {
            throw KmpwrightException("Timeout waiting for JavaScript condition")
        }
        return JsResult(evalString("(function() { return ($expression); })()") ?: "null", "function")
    }

    // Annotated screenshot
    override suspend fun annotatedScreenshot(options: AnnotatedScreenshotOptions): AnnotatedScreenshotResult {
        val snapshot = accessibilitySnapshot()
        val labels = mutableListOf<AnnotatedLabel>()

        // Build JS to inject numbered labels and collect bounding boxes in one pass
        val injectJs = buildString {
            append("(function() {\n")
            append("var labels = [];\n")
            for ((index, entry) in snapshot.refMap.entries.withIndex()) {
                val selector = entry.value.cssSelector ?: continue
                val refNum = entry.key.removePrefix("@")
                append("""
                    var el$index = (window.__kmpwright_snapshotNodes || {})[${jsStringLiteral(entry.key)}] || document.querySelector(${jsStringLiteral(selector)});
                    if (el$index) {
                        var r$index = el$index.getBoundingClientRect();
                        var lbl$index = document.createElement('div');
                        lbl$index.textContent = '$refNum';
                        lbl$index.setAttribute('data-ww-anno', 'true');
                        lbl$index.style.cssText = 'position:absolute;top:' + (r$index.top + window.scrollY) + 'px;left:' + (r$index.left + window.scrollX) + 'px;background:rgba(255,0,0,0.8);color:white;padding:2px 4px;font-size:12px;font-weight:bold;z-index:999999;pointer-events:none;border-radius:2px;';
                        document.body.appendChild(lbl$index);
                        labels.push({i:$index,x:r$index.left,y:r$index.top,w:r$index.width,h:r$index.height});
                    }
                """.trimIndent())
                append("\n")
            }
            append("return JSON.stringify(labels);\n")
            append("})()")
        }

        val bboxJson = evalString(injectJs) ?: "[]"
        val bboxes = try {
            kotlinx.serialization.json.Json.decodeFromString<List<Map<String, Double>>>(bboxJson)
        } catch (_: Exception) { emptyList() }

        val screenshotResult = try {
            screenshot(ScreenshotOptions(
                fullPage = options.fullPage,
                format = options.format,
                quality = options.quality,
                scale = options.scale
            ))
        } finally {
            evalString("(function() { document.querySelectorAll('[data-ww-anno]').forEach(function(el) { el.remove(); }); })()")
        }

        for (bbox in bboxes) {
            val idx = bbox["i"]?.toInt() ?: continue
            val entry = snapshot.refMap.values.elementAtOrNull(idx) ?: continue
            labels.add(AnnotatedLabel(
                index = idx,
                text = entry.role + ": " + entry.name,
                rectangle = Rectangle(bbox["x"] ?: 0.0, bbox["y"] ?: 0.0, bbox["w"] ?: 0.0, bbox["h"] ?: 0.0)
            ))
        }
        labels.sortBy { it.index }

        return AnnotatedScreenshotResult(screenshotResult, labels)
    }

    // Frame locator
    override fun frameLocator(selector: String): FrameLocator {
        return BaseFrameLocator(controller, selector, config).also {
            it.policyChecker = { action -> checkPolicy(action, currentPageUrl()) }
        }
    }

    override fun mainFrame(): FrameLocator {
        return BaseFrameLocator(controller, "", config).also {
            it.policyChecker = { action -> checkPolicy(action, currentPageUrl()) }
        }
    }

    // Snapshot diff
    override suspend fun diffSnapshot(before: A11ySnapshot, after: A11ySnapshot): SnapshotDiffResult {
        return AccessibilitySnapshotEngine.diffSnapshots(before.textTree, after.textTree)
    }

    override suspend fun diffSnapshot(name: String): SnapshotDiffResult {
        val previous = snapshotHistory[name]
            ?: throw KmpwrightException("No saved snapshot found with name: $name. Save a snapshot first using accessibilitySnapshot().")
        val current = accessibilitySnapshot()
        val result = AccessibilitySnapshotEngine.diffSnapshots(previous, current.textTree)
        snapshotHistory = snapshotHistory + (name to current.textTree)
        return result
    }

    override suspend fun diffUrls(url1: String, url2: String, timeoutMs: Long): SnapshotDiffResult {
        return try {
            kotlinx.coroutines.withTimeout(timeoutMs) {
                val html1 = platformHttpGetText(url1)
                val html2 = platformHttpGetText(url2)
                val text1 = htmlToSnapshotText(html1)
                val text2 = htmlToSnapshotText(html2)
                AccessibilitySnapshotEngine.diffSnapshots(text1, text2)
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw KmpwrightException("Timeout diffing URLs: $url1 vs $url2")
        } catch (e: KmpwrightException) {
            throw e
        } catch (e: Exception) {
            throw KmpwrightException("Failed diffing URLs: ${e.message}")
        }
    }

    override suspend fun clipboardRead(): String? {
        return try {
            evalString("""
                (function() {
                    try {
                        return navigator.clipboard.readText();
                    } catch(e) {
                        return document.execCommand('paste') ? window.getSelection().toString() : '';
                    }
                })()
            """.trimIndent())
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun clipboardWrite(text: String) {
        val escaped = escapeJsString(text)
        evalString("""
            (function() {
                try {
                    navigator.clipboard.writeText('$escaped');
                } catch(e) {
                    var ta = document.createElement('textarea');
                    ta.value = '$escaped';
                    ta.style.position = 'fixed';
                    ta.style.opacity = '0';
                    document.body.appendChild(ta);
                    ta.select();
                    document.execCommand('copy');
                    ta.remove();
                }
            })()
        """.trimIndent())
    }

    // State save/load (in-memory and file-based)
    override suspend fun saveState(name: String) = stateDelegate.saveState(name)
    override suspend fun loadState(name: String): Boolean = stateDelegate.loadState(name)
    override fun stateNames(): List<String> = stateDelegate.stateNames()
    override suspend fun deleteState(name: String) = stateDelegate.deleteState(name)

    override suspend fun saveStateToFile(name: String, path: String?, encrypt: Boolean, password: String?) =
        stateDelegate.saveStateToFile(name, path, encrypt, password)
    override suspend fun loadStateFromFile(name: String, path: String?, password: String?): Boolean = stateDelegate.loadStateFromFile(name, path, password)
    override suspend fun autosaveState(options: StateAutosaveOptions): StateAutosave {
        check(!closed.value) { "Page is closed" }
        return StateAutosave(pageScope, options.intervalMs) {
            check(!closed.value) { "Page is closed" }
            saveStateToFile(options.name, options.path, options.encrypt, options.password)
        }.start()
    }

    override fun listSavedStateFiles(dir: String?): List<String> = stateDelegate.listSavedStateFiles(dir)
    override fun deleteStateFile(name: String, path: String?): Boolean = stateDelegate.deleteStateFile(name, path)

    private suspend fun restoreStateInternal(
        stateUrl: String?,
        cookies: List<Cookie>,
        localStorage: Map<String, String>,
        sessionStorage: Map<String, String>
    ) {
        deleteAllCookies()
        for (cookie in cookies) {
            setCookie(cookie)
        }

        if (localStorage.isEmpty() && sessionStorage.isEmpty()) {
            return
        }

        val restoreUrl = stateUrl?.takeIf(::supportsOriginScopedStateRestore)
            ?: throw KmpwrightException(
                "Cannot restore localStorage/sessionStorage without a saved http(s) or file URL."
            )

        if (currentPageUrl() != restoreUrl) {
            val navigation = goto(restoreUrl)
            if (!navigation.ok) {
                throw KmpwrightException(
                    "Failed to navigate to saved state URL $restoreUrl: ${navigation.error ?: "unknown error"}"
                )
            }
        }

        storageDelegate.clearLocalStorage()
        for ((key, value) in localStorage) {
            storageDelegate.setLocalStorage(key, value)
        }
        storageDelegate.clearSessionStorage()
        for ((key, value) in sessionStorage) {
            storageDelegate.setSessionStorage(key, value)
        }
    }

    private fun currentPageUrl(): String? = controller.getUrl() ?: url.value

    private fun navigationGuardAllows(targetUrl: String): Boolean {
        val policy = actionPolicy ?: return true
        return when (policy.check(ActionType.Navigate, targetUrl)) {
            PolicyResult.Allow -> true
            PolicyResult.Deny -> false
            PolicyResult.Confirm -> approvedNavigationUrl == targetUrl
        }
    }

    private fun supportsOriginScopedStateRestore(url: String): Boolean {
        return url.startsWith("https://") ||
            url.startsWith("http://") ||
            url.startsWith("file://")
    }

    override fun consoleMessages(): List<ConsoleMessage> = accumulatedConsoleMessages

    override fun errors(): List<String> = accumulatedConsoleMessages
        .filter { it.type == "error" || it.type == "assertion" }
        .map { it.text }

    /**
     * Called by platform layers to report a console message.
     * The message is both emitted to [events.console] and accumulated for [consoleMessages].
     */
    internal fun reportConsoleMessage(message: ConsoleMessage) {
        accumulatedConsoleMessages = accumulatedConsoleMessages + message
        events.console.tryEmit(message)
    }

    internal fun reportDownload(event: DownloadEvent) {
        events.download.tryEmit(event)
    }

    internal fun reportFileChooser(event: FileChooserEvent) {
        events.fileChooser.tryEmit(event)
    }

    // Download handling
    private var downloadPath: String? = null
    private var downloadBehavior: DownloadBehavior = DownloadBehavior.SaveToDownloadPath

    open override suspend fun waitForDownload(urlPattern: String?, timeoutMs: Long): DownloadEvent {
        return try {
            kotlinx.coroutines.withTimeout(timeoutMs) {
                val result = if (urlPattern != null) {
                    events.download.first { waitUrlPatternMatches(urlPattern, it.url) }
                } else {
                    events.download.first()
                }
                result
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw KmpwrightException("Timeout waiting for download${if (urlPattern != null) " matching $urlPattern" else ""}")
        }
    }

    open override suspend fun waitForFileChooser(timeoutMs: Long): FileChooserEvent {
        return try {
            kotlinx.coroutines.withTimeout(timeoutMs) {
                events.fileChooser.first()
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw KmpwrightException("Timeout waiting for file chooser")
        }
    }

    open override fun setDownloadPath(path: String) {
        downloadPath = path
    }

    open override fun setDownloadBehavior(behavior: DownloadBehavior) {
        downloadBehavior = behavior
    }

    override suspend fun openNewTab(url: String?): NavigationResult {
        val runtime = tabRuntime
            ?: throw KmpwrightException("Tab management is not supported for pages detached from a browser runtime.")
        return runtime.openTab(this, url)
    }

    override suspend fun closeTab(): Boolean {
        val runtime = tabRuntime
            ?: throw KmpwrightException("Tab management is not supported for pages detached from a browser runtime.")
        return runtime.closeTab(this)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        if (reactDelegate.isInitialized()) runCatching { reactDelegate.value.dispose() }
        finishClose()
    }

    override suspend fun closeAsync() {
        if (!closed.compareAndSet(false, true)) return
        try {
            if (reactDelegate.isInitialized()) reactDelegate.value.disposeAsync()
        } finally {
            try { kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { controller.disposeAsync() } }
            finally { finishClose() }
        }
    }

    private fun finishClose() {
        inheritedSetup.clear()
        networkHistory.clear()
        harCapture.dispose()
        // Teardown is deliberately stepwise.  A controller is supplied by a
        // platform boundary and one failed best-effort operation must never
        // prevent later operations from releasing their ownership.
        runCatching { dialogListener.clearHandler() }
        runCatching { routeHandlers.clear() }
        // A navigation callback can otherwise retain this page through the
        // callback closure until the next navigation (or forever on a dead
        // target).
        runCatching { controller.setNavigationCallback(null) }
        runCatching { controller.setNavigationGuard(null) }
        runCatching { controller.removeAllRouteHandlers() }
        runCatching { controller.removeRequestListener(requestListener) }
        runCatching { controller.removeDialogListener(dialogListener) }
        // BaseLocator's registry is process-wide and must not retain a
        // controller after its owning page has been closed.
        runCatching { BaseLocator.invalidateHelpers(controller) }
        runCatching { controller.dispose() }
        runCatching { pageScope.cancel() }
        runCatching { tabRuntime?.pageClosed(this) }
    }

    // Platform tracing and profiling require a backend implementation.
    override suspend fun startTracing(options: TracingOptions): Unit = throw UnsupportedOperationException("Tracing is unavailable on this backend")
    override suspend fun stopTracing(): ByteArray? = throw UnsupportedOperationException("Tracing is unavailable on this backend")
    override suspend fun startProfiler(): Unit = throw UnsupportedOperationException("Profiling is unavailable on this backend")
    override suspend fun stopProfiler(): String? = throw UnsupportedOperationException("Profiling is unavailable on this backend")

    // --- Domain filter ---

    override suspend fun setDomainFilter(domains: Set<String>) {
        val copied = domains.toSet()
        if (controller.setDomainFilter(copied)) controller.setDomainFilterAsync(copied)
        else evalString(DomainFilter(copied).injectionScript())
        readAllowedDomains = copied
        rememberSetup("domains") { it.setDomainFilter(copied) }
    }

    // --- Stealth mode ---

    override suspend fun enableStealth() {
        evalString(stealthJsSource())
    }

    internal fun stealthJsSource(): String = """
        (function() {
            if (window.__kmpwright_stealth) return;
            window.__kmpwright_stealth = true;
            Object.defineProperty(navigator, 'webdriver', { get: function() { return undefined; } });
            Object.defineProperty(navigator, 'plugins', { get: function() { return [1, 2, 3, 4, 5]; } });
            Object.defineProperty(navigator, 'languages', { get: function() { return ['en-US', 'en']; } });
            window.chrome = window.chrome || {};
            window.chrome.runtime = window.chrome.runtime || {};
            var origQuery = window.navigator.permissions && window.navigator.permissions.query;
            if (origQuery) {
                window.navigator.permissions.query = function(parameters) {
                    return parameters.name === 'notifications'
                        ? Promise.resolve({ state: Notification.permission })
                        : origQuery(parameters);
                };
            }
        })();
    """.trimIndent()

    // --- Content boundary ---

    override fun createContentBoundary(): ContentBoundary =
        ContentBoundary.create(url.value ?: "about:blank")

    override suspend fun boundedContent(boundary: ContentBoundary?): String {
        val b = boundary ?: createContentBoundary()
        return b.wrap(content())
    }

    override suspend fun boundedSnapshot(options: A11ySnapshotOptions, boundary: ContentBoundary?): String {
        val b = boundary ?: createContentBoundary()
        return b.wrap(accessibilitySnapshot(options).textTree)
    }
}

internal fun sanitizeStateFileName(name: String): String {
    // Remove path traversal components and separators, but keep harmless content
    return name.replace("\\", "").replace("/", "").replace("..", "").ifEmpty { "unnamed" }
}
