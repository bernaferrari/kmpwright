package com.bernaferrari.kmpwright.core

import android.content.Context
import android.webkit.WebSettings
import android.webkit.WebView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.cancellation.CancellationException

/**
 * Android host for a [Browser]. When [webView] is null, Kmpwright creates and
 * owns a detached WebView. [delegateWebViewClient]/[delegateWebChromeClient]
 * receive every WebView callback; when null, the clients already installed on
 * [webView] keep receiving them.
 *
 * A host-provided [webView] is adjusted on attach: automation runs JavaScript, so
 * `settings.javaScriptEnabled` is set to true, and [KmpwrightConfig.userAgent] (when set) replaces
 * its user agent. Its size stays under the host's layout ([KmpwrightConfig.viewport] only sizes
 * WebViews that Kmpwright creates).
 */
public class AndroidPlatformContext(
    public val context: Context,
    public val webView: WebView? = null,
    public val delegateWebViewClient: android.webkit.WebViewClient? = null,
    public val delegateWebChromeClient: android.webkit.WebChromeClient? = null
) : PlatformContext

private const val ANDROID_CONTEXT_UNSUPPORTED_MESSAGE =
    "Android WebView cannot provide isolated BrowserContext semantics in-process. Use Browser pages directly or separate device/browser instances."

