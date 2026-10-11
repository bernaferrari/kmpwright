package com.bernaferrari.kmpwright.core

import kotlinx.coroutines.flow.StateFlow

interface Page {
    /** Experimental browser tools; support depends on the browser protocol. */
    /** Cold, latest-frame-wins stream. Collection owns capture; cancellation releases it. */
    fun screencast(options: ScreencastOptions = ScreencastOptions()): kotlinx.coroutines.flow.Flow<ScreencastFrame> =
        throw UnsupportedOperationException("Screencast is unavailable on this backend")
    suspend fun startRecording(options: RecordingOptions) {
        throw UnsupportedOperationException("Video recording is unavailable on this backend")
    }
    suspend fun stopRecording(): RecordingResult =
        throw UnsupportedOperationException("Video recording is unavailable on this backend")
    suspend fun startHarCapture(): Unit = throw UnsupportedOperationException("HAR capture is unavailable on this backend")
    suspend fun stopHarCapture(): String = throw UnsupportedOperationException("HAR capture is unavailable on this backend")
    val network: NetworkHistory
    val react: ReactInspector
    val webMcp: WebMcp
    val url: StateFlow<String?>
    val title: StateFlow<String?>

    val touch: Touch
    val mouse: Mouse
    val keyboard: Keyboard

    fun capabilities(): PageCapabilities

    /**
     * Preflight a workflow before it performs its first page operation.
     * Existing Page implementations remain source-compatible because this
     * has a default implementation based on [capabilities].
     */
    fun requireCapabilities(vararg requirements: PageCapabilityRequirement): PageCapabilities =
        capabilities().require(*requirements)

    /** Convenience overload for workflows requiring full support. */
    fun requireCapabilities(vararg capabilities: PageCapability): PageCapabilities =
        capabilities().require(*capabilities)

    /** Run a workflow only after all of its capability requirements pass. */
    suspend fun <R> withRequiredCapabilities(
        vararg requirements: PageCapabilityRequirement,
        block: suspend Page.() -> R
    ): R {
        requireCapabilities(*requirements)
        return block()
    }

    /** Convenience workflow form for fully-supported capabilities. */
    suspend fun <R> withRequiredCapabilities(
        vararg capabilities: PageCapability,
        block: suspend Page.() -> R
    ): R {
        requireCapabilities(*capabilities)
        return block()
    }

    suspend fun goto(url: String, options: GotoOptions = GotoOptions()): NavigationResult
    suspend fun pushState(url: String, timeoutMs: Long = 30_000): NavigationResult

    suspend fun reload(options: ReloadOptions = ReloadOptions())
    suspend fun back()
    suspend fun forward()

    fun locator(selector: String): Locator
    suspend fun evaluate(expression: String): JsResult
    suspend fun content(): String
    suspend fun setContent(html: String, options: SetContentOptions = SetContentOptions())

    suspend fun screenshot(options: ScreenshotOptions = ScreenshotOptions()): ScreenshotResult
    suspend fun waitForLoadState(state: LoadState, timeoutMs: Long = 30_000)
    suspend fun waitForTimeout(ms: Long)

    suspend fun waitForNavigation(options: WaitForNavigationOptions = WaitForNavigationOptions()): NavigationResult?
    suspend fun waitForEvent(event: String, options: WaitForEventOptions = WaitForEventOptions()): Any?

    suspend fun waitForRequest(urlPattern: String, timeoutMs: Long = 30_000): RequestEvent
    suspend fun waitForResponse(urlPattern: String, timeoutMs: Long = 30_000): ResponseEvent

    suspend fun waitForRequestUrl(url: String, timeoutMs: Long = 30_000): Request
    suspend fun waitForResponseUrl(url: String, timeoutMs: Long = 30_000): Response

    fun route(urlPattern: String, handler: suspend (Route) -> RouteHandler)
    fun unroute(urlPattern: String? = null)

    suspend fun read(url: String? = null, options: ReadOptions = ReadOptions()): ReadResult

