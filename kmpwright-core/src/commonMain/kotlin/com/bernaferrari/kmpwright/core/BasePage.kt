package com.bernaferrari.kmpwright.core

import kotlinx.atomicfu.atomic
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.TimeSource

/** Mutable sinks behind the read-only [PageEvents] view. Emission never suspends; slow collectors lose the oldest events. */
internal class PageEventSinks : PageEvents {
    private fun <T> sink(capacity: Int) = MutableSharedFlow<T>(extraBufferCapacity = capacity, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val consoleSink = sink<ConsoleMessage>(256)
    val navigationSink = sink<NavigationEvent>(64)
    val loadStateSink = sink<LoadState>(16)
    val dialogSink = sink<DialogEvent>(16)
    val requestSink = sink<Request>(256)
    val responseSink = sink<Response>(256)
    val downloadSink = sink<Download>(32)
    val fileChooserSink = sink<FileChooser>(16)
    val pageErrorSink = sink<PageError>(64)
    override val console: SharedFlow<ConsoleMessage> = consoleSink.asSharedFlow()
    override val navigation: SharedFlow<NavigationEvent> = navigationSink.asSharedFlow()
    override val loadState: SharedFlow<LoadState> = loadStateSink.asSharedFlow()
    override val dialog: SharedFlow<DialogEvent> = dialogSink.asSharedFlow()
    override val request: SharedFlow<Request> = requestSink.asSharedFlow()
    override val response: SharedFlow<Response> = responseSink.asSharedFlow()
    override val download: SharedFlow<Download> = downloadSink.asSharedFlow()
    override val fileChooser: SharedFlow<FileChooser> = fileChooserSink.asSharedFlow()
    override val pageError: SharedFlow<PageError> = pageErrorSink.asSharedFlow()
}

internal abstract class BasePage protected constructor(
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
    protected val closeSignal = CompletableDeferred<Unit>()
    protected fun beginPageClose() {
        closeSignal.complete(Unit)
        pendingNavigation.getAndSet(null)?.deferred?.completeExceptionally(KmpwrightException("Page closed during navigation"))
    }
    protected fun checkPageOpen() {
        if (closed.value || closeSignal.isCompleted) throw KmpwrightException("Page is closed")
    }
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

    override val touchscreen: Touchscreen
        get() = touchDevice

    override val mouse: Mouse
        get() = mouseDevice

    override val keyboard: Keyboard
        get() = keyboardDevice

    private val touchDevice by lazy(LazyThreadSafetyMode.NONE) { guardedTouch(createTouch()) }
    private val mouseDevice by lazy(LazyThreadSafetyMode.NONE) { guardedMouse(createMouse()) }
    private val keyboardDevice by lazy(LazyThreadSafetyMode.NONE) { guardedKeyboard(createKeyboard()) }

    protected abstract fun createTouch(): Touchscreen
    protected abstract fun createMouse(): Mouse
    protected abstract fun createKeyboard(): Keyboard

    private fun guardedTouch(delegate: Touchscreen): Touchscreen = object : Touchscreen {
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
    }

    override fun capabilities(): PageCapabilities = PageCapabilities()

    private val sinks = PageEventSinks()
    override val events: PageEvents get() = sinks

    // Shared mutable state is held in atomics: backend callbacks arrive on
    // arbitrary threads (WebView/binder threads, CDP socket readers).
    private val routeHandlers = atomic(persistentMapOf<String, Pair<Regex, suspend (Route) -> Unit>>())
    private val requestFlow = MutableSharedFlow<Request>(extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val responseFlow = MutableSharedFlow<Response>(extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val navigationResultFlow = MutableSharedFlow<NavigationResult>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val networkIdleSince = atomic(0L)
    private val navigationMutex = Mutex()

    private class PendingNavigation(
        val deferred: CompletableDeferred<NavigationResult>,
        val target: String?,
        val previousUrl: String?,
        val acceptSameUrl: Boolean,
        /** setContent: the document KMPwright itself loads (about:blank / data:) is always allowed. */
        val localContent: Boolean = false,
        /** The load state that completes this navigation (DOMContentLoaded completes before the load callback). */
        val waitUntil: LoadState = LoadState.Load
    ) {
        /** Set when the backend reports that a new document was committed after this navigation started. */
        @kotlin.concurrent.Volatile var committed: Boolean = false

        /** Ignores late callbacks from the document that was current before this navigation started. */
        fun accepts(result: NavigationResult): Boolean {
            if (target != null && sameUrl(result.url, target)) return true
            if (!result.committed) return result.url.isBlank() || target == null
            return acceptSameUrl || previousUrl == null || !sameUrl(result.url, previousUrl)
        }
    }
    private val pendingNavigation = atomic<PendingNavigation?>(null)

    /** Applies the origin allowlist and navigation guard to a committed document. */
    private fun validateNavigation(result: NavigationResult): NavigationResult {
        val pendingContent = pendingNavigation.value?.localContent == true
        val localDocument = result.url == "about:blank" ||
            (pendingContent && (result.url.startsWith("data:") || result.url.startsWith("about:")))
        val allowed = result.url.isNotBlank() && (localDocument ||
            ((config.allowedOriginRules.isEmpty() || config.allowedOriginRules.any { originRuleMatches(it, result.url) }) &&
                navigationGuardAllows(result.url)))
        return if (!result.committed || allowed) result else result.copy(ok = false,
            error = "Committed navigation origin is not allowed by KmpwrightConfig: ${result.url}")
    }

    /** DOMContentLoaded was already emitted for the current document (backends that report it natively). */
    private val domContentLoadedEmitted = atomic(false)

    private fun navigationCompleted(result: NavigationResult) {
        if (closed.value) return
        val validated = validateNavigation(result)
        invalidateOnNavigation()
        if (validated.committed) _url.value = validated.url else controller.getUrl()?.let { _url.value = it }
        _title.value = controller.getTitle()
        approvedNavigationUrl.value = null
        navigationResultFlow.tryEmit(validated)
        sinks.navigationSink.tryEmit(NavigationEvent(validated.url, validated.status, NavigationType.NewDocument))
        if (validated.committed) {
            if (!domContentLoadedEmitted.getAndSet(false)) sinks.loadStateSink.tryEmit(LoadState.DOMContentLoaded)
            sinks.loadStateSink.tryEmit(LoadState.Load)
        }
        val pending = pendingNavigation.value
        if (pending != null && pending.accepts(validated)) pending.deferred.complete(validated)
    }

    /**
     * Records a same-document navigation (pushState, replaceState, fragment change, same-document history
     * traversal): updates [url]/[title] and resolves `waitForNavigation`, as Playwright does. Idempotent per URL.
     */
    private fun sameDocumentNavigated(url: String, title: String? = controller.getTitle()): Boolean {
        if (closed.value || url.isBlank() || url == _url.value) return false
        _url.value = url
        title?.let { _title.value = it }
        val result = NavigationResult(url, 200, true)
        navigationResultFlow.tryEmit(result)
        sinks.navigationSink.tryEmit(NavigationEvent(url, 200, NavigationType.SameDocument))
        return true
    }

    private fun onLifecycleEvent(event: PageLifecycleEvent) {
        if (closed.value) return
        when (event) {
            is PageLifecycleEvent.Committed -> {
                domContentLoadedEmitted.value = false
                pendingNavigation.value?.committed = true
            }
            is PageLifecycleEvent.DomContentLoaded -> {
                domContentLoadedEmitted.value = true
                sinks.loadStateSink.tryEmit(LoadState.DOMContentLoaded)
                val pending = pendingNavigation.value ?: return
                if (pending.waitUntil != LoadState.DOMContentLoaded || !pending.committed) return
                val validated = validateNavigation(committedNavigation(event.url, event.status.takeIf { it > 0 } ?: 200))
                if (!pending.accepts(validated)) return
                if (validated.committed) _url.value = validated.url
                pending.deferred.complete(validated)
            }
            is PageLifecycleEvent.SameDocument -> sameDocumentNavigated(event.url)
            is PageLifecycleEvent.TitleChanged -> _title.value = event.title
        }
    }

    /**
     * Starts a navigation and waits for the backend's completion callback that belongs to it.
     * @throws KmpwrightTimeoutException when no matching navigation completes in time.
     */
    private suspend fun navigate(
        timeoutMs: Long,
        target: String?,
        acceptSameUrl: Boolean,
        description: String,
        localContent: Boolean = false,
        urlChangeFallback: Boolean = false,
        waitUntil: LoadState = LoadState.Load,
        start: () -> Unit
    ): NavigationResult = navigationMutex.withLock {
        checkPageOpen()
        val before = currentPageUrl()
        val pending = PendingNavigation(CompletableDeferred(), target, before, acceptSameUrl, localContent, waitUntil)
        pendingNavigation.value = pending
        try {
            awaitPageOperation(closeSignal) {
                controller.setNavigationCallback(::navigationCompleted)
                // WebViews have no native DOMContentLoaded signal: mark the current document so the new one
                // can be told apart, then poll its readyState (see awaitDomContentLoaded).
                val documentMark = if (waitUntil == LoadState.DOMContentLoaded && !controller.reportsDomContentLoaded && !urlChangeFallback) {
                    markCurrentDocument()
                } else null
                if (!controller.reportsDomContentLoaded) domContentLoadedEmitted.value = false
                start()
                withKmpwrightTimeout(timeoutMs, { "Timeout ${timeoutMs}ms exceeded while $description" }) {
                    if (documentMark != null) return@withKmpwrightTimeout awaitDomContentLoaded(pending, documentMark)
                    if (!urlChangeFallback) return@withKmpwrightTimeout pending.deferred.await()
                    // History traversals restored from the back/forward cache may not report a load:
                    // also accept a committed URL change of a fully loaded document.
                    while (true) {
                        if (pending.deferred.isCompleted) return@withKmpwrightTimeout pending.deferred.await()
                        val state = runCatching {
                            controller.normalizeJavaScriptResult(controller.evaluateJavaScriptAsync("document.readyState + ' ' + location.href"))
                        }.getOrNull()
                        val href = state?.substringAfter(' ', "")
                        if (state != null && state.startsWith("complete ") && !href.isNullOrBlank() && href != before) {
                            invalidateOnNavigation()
                            _url.value = href
                            _title.value = controller.getTitle()
                            return@withKmpwrightTimeout NavigationResult(href, 200, true)
                        }
                        delay(50)
                    }
                    @Suppress("UNREACHABLE_CODE") error("unreachable")
                }
            }
        } finally {
            pendingNavigation.compareAndSet(pending, null)
            approvedNavigationUrl.value = null
        }
    }

    private suspend fun finishLoad(result: NavigationResult, waitUntil: LoadState, started: TimeSource.Monotonic.ValueTimeMark, timeoutMs: Long): NavigationResult {
        // Backend completion callbacks fire at the load boundary, so DOMContentLoaded/Load are met.
        if (result.committed && waitUntil == LoadState.NetworkIdle) {
            val remaining = timeoutMs - started.elapsedNow().inWholeMilliseconds
            if (remaining <= 0) throw KmpwrightTimeoutException("Timeout ${timeoutMs}ms exceeded waiting for network idle after navigating to ${result.url}")
            waitForLoadState(LoadState.NetworkIdle, remaining)
        }
        return refineStatus(result)
    }

    /**
     * Backends whose callbacks only report error statuses (Android: onReceivedHttpError for >= 400) report
     * 200 for other loads; the Navigation Timing `responseStatus` of the document gives the real status.
     */
    private suspend fun refineStatus(result: NavigationResult): NavigationResult {
        if (controller.reportsHttpStatus || !result.committed || result.status != 200) return result
        if (!result.url.startsWith("http://") && !result.url.startsWith("https://")) return result
        val status = runCatching { evalString(NAVIGATION_STATUS_JS)?.toDoubleOrNull()?.toInt() }.getOrNull() ?: 0
        return if (status > 0 && status != result.status) committedNavigation(result.url, status) else result
    }

    private class DocumentMark(val key: String, val token: String)

    /** Marks the current document with a private token; null when the document cannot be marked. */
    private suspend fun markCurrentDocument(): DocumentMark? {
        val mark = DocumentMark("__kmpwright_doc_" + PageHelpers.key.takeLast(12), PageHelpers.seal.take(16) + currentTimeMillis())
        val marked = runCatching {
            controller.normalizeJavaScriptResult(controller.evaluateJavaScriptAsync(
                "(function(){try{Object.defineProperty(window,${jsStringLiteral(mark.key)},{value:${jsStringLiteral(mark.token)},configurable:true});" +
                    "return window[${jsStringLiteral(mark.key)}]===${jsStringLiteral(mark.token)}?'ok':'no';}catch(e){return 'no';}})()"
            ))
        }.getOrNull()
        return mark.takeIf { marked == "ok" }
    }

    /**
     * Completes a WebView navigation at DOMContentLoaded: the new document no longer carries [mark] and its
     * readyState is past `loading`. Failures, policy refusals and fast loads still complete through the
     * backend's navigation callback.
     */
    private suspend fun awaitDomContentLoaded(pending: PendingNavigation, mark: DocumentMark): NavigationResult {
        val probe = "(function(){try{if(window[${jsStringLiteral(mark.key)}]===${jsStringLiteral(mark.token)})return 'old';" +
            "return document.readyState+' '+($NAVIGATION_STATUS_JS)+' '+location.href;}catch(e){return 'old';}})()"
        while (true) {
            if (pending.deferred.isCompleted) return pending.deferred.await()
            val state = runCatching { controller.normalizeJavaScriptResult(controller.evaluateJavaScriptAsync(probe)) }.getOrNull()
            val parts = state?.split(' ', limit = 3)
            if (parts != null && parts.size == 3 && (parts[0] == "interactive" || parts[0] == "complete") && parts[2].isNotBlank()) {
                val status = parts[1].toDoubleOrNull()?.toInt()?.takeIf { it > 0 }
                    ?: controller.pendingNavigationStatus()?.takeIf { it > 0 } ?: 200
                val validated = validateNavigation(committedNavigation(parts[2], status))
                if (pending.accepts(validated)) {
                    if (pending.deferred.isCompleted) return pending.deferred.await()
                    sinks.loadStateSink.tryEmit(LoadState.DOMContentLoaded)
                    domContentLoadedEmitted.value = true
                    if (validated.committed) _url.value = validated.url
                    _title.value = controller.getTitle()
                    return validated
                }
            }
            withTimeoutOrNull(25) { pending.deferred.await() }
        }
    }

    // Element Reference System
    private val snapshotRefs = atomic<Map<String, RefEntry>>(emptyMap())
    internal var currentSnapshotRefMap: Map<String, RefEntry>
        get() = snapshotRefs.value
        set(value) { snapshotRefs.value = value }

    // Console messages and page errors: bounded histories that survive navigations. Each entry
    // carries the main-frame document generation it was reported in (PageMessageFilter.SinceNavigation).
    private val consoleHistory = PageMessageHistory<ConsoleMessage>(MAX_CONSOLE_MESSAGES)
    private val pageErrorHistory = PageMessageHistory<PageError>(MAX_CONSOLE_MESSAGES)
    private val documentGeneration = atomic(0L)

    // Action Policy System
    private val actionPolicy = atomic<ActionPolicy?>(null)
    private val policyConfirmCallback = atomic<(suspend (ActionType, String?) -> Boolean)?>(null)
    private val approvedNavigationUrl = atomic<String?>(null)

    // Delegates for focused concerns
    private val localStorageArea = PageWebStorage("localStorage", { script -> evalString(script) }) { checkPolicy(it, currentPageUrl()) }
    private val sessionStorageArea = PageWebStorage("sessionStorage", { script -> evalString(script) }) { checkPolicy(it, currentPageUrl()) }
    override val localStorage: WebStorage get() = localStorageArea
    override val sessionStorage: WebStorage get() = sessionStorageArea
    private val emulationDelegate by lazy { PageEmulationDelegate({ script -> evalString(script) }, controller) }
    private val httpDelegate by lazy { PageHttpDelegate(controller) }
    private val scrollDelegate by lazy { PageScrollDelegate { script -> evalString(script) } }

    private val harCapture = PageHarCapture(controller, pageScope)
    private val networkHistory = PageNetworkHistory(controller::networkResponseBody, {
        if (!config.allowDomExtraction) throw KmpwrightException("Response body access is disabled by KmpwrightConfig.allowDomExtraction")
        checkPolicy(ActionType.Evaluate, currentPageUrl())
    }, harStart = {
        checkPageOpen()
        if (!config.allowDomExtraction) throw KmpwrightException("HAR capture is disabled by KmpwrightConfig.allowDomExtraction")
        checkPolicy(ActionType.Evaluate, currentPageUrl())
        harCapture.start()
    }, harStop = { harCapture.stop() })
    override val network: NetworkHistory get() = networkHistory

    private val requestListener = object : RequestListener {
        override fun onRequestStarted(request: Request) {
            networkHistory.started(request)
            harCapture.started(request)
            networkIdleSince.value = 0
            requestFlow.tryEmit(request)
            sinks.requestSink.tryEmit(request)
        }

        override fun onResponseReceived(response: Response) {
            if (response.status == 0 && response.statusText == "idle") {
                networkIdleSince.value = currentTimeMillis()
                sinks.loadStateSink.tryEmit(LoadState.NetworkIdle)
            } else {
                response.bodyReader = { maxBytes -> networkHistory.responseBody(response, maxBytes) }
                networkHistory.received(response)
                harCapture.received(response)
                responseFlow.tryEmit(response)
                sinks.responseSink.tryEmit(response)
            }
        }

        override fun onRequestFinished(request: Request) {
            networkHistory.finished(request)
            harCapture.finished(request)
            if (controller.getPendingRequestCount() == 0) {
                networkIdleSince.value = currentTimeMillis()
                sinks.loadStateSink.tryEmit(LoadState.NetworkIdle)
            }
        }

        override fun onRequestFailed(request: Request, error: String) {
            networkHistory.failed(request, error)
            harCapture.failed(request, error)
            sinks.responseSink.tryEmit(Response(url = request.url, status = 0, statusText = error, id = request.id))
        }
    }

    /**
     * Dialog handlers form a stack: the most recently registered open handler answers. Closing a
     * handle removes exactly that registration. A pending `waitForDialog`/`waitForEvent("dialog")`
     * also counts as a listener (like Playwright's `page.waitForEvent('dialog')`): automation then
     * receives the dialog and, without a handler, dismisses it. With neither, dialogs are dismissed
     * (CDP, iOS) or left to the host WebChromeClient / native UI (Android).
     */
    private class DialogRegistration(val handler: suspend (DialogEvent) -> DialogResponse)
    private val dialogHandlers = atomic<PersistentList<DialogRegistration>>(persistentListOf())
    private val dialogWaiters = atomic(0)

    /** Runs [block] (which waits for a dialog event) while counting as a dialog listener. */
    internal suspend fun <T> awaitingDialog(block: suspend () -> T): T {
        dialogWaiters.incrementAndGet()
        try {
            return block()
        } finally {
            dialogWaiters.decrementAndGet()
        }
    }

    private val dialogListener = object : DialogListener {
        override val hasDialogHandlers: Boolean get() = dialogHandlers.value.isNotEmpty() || dialogWaiters.value > 0

        override fun onDialogObserved(type: DialogType, message: String, defaultPrompt: String?) {
            sinks.dialogSink.tryEmit(DialogEvent(type, message, defaultPrompt))
        }

        override suspend fun onDialogAsync(type: DialogType, message: String, defaultPrompt: String?): DialogResponse {
            val event = DialogEvent(type, message, defaultPrompt)
            sinks.dialogSink.tryEmit(event)
            val registration = dialogHandlers.value.lastOrNull() ?: return DialogResponse.Dismiss
            return try {
                withTimeoutOrNull(config.timeouts.handler) { registration.handler(event) } ?: DialogResponse.Dismiss
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (_: Exception) {
                DialogResponse.Dismiss
            }
        }
    }

    private val diagnosticsListener = object : PageDiagnosticsListener {
        override fun onConsoleMessage(message: ConsoleMessage) = reportConsoleMessage(message)
        override fun onPageError(error: PageError) = reportPageError(error)
        override fun onMainFrameDocumentCommitted() = markDocumentCommitted()
    }

    init {
        require(!config.productionMode || !config.debug.enabled) {
            "Debug mode cannot be enabled when KmpwrightConfig.productionMode is true"
        }
        controller.addRequestListener(requestListener)
        controller.addDialogListener(dialogListener)
        controller.addDiagnosticsListener(diagnosticsListener)
        controller.setRequestIdleTimeout(500)
        controller.setNavigationCallback(::navigationCompleted)
        controller.setLifecycleListener(::onLifecycleEvent)
    }

    // ------------------------------------------------------------------ navigation

    override suspend fun goto(url: String, options: GotoOptions): NavigationResult {
        checkPolicy(ActionType.Navigate, url)
        return gotoApproved(url, options)
    }

    private suspend fun gotoApproved(url: String, options: GotoOptions): NavigationResult {
        validateNavigationOrigin(url)
        val timeout = config.navigationTimeout(options.timeoutMs)
        val started = TimeSource.Monotonic.markNow()
        approvedNavigationUrl.value = url
        networkIdleSince.value = 0
        invalidateOnNavigation()
        val previous = currentPageUrl()
        val result = navigate(timeout, url, acceptSameUrl = previous != null && sameUrl(previous, url),
            description = "navigating to \"$url\"", waitUntil = options.waitUntil) { controller.loadUrl(url) }
        return finishLoad(result, options.waitUntil, started, timeout)
    }

    private fun validateNavigationOrigin(url: String) {
        if (url != "about:blank" && config.allowedOriginRules.isNotEmpty() && config.allowedOriginRules.none { originRuleMatches(it, url) }) {
            throw KmpwrightException("Navigation origin is not allowed by KmpwrightConfig: $url")
        }

    }

    /** Evaluates an internal script with the default timeout; never leaks coroutine timeout types. */
    private suspend fun evalString(script: String): String? = awaitPageOperation(closeSignal) {
        withKmpwrightTimeout(config.timeouts.default, { "Timeout ${config.timeouts.default}ms exceeded evaluating a page script" }) {
            controller.normalizeJavaScriptResult(controller.evaluateJavaScriptAsync(script))
        }
    }

    override suspend fun reload(options: ReloadOptions): NavigationResult {
        checkPolicy(ActionType.Navigate, currentPageUrl())
        val timeout = config.navigationTimeout(options.timeoutMs)
        val started = TimeSource.Monotonic.markNow()
        invalidateOnNavigation()
        val result = navigate(timeout, currentPageUrl(), acceptSameUrl = true, description = "reloading", waitUntil = options.waitUntil) { controller.reload() }
        return finishLoad(result, options.waitUntil, started, timeout)
    }

    override suspend fun goBack(options: HistoryNavigationOptions): NavigationResult? = traverseHistory(-1, options)

    override suspend fun goForward(options: HistoryNavigationOptions): NavigationResult? = traverseHistory(1, options)

    private suspend fun traverseHistory(delta: Int, options: HistoryNavigationOptions): NavigationResult? {
        checkPolicy(ActionType.Navigate, currentPageUrl())
        val timeout = config.navigationTimeout(options.timeoutMs)
        val started = TimeSource.Monotonic.markNow()
        val label = if (delta < 0) "going back" else "going forward"
        if (controller.canTraverseHistory(delta) == false) return null
        val kind = runCatching {
            evalString("""
                (function(){
                  try {
                    if (${delta < 0} && history.length <= 1) return 'none';
                    if (typeof navigation === 'undefined' || !navigation.currentEntry) return 'unknown';
                    var entries = navigation.entries(), index = navigation.currentEntry.index + ($delta);
                    if (index < 0 || index >= entries.length) return ${if (delta < 0) "'unknown'" else "'none'"};
                    return entries[index].sameDocument ? 'same' : 'cross';
                  } catch (e) { return 'unknown'; }
                })()
            """.trimIndent())
        }.getOrNull() ?: "unknown"
        if (kind == "none") return null
        val before = currentPageUrl()
        val start: () -> Unit = { if (delta < 0) controller.goBack() else controller.goForward() }
        if (kind == "same") {
            invalidateOnNavigation()
            start()
            val target = pollForUrlChange(before, timeout)
                ?: throw KmpwrightTimeoutException("Timeout ${timeout}ms exceeded while $label")
            sameDocumentNavigated(target)
            return NavigationResult(target, 200, true)
        }
        if (kind == "unknown") {
            // No reliable history information: a traversal that changes nothing within a short grace
            // period means there was no entry to go to.
            val grace = minOf(timeout, HISTORY_PROBE_GRACE_MS)
            invalidateOnNavigation()
            val result = try {
                navigate(grace, null, acceptSameUrl = true, description = label, urlChangeFallback = true, start = start)
            } catch (_: KmpwrightTimeoutException) {
                val changed = currentPageUrl()?.takeIf { before == null || !sameUrl(it, before) }
                if (changed != null) {
                    _url.value = changed
                    return NavigationResult(changed, 200, true)
                }
                return null
            }
            return finishLoad(result, options.waitUntil, started, timeout)
        }
        invalidateOnNavigation()
        val result = navigate(timeout, null, acceptSameUrl = true, description = label, urlChangeFallback = true,
            waitUntil = options.waitUntil, start = start)
        return finishLoad(result, options.waitUntil, started, timeout)
    }

    private suspend fun pollForUrlChange(before: String?, timeoutMs: Long): String? {
        val started = TimeSource.Monotonic.markNow()
        while (started.elapsedNow().inWholeMilliseconds < timeoutMs) {
            val href = runCatching { evalString("location.href") }.getOrNull() ?: controller.getUrl()
            if (href != null && (before == null || href != before)) return href
            delay(25)
        }
        return null
    }

    override suspend fun setContent(html: String, options: SetContentOptions) {
        checkPolicy(ActionType.SetContent, currentPageUrl())
        val timeout = config.navigationTimeout(options.timeoutMs)
        val started = TimeSource.Monotonic.markNow()
        networkIdleSince.value = 0
        invalidateOnNavigation()
        val result = navigate(timeout, null, acceptSameUrl = true, description = "setting page content", localContent = true,
            waitUntil = options.waitUntil) { controller.loadHtml(html, null) }
        if (!result.committed) throw KmpwrightException(result.error ?: "Failed to load HTML content")
        if (options.waitUntil == LoadState.NetworkIdle) finishLoad(result, options.waitUntil, started, timeout)
        else waitForLoadState(options.waitUntil, (timeout - started.elapsedNow().inWholeMilliseconds).coerceAtLeast(1))
    }

    internal fun invalidateOnNavigation() {
        currentSnapshotRefMap = emptyMap()
    }

    final override fun locator(selector: String): Locator {
        val loc = createPlatformLocator(selector)
        loc.policyChecker = { actionType -> checkPolicy(actionType, currentPageUrl()) }
        loc.lifetime = closeSignal
        return loc
    }

    protected abstract fun createPlatformLocator(selector: String): BaseLocator

    // ------------------------------------------------------------------ evaluation

    override suspend fun evaluate(expression: String): JsResult {
        checkPolicy(ActionType.Evaluate, currentPageUrl())
        if (!config.allowEval) throw KmpwrightException("JavaScript evaluation is disabled by KmpwrightConfig.allowEval")
        return evaluateInternal(expression, config.timeouts.default)
    }

    private val scriptTask by lazy { PageScriptTask(controller) }

    /**
     * Single expressions are wrapped so Promises are awaited (natively, or through [PageScriptTask]
     * on bridges that cannot await) and the JavaScript type is reported. Statement lists are
     * evaluated verbatim, as before, and report type `"unknown"`.
     */
    internal suspend fun evaluateInternal(expression: String, timeoutMs: Long): JsResult = awaitPageOperation(closeSignal) {
        if (!isSingleExpression(expression)) {
            val raw = withKmpwrightTimeout(timeoutMs, { "Timeout ${timeoutMs}ms exceeded evaluating JavaScript" }) {
                controller.normalizeJavaScriptResult(controller.evaluateJavaScriptAsync(expression))
            }
            return@awaitPageOperation JsResult(raw ?: "null", if (raw == null) "undefined" else "unknown")
        }
        val encoder = """
            (async function(){
              var v = await (
            ${expression.trim().removeSuffix(";")}
            );
              var t = v === null ? 'null' : Array.isArray(v) ? 'array' : typeof v, s;
              if (t === 'string') s = v;
              else if (t === 'undefined') s = 'null';
              else if (t === 'function' || t === 'symbol') s = String(v);
              else { try { s = JSON.stringify(v); } catch (e) { s = String(v); } if (s === undefined) s = String(v); }
              return JSON.stringify({t: t, v: s});
            })()
        """.trimIndent()
        val raw = withKmpwrightTimeout(timeoutMs, { "Timeout ${timeoutMs}ms exceeded evaluating JavaScript" }) {
            if (controller.supportsPromiseEvaluation) controller.normalizeJavaScriptResult(controller.evaluateJavaScriptAsync(encoder))
            else scriptTask.evaluate(encoder, timeoutMs)
        }
        val envelope = raw?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }
        val type = envelope?.get("t")?.jsonPrimitive?.content
        val value = envelope?.get("v")?.jsonPrimitive?.content
        if (envelope == null || type == null || value == null) {
            // Custom bridges that do not return the envelope: report the raw value.
            JsResult(raw ?: "null", if (raw == null) "undefined" else "unknown")
        } else JsResult(value, type)
    }

    override suspend fun content(): String {
        checkPolicy(ActionType.ReadContent, currentPageUrl())
        if (!config.allowDomExtraction) throw KmpwrightException("DOM extraction is disabled by KmpwrightConfig.allowDomExtraction")
        return evalString("document.documentElement.outerHTML") ?: ""
    }

    override suspend fun screenshot(options: ScreenshotOptions): ScreenshotResult {
        checkPolicy(ActionType.Screenshot, currentPageUrl())
        if (options.annotate) return annotatedScreenshot(options)
        return captureScreenshot(options)
    }

    private suspend fun captureScreenshot(options: ScreenshotOptions): ScreenshotResult {
        val screenshotController = createScreenshotController()
        val timeout = config.defaultTimeout(options.timeoutMs)
        return try {
            awaitPageOperation(closeSignal) {
                withKmpwrightTimeout(timeout, { "Screenshot timed out after ${timeout}ms" }) { screenshotController.capture(options) }
            }
        } catch (e: KmpwrightException) {
            throw e
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            throw KmpwrightException("Screenshot failed: ${e.message}", e)
        }
    }

    protected open fun createScreenshotController(): ScreenshotController {
        throw IllegalStateException("createScreenshotController must be implemented by platform")
    }

    override suspend fun waitForLoadState(state: LoadState, timeoutMs: Long?) {
        awaitLoadState(state, config.navigationTimeout(timeoutMs))
    }

    private suspend fun awaitLoadState(state: LoadState, timeoutMs: Long) {
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
                throw KmpwrightTimeoutException("Timeout ${timeoutMs}ms exceeded waiting for DOMContentLoaded")
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
                throw KmpwrightTimeoutException("Timeout ${timeoutMs}ms exceeded waiting for load")
            }
            LoadState.NetworkIdle -> {
                var quietSince = if (controller.getPendingRequestCount() == 0) currentTimeMillis() else 0L
                while (currentTimeMillis() - startTime < timeoutMs) {
                    checkPageOpen()
                    val now = currentTimeMillis()
                    if (controller.getPendingRequestCount() != 0) quietSince = 0L
                    if (controller.getPendingRequestCount() == 0) {
                        if (quietSince == 0L) quietSince = now
                        // A request can start and finish between probes; its completion
                        // timestamp resets the continuous quiet interval as well.
                        quietSince = maxOf(quietSince, networkIdleSince.value)
                        if (now - quietSince >= 500) return
                    }
                    delay(minOf(25L, (timeoutMs - (now - startTime)).coerceAtLeast(1)))
                }
                throw KmpwrightTimeoutException("Timeout ${timeoutMs}ms exceeded waiting for network idle")
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
            val remaining = (timeoutMs - (currentTimeMillis() - startTime)).coerceAtLeast(1)
            if (withTimeoutOrNull(minOf(500L, remaining)) {
                    events.loadState.first(accepts)
                } != null) return true
            if (isReady(evalString("document.readyState"))) return true
        }
        return false
    }

    override suspend fun waitForTimeout(ms: Long) = delay(ms)

    /** Returns null when no navigation happens within the timeout (unchanged contract). */
    override suspend fun waitForNavigation(options: WaitForNavigationOptions): NavigationResult? = awaitPageOperation(closeSignal) {
        val timeout = config.navigationTimeout(options.timeoutMs)
        val started = TimeSource.Monotonic.markNow()
        val result = withTimeoutOrNull(timeout) {
            navigationResultFlow.first { navigation ->
                options.url == null || waitUrlPatternMatches(options.url, navigation.url)
            }
        } ?: return@awaitPageOperation null
        options.waitUntil?.let { awaitLoadState(it, (timeout - started.elapsedNow().inWholeMilliseconds).coerceAtLeast(1)) }
        result
    }

    override suspend fun waitForURL(url: String, options: WaitForURLOptions) {
        val pattern = urlPatternRegex(url)
        awaitUrl("matching $url", options) { pattern.matches(it) }
    }

    override suspend fun waitForURL(url: Regex, options: WaitForURLOptions) =
        awaitUrl("matching /${url.pattern}/", options) { url.containsMatchIn(it) }

    override suspend fun waitForURL(options: WaitForURLOptions, predicate: (String) -> Boolean) =
        awaitUrl("accepted by the predicate", options, predicate)

    /** Polls the live `location.href` (so same-document navigations count), then waits for [WaitForURLOptions.waitUntil]. */
    private suspend fun awaitUrl(description: String, options: WaitForURLOptions, matches: (String) -> Boolean) {
        val timeout = config.navigationTimeout(options.timeoutMs)
        val started = TimeSource.Monotonic.markNow()
        withKmpwrightTimeout(timeout, { "Timeout ${timeout}ms exceeded waiting for a URL $description" }) {
            while (true) {
                checkPageOpen()
                val href = runCatching { evalString("location.href") }.getOrNull() ?: currentPageUrl()
                if (href != null && matches(href)) break
                withTimeoutOrNull(100) { url.first { it != href } }
            }
        }
        awaitLoadState(options.waitUntil, (timeout - started.elapsedNow().inWholeMilliseconds).coerceAtLeast(1))
    }

    override suspend fun waitForEvent(event: String, options: WaitForEventOptions): Any? = awaitPageOperation(closeSignal) {
        withTimeoutOrNull(config.defaultTimeout(options.timeoutMs)) {
            when (event.lowercase()) {
                "console" -> events.console.first { options.predicate == null || options.predicate(it) }
                "navigation" -> events.navigation.first { options.predicate == null || options.predicate(it) }
                "dialog" -> awaitingDialog { events.dialog.first { options.predicate == null || options.predicate(it) } }
                "request" -> events.request.first { options.predicate == null || options.predicate(it) }
                "response" -> events.response.first { options.predicate == null || options.predicate(it) }
                "download" -> events.download.first { options.predicate == null || options.predicate(it) }
                "filechooser" -> events.fileChooser.first { options.predicate == null || options.predicate(it) }
                "pageerror" -> events.pageError.first { options.predicate == null || options.predicate(it) }
                "domcontentloaded" -> events.loadState.first { it == LoadState.DOMContentLoaded }
                "load" -> events.loadState.first { it == LoadState.Load }
                "networkidle" -> events.loadState.first { it == LoadState.NetworkIdle }
                else -> throw KmpwrightException("Unsupported page event: $event")
            }
        }
    }

    override suspend fun waitForRequest(url: String, timeoutMs: Long?): Request =
        awaitRequest("matching $url", timeoutMs) { waitUrlPatternMatches(url, it.url) }

    override suspend fun waitForRequest(url: Regex, timeoutMs: Long?): Request =
        awaitRequest("matching /${url.pattern}/", timeoutMs) { url.containsMatchIn(it.url) }

    override suspend fun waitForRequest(timeoutMs: Long?, predicate: (Request) -> Boolean): Request =
        awaitRequest("accepted by the predicate", timeoutMs, predicate)

    private suspend fun awaitRequest(description: String, timeoutMs: Long?, matches: (Request) -> Boolean): Request {
        val timeout = config.defaultTimeout(timeoutMs)
        return withKmpwrightTimeout(timeout, { "Timeout ${timeout}ms exceeded waiting for a request $description" }) {
            requestFlow.first(matches)
        }
    }

    override suspend fun waitForResponse(url: String, timeoutMs: Long?): Response =
        awaitResponse("matching $url", timeoutMs) { waitUrlPatternMatches(url, it.url) }

    override suspend fun waitForResponse(url: Regex, timeoutMs: Long?): Response =
        awaitResponse("matching /${url.pattern}/", timeoutMs) { url.containsMatchIn(it.url) }

    override suspend fun waitForResponse(timeoutMs: Long?, predicate: (Response) -> Boolean): Response =
        awaitResponse("accepted by the predicate", timeoutMs, predicate)

    private suspend fun awaitResponse(description: String, timeoutMs: Long?, matches: (Response) -> Boolean): Response {
        val timeout = config.defaultTimeout(timeoutMs)
        return withKmpwrightTimeout(timeout, { "Timeout ${timeout}ms exceeded waiting for a response $description" }) {
            responseFlow.first(matches)
        }
    }

    // ------------------------------------------------------------------ routing

    override suspend fun route(url: String, handler: suspend (Route) -> Unit) {
        addRoute(url, urlPatternRegex(url), handler)
        rememberSetup("route:$url") { it.route(url, handler) }
        controller.syncRouteHandlers()
    }

    override suspend fun route(url: Regex, handler: suspend (Route) -> Unit) {
        val key = regexRouteKey(url)
        addRoute(key, Regex(".*(?:" + url.pattern + ").*", url.options), handler)
        rememberSetup("route:$key") { it.route(url, handler) }
        controller.syncRouteHandlers()
    }

    private fun regexRouteKey(url: Regex) = "regex:" + url.pattern

    /**
     * Registers (or re-registers, making it the newest) one route. Only the changed route is
     * touched on the backend so other routes stay active throughout (no interception gap).
     */
    private fun addRoute(key: String, regex: Regex, handler: suspend (Route) -> Unit) {
        checkPageOpen()
        var replaced: Pair<Regex, suspend (Route) -> Unit>?
        while (true) {
            val before = routeHandlers.value
            replaced = before[key]
            // remove-then-put moves an existing key to the end (newest registration).
            if (routeHandlers.compareAndSet(before, before.remove(key).put(key, regex to handler))) break
        }
        replaced?.let { controller.removeRouteHandler(it.first.pattern) }
        controller.addRouteHandler(regex.pattern) { route -> handler(route) }
    }

    override suspend fun unrouteAll() {
        routeHandlers.value = persistentMapOf()
        inheritedSetup.forgetPrefix("route:")
        controller.removeAllRouteHandlers()
        controller.syncRouteHandlers()
    }

    override suspend fun unroute(url: Regex) {
        removeRoute(regexRouteKey(url))
        controller.syncRouteHandlers()
    }

    override suspend fun unroute(url: String) {
        removeRoute(url)
        controller.syncRouteHandlers()
    }

    private fun removeRoute(urlPattern: String) {
        var removed: Pair<Regex, suspend (Route) -> Unit>?
        while (true) {
            val before = routeHandlers.value
            removed = before[urlPattern]
            if (routeHandlers.compareAndSet(before, before.remove(urlPattern))) break
        }
        forgetSetup("route:$urlPattern")
        removed?.let { controller.removeRouteHandler(it.first.pattern) }
    }

    private val readAllowedDomains = atomic<Set<String>>(emptySet())
    private val httpReader by lazy { HttpReader(config) }
    override suspend fun read(url: String?, options: ReadOptions): ReadResult {
        if (!config.allowDomExtraction) throw KmpwrightException("DOM extraction is disabled")
        if (url == null) return httpReader.activeHtml(currentPageUrl().orEmpty(), content(), options)
        val domains = readAllowedDomains.value
        return httpReader.readChecked(url, options) { target ->
            checkPolicy(ActionType.Navigate, target)
            if (!DomainFilter(domains).isAllowed(canonicalHttpHost(target).orEmpty())) throw KmpwrightException("HTTP read domain is not allowed")
        }
    }

    private val reactDelegate = lazy {
        PageReactInspector(this, controller, config.timeouts.default) { extraction ->
            if (!config.allowEval) throw KmpwrightException("JavaScript evaluation is disabled")
            if (extraction && !config.allowDomExtraction) throw KmpwrightException("DOM extraction is disabled")
            checkPolicy(ActionType.Evaluate, currentPageUrl())
        }
    }
    override val react: ReactInspector get() = reactDelegate.value

    private val vitalsDelegate by lazy { PageVitalsDelegate(controller) }

    override suspend fun pushState(url: String, timeoutMs: Long?): NavigationResult {
        val timeout = config.navigationTimeout(timeoutMs)
        require(timeout > 0) { "Navigation timeout must be positive" }
        if (!config.allowEval) throw KmpwrightException("JavaScript evaluation is disabled")
        val target = evalString("new URL(${jsStringLiteral(url)}, location.href).href")
            ?: throw KmpwrightException("Could not resolve navigation URL")
        checkPolicy(ActionType.Navigate, target)
        if (config.allowedOriginRules.isNotEmpty() && config.allowedOriginRules.none { originRuleMatches(it, target) }) {
            throw KmpwrightException("Navigation origin is not allowed by KmpwrightConfig: $target")
        }
        withKmpwrightTimeout(timeout, { "Timeout waiting for router navigation: $target" }) {
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
            """.trimIndent(), timeout)
            if (!awaitPageCondition(controller, "location.href === ${jsStringLiteral(target)}", timeout)) {
                throw KmpwrightTimeoutException("Timeout waiting for router navigation: $target")
            }
        }
        sameDocumentNavigated(target)
        return NavigationResult(target, 200, true)
    }

    override suspend fun webVitals(options: WebVitalsOptions): WebVitalsReport {
        if (!config.allowEval) throw KmpwrightException("JavaScript evaluation is disabled")
        checkPolicy(ActionType.Evaluate, currentPageUrl())
        return vitalsDelegate.capture(this, options.copy(timeoutMs = config.defaultTimeout(options.timeoutMs)))
    }

    override suspend fun metrics(): PageMetrics {
        if (!config.allowDomExtraction) throw KmpwrightException("DOM extraction is disabled")
        checkPolicy(ActionType.ReadContent, currentPageUrl())
        val values = evalString("""
            JSON.stringify({
                resources: performance.getEntriesByType('resource').length,
                jsHeapUsedSize: performance.memory ? performance.memory.usedJSHeapSize : null,
                jsHeapTotalSize: performance.memory ? performance.memory.totalJSHeapSize : null
            })
        """.trimIndent())?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }
        fun number(name: String) = values?.get(name)?.jsonPrimitive?.content?.toLongOrNull()
        return PageMetrics(
            resources = number("resources")?.toInt(),
            jsHeapUsedSize = number("jsHeapUsedSize"),
            jsHeapTotalSize = number("jsHeapTotalSize"),
            networkIdle = controller.getPendingRequestCount() == 0
        )
    }

    override suspend fun serviceWorkers(): List<ServiceWorker> = emptyList()

    override fun onDialog(handler: suspend (DialogEvent) -> DialogResponse): DialogHandler {
        val registration = DialogRegistration(handler)
        while (true) {
            val before = dialogHandlers.value
            if (dialogHandlers.compareAndSet(before, before.add(registration))) break
        }
        return object : DialogHandler {
            override fun close() {
                while (true) {
                    val before = dialogHandlers.value
                    val index = before.indexOfFirst { it === registration }
                    if (index < 0) return
                    if (dialogHandlers.compareAndSet(before, before.removeAt(index))) return
                }
            }
        }
    }

    override suspend fun pdf(options: PdfOptions): PdfResult {
        checkPolicy(ActionType.Screenshot, currentPageUrl())
        controller.validatePdfOptions(options)
        val timeout = config.defaultTimeout(options.timeoutMs)
        return awaitPageOperation(closeSignal) {
            withKmpwrightTimeout(timeout, { "PDF generation timed out after ${timeout}ms" }) { PdfResult(controller.generatePdfAsync(options)) }
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
        if (!config.allowEval) throw KmpwrightException("Script insertion is disabled by KmpwrightConfig.allowEval")
        if (options.url == null && options.content == null) throw KmpwrightException("Either url or content must be provided for addScriptTag")
        val scriptId = options.id.ifEmpty { "script-${currentTimeMillis()}" }
        val result = evalString("""
            (function() {
                try {
                    var node = document.createElement('script');
                    node.type = ${jsStringLiteral(options.type)};
                    node.id = ${jsStringLiteral(scriptId)};
                    node.async = ${options.async};
                    node.defer = ${options.defer};
                    ${options.url?.let { "node.src = ${jsStringLiteral(it)};" } ?: ""}
                    ${options.content?.let { "node.text = ${jsStringLiteral(it)};" } ?: ""}
                    (document.head || document.documentElement).appendChild(node);
                    return ${jsStringLiteral(scriptId)};
                } catch (e) {
                    return 'error: ' + e.message;
                }
            })()
        """.trimIndent()) ?: scriptId
        if (result.startsWith("error:")) throw KmpwrightException(result.substring(6).trim())
        return result
    }

    override suspend fun addStyleTag(options: StyleTagOptions): String {
        checkPolicy(ActionType.AddStyleTag, currentPageUrl())
        if (options.url == null && options.content == null) throw KmpwrightException("Either url or content must be provided for addStyleTag")
        val styleId = options.id.ifEmpty { "style-${currentTimeMillis()}" }
        val result = evalString("""
            (function() {
                try {
                    var node;
                    if (${options.url != null}) {
                        node = document.createElement('link');
                        node.rel = 'stylesheet';
                        node.href = ${jsStringLiteral(options.url.orEmpty())};
                    } else {
                        node = document.createElement('style');
                        node.textContent = ${jsStringLiteral(options.content.orEmpty())};
                    }
                    node.id = ${jsStringLiteral(styleId)};
                    (document.head || document.documentElement).appendChild(node);
                    return ${jsStringLiteral(styleId)};
                } catch (e) {
                    return 'error: ' + e.message;
                }
            })()
        """.trimIndent()) ?: styleId
        if (result.startsWith("error:")) throw KmpwrightException(result.substring(6).trim())
        return result
    }

    // ------------------------------------------------------------------ cookies

    override suspend fun cookies(urls: List<String>): List<Cookie> {
        checkPolicy(ActionType.ReadContent, currentPageUrl())
        val all = readAllCookies()
        if (urls.isEmpty()) return all
        return all.filter { cookie -> urls.any { cookieMatchesUrl(cookie, it) } }
    }

    private suspend fun readAllCookies(): List<Cookie> = awaitPageOperation(closeSignal) {
        withKmpwrightTimeout(config.timeouts.default, { "Timeout reading cookies" }) { controller.getAllCookiesAsync() }
    }

    override suspend fun addCookies(cookies: List<Cookie>) {
        checkPolicy(ActionType.SetCookie, currentPageUrl())
        cookies.forEach { validateCookie(it) }
        cookies.forEach { controller.setCookieAsync(it) }
    }

    override suspend fun clearCookies(name: String?, domain: String?) {
        checkPolicy(ActionType.DeleteCookie, currentPageUrl())
        if (name == null && domain == null) {
            controller.deleteAllCookiesAsync()
            return
        }
        readAllCookies()
            .filter { (name == null || it.name == name) && (domain == null || it.domain.trimStart('.') == domain.trimStart('.')) }
            .forEach { controller.deleteCookieAsync(it) }
    }

    // ------------------------------------------------------------------ scrolling

    override suspend fun scroll(direction: ScrollDirection, amount: Double) {
        checkPolicy(ActionType.Scroll, currentPageUrl())
        scrollDelegate.scroll(direction, amount)
    }
    override suspend fun scrollToTop() {
        checkPolicy(ActionType.Scroll, currentPageUrl())
        scrollDelegate.scrollToTop()
    }
    override suspend fun scrollToBottom() {
        checkPolicy(ActionType.Scroll, currentPageUrl())
        scrollDelegate.scrollToBottom()
    }

    // ------------------------------------------------------------------ accessibility snapshot

    private val snapshotHistory = atomic<Map<String, String>>(emptyMap())

    private val auditDelegate by lazy { PageAccessibilityAudit(controller) }

    override suspend fun accessibilityAudit(options: AccessibilityAuditOptions): AccessibilityAuditReport {
        if (!config.allowDomExtraction) throw KmpwrightException("DOM extraction is disabled")
        if (!config.allowEval) throw KmpwrightException("JavaScript evaluation is disabled")
        checkPolicy(ActionType.Evaluate, currentPageUrl())
        return auditDelegate.run(options.copy(timeoutMs = config.defaultTimeout(options.timeoutMs)))
    }

    /** Runs [body] with the page runtime bound to `H`, installing it on demand. */
    private suspend fun runWithRuntime(body: String): kotlinx.serialization.json.JsonElement {
        repeat(3) {
            when (val outcome = PageHelpers.decode(evalString(PageHelpers.envelope(body)))) {
                is PageHelpers.Outcome.Value -> return outcome.value
                is PageHelpers.Outcome.Error -> throw KmpwrightException(outcome.message)
                PageHelpers.Outcome.Tampered -> throw KmpwrightException("The page replaced or pre-defined the KMPwright runtime slot; refusing to automate this document")
                PageHelpers.Outcome.Missing -> {
                    if (config.installMode == InstallMode.Disabled) throw KmpwrightException("Selector helpers are disabled by KmpwrightConfig.installMode")
                    if (evalString(PageHelpers.installScript()) == PageHelpers.TAMPERED) {
                        throw KmpwrightException("The page replaced or pre-defined the KMPwright runtime slot; refusing to automate this document")
                    }
                }
            }
        }
        throw KmpwrightException("Could not install the KMPwright page runtime")
    }

    override suspend fun snapshot(options: SnapshotOptions): Snapshot {
        checkPolicy(ActionType.ReadContent, currentPageUrl())
        if (!config.allowDomExtraction) throw KmpwrightException("DOM extraction is disabled by KmpwrightConfig.allowDomExtraction")
        withKmpwrightTimeout(config.timeouts.default, { "Timeout preparing the accessibility snapshot" }) { controller.prepareAccessibilitySnapshot() }
        val value = runWithRuntime(AccessibilitySnapshotEngine.snapshotBody(options))
        // Custom bridges that report nothing yield an empty snapshot; malformed data throws.
        val raw = PageHelpers.valueAsString(value) ?: "null"
        val error = runCatching { Json.parseToJsonElement(raw).jsonObject["error"]?.jsonPrimitive?.content }.getOrNull()
        if (error != null) throw KmpwrightException(error)
        val snapshot = AccessibilitySnapshotEngine.parseSnapshotJson(raw, options)
        currentSnapshotRefMap = snapshot.refs
        // Auto-save as baseline for diffing
        updateSnapshotHistory(BASELINE_SNAPSHOT, snapshot.tree)
        return snapshot
    }

    private fun updateSnapshotHistory(name: String, tree: String) {
        while (true) {
            val before = snapshotHistory.value
            if (snapshotHistory.compareAndSet(before, before + (name to tree))) return
        }
    }


    // ------------------------------------------------------------------ policy

    override fun setActionPolicy(policy: ActionPolicy) {
        actionPolicy.value = policy
        // Keep the backend's synchronous navigation boundary in step with
        // policy changes so redirects and browser-initiated navigations are
        // checked before they can become the committed page.
        controller.setNavigationGuard { target -> navigationGuardAllows(target) }
    }

    override fun setPolicyConfirmCallback(callback: (suspend (ActionType, String?) -> Boolean)?) {
        policyConfirmCallback.value = callback
    }

    internal suspend fun checkPolicy(action: ActionType, url: String? = null) {
        checkPageOpen()
        val policy = actionPolicy.value ?: return
        when (policy.check(action, url)) {
            PolicyResult.Allow -> { /* ok */ }
            PolicyResult.Deny -> throw KmpwrightException("Action denied by policy: $action")
            PolicyResult.Confirm -> {
                val callback = policyConfirmCallback.value
                    ?: throw KmpwrightException("Action $action requires confirmation but no confirm callback is set")
                if (!callback(action, url)) {
                    throw KmpwrightException("Action denied by user confirmation: $action")
                }
            }
        }
    }

    // ------------------------------------------------------------------ emulation

    override suspend fun emulateMedia(colorScheme: ColorScheme?) {
        checkPolicy(ActionType.Emulation, currentPageUrl())
        emulationDelegate.emulateMedia(colorScheme)
        rememberSetup("color") { it.emulateMedia(colorScheme) }
    }

    override suspend fun setGeolocation(geolocation: Geolocation?) {
        checkPolicy(ActionType.Emulation, currentPageUrl())
        emulationDelegate.setGeolocation(geolocation)
        rememberSetup("geo") { it.setGeolocation(geolocation) }
    }

    override suspend fun setOffline(offline: Boolean) {
        checkPolicy(ActionType.Emulation, currentPageUrl())
        emulationDelegate.emulateNetworkConditions(offline, 0, 0, 0)
        rememberSetup("network") { it.setOffline(offline) }
    }

    override suspend fun emulateNetworkConditions(conditions: NetworkConditions?) {
        checkPolicy(ActionType.Emulation, currentPageUrl())
        val applied = conditions ?: NetworkConditions()
        emulationDelegate.emulateNetworkConditions(applied.offline, applied.latencyMs, applied.downloadThroughput, applied.uploadThroughput)
        rememberSetup("network") { it.emulateNetworkConditions(conditions) }
    }

    override suspend fun setUserAgent(userAgent: String) {
        checkPolicy(ActionType.Emulation, currentPageUrl())
        emulationDelegate.setUserAgent(userAgent)
        rememberSetup("userAgent") { it.setUserAgent(userAgent) }
    }
    override suspend fun emulateDevice(device: DeviceDescriptor) {
        checkPolicy(ActionType.Emulation, currentPageUrl())
        emulationDelegate.emulateDevice(device)
        rememberSetup("device") { it.emulateDevice(device) }
    }
    override suspend fun setViewportSize(width: Int, height: Int) {
        checkPolicy(ActionType.Emulation, currentPageUrl())
        emulationDelegate.setViewportSize(width, height)
        rememberSetup("viewport") { it.setViewportSize(width, height) }
    }
    override fun viewportSize(): ViewportSize = emulationDelegate.viewportSize()
    override suspend fun setDeviceScaleFactor(scale: Double) {
        checkPolicy(ActionType.Emulation, currentPageUrl())
        emulationDelegate.setDeviceScaleFactor(scale)
        rememberSetup("scale") { it.setDeviceScaleFactor(scale) }
    }

    override suspend fun setExtraHTTPHeaders(headers: Map<String, String>) {
        checkPolicy(ActionType.Network, currentPageUrl())
        val copied = headers.toMap()
        httpDelegate.setExtraHTTPHeaders(copied)
        rememberSetup("headers") { it.setExtraHTTPHeaders(copied) }
    }
    override suspend fun setHTTPCredentials(credentials: HttpCredentials?) {
        checkPolicy(ActionType.Network, currentPageUrl())
        if (credentials == null) httpDelegate.clearHTTPCredentials()
        else httpDelegate.setHTTPCredentials(credentials.username, credentials.password)
        rememberSetup("credentials") { it.setHTTPCredentials(credentials) }
    }

    override suspend fun waitForText(text: String, timeoutMs: Long?) {
        checkPolicy(ActionType.ReadContent, currentPageUrl())
        val timeout = config.defaultTimeout(timeoutMs)
        val conditionJs = "(function() { return !!document.body && document.body.innerText.indexOf(${jsStringLiteral(text)}) !== -1; })()"
        if (!awaitPageOperation(closeSignal) { awaitPageCondition(controller, conditionJs, timeout) }) {
            throw KmpwrightTimeoutException("Timeout ${timeout}ms exceeded waiting for text \"$text\"")
        }
    }

    override suspend fun waitForFunction(expression: String, timeoutMs: Long?): JsResult {
        checkPolicy(ActionType.Evaluate, currentPageUrl())
        if (!config.allowEval) throw KmpwrightException("JavaScript evaluation is disabled by KmpwrightConfig.allowEval")
        val timeout = config.defaultTimeout(timeoutMs)
        if (timeout <= 0) throw KmpwrightException("Timeout must be positive, was ${timeout}ms")
        // Playwright semantics: evaluate once per poll, await a returned Promise (and call a returned
        // function), and resolve with the first truthy value. Exceptions thrown by the expression fail
        // the wait immediately.
        val probe = """
            (async function(){
              var v = await (
            ${expression.trim().removeSuffix(";")}
              );
              if (typeof v === 'function') v = await v();
              if (!v) return JSON.stringify({ok: false});
              var t = v === null ? 'null' : Array.isArray(v) ? 'array' : typeof v, s;
              if (t === 'string') s = v;
              else if (t === 'function' || t === 'symbol') s = String(v);
              else { try { s = JSON.stringify(v); } catch (e) { s = String(v); } if (s === undefined) s = String(v); }
              return JSON.stringify({ok: true, t: t, v: s});
            })().catch(function(e){ return JSON.stringify({ok: false, e: String((e && e.message) || e)}); })
        """.trimIndent()
        val started = TimeSource.Monotonic.markNow()
        var attempt = 0
        return awaitPageOperation(closeSignal) {
            while (true) {
                checkPageOpen()
                val remaining = timeout - started.elapsedNow().inWholeMilliseconds
                if (remaining <= 0) throw KmpwrightTimeoutException("Timeout ${timeout}ms exceeded waiting for JavaScript condition")
                val raw = withKmpwrightTimeout(remaining, { "Timeout ${timeout}ms exceeded waiting for JavaScript condition" }) {
                    if (controller.supportsPromiseEvaluation) controller.normalizeJavaScriptResult(controller.evaluateJavaScriptAsync(probe))
                    else scriptTask.evaluate(probe, remaining)
                }
                val envelope = raw?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }?.takeIf { it.containsKey("ok") }
                envelope?.get("e")?.jsonPrimitive?.content?.let { throw KmpwrightException("waitForFunction failed: $it") }
                if (envelope == null && raw != null && raw !in FALSY_RAW_RESULTS) {
                    // Custom bridges that cannot return the envelope: report the raw truthy value.
                    return@awaitPageOperation JsResult(raw, "unknown")
                }
                if (envelope?.get("ok")?.jsonPrimitive?.content == "true") {
                    return@awaitPageOperation JsResult(
                        envelope["v"]?.jsonPrimitive?.content ?: "null",
                        envelope["t"]?.jsonPrimitive?.content ?: "unknown"
                    )
                }
                val wait = WAIT_FOR_FUNCTION_POLL_MS[minOf(attempt++, WAIT_FOR_FUNCTION_POLL_MS.lastIndex)]
                delay(minOf(wait, (timeout - started.elapsedNow().inWholeMilliseconds).coerceAtLeast(1)))
            }
            @Suppress("UNREACHABLE_CODE") JsResult("null", "undefined")
        }
    }

    /** `screenshot(annotate = true)`: labels every snapshot ref on the image (agent-browser `screenshot --annotate`). */
    private suspend fun annotatedScreenshot(options: ScreenshotOptions): ScreenshotResult {
        val snapshot = snapshot()
        val entries = snapshot.refs.values.toList()
        val refs = kotlinx.serialization.json.buildJsonArray { entries.forEach { add(JsonPrimitive(it.ref)) } }
        val boxes = runWithRuntime("""
            var refs = $refs, labels = [];
            for (var i = 0; i < refs.length; i++) {
                var el = H.refElement(refs[i]);
                if (!el) continue;
                var r = el.getBoundingClientRect();
                if (!(r.width > 0 && r.height > 0)) continue;
                // Elements inside same-origin iframes: shift by each frame's content box.
                var dx = 0, dy = 0, frame = el.ownerDocument && el.ownerDocument.defaultView && el.ownerDocument.defaultView.frameElement;
                while (frame) {
                    var fr = frame.getBoundingClientRect();
                    dx += fr.left + frame.clientLeft; dy += fr.top + frame.clientTop;
                    frame = frame.ownerDocument && frame.ownerDocument.defaultView && frame.ownerDocument.defaultView.frameElement;
                }
                r = {left: r.left + dx, top: r.top + dy, width: r.width, height: r.height};
                var lbl = document.createElement('div');
                // Label [N] stands for ref @eN, like agent-browser.
                lbl.textContent = refs[i].slice(2);
                lbl.setAttribute('data-ww-anno', 'true');
                lbl.style.cssText = 'position:absolute;top:' + (r.top + window.scrollY) + 'px;left:' + (r.left + window.scrollX) + 'px;background:rgba(255,0,0,0.8);color:white;padding:2px 4px;font-size:12px;font-weight:bold;z-index:2147483647;pointer-events:none;border-radius:2px;';
                (document.body || document.documentElement).appendChild(lbl);
                labels.push({i: i, x: r.left, y: r.top, w: r.width, h: r.height});
            }
            return JSON.stringify(labels);
        """.trimIndent())
        val bboxes = try {
            Json.decodeFromString<List<Map<String, Double>>>(PageHelpers.valueAsString(boxes) ?: "[]")
        } catch (e: Exception) {
            throw KmpwrightException("Could not decode annotation boxes", e)
        }

        val screenshotResult = try {
            captureScreenshot(options.copy(annotate = false))
        } finally {
            withContext(NonCancellable) {
                runCatching { evalString("(function() { document.querySelectorAll('[data-ww-anno]').forEach(function(el) { el.remove(); }); })()") }
            }
        }

        val annotations = bboxes.mapNotNull { bbox ->
            val idx = bbox["i"]?.toInt() ?: return@mapNotNull null
            val entry = entries.getOrNull(idx) ?: return@mapNotNull null
            idx to Annotation(
                ref = entry.ref,
                role = entry.role,
                name = entry.name,
                box = Rectangle(bbox["x"] ?: 0.0, bbox["y"] ?: 0.0, bbox["w"] ?: 0.0, bbox["h"] ?: 0.0)
            )
        }.sortedBy { it.first }.map { it.second }

        return screenshotResult.copy(annotations = annotations)
    }

    // Frame locator
    override fun frameLocator(selector: String): FrameLocator {
        return BaseFrameLocator(controller, selector, config).also {
            it.policyChecker = { action -> checkPolicy(action, currentPageUrl()) }
            it.lifetime = closeSignal
        }
    }

    override fun mainFrame(): FrameLocator {
        return BaseFrameLocator(controller, "", config).also {
            it.policyChecker = { action -> checkPolicy(action, currentPageUrl()) }
            it.lifetime = closeSignal
        }
    }

    // Snapshot diff
    override suspend fun diffSnapshot(before: Snapshot, after: Snapshot): SnapshotDiffResult {
        return AccessibilitySnapshotEngine.diffSnapshots(before.tree, after.tree)
    }

    /**
     * Diffs the current snapshot against the one saved under [name], then stores the current one.
     * The first call for a new name records the baseline and reports no changes.
     */
    override suspend fun diffSnapshot(name: String): SnapshotDiffResult {
        val previous = snapshotHistory.value[name]
        val current = snapshot()
        if (name != BASELINE_SNAPSHOT) updateSnapshotHistory(name, current.tree)
        val baseline = previous ?: current.tree
        return AccessibilitySnapshotEngine.diffSnapshots(baseline, current.tree)
    }

    override suspend fun diffUrls(url1: String, url2: String, timeoutMs: Long?): SnapshotDiffResult {
        if (!config.allowDomExtraction) throw KmpwrightException("DOM extraction is disabled")
        val timeout = config.defaultTimeout(timeoutMs)
        return try {
            withKmpwrightTimeout(timeout, { "Timeout diffing URLs: $url1 vs $url2" }) {
                val html1 = read(url1, ReadOptions(raw = true, timeoutMs = timeout)).content
                val html2 = read(url2, ReadOptions(raw = true, timeoutMs = timeout)).content
                AccessibilitySnapshotEngine.diffSnapshots(htmlToSnapshotText(html1), htmlToSnapshotText(html2))
            }
        } catch (e: KmpwrightException) {
            throw e
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            throw KmpwrightException("Failed diffing URLs: ${e.message}", e)
        }
    }

    override val clipboard: Clipboard = object : Clipboard {
        override suspend fun readText(): String? = clipboardRead()
        override suspend fun writeText(text: String) = clipboardWrite(text)
    }

    /** Reads the clipboard; returns null when the page is not allowed to read it. */
    private suspend fun clipboardRead(): String? {
        checkPolicy(ActionType.Clipboard, currentPageUrl())
        val result = evaluateInternal("""
            (async function() {
                try { return await navigator.clipboard.readText(); }
                catch (e) { return null; }
            })()
        """.trimIndent(), config.timeouts.default)
        return result.value.takeUnless { result.type == "null" || result.type == "undefined" }
    }

    private suspend fun clipboardWrite(text: String) {
        checkPolicy(ActionType.Clipboard, currentPageUrl())
        val result = evaluateInternal("""
            (async function() {
                var text = ${jsStringLiteral(text)};
                try { await navigator.clipboard.writeText(text); return true; }
                catch (e) {
                    var ta = document.createElement('textarea');
                    ta.value = text;
                    ta.style.position = 'fixed';
                    ta.style.opacity = '0';
                    (document.body || document.documentElement).appendChild(ta);
                    ta.select();
                    var ok = false;
                    try { ok = document.execCommand('copy'); } finally { ta.remove(); }
                    return ok;
                }
            })()
        """.trimIndent(), config.timeouts.default)
        if (result.type == "boolean" && result.value == "false") throw KmpwrightException("The page could not write to the clipboard")
    }

    // ------------------------------------------------------------------ storage state

    override suspend fun storageState(): StorageState {
        checkPolicy(ActionType.ReadContent, currentPageUrl())
        return StorageState(
            url = currentPageUrl(),
            cookies = readAllCookies(),
            localStorage = localStorageArea.readEntries(),
            sessionStorage = sessionStorageArea.readEntries()
        )
    }

    override suspend fun setStorageState(state: StorageState) {
        val stateUrl = state.url
        val cookies = state.cookies
        val localStorage = state.localStorage
        val sessionStorage = state.sessionStorage
        cookies.forEach { validateCookie(it) }
        val restoreUrl = stateUrl?.takeIf(::supportsOriginScopedStateRestore)
        if (restoreUrl == null && (localStorage.isNotEmpty() || sessionStorage.isNotEmpty())) {
            throw KmpwrightException("Cannot restore localStorage/sessionStorage without a saved http(s) or file URL.")
        }
        // Preflight the entire restore before changing any cookie or storage. Use the
        // approved operations below so confirmation callbacks are never asked twice.
        val currentUrl = currentPageUrl()
        checkPolicy(ActionType.DeleteCookie, currentUrl)
        checkPolicy(ActionType.SetCookie, currentUrl)
        if (restoreUrl != null) {
            validateNavigationOrigin(restoreUrl)
            checkPolicy(ActionType.Storage, restoreUrl)
            if (currentUrl != restoreUrl) checkPolicy(ActionType.Navigate, restoreUrl)
        }
        controller.deleteAllCookiesAsync()
        cookies.forEach { controller.setCookieAsync(it) }

        // A saved empty map clears later storage for the saved origin too.
        if (restoreUrl == null) return

        if (currentPageUrl() != restoreUrl) {
            val navigation = gotoApproved(restoreUrl, GotoOptions())
            if (!navigation.committed) {
                throw KmpwrightException(
                    "Failed to navigate to saved state URL $restoreUrl: ${navigation.error ?: "unknown error"}"
                )
            }
        }

        localStorageArea.replaceAll(localStorage)
        sessionStorageArea.replaceAll(sessionStorage)
    }

    override suspend fun autosaveStorageState(options: StorageStateAutosaveOptions): StorageStateAutosave {
        checkPageOpen()
        return StorageStateAutosave(pageScope, options.intervalMs) {
            checkPageOpen()
            storageState().save(options.path, options.password)
        }.start()
    }

    override suspend fun login(profile: AuthProfile, options: AuthLoginOptions): AuthLoginResult = loginWithProfile(profile, options)

    private fun currentPageUrl(): String? = controller.getUrl() ?: url.value

    /** Synchronous backend guard: never throws (a closed page simply allows nothing). */
    private fun navigationGuardAllows(targetUrl: String): Boolean {
        if (closed.value) return false
        val policy = actionPolicy.value ?: return true
        return when (policy.check(ActionType.Navigate, targetUrl)) {
            PolicyResult.Allow -> true
            PolicyResult.Deny -> false
            PolicyResult.Confirm -> approvedNavigationUrl.value == targetUrl
        }
    }

    private fun supportsOriginScopedStateRestore(url: String): Boolean {
        return url.startsWith("https://") ||
            url.startsWith("http://") ||
            url.startsWith("file://")
    }

    override fun consoleMessages(filter: PageMessageFilter): List<ConsoleMessage> =
        consoleHistory.read(filter, documentGeneration.value)

    override fun pageErrors(filter: PageMessageFilter): List<String> =
        pageErrorHistory.read(filter, documentGeneration.value).map { it.text }

    override fun clearConsoleMessages() = consoleHistory.clear()

    override fun clearPageErrors() = pageErrorHistory.clear()

    /**
     * Called by platform layers to report a console message. The message is emitted to
     * [PageEvents.console] and kept in a bounded history (the last [MAX_CONSOLE_MESSAGES]) for [consoleMessages].
     */
    internal fun reportConsoleMessage(message: ConsoleMessage) {
        if (closed.value) return
        consoleHistory.add(message, documentGeneration.value)
        sinks.consoleSink.tryEmit(message)
    }

    /** Uncaught exception or unhandled rejection: [PageEvents.pageError] and [pageErrors]. */
    internal fun reportPageError(error: PageError) {
        if (closed.value) return
        pageErrorHistory.add(error, documentGeneration.value)
        sinks.pageErrorSink.tryEmit(error)
    }

    /** A new main-frame document committed: starts the [PageMessageFilter.SinceNavigation] window. */
    internal fun markDocumentCommitted() {
        documentGeneration.incrementAndGet()
    }

    internal fun reportDownload(download: Download) {
        sinks.downloadSink.tryEmit(download)
    }

    internal fun reportFileChooser(chooser: FileChooser) {
        sinks.fileChooserSink.tryEmit(chooser)
    }

    override suspend fun waitForDownload(url: String?, timeoutMs: Long?): Download {
        val timeout = config.defaultTimeout(timeoutMs)
        return withKmpwrightTimeout(timeout, { "Timeout ${timeout}ms exceeded waiting for download${if (url != null) " matching $url" else ""}" }) {
            if (url != null) events.download.first { waitUrlPatternMatches(url, it.url) } else events.download.first()
        }
    }

    override suspend fun waitForFileChooser(timeoutMs: Long?): FileChooser {
        val timeout = config.defaultTimeout(timeoutMs)
        return withKmpwrightTimeout(timeout, { "Timeout ${timeout}ms exceeded waiting for file chooser" }) {
            events.fileChooser.first()
        }
    }

    override suspend fun setFileChooserInterception(enabled: Boolean): Unit =
        throw KmpwrightUnsupportedException("File chooser interception is unavailable on this backend")

    /** Backends that manage downloads override these; the common page has nothing to configure. */
    override suspend fun setDownloadPath(path: String) {}

    override suspend fun setDownloadBehavior(behavior: DownloadBehavior) {}

    override suspend fun openNewTab(url: String?): NavigationResult {
        val runtime = tabRuntime
            ?: throw KmpwrightException("Tab management is not supported for pages detached from a browser runtime.")
        return runtime.openTab(this, url)
    }

    override fun dispose() {
        if (!closed.compareAndSet(false, true)) return
        beginPageClose()
        if (reactDelegate.isInitialized()) runCatching { reactDelegate.value.dispose() }
        finishClose(disposeController = true)
    }

    override suspend fun close() {
        if (!closed.compareAndSet(false, true)) return
        beginPageClose()
        try {
            if (reactDelegate.isInitialized()) reactDelegate.value.disposeAsync()
        } finally {
            try { withContext(NonCancellable) { controller.disposeAsync() } }
            finally { finishClose(disposeController = false) }
        }
    }

    /** Releases every owner exactly once; [disposeController] is false when [close] already disposed it. */
    private fun finishClose(disposeController: Boolean) {
        inheritedSetup.clear()
        networkHistory.clear()
        harCapture.dispose()
        // Teardown is deliberately stepwise.  A controller is supplied by a
        // platform boundary and one failed best-effort operation must never
        // prevent later operations from releasing their ownership.
        runCatching { dialogHandlers.value = persistentListOf() }
        runCatching { routeHandlers.value = persistentMapOf() }
        // A navigation callback can otherwise retain this page through the
        // callback closure until the next navigation (or forever on a dead target).
        runCatching { controller.setNavigationCallback(null) }
        runCatching { controller.setNavigationGuard(null) }
        runCatching { controller.removeAllRouteHandlers() }
        runCatching { controller.removeRequestListener(requestListener) }
        runCatching { controller.removeDialogListener(dialogListener) }
        runCatching { controller.removeDiagnosticsListener(diagnosticsListener) }
        if (disposeController) runCatching { controller.dispose() }
        runCatching { pageScope.cancel() }
        runCatching { tabRuntime?.pageClosed(this) }
    }

    // Platform tracing, profiling and recording require a backend implementation.
    protected open suspend fun startTracing(options: TracingOptions): Unit = throw KmpwrightUnsupportedException("Tracing is unavailable on this backend")
    protected open suspend fun stopTracing(): ByteArray? = throw KmpwrightUnsupportedException("Tracing is unavailable on this backend")
    protected open suspend fun startProfiler(): Unit = throw KmpwrightUnsupportedException("Profiling is unavailable on this backend")
    protected open suspend fun stopProfiler(): String? = throw KmpwrightUnsupportedException("Profiling is unavailable on this backend")
    protected open suspend fun startRecording(options: RecordingOptions): Unit = throw KmpwrightUnsupportedException("Video recording is unavailable on this backend")
    protected open suspend fun stopRecording(): RecordingResult = throw KmpwrightUnsupportedException("Video recording is unavailable on this backend")
    override fun screencast(options: ScreencastOptions): kotlinx.coroutines.flow.Flow<ScreencastFrame> =
        throw KmpwrightUnsupportedException("Screencast is unavailable on this backend")

    override val tracing: Tracing = object : Tracing {
        override suspend fun start(options: TracingOptions) = startTracing(options)
        override suspend fun stop(): ByteArray? = stopTracing()
    }

    override val profiler: Profiler = object : Profiler {
        override suspend fun start() = startProfiler()
        override suspend fun stop(): String? = stopProfiler()
    }

    override val video: Video = object : Video {
        override suspend fun start(options: RecordingOptions) = startRecording(options)
        override suspend fun stop(): RecordingResult = stopRecording()
    }

    // --- Allowed domains ---

    /**
     * Prefers the backend's request-layer filter. Without one, installs the document-start JavaScript
     * fallback (see [Page.setAllowedDomains]); [capabilities] of such backends report the filter as Partial.
     */
    override suspend fun setAllowedDomains(domains: List<String>) {
        checkPolicy(ActionType.Network, currentPageUrl())
        val copied = domains.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        if (controller.setDomainFilter(copied)) {
            controller.setDomainFilterAsync(copied)
        } else {
            emulationDelegate.setDomainFilterFallback(copied)
        }
        readAllowedDomains.value = copied
        rememberSetup("domains") { it.setAllowedDomains(copied.toList()) }
    }

    /** Whether the active domain filter is only the JavaScript fallback (current document only when init scripts are unavailable). */
    internal val domainFilterIsJavaScriptFallback: Boolean
        get() = emulationDelegate.current.allowedDomains.isNotEmpty()

    // --- Content boundary ---

    override fun createContentBoundary(): ContentBoundary =
        ContentBoundary.create(url.value ?: "about:blank")

    override suspend fun boundedContent(boundary: ContentBoundary?): String {
        val b = boundary ?: createContentBoundary()
        return b.wrap(content())
    }

    override suspend fun boundedSnapshot(options: SnapshotOptions, boundary: ContentBoundary?): String {
        val b = boundary ?: createContentBoundary()
        return b.wrap(snapshot(options).tree)
    }

    /** The configuration this page was created with (used by top-level `expect`). */
    internal val pageConfig: KmpwrightConfig get() = config

    /** Live `document.title` and `location.href` for web-first page assertions. */
    internal suspend fun liveTitle(): String = evalString("document.title") ?: title.value.orEmpty()
    internal suspend fun liveUrl(): String = runCatching { evalString("location.href") }.getOrNull() ?: currentPageUrl().orEmpty()

    internal companion object {
        const val BASELINE_SNAPSHOT = "__baseline__"
        /** Playwright keeps (currently) the last 200 console messages and page errors. */
        const val MAX_CONSOLE_MESSAGES = 200
        const val HISTORY_PROBE_GRACE_MS = 750L
        /** HTTP status of the current document from Navigation Timing (0 when unavailable). */
        const val NAVIGATION_STATUS_JS = "(function(){try{var e=performance.getEntriesByType('navigation')[0];return (e&&e.responseStatus)||0;}catch(x){return 0;}})()"
        private val FALSY_RAW_RESULTS = setOf("", "false", "0", "-0", "null", "undefined", "NaN")
        /** waitForFunction polling backoff (Playwright polls every animation frame; this stays bridge-friendly). */
        val WAIT_FOR_FUNCTION_POLL_MS = longArrayOf(0, 20, 50, 100, 100)
    }
}

/** Compares URLs ignoring the fragment and a trailing slash. */
internal fun sameUrl(a: String, b: String): Boolean {
    fun canonical(url: String) = url.substringBefore('#').trimEnd('/')
    return canonical(a) == canonical(b)
}

/**
 * Heuristic: whether [script] is a single JavaScript expression (so it can be wrapped in parentheses
 * and awaited) rather than a statement list. Misclassifying an expression as statements is safe — it
 * is evaluated verbatim — so the check errs on that side.
 */
internal fun isSingleExpression(script: String): Boolean {
    val trimmed = script.trim().removeSuffix(";").trim()
    if (trimmed.isEmpty()) return false
    val statementStart = Regex("^(?:(?:var|let|const|if|for|while|do|switch|try|throw|return|class|import|export|debugger)\\b|(?:async\\s+)?function\\s+[A-Za-z_$])")
    if (statementStart.containsMatchIn(trimmed)) return false
    var depth = 0
    var quote: Char? = null
    var index = 0
    while (index < trimmed.length) {
        val char = trimmed[index]
        if (quote != null) {
            if (char == '\\') index++
            else if (char == quote) quote = null
        } else when (char) {
            '\'', '"', '`' -> quote = char
            '(', '[', '{' -> depth++
            ')', ']', '}' -> depth--
            ';' -> if (depth == 0) return false
            '/' -> if (index + 1 < trimmed.length && trimmed[index + 1] == '/') {
                // A line comment is fine inside the wrapper (it ends with a newline).
                index = trimmed.indexOf('\n', index).let { if (it < 0) trimmed.length else it }
                continue
            }
            '\n' -> if (depth == 0) {
                // Multiple top-level lines are likely statements unless the line continues an expression.
                val rest = trimmed.substring(index + 1).trimStart()
                val before = trimmed.substring(0, index).trimEnd()
                val continues = rest.startsWith(".") || rest.startsWith("?") || rest.startsWith(":") || rest.startsWith(")") ||
                    before.endsWith("(") || before.endsWith(",") || before.endsWith("+") || before.endsWith("&&") ||
                    before.endsWith("||") || before.endsWith("=") || before.endsWith("?") || before.endsWith(":") || before.endsWith("{")
                if (!continues) return false
            }
        }
        index++
    }
    return depth == 0 && quote == null
}

internal fun sanitizeStateFileName(name: String): String {
    // Remove path traversal components and separators, but keep harmless content
    return name.replace("\\", "").replace("/", "").replace("..", "").ifEmpty { "unnamed" }
}

/** Whether [cookie] would be sent to [url] (domain, path and `Secure` rules), for [Page.cookies]. */
internal fun cookieMatchesUrl(cookie: Cookie, url: String): Boolean {
    val host = canonicalHttpHost(url) ?: return false
    val domain = cookie.domain.trimStart('.').lowercase()
    val hostMatches = host == domain || (!cookie.hostOnly && host.endsWith(".$domain"))
    if (!hostMatches) return false
    if (cookie.secure && !url.startsWith("https://", ignoreCase = true)) return false
    val path = "/" + url.substringAfter("://").substringAfter('/', "").substringBefore('?').substringBefore('#')
    val cookiePath = cookie.path.ifEmpty { "/" }
    return cookiePath == "/" || path == cookiePath || path.startsWith(cookiePath.trimEnd('/') + "/")
}

/** Lock-free bounded history; entries are tagged with the document generation they arrived in. */
private class PageMessageHistory<T>(private val capacity: Int) {
    private class Entry<T>(val value: T, val generation: Long)
    private val entries = atomic<PersistentList<Entry<T>>>(persistentListOf())

    fun add(value: T, generation: Long) {
        while (true) {
            val before = entries.value
            val trimmed = if (before.size >= capacity) before.removeAt(0) else before
            if (entries.compareAndSet(before, trimmed.add(Entry(value, generation)))) return
        }
    }

    fun read(filter: PageMessageFilter, currentGeneration: Long): List<T> = entries.value
        .filter { filter == PageMessageFilter.All || it.generation == currentGeneration }
        .map { it.value }

    fun clear() { entries.value = persistentListOf() }
}