public actual class Browser @JvmOverloads public actual constructor(
    private val context: PlatformContext,
    private val config: KmpwrightConfig
) {
    private val platformContext = context as AndroidPlatformContext
    private val androidContext = platformContext.context
    private val browserScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val pages = CopyOnWriteArrayList<Page>()
    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)

    private val _isConnected = MutableStateFlow(true)
    public actual val isConnected: StateFlow<Boolean> = _isConnected

    private val _connectionError = MutableStateFlow<String?>(null)
    public actual val connectionError: StateFlow<String?> = _connectionError
    private val tabRuntime = object : PageTabRuntime {
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

    init {
        checkMainThread("Browser(AndroidPlatformContext)")
        AndroidAppStorage.remember(androidContext)
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

            config.viewport?.let { viewport -> applyCssViewportSize(viewport.width, viewport.height) }
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

    internal actual suspend fun beginIndependentLease(): Unit = throw KmpwrightUnsupportedException(
        "Independent browser-farm leases need isolated contexts. Android WebView requires a separate host process per owner.")
    internal actual suspend fun endIndependentLease() {}

    /** Creates a page backed by a new managed WebView (created on the main thread). */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public actual suspend fun newPage(): Page = onMain {
        check(!closed.get()) { "Browser is closed" }
        val page = createPage(createManagedWebView(), ownsWebView = true)
        pages.add(page)
        page
    }

    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public actual suspend fun targets(): List<BrowserTarget> = throw KmpwrightUnsupportedException("Browser target discovery requires CDP")
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public actual suspend fun attachTarget(targetId: String): Page = throw KmpwrightUnsupportedException("Browser target attachment requires CDP")
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public actual suspend fun activateTarget(targetId: String): Unit = throw KmpwrightUnsupportedException("Browser target activation requires CDP")
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public actual suspend fun closeTarget(targetId: String): Boolean = throw KmpwrightUnsupportedException("Browser target closing requires CDP")
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public actual suspend fun newWindow(): Page = throw KmpwrightUnsupportedException("Browser windows require CDP")

    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public actual suspend fun newContext(): BrowserContext {
        throw KmpwrightUnsupportedException(ANDROID_CONTEXT_UNSUPPORTED_MESSAGE)
    }

    public actual fun pages(): List<Page> = pages.toList()

    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class)
    public actual fun page(): Page = pages.lastOrNull() ?: throw IllegalStateException("No page available")

    /** The installed Android System WebView provider, like the CDP backend reports Chrome. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public actual suspend fun version(): BrowserVersion {
        val provider = androidx.webkit.WebViewCompat.getCurrentWebViewPackage(androidContext)
        return BrowserVersion(
            product = "Android WebView",
            version = provider?.versionName ?: "unknown",
            userAgent = config.userAgent ?: WebSettings.getDefaultUserAgent(androidContext)
        )
    }

    public actual fun dispose() {
        if (!closed.compareAndSet(false, true)) return
        _isConnected.value = false
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) finishDispose()
        else android.os.Handler(android.os.Looper.getMainLooper()).post { finishDispose() }
    }

    private fun finishDispose() {
        val open = pages.toList()
        pages.clear()
        open.forEach { it.dispose() }
        _isConnected.value = false
        browserScope.cancel()
    }

    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public actual suspend fun close(): Unit = kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) {
        if (!closed.compareAndSet(false, true)) return@withContext
        _isConnected.value = false
        try { pages.toList().forEach { it.close() } } finally { finishDispose() }
    }
}

public actual class BrowserContext public actual constructor(
    private val context: PlatformContext?,
    private val config: KmpwrightConfig
) {
    private val pages = mutableListOf<Page>()

    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public actual suspend fun newPage(): Page {
        throw KmpwrightUnsupportedException(ANDROID_CONTEXT_UNSUPPORTED_MESSAGE)
    }

    public actual fun pages(): List<Page> = pages.toList()

    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public actual suspend fun clearCookies() {
        throw KmpwrightUnsupportedException(ANDROID_CONTEXT_UNSUPPORTED_MESSAGE)
    }

    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public actual suspend fun clearData() {
        throw KmpwrightUnsupportedException(ANDROID_CONTEXT_UNSUPPORTED_MESSAGE)
    }

    public actual fun dispose() {
        val open = pages.toList()
        pages.clear()
        open.forEach { it.dispose() }
    }

    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public actual suspend fun close() {
        pages.toList().forEach { it.close() }
        dispose()
    }
}

internal class AndroidPage(
    controller: WebViewController,
    config: KmpwrightConfig = KmpwrightConfig(),
    tabRuntime: PageTabRuntime? = null
) : BasePage(controller, config, tabRuntime) {

    internal val androidController: AndroidWebViewController? get() = controller as? AndroidWebViewController

    override fun capabilities(): PageCapabilities = androidPageCapabilities()

    override fun createPlatformLocator(selector: String): BaseLocator = AndroidLocator(controller, selector, config)

    override fun createTouch(): Touchscreen {
        if (config.inputMode == InputMode.JsOnly) throw KmpwrightUnsupportedException("Native touch is disabled by InputMode.JsOnly")
        return AndroidTouch(controller as AndroidWebViewController, config)
    }

    override fun createMouse(): Mouse {
        if (config.inputMode != InputMode.Hybrid) throw KmpwrightUnsupportedException("Native mouse requires InputMode.Hybrid")
        return AndroidMouse(controller as AndroidWebViewController, config)
    }

    override fun createKeyboard(): Keyboard {
        if (config.inputMode != InputMode.Hybrid) throw KmpwrightUnsupportedException("Native keyboard requires InputMode.Hybrid")
        return AndroidKeyboard(controller as AndroidWebViewController, config)
    }

    override fun createScreenshotController(): ScreenshotController {
        val webView = (controller as? AndroidWebViewController)?.getWebView()
            ?: throw IllegalStateException("ScreenshotController requires AndroidWebViewController")
        return AndroidScreenshotController(webView, config)
    }

    override suspend fun waitForDownload(url: String?, timeoutMs: Long?): Download {
        throw KmpwrightUnsupportedException("Android WebView does not currently surface download events.")
    }

    override suspend fun waitForFileChooser(timeoutMs: Long?): FileChooser {
        throw KmpwrightUnsupportedException("Android WebView does not currently surface file chooser events.")
    }

    override suspend fun setDownloadPath(path: String) {
        throw KmpwrightUnsupportedException("Android WebView does not currently support download path management.")
    }

    override suspend fun setDownloadBehavior(behavior: DownloadBehavior) {
        throw KmpwrightUnsupportedException("Android WebView does not currently support download behavior management.")
    }
}

/**
 * Observable WebView state (URL, title, loading, history, renderer crashes) of a page created by an
 * Android [Browser], or null for other backends. Used by `kmpwright-compose`.
 */
public fun Page.androidWebViewState(): StateFlow<AndroidWebViewState>? = (this as? AndroidPage)?.androidController?.state

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
    crossOriginFrames = SupportLevel.Unsupported,
    notes = listOf(
        "Android touch and keyboard automation dispatch native WebView events.",
        "Mouse automation is still partial and may fall back to hover/tap approximations.",
        "Routing works at the WebView interception layer. Fulfill and abort are supported; continue overrides are applied only for requests WebView exposes to shouldInterceptRequest.",
        "Network emulation is offline-only and implemented in JavaScript (navigator.onLine, fetch, XMLHttpRequest, " +
            "sendBeacon); WebView has no native latency/throughput throttling, so those parameters are rejected.",
        "Color scheme emulation is a JavaScript fallback (matchMedia and the root color-scheme property): with targetSdk 33+ " +
            "WebView derives the CSS prefers-color-scheme media feature from the app theme, so stylesheet @media rules are not affected.",
        "Geolocation emulation is a JavaScript fallback that patches navigator.geolocation in every document.",
        "The domain filter is enforced natively: requests to disallowed hosts fail as network errors in shouldInterceptRequest " +
            "(fetch, XHR, images, iframes) and navigations, including main-frame server redirects, are cancelled in " +
            "loadUrl/shouldOverrideUrlLoading. It is Partial because WebView exposes no hook for server redirects of " +
            "subresource requests (an open redirect on an allowed host reaches the target), WebSocket connections, " +
            "or requests made by service workers.",
        "Cookie enumeration is limited to origins observed by this WebView and does not expose full browser-cookie metadata.",
        "Download and file chooser events are not currently surfaced from Android WebView.",
        "Dialogs reach automation only while a Page.onDialog handler or a waitForDialog is active; otherwise WebView hands " +
            "them to the host WebChromeClient (or its native UI) and no dialog event is emitted.",
        "WebView reports no response for requests it loads itself: the main-frame document gets its status when the page " +
            "finishes, any request gets status and headers from onReceivedHttpError (4xx/5xx), network errors fail the record, " +
            "and other subresources finish without a response. resourceType is \"document\" for the main frame and \"other\" " +
            "otherwise. Response bodies are unavailable.",
        "Screenshots support PNG, JPEG and WEBP; omitBackground is unsupported because WebView always paints its background.",
        "emulateDevice applies the viewport size and user agent; the device scale factor, mobile layout and touch support " +
            "come from the device and are not emulated.",
        "frameLocator reaches same-origin frames only: page JavaScript cannot enter cross-origin iframes."
    )
)

internal class AndroidLocator(
    controller: WebViewLocatorController,
    selector: String,
    config: KmpwrightConfig,
    options: FilterOptions? = null
) : BaseLocator(controller, selector, config, options) {

    override fun createLocator(newSelector: String, options: FilterOptions?): Locator =
        AndroidLocator(controller, newSelector, config, options)

    override suspend fun evaluateJavaScriptAsync(script: String): String? =
        controller.normalizeJavaScriptResult(controller.evaluateJavaScriptAsync(script))
}