    suspend fun webVitals(options: WebVitalsOptions = WebVitalsOptions()): WebVitalsReport

    suspend fun metrics(): PageMetrics
    fun serviceWorkers(): List<ServiceWorker>

    val events: PageEvents

    fun expect(locator: Locator): KmpwrightExpect

    fun onDialog(handler: suspend (DialogEvent) -> DialogResponse): DialogHandler

    suspend fun pdf(options: PdfOptions = PdfOptions()): PdfResult

    /** Run JavaScript before page scripts in subsequent documents and child frames. Returns a removable identifier. */
    suspend fun addInitScript(script: String): String
    suspend fun removeInitScript(id: String)

    suspend fun addScriptTag(options: ScriptTagOptions): String
    suspend fun addStyleTag(options: StyleTagOptions): String

    suspend fun setCookie(cookie: Cookie)
    suspend fun getCookie(name: String): Cookie?
    suspend fun getAllCookies(): List<Cookie>
    suspend fun deleteCookie(name: String, domain: String)
    suspend fun deleteAllCookies()

    suspend fun accessibilityAudit(options: AccessibilityAuditOptions = AccessibilityAuditOptions()): AccessibilityAuditReport

    suspend fun accessibilitySnapshot(options: A11ySnapshotOptions = A11ySnapshotOptions()): A11ySnapshot
    suspend fun locatorByRef(ref: String): Locator
    suspend fun snapshotAndInteract(ref: String, action: suspend (Locator) -> Unit)

    suspend fun scroll(x: Double, y: Double)
    suspend fun scrollDown(distance: Double)
    suspend fun scrollUp(distance: Double)
    suspend fun scrollToLeft(distance: Double)
    suspend fun scrollToTop()
    suspend fun scrollToBottom()

    fun consoleMessages(): List<ConsoleMessage>
    fun errors(): List<String>

    suspend fun setLocalStorage(key: String, value: String)
    suspend fun getLocalStorage(key: String): String?
    suspend fun removeLocalStorage(key: String)
    suspend fun getAllLocalStorage(): Map<String, String>
    suspend fun clearLocalStorage()

    suspend fun setSessionStorage(key: String, value: String)
    suspend fun getSessionStorage(key: String): String?
    suspend fun removeSessionStorage(key: String)
    suspend fun getAllSessionStorage(): Map<String, String>
    suspend fun clearSessionStorage()

    suspend fun emulateColorScheme(scheme: ColorScheme)
    suspend fun resetColorScheme()

    suspend fun emulateGeolocation(latitude: Double, longitude: Double)
    suspend fun resetGeolocation()

    suspend fun emulateOffline(offline: Boolean)
    suspend fun emulateNetwork(offline: Boolean, latency: Long = 0, downloadThroughput: Long = 0, uploadThroughput: Long = 0)

    suspend fun setUserAgent(userAgent: String)
    suspend fun setViewportSize(width: Int, height: Int)
    suspend fun setDeviceScaleFactor(scale: Double)
    fun viewportSize(): Pair<Int, Int>

    /**
     * Emulates a device by setting its viewport size, user agent, and device scale factor.
     * Uses a [DeviceDescriptor] from [Devices].
     *
     * Usage:
     *   page.emulateDevice(Devices.iPhone15)
     *   page.emulateDevice(Devices.Pixel7)
     */
    suspend fun emulateDevice(device: DeviceDescriptor)

    suspend fun setExtraHTTPHeaders(headers: Map<String, String>)
    suspend fun setHTTPCredentials(username: String, password: String)
    suspend fun clearHTTPCredentials()

    suspend fun waitForText(text: String, timeoutMs: Long = 30_000): Boolean
    suspend fun waitForFunction(expression: String, timeoutMs: Long = 30_000): JsResult

    suspend fun annotatedScreenshot(options: AnnotatedScreenshotOptions = AnnotatedScreenshotOptions()): AnnotatedScreenshotResult

