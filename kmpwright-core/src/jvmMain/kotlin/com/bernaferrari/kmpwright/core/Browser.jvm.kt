package com.bernaferrari.kmpwright.core

import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.coroutines.resume
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException

internal data class CdpBootstrap(
    val connection: CdpConnection,
    val session: ProviderSession,
    val endpointScope: CdpEndpointScope
)

public actual class Browser public actual constructor(
    private val context: PlatformContext,
    private val config: KmpwrightConfig
) {
    private val ownedContexts = java.util.concurrent.CopyOnWriteArrayList<BrowserContext>()
    @Volatile private var leaseContext: BrowserContext? = null

    internal actual suspend fun beginIndependentLease() {
        check(leaseContext == null) { "Browser already has an active lease" }
        endIndependentLease()
        leaseContext = newContext()
    }

    internal actual suspend fun endIndependentLease() {
        ownedContexts.toList().forEach { it.close() }
        ownedContexts.clear()
        leaseContext = null
        val owned = pagesMutex.withLock { _pages.toList() }
        owned.forEach { it.close() }
    }

    private val _isConnected = MutableStateFlow(false)
    public actual val isConnected: StateFlow<Boolean> = _isConnected

    private val _connectionError = MutableStateFlow<String?>(null)
    public actual val connectionError: StateFlow<String?> = _connectionError

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + kotlinx.coroutines.CoroutineExceptionHandler { _, error ->
        _connectionError.value = error.message ?: error.toString()
        kmpwrightLogWarning("Browser background task failed", error)
    })

    @Volatile private var _pages = persistentListOf<Page>()
    @Volatile private var connection: CdpConnection? = null
    @Volatile private var providerSession: ProviderSession? = null
    @Volatile private var endpointScope: CdpEndpointScope? = null
    @Volatile private var connectionMonitor: kotlinx.coroutines.Job? = null
    private val connectionMutex = Mutex()
    private val pagesMutex = Mutex()
    /** Test seam for exercising serialized bootstrap without requiring a Chrome process. */
    internal var connectionBootstrapOverride: (suspend () -> CdpBootstrap)? = null
    internal var providerSessionReleaser: suspend (ProviderSession) -> Unit = { CloudProviders.releaseSession(it) }
    internal var endpointScopeResolverOverride: (suspend (CdpConnection) -> CdpEndpointScope)? = null
    internal var pageFactoryOverride: (suspend (CdpConnection, CdpPageSession) -> Page)? = null
    private val tabRuntime = object : PageTabRuntime {
        override suspend fun openTab(source: Page, url: String?): NavigationResult {
            val page = newPage()
            try { (source as? BasePage)?.inheritSetupTo(page) }
            catch (error: Throwable) { page.close(); throw error }
            return page.goto(url ?: "about:blank")
        }

        override fun pageClosed(page: Page) {
            // Called from page teardown, possibly on a CDP callback thread:
            // update atomically instead of blocking on the creation mutex.
            updatePages { it.removing(page) }
        }
    }

    private val pagesLock = Any()
    private fun updatePages(transform: (kotlinx.collections.immutable.PersistentList<Page>) -> kotlinx.collections.immutable.PersistentList<Page>) {
        synchronized(pagesLock) { _pages = transform(_pages) }
    }

    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public actual suspend fun newPage(): Page {
        leaseContext?.let { return it.newPage() }
        return pagesMutex.withLock {
            val conn = ensureConnection()
            val epScope = endpointScope ?: CdpEndpointScope.Page
            if (epScope == CdpEndpointScope.Page && _pages.isNotEmpty()) {
                throw KmpwrightUnsupportedException(
                    "This CDP endpoint is page-scoped. Open a browser-scoped endpoint to manage multiple pages."
                )
            }
            val session = if (pageFactoryOverride != null) {
                CdpPageSession(epScope, targetId = "test-target", sessionId = "test-session")
            } else createCdpPageSession(conn, epScope)
            var page: Page? = null
            var controller: CdpWebViewController? = null
            try {
                page = pageFactoryOverride?.invoke(conn, session) ?: run {
                    controller = CdpWebViewController(conn, session.sessionId,
                        hideScrollbarsForScreenshots = shouldHideCdpScrollbars(config), allowedOriginRules = config.allowedOriginRules,
            handlerTimeoutMs = config.timeouts.handler)
                    requireNotNull(controller).applyConfiguredEmulation(config)
                    CdpPage(requireNotNull(controller), config, this.scope, conn, session, tabRuntime)
                }
                updatePages { it.adding(page) }
                page
            } catch (error: Throwable) {
                withContext(NonCancellable) {
                    if (page != null) page.close()
                    else { controller?.disposeAsync(); closeCdpPageSession(conn, session) }
                }
                throw error
            }
        }
    }

    private suspend fun targetConnection(): CdpConnection {
        val conn = ensureConnection()
        if (endpointScope != CdpEndpointScope.Browser) throw KmpwrightUnsupportedException("Target management requires a browser-scoped CDP endpoint")
        return conn
    }

    private suspend fun targetCommand(conn: CdpConnection, method: String, params: Map<String, Any?> = emptyMap()): JsonObject {
        val response = conn.send(method, params)
        response.error?.let { throw KmpwrightException("$method: ${it.message}") }
        return response.result ?: JsonObject(emptyMap())
    }

    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public actual suspend fun targets(): List<BrowserTarget> {
        val conn = targetConnection()
        return targetCommand(conn, "Target.getTargets")["targetInfos"]?.jsonArray.orEmpty().mapNotNull { entry ->
            val info = entry.jsonObject
            if (info["type"]?.jsonPrimitive?.content != "page") return@mapNotNull null
            BrowserTarget(info.getValue("targetId").jsonPrimitive.content,
                info["title"]?.jsonPrimitive?.content.orEmpty(), info["url"]?.jsonPrimitive?.content.orEmpty(),
                info["attached"]?.jsonPrimitive?.booleanOrNull ?: false,
                info["browserContextId"]?.jsonPrimitive?.content)
        }
    }

    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public actual suspend fun attachTarget(targetId: String): Page = pagesMutex.withLock {
        require(targetId.isNotBlank()) { "Target identifier must not be blank" }
        _pages.filterIsInstance<CdpPage>().firstOrNull { it.targetId == targetId }?.let { return@withLock it }
        val conn = targetConnection()
        if (targets().none { it.id == targetId }) throw KmpwrightException("No browser page target: $targetId")
        // Activation revives a discarded renderer without reloading a live page.
        targetCommand(conn, "Target.activateTarget", mapOf("targetId" to targetId))
        val session = attachCdpPageSession(CdpConnectionTransport(conn), targetId)
        val controller = CdpWebViewController(conn, session.sessionId,
            hideScrollbarsForScreenshots = shouldHideCdpScrollbars(config), allowedOriginRules = config.allowedOriginRules,
            handlerTimeoutMs = config.timeouts.handler)
        try {
            withTimeout(10_000) { enableCdpDomains(CdpConnectionTransport(conn), session.sessionId) }
            val page = CdpPage(controller, config, scope, conn, session, tabRuntime)
            controller.observeFrames()
            updatePages { it.adding(page) }
            page
        } catch (error: Throwable) {
            withContext(NonCancellable) { controller.disposeAsync(); closeCdpPageSession(conn, session) }
            throw error
        }
    }

    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public actual suspend fun activateTarget(targetId: String) {
        require(targetId.isNotBlank()) { "Target identifier must not be blank" }
        targetCommand(targetConnection(), "Target.activateTarget", mapOf("targetId" to targetId))
    }

    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public actual suspend fun closeTarget(targetId: String): Boolean {
        require(targetId.isNotBlank()) { "Target identifier must not be blank" }
        val conn = targetConnection()
        // Release library callbacks before closing a caller-owned target explicitly.
        val page = pagesMutex.withLock { _pages.filterIsInstance<CdpPage>().firstOrNull { it.targetId == targetId } }
        val accepted = if (page?.ownsTarget == true) {
            page.close()
            true
        } else {
            page?.close()
            targetCommand(conn, "Target.closeTarget", mapOf("targetId" to targetId))["success"]?.jsonPrimitive?.booleanOrNull ?: false
        }
        if (!accepted) return false
        // Chromium acknowledges the command before destroying the target.
        // Await the observable result so a subsequent targets() sees it closed.
        withKmpwrightTimeout(config.timeouts.action, { "Timeout ${config.timeouts.action}ms exceeded closing browser target $targetId" }) {
            while (targets().any { it.id == targetId }) delay(25)
        }
        return true
    }

    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public actual suspend fun newWindow(): Page = pagesMutex.withLock {
        val conn = targetConnection()
        val session = createCdpPageSession(conn, CdpEndpointScope.Browser, newWindow = true)
        val controller = CdpWebViewController(conn, session.sessionId,
            hideScrollbarsForScreenshots = shouldHideCdpScrollbars(config), allowedOriginRules = config.allowedOriginRules,
            handlerTimeoutMs = config.timeouts.handler)
        try {
            controller.applyConfiguredEmulation(config)
            val page = CdpPage(controller, config, scope, conn, session, tabRuntime)
            updatePages { it.adding(page) }
            page
        } catch (error: Throwable) {
            withContext(NonCancellable) { controller.disposeAsync(); closeCdpPageSession(conn, session) }
            throw error
        }
    }

    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public actual suspend fun newContext(): BrowserContext {
        val ctx = BrowserContext(context, config)
        ctx.initialize(ensureConnection(), scope)
        ownedContexts.add(ctx)
        return ctx
    }

    public actual fun pages(): List<Page> = _pages.toList() + ownedContexts.flatMap { it.pages() }

    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class)
    public actual fun page(): Page {
        leaseContext?.let { context -> return context.pages().lastOrNull() ?: runBlocking { context.newPage() } }
        // Only block when a page actually has to be created.
        return _pages.lastOrNull() ?: runBlocking { newPage() }
    }

    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public actual suspend fun version(): BrowserVersion {
        try {
            val conn = ensureConnection()
            val result = conn.send(CdpMethods.BROWSER_GET_VERSION)
            result.error?.let {
                throw KmpwrightException("CDP error getting version: ${it.message}")
            }
            val product = result.result?.get("product")?.let { it.toString().trim('"') } ?: "Chrome"
            val version = product.substringAfter('/', "unknown")
            val userAgent = result.result?.get("userAgent")?.let { it.toString().trim('"') } ?: ""
            return BrowserVersion(product, version, userAgent)
        } catch (e: KmpwrightException) {
            throw e
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            throw KmpwrightException("Could not read browser version: ${error.message}")
        }
    }

    public actual fun dispose() {
        detachedTeardownScope.launch { runCatching { close() } }
    }

    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public actual suspend fun close(): Unit = withContext(NonCancellable) {
        ownedContexts.toList().forEach { it.close() }
        ownedContexts.clear()
        leaseContext = null

        val pagesToClose = pagesMutex.withLock { _pages.toList() }
        pagesToClose.forEach { page ->
            try {
                page.close()
            } catch (_: Throwable) {
            }
        }
        // Serialize against ensureConnection so a concurrent bootstrap can
        // neither resurrect nor leak the connection being torn down.
        connectionMutex.withLock {
            connectionMonitor?.cancel()
            connectionMonitor = null
            val session = providerSession.also { providerSession = null }
            val conn = connection.also { connection = null }
            try { session?.let { providerSessionReleaser(it) } }
            finally {
                try { conn?.close() }
                finally {
                    updatePages { persistentListOf() }
                    _isConnected.value = false
                    endpointScope = null
                }
            }
        }
    }

    private suspend fun ensureConnection(): CdpConnection {
        return connectionMutex.withLock {
            connection?.let {
                if (it.isConnected.value) return@withLock it
            }
            connectionMonitor?.cancel()
            connectionMonitor = null
            connection?.close()
            connection = null
            providerSession?.let { previous ->
                withContext(NonCancellable) { withTimeout(5_000) { providerSessionReleaser(previous) } }
            }
            providerSession = null
            val cdpConfig = config.cdp ?: throw KmpwrightException("No CDP config provided")
            val override = connectionBootstrapOverride
            val session: ProviderSession
            var conn: CdpConnection? = null
            var bootstrap: CdpBootstrap? = null
            session = if (override != null) override().also { bootstrap = it }.session
            else CloudProviders.resolveWsUrl(cdpConfig)
            providerSession = session
            try {
                if (conn == null) conn = bootstrap?.connection ?: CdpConnection(
                    session.websocketUrl, cdpConfig.connectionTimeoutMs, proxy = cdpConfig.connectionProxy
                )
                if (override == null) conn.connect()
                connection = conn
                _isConnected.value = conn.isConnected.value
                _connectionError.value = null
                val observedConnection = conn
                connectionMonitor = scope.launch {
                    observedConnection.isConnected.collect { connected ->
                        if (connection === observedConnection) {
                            _isConnected.value = connected
                            if (!connected) _connectionError.value = "CDP connection disconnected"
                        }
                    }
                }
                endpointScope = endpointScopeResolverOverride?.invoke(conn)
                    ?: bootstrap?.endpointScope ?: detectCdpEndpointScope(conn)
                conn
            } catch (t: Throwable) {
                (connection ?: conn)?.let {
                    try { it.close() } catch (_: Throwable) { }
                }
                connection = null
                endpointScope = null
                _isConnected.value = false
                _connectionError.value = t.message
                providerSession?.let { providerSessionReleaser(it) }
                providerSession = null
                throw t
            }
        }
    }

    internal suspend fun ensureConnectionForTesting(): CdpConnection = ensureConnection()
}