    fun frameLocator(selector: String): FrameLocator
    fun mainFrame(): FrameLocator

    suspend fun diffSnapshot(before: A11ySnapshot, after: A11ySnapshot): SnapshotDiffResult
    suspend fun diffSnapshot(name: String): SnapshotDiffResult
    suspend fun diffUrls(url1: String, url2: String, timeoutMs: Long = 30_000): SnapshotDiffResult

    suspend fun clipboardRead(): String?
    suspend fun clipboardWrite(text: String)

    suspend fun saveState(name: String)
    suspend fun loadState(name: String): Boolean
    fun stateNames(): List<String>
    suspend fun deleteState(name: String)

    suspend fun saveStateToFile(
        name: String,
        path: String? = null,
        encrypt: Boolean = false,
        password: String? = null
    )
    suspend fun loadStateFromFile(name: String, path: String? = null, password: String? = null): Boolean
    suspend fun login(profile: AuthProfile, options: AuthLoginOptions = AuthLoginOptions()): AuthLoginResult = loginWithProfile(profile, options)
    suspend fun autosaveState(options: StateAutosaveOptions): StateAutosave = throw UnsupportedOperationException("State autosave is unavailable on this backend")
    fun listSavedStateFiles(dir: String? = null): List<String>
    fun deleteStateFile(name: String, path: String? = null): Boolean

    suspend fun openNewTab(url: String? = null): NavigationResult
    suspend fun closeTab(): Boolean

    fun setActionPolicy(policy: ActionPolicy)
    fun setPolicyConfirmCallback(callback: (suspend (ActionType, String?) -> Boolean)?)

    /** Restricts page network access to allowed domains. Empty set = no restriction. */
    suspend fun setDomainFilter(domains: Set<String>)

    /** Injects stealth scripts to hide automation signals. Called automatically when CdpConfig.stealth=true. */
    suspend fun enableStealth()

    /** Creates a ContentBoundary with a CSPRNG nonce for this page's origin. */
    fun createContentBoundary(): ContentBoundary

    /** Returns page HTML wrapped in boundary markers. */
    suspend fun boundedContent(boundary: ContentBoundary? = null): String

    /** Returns accessibility snapshot text wrapped in boundary markers. */
    suspend fun boundedSnapshot(options: A11ySnapshotOptions = A11ySnapshotOptions(), boundary: ContentBoundary? = null): String

    suspend fun waitForDownload(urlPattern: String? = null, timeoutMs: Long = 30_000): DownloadEvent
    suspend fun waitForFileChooser(timeoutMs: Long = 30_000): FileChooserEvent
    /** Enable before clicking an input; CDP intercepts the native chooser and emits an opaque handle. */
    suspend fun setFileChooserInterception(enabled: Boolean = true): Unit = throw UnsupportedOperationException("File chooser interception is unavailable on this backend")
    /** A handle is single-use and expires on navigation or disabled interception. Empty files cancel selection. */
    suspend fun setFileChooserFiles(event: FileChooserEvent, files: List<String>): Unit = throw UnsupportedOperationException("File chooser upload is unavailable on this backend")
    fun setDownloadPath(path: String)
    fun setDownloadBehavior(behavior: DownloadBehavior)

    /**
     * Starts Chrome DevTools Protocol tracing.
     * Only supported on CDP-backed pages; no-op on WebView.
     */
    suspend fun startTracing(options: TracingOptions = TracingOptions())

    /**
     * Stops tracing and returns the trace data.
     * @return Trace data, or null if tracing was not active or not supported.
     */
    suspend fun stopTracing(): ByteArray?

    /**
     * Starts the V8 CPU profiler.
     * Only supported on CDP-backed pages.
     */
    suspend fun startProfiler()

    /**
     * Stops the V8 CPU profiler and returns the profile as JSON.
     * @return Profile JSON string, or null if profiling was not active or not supported.
     */
    suspend fun stopProfiler(): String?

    suspend fun closeAsync() { close() }
    fun close()
}