public actual class BrowserContext public actual constructor(
    private val context: PlatformContext?,
    private val config: KmpwrightConfig
) {
    private val pages = java.util.concurrent.CopyOnWriteArrayList<Page>()
    @Volatile private var browserContextId: String? = null
    @Volatile private var connection: CdpConnection? = null
    @Volatile private var scope: CoroutineScope? = null
    private val pagesMutex = Mutex()
    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)
    private val visitedOrigins = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    internal suspend fun initialize(conn: CdpConnection, browserScope: CoroutineScope) {
        check(!closed.get()) { "BrowserContext is closed" }
        connection = conn
        scope = browserScope
        val result = conn.send("Target.createBrowserContext", mapOf(
            "disposeOnDetach" to true
        ))
        result.error?.let {
            throw KmpwrightException("Failed to create browser context: ${it.message}")
        }
        browserContextId = result.result?.get("browserContextId")?.toString()?.trim('"')
    }

    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public actual suspend fun newPage(): Page {
        return pagesMutex.withLock {
        check(!closed.get()) { "BrowserContext is closed" }
        val conn = connection ?: throw KmpwrightException("BrowserContext not initialized")
        val browserScope = scope ?: throw KmpwrightException("BrowserContext not initialized")

        val session = createCdpPageSession(conn, CdpEndpointScope.Browser, browserContextId = browserContextId)
        val sessionId = session.sessionId
        val controller = CdpWebViewController(
            conn,
            sessionId,
            hideScrollbarsForScreenshots = shouldHideCdpScrollbars(config),
            allowedOriginRules = config.allowedOriginRules,
            handlerTimeoutMs = config.timeouts.handler,
            onOriginObserved = { visitedOrigins.add(it) }
        )
        val tabRuntime = object : PageTabRuntime {
            override suspend fun openTab(source: Page, url: String?): NavigationResult {
                val page = newPage()
                try { (source as? BasePage)?.inheritSetupTo(page) }
                catch (error: Throwable) { page.close(); throw error }
                return page.goto(url ?: "about:blank")
            }

            override fun pageClosed(page: Page) {
                pages.remove(page)
            }
        }
        try {
            controller.applyConfiguredEmulation(config)
            val page = CdpPage(controller, config, browserScope, conn, session, tabRuntime)
            pages.add(page)
            page
        } catch (error: Throwable) {
            withContext(NonCancellable) { controller.disposeAsync(); closeCdpPageSession(conn, session) }
            throw error
        }
        }
    }

    public actual fun pages(): List<Page> = pages.toList()

    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public actual suspend fun clearCookies() {
        val conn = connection ?: throw KmpwrightException("BrowserContext is closed")
        val ctxId = browserContextId ?: throw KmpwrightException("BrowserContext not initialized")
        val response = conn.send("Storage.clearCookies", mapOf("browserContextId" to ctxId))
        response.error?.let { throw KmpwrightException("Could not clear cookies: ${it.message}") }
    }

    /** Clears all observed origins, including pages and frames that navigated away or closed. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public actual suspend fun clearData(): Unit = pagesMutex.withLock {
        check(!closed.get()) { "BrowserContext is closed" }
        val conn = connection ?: throw KmpwrightException("BrowserContext not initialized")
        val ctxId = browserContextId ?: throw KmpwrightException("BrowserContext not initialized")
        // A target in this context selects its storage partition, even when all
        // user pages are closed. Never clear through the browser/default session.
        val temporary = createCdpPageSession(conn, CdpEndpointScope.Browser, browserContextId = ctxId)
        try {
            clearCookies()
            val failures = visitedOrigins.toList().mapNotNull { origin ->
                conn.sendSession(requireNotNull(temporary.sessionId), "Storage.clearDataForOrigin", mapOf("origin" to origin, "storageTypes" to "all"))
                    .error?.let { KmpwrightException("Could not clear storage for $origin: ${it.message}") }
            }
            failures.firstOrNull()?.let { first -> failures.drop(1).forEach(first::addSuppressed); throw first }
        } finally {
            withContext(NonCancellable) { withTimeout(5_000) { closeCdpPageSession(conn, temporary) } }
        }
    }

    public actual fun dispose() {
        detachedTeardownScope.launch { runCatching { close() } }
    }

    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public actual suspend fun close(): Unit = withContext(NonCancellable) { pagesMutex.withLock {
        if (!closed.compareAndSet(false, true)) return@withLock
        val pagesToClose = pages.toList()
        pagesToClose.forEach { page ->
            try { page.close() } catch (error: Exception) { kmpwrightLogWarning("Could not close page", error) }
        }
        pages.clear()
        browserContextId?.let { ctxId ->
            val response = connection?.send("Target.disposeBrowserContext", mapOf(
                "browserContextId" to ctxId
            ))
            response?.error?.let { kmpwrightLogWarning("Could not dispose browser context $ctxId: ${it.message}", null) }
        }
        browserContextId = null
        connection = null
        scope = null
        visitedOrigins.clear()
    } }
}

/** Runs best-effort teardown started by non-suspending `dispose()` calls. */
internal val detachedTeardownScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

internal fun shouldHideCdpScrollbars(config: KmpwrightConfig): Boolean {
    val cdp = config.cdp ?: return false
    return cdp.hideScrollbars
}

private class CdpScreenshotController(
    private val controller: WebViewController
) : ScreenshotController {
    override suspend fun captureViewport(scale: Double): ScreenshotResult =
        capture(ScreenshotOptions(scale = scale))

    override suspend fun captureFullPage(scale: Double): ScreenshotResult =
        capture(ScreenshotOptions(fullPage = true, scale = scale))

    override suspend fun captureClip(
        x: Double,
        y: Double,
        width: Double,
        height: Double,
        scale: Double
    ): ScreenshotResult =
        capture(ScreenshotOptions(clip = Rectangle(x, y, width, height), scale = scale))

    override fun getPageDimensions(): PageDimensions {
        val (width, height) = controller.getViewportSize()
        return PageDimensions(
            contentWidth = width,
            contentHeight = height,
            viewportWidth = width,
            viewportHeight = height
        )
    }

    override suspend fun capture(options: ScreenshotOptions): ScreenshotResult =
        (controller as? CdpWebViewController)?.captureScreenshot(options) ?: kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            controller.takeScreenshot(options) { bytes, width, height ->
                if (!cont.isActive) return@takeScreenshot
                if (bytes == null) {
                    cont.resumeWith(Result.failure(KmpwrightException("CDP screenshot returned no data")))
                } else {
                    cont.resume(ScreenshotResult(bytes, width, height)) { _, _, _ -> }
                }
            }
        }
}