/** Top-level spelling for workflow code that prefers an extension DSL. */
suspend fun <R> Page.withCapabilities(
    vararg requirements: PageCapabilityRequirement,
    block: suspend Page.() -> R
): R = withRequiredCapabilities(*requirements, block = block)

interface DialogHandler {
    fun close()
}

data class GotoOptions(
    val timeoutMs: Long = 30_000,
    val waitUntil: LoadState = LoadState.DOMContentLoaded,
    val referer: String? = null
)

data class SetContentOptions(
    val timeoutMs: Long = 30_000,
    val waitUntil: LoadState = LoadState.Load
)

data class ReloadOptions(
    val timeoutMs: Long = 30_000
)

data class WaitForNavigationOptions(
    val timeoutMs: Long = 30_000,
    val urlPattern: String? = null,
    val waitUntil: LoadState? = null
)

data class WaitForEventOptions(
    val timeoutMs: Long = 30_000,
    val predicate: ((Any?) -> Boolean)? = null
)

enum class LoadState {
    DOMContentLoaded,
    Load,
    NetworkIdle
}

data class NavigationResult(
    val url: String,
    val status: Int,
    val ok: Boolean,
    val error: String? = null
)

data class JsResult(
    val value: String,
    val type: String
)

data class ScreenshotOptions(
    val fullPage: Boolean = false,
    val clip: Rectangle? = null,
    val omitBackground: Boolean = false,
    val timeoutMs: Long = 30_000,
    val quality: Int = 90,
    val format: ScreenshotFormat = ScreenshotFormat.PNG,
    val scale: Double = 1.0
)

enum class ScreenshotFormat {
    PNG,
    JPEG,
    WEBP
}

data class ScreenshotResult(
    val bytes: ByteArray,
    val width: Int,
    val height: Int
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ScreenshotResult) return false
        if (!bytes.contentEquals(other.bytes)) return false
        if (width != other.width) return false
        if (height != other.height) return false
        return true
    }

    override fun hashCode(): Int {
        var result = bytes.contentHashCode()
        result = 31 * result + width
        result = 31 * result + height
        return result
    }
}

data class Rectangle(
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double
)

interface PageEvents {
    val console: kotlinx.coroutines.flow.MutableSharedFlow<ConsoleMessage>
    val navigation: kotlinx.coroutines.flow.MutableSharedFlow<NavigationEvent>
    val loadState: kotlinx.coroutines.flow.MutableSharedFlow<LoadState>
    val dialog: kotlinx.coroutines.flow.MutableSharedFlow<DialogEvent>
    val request: kotlinx.coroutines.flow.MutableSharedFlow<RequestEvent>
    val response: kotlinx.coroutines.flow.MutableSharedFlow<ResponseEvent>
    val download: kotlinx.coroutines.flow.MutableSharedFlow<DownloadEvent>
    val fileChooser: kotlinx.coroutines.flow.MutableSharedFlow<FileChooserEvent>
}

data class ConsoleMessage(
    val type: String,
    val text: String,
    val location: SourceLocation?
)

data class SourceLocation(
    val url: String,
    val lineNumber: Int,
    val columnNumber: Int
)

data class NavigationEvent(
    val url: String,
    val status: Int,
    val navigationType: NavigationType
)

enum class NavigationType {
    SameDocument,
    NewDocument
}

data class DialogEvent(
    val type: DialogType,
    val message: String,
    val defaultPrompt: String? = null
)

enum class DialogType {
    Alert,
    Confirm,
    Prompt
}

sealed class DialogResponse {
    data object Accept : DialogResponse()
    data object Dismiss : DialogResponse()
    data class Text(val text: String) : DialogResponse()
}

data class RequestEvent(
    val url: String,
    val method: String,
    val headers: Map<String, String>,
    val timestamp: Long
)

data class ResponseEvent(
    val url: String,
    val status: Int,
    val statusText: String?,
    val headers: Map<String, String>,
    val timestamp: Long
)