/**
 * Applies [KmpwrightConfig.viewport] and [KmpwrightConfig.userAgent] to a page created by KMPwright,
 * like the Android and iOS backends do for the WebViews they create.
 */
internal suspend fun CdpWebViewController.applyConfiguredEmulation(config: KmpwrightConfig) {
    config.viewport?.let { emulateViewportAsync(it) }
    config.userAgent?.let { setUserAgentAsync(it) }
}

/** Complete JVM/CDP advertisement used by the backend and platform contracts. */
internal fun jvmCdpPageCapabilities(): PageCapabilities = PageCapabilities(
    backend = BackendKind.JvmCdp,
    routeInterception = SupportLevel.Supported,
    touchInput = SupportLevel.Partial,
    mouseInput = SupportLevel.Supported,
    keyboardInput = SupportLevel.Supported,
    pdf = SupportLevel.Partial,
    browserContexts = SupportLevel.Supported,
    serviceWorkers = SupportLevel.Supported,
    domainFiltering = SupportLevel.Supported,
    networkEmulation = SupportLevel.Supported,
    harRecording = SupportLevel.Supported,
    downloads = SupportLevel.Supported,
    fileChooser = SupportLevel.Partial,
    tracing = SupportLevel.Supported,
    profiling = SupportLevel.Supported,
    tabs = SupportLevel.Supported,
    cdp = SupportLevel.Supported,
    initScripts = SupportLevel.Supported,
    webMcp = SupportLevel.Partial,
    accessibilityAudit = SupportLevel.Supported,
    webVitals = SupportLevel.Supported,
    reactInspection = SupportLevel.Supported,
    httpRead = SupportLevel.Supported,
    networkHistory = SupportLevel.Partial,
    videoRecording = SupportLevel.Supported,
    crossOriginFrames = SupportLevel.Supported,
    screencast = SupportLevel.Supported,
    notes = listOf(
        "CDP tracing, profiling, downloads, and routing are available.",
        "Mouse and keyboard automation use native CDP Input events.",
        "Touch automation uses CDP touch emulation and supports taps, swipes, scrolls, and pinch gestures.",
        "File chooser events are surfaced for page and iframe inputs once interception is enabled.",
        "Color scheme, geolocation, and network conditions (offline, latency, throughput) are emulated natively " +
            "(Emulation.setEmulatedMedia, Emulation.setGeolocationOverride, Network.emulateNetworkConditions).",
        "Geolocation emulation grants the geolocation permission to the page's browser context; it stays granted after reset.",
        "The domain filter is enforced at the request layer through Fetch interception: navigations, iframes, redirects and " +
            "every HTTP(S) subresource. WebSocket handshakes are not visible to Fetch interception and are not filtered.",
        "frameLocator reaches cross-origin (out-of-process) iframes through their CDP target sessions.",
        "Network history records every request with its CDP resourceType and response; response bodies are readable " +
            "(Response.body/text/json, NetworkHistory.responseBody) while the browser retains them."
    )
)

internal class CdpPage(
    controller: WebViewController,
    config: KmpwrightConfig,
    private val scope: CoroutineScope,
    private val connection: CdpConnection,
    private val session: CdpPageSession,
    tabRuntime: PageTabRuntime? = null
) : BasePage(controller, config, tabRuntime, Dispatchers.IO) {
    internal val targetId: String? get() = session.targetId
    internal val cdpSessionId: String? get() = session.sessionId
    internal val ownsTarget: Boolean get() = session.ownsTarget
    private val chooserRuntimeDelegate: Lazy<CdpFileChooser> = lazy {
        CdpFileChooser(connection, session.sessionId, config) { handle, multiple ->
            reportFileChooser(FileChooser(handle, multiple) { chooser, files ->
                checkOpen()
                checkPolicy(ActionType.Fill, url.value)
                chooserRuntime.setFiles(chooser.handle, files)
            })
        }
    }
    private val chooserRuntime: CdpFileChooser by chooserRuntimeDelegate
    init {
        (controller as CdpWebViewController).childSessionReady = { child ->
            if (chooserRuntimeDelegate.isInitialized()) chooserRuntime.frameReady(child)
        }
        controller.childSessionGone = { child ->
            if (chooserRuntimeDelegate.isInitialized()) scope.launch { chooserRuntime.frameGone(child) }
        }
        // page.title follows document.title changes without a navigation.
        scope.launch { runCatching { controller.startTitleObserver() } }
    }
    override suspend fun setFileChooserInterception(enabled: Boolean) {
        checkOpen()
        checkPolicy(ActionType.Fill, url.value)
        chooserRuntime.setEnabled(enabled)
        if (enabled) (controller as CdpWebViewController).observedChildSessions().forEach { chooserRuntime.frameReady(it) }
    }
    private val frameRuntimeDelegate = lazy { CdpFrameRuntime(connection, session.sessionId, controller, config, scope, onFrameGone = {
        if (chooserRuntimeDelegate.isInitialized()) chooserRuntime.frameGone(it)
    }) {
        if (chooserRuntimeDelegate.isInitialized()) chooserRuntime.frameReady(it)
    } }
    private val frameRuntime by frameRuntimeDelegate
    override fun frameLocator(selector: String): FrameLocator = CdpFrameLocator(frameRuntime,
        cdpFrameSteps(selector), config, closeSignal) { action, url -> checkPolicy(action, url) }
    override fun mainFrame(): FrameLocator = CdpFrameLocator(frameRuntime, emptyList(), config, closeSignal) { action, url -> checkPolicy(action, url) }
    private val recordingMutex = Mutex()
    private var videoRecorder: CdpVideoRecorder? = null
    private var recordingPointer: CdpRecordingPointer? = null
    override suspend fun startRecording(options: RecordingOptions) = recordingMutex.withLock {
        checkOpen()
        checkPolicy(ActionType.Screenshot, url.value)
        if (videoRecorder != null) throw KmpwrightException("Video recording is already active; call page.video.stop() first")
        val cursor = (controller as CdpWebViewController).recordingCursor
        try {
            videoRecorder = CdpVideoRecorder.start(screencast(ScreencastOptions(options.fps, options.quality,
                options.maxWidth, options.maxHeight)), options, cursor)
            if (options.cursor) recordingPointer = CdpRecordingPointer(connection, session.sessionId, controller, cursor).also { it.start() }
        } catch (error: Throwable) {
            recordingPointer?.close(); recordingPointer = null
            videoRecorder?.abort(); videoRecorder = null
            throw error
        }
    }
    override suspend fun stopRecording(): RecordingResult = recordingMutex.withLock {
        val recorder = videoRecorder ?: throw KmpwrightException("Video recording is not active")
        try { recorder.stop() } finally { videoRecorder = null; recordingPointer?.close(); recordingPointer = null }
    }
    private val screencastRuntime = CdpScreencast(connection, session)
    override fun screencast(options: ScreencastOptions): kotlinx.coroutines.flow.Flow<ScreencastFrame> =
        kotlinx.coroutines.flow.flow {
            checkOpen()
            checkPolicy(ActionType.Screenshot, url.value)
            screencastRuntime.frames(options).collect { emit(it) }
        }
    private val inputBridge = controller as CdpInputBridge
    private val closed = AtomicBoolean(false)
    private val subscriptionLock = Any()

    override fun capabilities(): PageCapabilities = jvmCdpPageCapabilities()

    override suspend fun serviceWorkers(): List<ServiceWorker> {
        checkOpen()
        return withContext(Dispatchers.IO) {
            withKmpwrightTimeout(config.timeouts.default, { "Timed out listing service workers" }) {
                val result = connection.send(CdpMethods.TARGET_GET_TARGETS)
                result.error?.let { throw KmpwrightException("Could not list service workers: ${it.message}") }
                val targets = result.result?.get("targetInfos")?.jsonArray
                    ?: throw KmpwrightException("Could not list service workers: no target data")
                targets.mapNotNull { item ->
                    val target = item.jsonObject
                    val type = target["type"]?.jsonPrimitive?.content
                    if (type != "service_worker") return@mapNotNull null
                    val targetId = target["targetId"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    val url = target["url"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    ServiceWorker(
                        url = url,
                        state = if (target["attached"]?.jsonPrimitive?.booleanOrNull == true) "attached" else "running",
                        scriptUrl = url
                    ) {
                        unregisterServiceWorkerTarget(targetId)
                    }
                }
            }
        }
    }

    private suspend fun unregisterServiceWorkerTarget(targetId: String): Boolean {
        val attach = connection.send(CdpMethods.TARGET_ATTACH_TO_TARGET, mapOf(
            "targetId" to targetId,
            "flatten" to true
        ))
        val sessionId = attach.result?.get("sessionId")?.jsonPrimitive?.content ?: return false
        return try {
            val result = connection.sendSession(sessionId, CdpMethods.RUNTIME_EVALUATE, mapOf(
                "expression" to "self.registration.unregister()",
                "awaitPromise" to true,
                "returnByValue" to true
            ))
            result.error == null
        } catch (_: Exception) {
            false
        } finally {
            try {
                connection.send(CdpMethods.TARGET_DETACH_FROM_TARGET, mapOf("sessionId" to sessionId))
            } catch (_: Throwable) {}
        }
    }

    private var tracingActive = false
    private var profilerActive = false
    private var tracingStream: String? = null
    private var tracingOutputPath: String? = null
    private val tracingEventChunks = mutableListOf<String>()
    private var cdpDownloadPath: String? = null
    private val tracingSubscriptions = mutableListOf<CdpEventSubscription>()
    private val downloadSubscriptions = mutableListOf<CdpEventSubscription>()

    /**
     * Take ownership of subscriptions before cancelling them.  Clearing the
     * list first makes teardown safe to repeat and ensures an exception from a
     * misbehaving subscription cannot cause the remaining callbacks to leak.
     */
    private fun cancelSubscriptions(subscriptions: MutableList<CdpEventSubscription>) {
        val owned = synchronized(subscriptionLock) {
            subscriptions.toList().also { subscriptions.clear() }
        }
        owned.forEach { subscription ->
            try {
                subscription.cancel()
            } catch (_: Throwable) {
                // Cleanup is best effort; continue cancelling every callback.
            }
        }
    }

    private fun checkOpen() {
        check(!closed.get()) { "Page is closed" }
    }

    /** Finish tracing while its completion listener is still registered. */
    private suspend fun finishTracingForClose(wasActive: Boolean) {
        if (wasActive) {
            try {
                withTimeoutOrNull(500) { connection.send(CdpMethods.TRACING_END) }
            } catch (_: Throwable) {}
        }

        // ReturnAsStream delivers the IO handle in Tracing.tracingComplete,
        // which can arrive after Tracing.end returns. Give that callback a
        // bounded window before removing the subscription.
        var attempts = 0
        while (tracingStream == null && attempts < 20) {
            delay(25)
            attempts += 1
        }
        tracingStream?.let { handle ->
            try {
                withTimeoutOrNull(500) {
                    connection.send(CdpMethods.IO_CLOSE, mapOf("handle" to handle))
                }
            } catch (_: Throwable) {}
            tracingStream = null
        }
        connection.tracingOwner.compareAndSet(this, null)
    }

    private suspend fun sendForPage(method: String, params: Map<String, Any?> = emptyMap()): CdpResponse =
        session.sessionId?.let { connection.sendSession(it, method, params) } ?: connection.send(method, params)

    override fun createPlatformLocator(selector: String): BaseLocator {
        return CdpLocator(controller, selector, config, frameFactory = { frameSelector, scope ->
            CdpFrameLocator(frameRuntime, cdpFrameSteps(frameSelector, scope), config, closeSignal) { action, url -> checkPolicy(action, url) }
        })
    }

    override fun createScreenshotController(): ScreenshotController =
        CdpScreenshotController(controller)

    override fun createTouch(): Touchscreen {
        if (config.inputMode == InputMode.JsOnly) throw KmpwrightUnsupportedException("Native touch is disabled by InputMode.JsOnly")
        return CdpTouch(inputBridge, config)
    }

    override fun createMouse(): Mouse {
        if (config.inputMode != InputMode.Hybrid) throw KmpwrightUnsupportedException("Native mouse requires InputMode.Hybrid")
        return CdpMouse(inputBridge, config)
    }

    override fun createKeyboard(): Keyboard {
        if (config.inputMode != InputMode.Hybrid) throw KmpwrightUnsupportedException("Native keyboard requires InputMode.Hybrid")
        return CdpKeyboard(inputBridge, config)
    }

    override fun dispose() {
        detachedTeardownScope.launch { runCatching { close() } }
    }

    override suspend fun close(): Unit = withContext(NonCancellable) {
        if (!closed.compareAndSet(false, true)) return@withContext
        beginPageClose()

        // Mark producers inactive before removing their callbacks.  An event
        // already in the CDP dispatch loop can then harmlessly observe the
        // inactive state while teardown is in progress.
        val tracingWasActive = tracingActive
        tracingActive = false
        val profilerWasActive = profilerActive
        profilerActive = false

        cdpDownloadSetup = false
        cdpDownloadPath = null
        cancelSubscriptions(downloadSubscriptions)

        var failure: Throwable? = null
        suspend fun cleanup(block: suspend () -> Unit) {
            try { block() } catch (error: Throwable) { if (failure == null) failure = error else failure.addSuppressed(error) }
        }
        cleanup {
            recordingMutex.withLock {
                val recorder = videoRecorder
                videoRecorder = null
                if (recorder != null) {
                    try { recorder.stop() } catch (error: Throwable) {
                        try { recorder.abort() } catch (aborted: Throwable) { error.addSuppressed(aborted) }
                        throw error
                    }
                }
            }
        }
        cleanup { recordingPointer?.close() }; recordingPointer = null
        cleanup { screencastRuntime.close() }
        cleanup { if (chooserRuntimeDelegate.isInitialized()) chooserRuntime.close() }
        cleanup { if (frameRuntimeDelegate.isInitialized()) frameRuntime.close() }
        cleanup { super.close() }
        // Keep tracing callbacks until the bounded completion-stream wait finishes.
        cleanup { if (tracingWasActive || tracingStream != null) finishTracingForClose(tracingWasActive) }
        tracingOutputPath = null
        tracingEventChunks.clear()
        cancelSubscriptions(tracingSubscriptions)
        if (profilerWasActive) {
            try { sendForPage(CdpMethods.PROFILER_STOP) } catch (_: Throwable) {}
            try { sendForPage(CdpMethods.PROFILER_DISABLE) } catch (_: Throwable) {}
        }
        try { closeCdpPageSession(connection, session) } catch (_: Exception) {}
        failure?.let { throw it }
    }

    override suspend fun startTracing(options: TracingOptions) {
        checkOpen()
        if (!config.enableTraceExport && options.path != null) {
            throw KmpwrightException("Trace export is disabled by KmpwrightConfig.enableTraceExport")
        }
        if (!connection.tracingOwner.compareAndSet(null, this)) throw KmpwrightException("Tracing is already owned by another page on this browser")
        tracingEventChunks.clear()
        tracingStream = null
        tracingOutputPath = options.path
        tracingActive = true
        cancelSubscriptions(tracingSubscriptions)
        tracingSubscriptions += connection.onEvent("Tracing.dataCollected") { params ->
            params["value"]?.jsonArray?.forEach { tracingEventChunks.add(it.toString()) }
        }
        tracingSubscriptions += connection.onEvent("Tracing.tracingComplete") { params ->
            tracingStream = params["stream"]?.jsonPrimitive?.content
        }
        val categories = options.categories
        val params = mutableMapOf<String, Any?>(
            "categories" to categories,
            "transferMode" to "ReturnAsStream"
        )
        if (options.screenshots && "disabled-by-default-devtools.screenshot" !in categories) {
            params["categories"] = "$categories,disabled-by-default-devtools.screenshot"
        }
        try {
            val response = connection.send(CdpMethods.TRACING_START, params)
            response.error?.let { throw KmpwrightException("Tracing start failed: ${it.message}") }
        } catch (failure: Throwable) {
            tracingActive = false
            cancelSubscriptions(tracingSubscriptions)
            connection.tracingOwner.compareAndSet(this, null)
            throw failure
        }
    }

    override suspend fun stopTracing(): ByteArray? {
        var streamToClose: String? = null
        return try {
            if (!tracingActive) return null
            val ended = connection.send(CdpMethods.TRACING_END)
            ended.error?.let { throw KmpwrightException("Tracing stop failed: ${it.message}") }
            var attempts = 0
            while (tracingStream == null && tracingEventChunks.isEmpty() && attempts < 100) {
                delay(50)
                attempts += 1
            }
            val stream = tracingStream
            streamToClose = stream
            val bytes = if (stream != null) {
                val data = readCdpTraceStream(CdpConnectionTransport(connection), stream)
                connection.send(CdpMethods.IO_CLOSE, mapOf("handle" to stream))
                streamToClose = null
                data
            } else if (tracingEventChunks.isNotEmpty()) {
                "[${tracingEventChunks.joinToString(",")}]".encodeToByteArray()
            } else {
                null
            }
            if (bytes != null) {
                tracingOutputPath?.let { path ->
                    val dir = path.substringBeforeLast("/", "")
                    if (dir.isNotEmpty()) mkdirs(dir)
                    fileWrite(path, bytes.decodeToString())
                }
            }
            bytes
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            throw KmpwrightException("Tracing stop failed: ${failure.message}")
        } finally {
            tracingActive = false
            connection.tracingOwner.compareAndSet(this, null)
            streamToClose?.let { handle ->
                try {
                    withContext(NonCancellable) { withTimeoutOrNull(500) {
                        connection.send(CdpMethods.IO_CLOSE, mapOf("handle" to handle))
                    } }
                } catch (_: Exception) {}
            }
            tracingStream = null
            tracingOutputPath = null
            tracingEventChunks.clear()
            cancelSubscriptions(tracingSubscriptions)
        }
    }

    override suspend fun startProfiler() {
        checkOpen()
        val enable = sendForPage(CdpMethods.PROFILER_ENABLE)
        enable.error?.let {
            throw KmpwrightException("CDP error for ${CdpMethods.PROFILER_ENABLE} (${it.code}): ${it.message}")
        }
        try {
            val start = sendForPage(CdpMethods.PROFILER_START)
            start.error?.let {
                throw KmpwrightException("CDP error for ${CdpMethods.PROFILER_START} (${it.code}): ${it.message}")
            }
            profilerActive = true
        } catch (error: Throwable) {
            try { sendForPage(CdpMethods.PROFILER_DISABLE) } catch (_: Exception) {}
            throw error
        }
    }

    override suspend fun stopProfiler(): String? {
        if (!profilerActive) return null
        profilerActive = false
        return try {
            val result = sendForPage(CdpMethods.PROFILER_STOP)
            result.error?.let { throw KmpwrightException("CDP error for ${CdpMethods.PROFILER_STOP} (${it.code}): ${it.message}") }
            result.result?.get("profile")?.toString()
                ?: throw KmpwrightException("Profiler stopped without returning a profile")
        } finally {
            withContext(NonCancellable) {
                runCatching { sendForPage(CdpMethods.PROFILER_DISABLE) }
            }
        }
    }

    private var cdpDownloadSetup = false
    /** CDP download behavior in effect ("default", "allow", "allowAndName" or "deny"). */
    @Volatile private var cdpDownloadMode = "default"
    private val pendingDownloads = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.CompletableDeferred<String?>>()

    private fun ensureCdpDownloadSetup() {
        if (cdpDownloadSetup) return
        cdpDownloadSetup = true
        downloadSubscriptions += connection.onEvent("Browser.downloadWillBegin") { params ->
            val frame = params["frameId"]?.jsonPrimitive?.content ?: return@onEvent
            if (!(controller as CdpWebViewController).ownsFrame(frame)) return@onEvent
            val url = params["url"]?.jsonPrimitive?.content ?: return@onEvent
            val suggestedFilename = params["suggestedFilename"]?.jsonPrimitive?.content ?: ""
            val guid = params["guid"]?.jsonPrimitive?.content
            val directory = cdpDownloadPath
            val mode = cdpDownloadMode
            // Downloads are only written to a known location when a download path is configured.
            val saved = directory != null && guid != null && (mode == "allow" || mode == "allowAndName")
            val completion = kotlinx.coroutines.CompletableDeferred<String?>()
            if (saved) pendingDownloads[guid] = completion else completion.complete(null)
            val expectedPath = if (!saved) null else if (mode == "allowAndName") "$directory/$guid" else "$directory/$suggestedFilename"
            reportDownload(Download(url, suggestedFilename, pathProvider = {
                completion.await()?.let { reported -> reported.ifEmpty { expectedPath } }
            }, copy = { from, to ->
                withContext(Dispatchers.IO) {
                    val target = java.nio.file.Path.of(to).toAbsolutePath()
                    target.parent?.let { java.nio.file.Files.createDirectories(it) }
                    java.nio.file.Files.copy(java.nio.file.Path.of(from), target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                }
            }))
        }
        downloadSubscriptions += connection.onEvent("Browser.downloadProgress") { params ->
            val guid = params["guid"]?.jsonPrimitive?.content ?: return@onEvent
            when (params["state"]?.jsonPrimitive?.content) {
                "completed" -> pendingDownloads.remove(guid)?.complete(params["filePath"]?.jsonPrimitive?.content ?: "")
                "canceled" -> pendingDownloads.remove(guid)?.complete(null)
            }
        }
    }

    private val downloadContextKey: String get() = session.browserContextId ?: "__default__"

    private suspend fun configureDownloads(behavior: String, path: String? = null) {
        connection.downloadConfigurationMutex.withLock {
            val params = mutableMapOf<String, Any?>("behavior" to behavior, "eventsEnabled" to true)
            session.browserContextId?.let { params["browserContextId"] = it }
            path?.let { params["downloadPath"] = it }
            val response = connection.send(CdpMethods.BROWSER_SET_DOWNLOAD_BEHAVIOR, params)
            response.error?.let { throw KmpwrightException("Download configuration failed: ${it.message}") }
            connection.configuredDownloadContexts.add(downloadContextKey)
            cdpDownloadMode = behavior
        }
    }

    override suspend fun waitForDownload(url: String?, timeoutMs: Long?): Download {
        checkOpen()
        ensureCdpDownloadSetup()
        if (downloadContextKey !in connection.configuredDownloadContexts) configureDownloads("default")
        return awaitPageOperation(closeSignal) { super.waitForDownload(url, timeoutMs) }
    }

    override suspend fun setDownloadPath(path: String) {
        checkOpen()
        require(path.isNotBlank()) { "Download path must not be blank" }
        ensureCdpDownloadSetup()
        // Do not return before the browser honours the path.
        withKmpwrightTimeout(config.timeouts.default, { "Timed out configuring the download path" }) { configureDownloads("allowAndName", path) }
        super.setDownloadPath(path)
        cdpDownloadPath = path
    }

    override suspend fun setDownloadBehavior(behavior: DownloadBehavior) {
        checkOpen()
        val path = if (behavior == DownloadBehavior.Deny) null else cdpDownloadPath
            ?: throw KmpwrightException("Set a download path before allowing downloads")
        ensureCdpDownloadSetup()
        val value = when (behavior) {
            DownloadBehavior.Allow -> "allow"
            DownloadBehavior.SaveToDownloadPath -> "allowAndName"
            DownloadBehavior.Deny -> "deny"
        }
        withKmpwrightTimeout(config.timeouts.default, { "Timed out configuring downloads" }) { configureDownloads(value, path) }
        super.setDownloadBehavior(behavior)
    }

}