data class PageMetrics(
    val documents: Int = 0,
    val frames: Int = 1,
    val jsHeapUsedSize: Long = 0,
    val jsHeapTotalSize: Long = 0,
    val resources: Int = 0,
    val networkIdle: Boolean = true
)

data class ServiceWorkerRegistration(
    val scopeUrl: String,
    val scriptUrl: String,
    val state: String
)

class ServiceWorker(
    val url: String,
    val state: String,
    val scriptUrl: String,
    private val unregisterAction: suspend () -> Boolean
) {
    suspend fun unregister(): Boolean = unregisterAction()
}

data class PdfOptions(
    val format: PdfFormat = PdfFormat.Letter,
    val landscape: Boolean = false,
    val displayHeaderFooter: Boolean = false,
    val headerTemplate: String = "",
    val footerTemplate: String = "",
    val printBackground: Boolean = true,
    val scale: Double = 1.0,
    val width: Double? = null,
    val height: Double? = null,
    val margin: PdfMargin = PdfMargin(),
    val pageRanges: String = ""
)

data class PdfMargin(
    val top: String = "1cm",
    val bottom: String = "1cm",
    val left: String = "1cm",
    val right: String = "1cm"
)

enum class PdfFormat {
    Letter,
    Legal,
    Tabloid,
    Ledger,
    A0,
    A1,
    A2,
    A3,
    A4,
    A5,
    A6
}

data class PdfResult(
    val bytes: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PdfResult) return false
        return bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        return bytes.contentHashCode()
    }
}

data class ScriptTagOptions(
    val url: String? = null,
    val content: String? = null,
    val type: String = "text/javascript",
    val id: String = "",
    val async: Boolean = false,
    val defer: Boolean = false
)

data class StyleTagOptions(
    val url: String? = null,
    val content: String? = null,
    val id: String = ""
)

enum class ColorScheme {
    Light,
    Dark,
    NoPreference
}

data class PageState(
    val url: String? = null,
    val cookies: List<Cookie>,
    val localStorage: Map<String, String>,
    val sessionStorage: Map<String, String>,
    val timestamp: Long = currentTimeMillis()
)

data class AnnotatedScreenshotOptions(
    val fullPage: Boolean = false,
    val format: ScreenshotFormat = ScreenshotFormat.PNG,
    val quality: Int = 90,
    val scale: Double = 1.0
)

data class AnnotatedScreenshotResult(
    val screenshot: ScreenshotResult,
    val labels: List<AnnotatedLabel>
)

data class AnnotatedLabel(
    val index: Int,
    val text: String,
    val rectangle: Rectangle
)

data class DownloadEvent(
    val url: String,
    val suggestedFilename: String,
    val timestamp: Long = currentTimeMillis()
)

data class FileChooserEvent(
    val element: String,
    val multiple: Boolean,
    val timestamp: Long = currentTimeMillis()
)

enum class DownloadBehavior {
    SaveToDownloadPath,
    Deny,
    Allow
}

data class TracingOptions(
    /** Comma-separated list of trace event categories. Default: typical web categories. */
    val categories: String = "blink,devtools.timeline,v8,benchmark",
    /** Whether to include screenshots in the trace. */
    val screenshots: Boolean = false,
    /** Path to record the trace to. If null, returns data from stopTracing(). */
    val path: String? = null
)

/**
 * Preset network profiles for throttling.
 */
enum class NetworkProfile(val label: String, val latency: Long, val downloadKbps: Long, val uploadKbps: Long) {
    /** No throttling. */
    None("No throttling", 0, 0, 0),
    /** ~750 Kbps down, ~250 Kbps up, 100ms RTT. */
    Slow3G("Slow 3G", 100, 750_000 / 8, 250_000 / 8),
    /** ~1.6 Mbps down, ~750 Kbps up, 56ms RTT. */
    Fast3G("Fast 3G", 56, 1_600_000 / 8, 750_000 / 8),
    /** Offline — all requests fail. */
    Offline("Offline", 0, 0, 0)
}
