package com.bernaferrari.kmpwright.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.coroutines.cancellation.CancellationException

/**
 * A browser tab or WebView, modelled on Playwright's `Page` and extended with the agent features of
 * Vercel's agent-browser (`snapshot`, `@eN` refs, `--allowed-domains`, `runCommand`).
 *
 * Every operation is `suspend`; options are data classes; a `timeoutMs` of null uses the matching
 * [TimeoutConfig] default. Selector-taking conveniences such as `page.click("#save")` are extension
 * functions (see `PageConvenience.kt`).
 */
public interface Page : ElementFinder {
    /** The committed URL, updated on every navigation (Playwright `page.url()`). */
    public val url: StateFlow<String?>

    /** The document title as of the last navigation (Playwright `page.title()`). */
    public val title: StateFlow<String?>

    /** Hot event streams (console, requests, dialogs, downloads...), like Playwright's `page.on(...)`. */
    public val events: PageEvents

    /** Recorded network requests and HAR capture (agent-browser `network requests` / `network har`). */
    public val network: NetworkHistory

    /** React component inspection (agent-browser `react`). */
    public val react: ReactInspector

    /** WebMCP tools exposed by the page (agent-browser `webmcp`). */
    public val webMcp: WebMcp

    /** Touch input, like Playwright's `page.touchscreen`. */
    public val touchscreen: Touchscreen

    /** Mouse input, like Playwright's `page.mouse`. */
    public val mouse: Mouse

    /** Keyboard input, like Playwright's `page.keyboard`. */
    public val keyboard: Keyboard

    /** The page origin's `localStorage`. */
    public val localStorage: WebStorage

    /** The page origin's `sessionStorage`. */
    public val sessionStorage: WebStorage

    /** The system clipboard as seen by the page (web `navigator.clipboard` API names). */
    public val clipboard: Clipboard

    /** Chrome trace recording, like Playwright's `page.context().tracing` (CDP only). */
    public val tracing: Tracing

    /** V8 CPU profiling (agent-browser `profiler`; CDP only). */
    public val profiler: Profiler

    /** Video recording of the page (agent-browser `record`; CDP only). */
    public val video: Video

    /** What this backend supports; check before relying on platform-dependent features. */
    public fun capabilities(): PageCapabilities

    /** Throws [KmpwrightUnsupportedException] unless every requirement is met; returns the capabilities. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class)
    public fun requireCapabilities(vararg requirements: PageCapabilityRequirement): PageCapabilities =
        capabilities().require(*requirements)

    /** Throws [KmpwrightUnsupportedException] unless every capability is fully supported. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class)
    public fun requireCapabilities(vararg capabilities: PageCapability): PageCapabilities =
        capabilities().require(*capabilities)

    // ------------------------------------------------------------------ navigation

    /**
     * Navigates the main frame and waits for [GotoOptions.waitUntil] (`DOMContentLoaded` returns before
     * subresources such as images finish; `Load` at the load event). Returns the result (final URL after
     * redirects and its HTTP status): [NavigationResult.ok] is Playwright's `response.ok()` (status
     * 200..299); a failed or refused navigation has `ok == false` and a non-null [NavigationResult.error].
     * @throws KmpwrightTimeoutException when the navigation does not finish in time.
     */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun goto(url: String, options: GotoOptions = GotoOptions()): NavigationResult

    /** Reloads the page and waits for the new document. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun reload(options: ReloadOptions = ReloadOptions()): NavigationResult

    /** Navigates back in history; null when there is no previous entry. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun goBack(options: HistoryNavigationOptions = HistoryNavigationOptions()): NavigationResult?

    /** Navigates forward in history; null when there is no next entry. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun goForward(options: HistoryNavigationOptions = HistoryNavigationOptions()): NavigationResult?

    /** Client-side navigation through the app router (Next.js) or `history.pushState` (agent-browser `pushstate`). */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun pushState(url: String, timeoutMs: Long? = null): NavigationResult

    /**
     * Waits until the page URL matches [url] (a Playwright URL glob: an exact URL when it has no glob
     * characters; see [route] for the rules). Same-document navigations (pushState, hash changes) count.
     */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun waitForURL(url: String, options: WaitForURLOptions = WaitForURLOptions())

    /** Waits until the page URL matches [url]. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun waitForURL(url: Regex, options: WaitForURLOptions = WaitForURLOptions())

    /** Waits until [predicate] accepts the page URL. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun waitForURL(options: WaitForURLOptions = WaitForURLOptions(), predicate: (String) -> Boolean)

    /**
     * Waits for the document to reach [state]: `DOMContentLoaded` (CDP: `Page.domContentEventFired`;
     * WebViews: `document.readyState` past `loading`), `Load` or `NetworkIdle`.
     */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun waitForLoadState(state: LoadState = LoadState.Load, timeoutMs: Long? = null)

    /** Suspends for [ms] milliseconds. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun waitForTimeout(ms: Long)

    /**
     * Waits for the next navigation; returns null when none happens in time. Prefer the action-scoped
     * overload `waitForNavigation { click(...) }`, which cannot miss a fast navigation.
     */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun waitForNavigation(options: WaitForNavigationOptions = WaitForNavigationOptions()): NavigationResult?

    /** Waits for an event by name (`"console"`, `"request"`, `"load"`...); null on timeout. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun waitForEvent(event: String, options: WaitForEventOptions = WaitForEventOptions()): Any?

    /**
     * Polls [expression] (evaluated once per poll; a returned Promise is awaited and a returned function is
     * called) until its value is truthy, and returns that value. An exception thrown by the expression
     * fails the wait immediately, as in Playwright.
     */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun waitForFunction(expression: String, timeoutMs: Long? = null): JsResult

    /** Waits until [text] appears in the page's visible text (agent-browser `wait --text`). */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun waitForText(text: String, timeoutMs: Long? = null)

    /** Waits for a request whose URL matches [url] (Playwright URL glob; exact URL when it has no glob characters). */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun waitForRequest(url: String, timeoutMs: Long? = null): Request

    /** Waits for a request whose URL matches [url]. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun waitForRequest(url: Regex, timeoutMs: Long? = null): Request

    /** Waits for a request accepted by [predicate]. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun waitForRequest(timeoutMs: Long? = null, predicate: (Request) -> Boolean): Request

    /** Waits for a response whose URL matches [url] (Playwright URL glob; exact URL when it has no glob characters). */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun waitForResponse(url: String, timeoutMs: Long? = null): Response

    /** Waits for a response whose URL matches [url]. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun waitForResponse(url: Regex, timeoutMs: Long? = null): Response

    /** Waits for a response accepted by [predicate]. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun waitForResponse(timeoutMs: Long? = null, predicate: (Response) -> Boolean): Response

    /** Waits for the next download, optionally one whose URL matches [url] (Playwright URL glob, see [route]). */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun waitForDownload(url: String? = null, timeoutMs: Long? = null): Download

    /** Waits for the next file chooser (CDP requires [setFileChooserInterception] first). */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun waitForFileChooser(timeoutMs: Long? = null): FileChooser

    // ------------------------------------------------------------------ content

    /**
     * Evaluates [expression] in the page. Promises are awaited. The value is returned as text: strings
     * verbatim, other values JSON-encoded; [JsResult.type] reports the JavaScript type.
     */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun evaluate(expression: String): JsResult

    /** The full HTML of the document. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun content(): String

    /** Replaces the document with [html]. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun setContent(html: String, options: SetContentOptions = SetContentOptions())

    /**
     * Captures the viewport, the full page or a clip. With [ScreenshotOptions.annotate] the snapshot
     * refs are drawn on the image and returned in [ScreenshotResult.annotations] (agent-browser
     * `screenshot --annotate`).
     */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun screenshot(options: ScreenshotOptions = ScreenshotOptions()): ScreenshotResult

    /** Renders the page as PDF. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun pdf(options: PdfOptions = PdfOptions()): PdfResult

    /** Experimental: cold, latest-frame-wins frame stream (agent-browser `screencast`; CDP only). */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class)
    public fun screencast(options: ScreencastOptions = ScreencastOptions()): Flow<ScreencastFrame>

    /** Runs [script] before page scripts in later documents and child frames; returns an id for [removeInitScript]. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun addInitScript(script: String): String

    /** Removes a script added with [addInitScript]. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun removeInitScript(id: String)

    /** Adds a `<script>` tag; returns its element id. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun addScriptTag(options: ScriptTagOptions): String

    /** Adds a `<style>` or stylesheet `<link>`; returns its element id. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun addStyleTag(options: StyleTagOptions): String

    /** Fetches [url] over HTTP (or reads this page when null) and returns readable text (agent-browser `read`). */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun read(url: String? = null, options: ReadOptions = ReadOptions()): ReadResult

    /** Core Web Vitals of the current document (agent-browser `vitals`). */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun webVitals(options: WebVitalsOptions = WebVitalsOptions()): WebVitalsReport

    /** Runtime metrics of the current document. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun metrics(): PageMetrics

    /** Service workers controlling the page (CDP only). */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun serviceWorkers(): List<ServiceWorker>

    /** Runs an accessibility audit of the current document. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun accessibilityAudit(options: AccessibilityAuditOptions = AccessibilityAuditOptions()): AccessibilityAuditReport

    /**
     * Up to the last 200 console messages of this page (Playwright's `page.consoleMessages()`), by
     * default only those logged after the last committed main-frame navigation.
     */
    public fun consoleMessages(filter: PageMessageFilter = PageMessageFilter.SinceNavigation): List<ConsoleMessage>

    /**
     * Up to the last 200 page errors (uncaught exceptions and unhandled promise rejections, not
     * `console.error`) formatted as `"Name: message"`, like Playwright's `page.pageErrors()`. See
     * [PageEvents.pageError] for the structured [PageError].
     */
    public fun pageErrors(filter: PageMessageFilter = PageMessageFilter.SinceNavigation): List<String>

    /** Forgets stored console messages; later [consoleMessages] calls only return newer ones. */
    public fun clearConsoleMessages()

    /** Forgets stored page errors; later [pageErrors] calls only return newer ones. */
    public fun clearPageErrors()

    /** The main frame as a [FrameLocator]. */
    public fun mainFrame(): FrameLocator

    // ------------------------------------------------------------------ agent features

    /**
     * Takes an accessibility snapshot (agent-browser `snapshot`). Interactive elements get refs such as
     * `@e3` that `locator("@e3")` and the selector conveniences accept until the next snapshot.
     */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun snapshot(options: SnapshotOptions = SnapshotOptions()): Snapshot

    /** Line diff of two snapshots (agent-browser `diff snapshot`). */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun diffSnapshot(before: Snapshot, after: Snapshot): SnapshotDiffResult

    /**
     * Snapshots now and diffs against the snapshot saved under [name], then saves the new one. The
     * first call for a name records the baseline and reports no changes.
     */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun diffSnapshot(name: String): SnapshotDiffResult

    /** Diffs the text structure of two URLs fetched over HTTP (agent-browser `diff url`). */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun diffUrls(url1: String, url2: String, timeoutMs: Long? = null): SnapshotDiffResult

    /** A boundary with a fresh nonce for wrapping untrusted page content in tool output. */
    public fun createContentBoundary(): ContentBoundary

    /** The page HTML wrapped in boundary markers (agent-browser `--content-boundaries`). */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun boundedContent(boundary: ContentBoundary? = null): String

    /** A snapshot tree wrapped in boundary markers (agent-browser `--content-boundaries`). */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun boundedSnapshot(options: SnapshotOptions = SnapshotOptions(), boundary: ContentBoundary? = null): String

    /** Applies an [ActionPolicy] to every later action (agent-browser `--action-policy`). */
    public fun setActionPolicy(policy: ActionPolicy)

    /** Answers actions that the policy marks as [PolicyResult.Confirm] (agent-browser `--confirm-actions`). */
    public fun setPolicyConfirmCallback(callback: (suspend (ActionType, String?) -> Boolean)?)

    /**
     * Restricts the hosts the page may contact (agent-browser `--allowed-domains`); `*.example.com`
     * matches the apex and subdomains, an empty list removes the restriction. Enforcement depends on the
     * backend, see [PageCapabilities.domainFiltering]: request-layer on CDP and iOS, a request-layer
     * filter with known gaps on Android, and a JavaScript patch where no hook exists.
     */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun setAllowedDomains(domains: List<String>)

    /** Fills and submits the login form described by [profile] (agent-browser `auth login`). */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun login(profile: AuthProfile, options: AuthLoginOptions = AuthLoginOptions()): AuthLoginResult

    // ------------------------------------------------------------------ network

    /**
     * Intercepts requests whose URL matches [url].
     *
     * URL patterns (one rule for every String URL parameter: route, unroute, waitForURL, waitForRequest,
     * waitForResponse, waitForNavigation, waitForDownload and `expect(page).toHaveURL`), as in Playwright:
     * a glob over the full URL where `**` matches anything including `/`, `*` anything except `/`,
     * `{a,b}` either alternative and every other character (including `?`) is literal, so a string
     * without glob characters is an exact URL (`"https://example.com"` equals `https://example.com/`).
     * Prefix a path with `**` to match it on any host (`"**` + `/api/items?page=2"`), or use a [Regex]
     * overload. The [handler] fulfills, aborts, resumes or falls back; returning without a decision
     * falls back. Registering the same [url] again replaces its handler. Returns once interception is
     * active, so requests issued afterwards are routed.
     */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun route(url: String, handler: suspend (Route) -> Unit)

    /** Intercepts requests whose full URL matches [url]. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun route(url: Regex, handler: suspend (Route) -> Unit)

    /** Removes the route registered for the glob [url]. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun unroute(url: String)

    /** Removes the route registered for [url]. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun unroute(url: Regex)

    /** Removes every route. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun unrouteAll()

    /**
     * Answers JavaScript dialogs; the newest handler wins. Close the returned handle to remove it.
     * A handler that does not answer within [TimeoutConfig.handler] dismisses the dialog. With no
     * handler and no pending `waitForDialog`, dialogs are dismissed on CDP and iOS, and handled by
     * the host WebChromeClient (or WebView's native UI) on Android.
     */
    public fun onDialog(handler: suspend (DialogEvent) -> DialogResponse): DialogHandler

    /** Sends [headers] with every request of this page. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun setExtraHTTPHeaders(headers: Map<String, String>)

    /** Answers HTTP authentication challenges with [credentials]; null clears them. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun setHTTPCredentials(credentials: HttpCredentials?)

    /** Enables native file chooser interception (CDP); required before [waitForFileChooser] there. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun setFileChooserInterception(enabled: Boolean = true)

    /** Directory where downloads are saved. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun setDownloadPath(path: String)

    /** Whether downloads are saved, allowed or denied. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun setDownloadBehavior(behavior: DownloadBehavior)

    // ------------------------------------------------------------------ state

    /** Cookies visible to [urls], or every cookie when empty (Playwright `context.cookies()`). */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun cookies(urls: List<String> = emptyList()): List<Cookie>

    /** Adds or replaces [cookies] (Playwright `context.addCookies()`). */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun addCookies(cookies: List<Cookie>)

    /** Removes cookies matching [name] and [domain]; both null removes every cookie. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun clearCookies(name: String? = null, domain: String? = null)

    /** Cookies plus this origin's localStorage and sessionStorage (Playwright `storageState()`). */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun storageState(): StorageState

    /** Restores [state]: replaces cookies, navigates to its URL when needed and restores storage. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun setStorageState(state: StorageState)

    /** Saves [storageState] periodically to a file until [StorageStateAutosave.stop]. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun autosaveStorageState(options: StorageStateAutosaveOptions): StorageStateAutosave

    // ------------------------------------------------------------------ emulation

    /**
     * Emulates `prefers-color-scheme`; null resets. On WebViews this overrides `matchMedia` and the
     * `color-scheme` property at document start (see [PageCapabilities.notes]).
     */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun emulateMedia(colorScheme: ColorScheme?)

    /** Overrides `navigator.geolocation`; null resets. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun setGeolocation(geolocation: Geolocation?)

    /** Emulates being offline. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun setOffline(offline: Boolean)

    /**
     * Throttles the network; null removes throttling. WebView backends only emulate
     * [NetworkConditions.offline] and throw [KmpwrightUnsupportedException] for latency or throughput.
     */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun emulateNetworkConditions(conditions: NetworkConditions?)

    /** Overrides the user agent for later requests. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun setUserAgent(userAgent: String)

    /** Resizes the viewport in CSS pixels. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun setViewportSize(width: Int, height: Int)

    /** The current viewport size in CSS pixels (never null: every backend knows its viewport). */
    public fun viewportSize(): ViewportSize

    /** Sets the device pixel ratio. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun setDeviceScaleFactor(scale: Double)

    /**
     * Applies a [DeviceDescriptor], for example `Devices.iPhone15`: its viewport size and user agent on
     * every backend, plus device scale factor, mobile layout and touch on CDP. Android and iOS WebViews
     * keep the host display's scale factor and their native touch support (see [PageCapabilities.notes]);
     * nothing is applied when the descriptor is invalid.
     */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun emulateDevice(device: DeviceDescriptor)

    // ------------------------------------------------------------------ scrolling

    /** Scrolls the page by [amount] CSS pixels in [direction] (agent-browser `scroll`). */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun scroll(direction: ScrollDirection, amount: Double = 300.0)

    /** Scrolls to the top of the page. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun scrollToTop()

    /** Scrolls to the bottom of the page. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun scrollToBottom()

    // ------------------------------------------------------------------ tabs and lifecycle

    /** Opens a new tab that inherits this page's routes and emulation, and navigates it to [url]. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun openNewTab(url: String? = null): NavigationResult

    /** Closes the page and waits for its resources to be released. Idempotent. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun close()

    /**
     * Non-suspending, best-effort teardown for lifecycle callbacks (Compose `onDispose`,
     * `Activity.onDestroy`). Returns immediately and is idempotent; prefer [close] when you can suspend.
     */
    public fun dispose()
}

/** A registration returned by [Page.onDialog]. */
public interface DialogHandler {
    /** Removes the dialog handler. */
    public fun close()
}

/** `localStorage` or `sessionStorage` of the page's current origin. */
public interface WebStorage {
    /** The value stored under [key], or null. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun get(key: String): String?

    /** Stores [value] under [key]. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun set(key: String, value: String)

    /** Removes [key]. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun remove(key: String)

    /** Removes every entry. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun clear()

    /** Every entry. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun entries(): Map<String, String>
}

/** The clipboard as seen by the page (`navigator.clipboard`). */
public interface Clipboard {
    /** The clipboard text, or null when the page may not read it. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun readText(): String?

    /** Replaces the clipboard text. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun writeText(text: String)
}

/** Chrome trace recording (CDP only). */
public interface Tracing {
    /** Starts recording a trace. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun start(options: TracingOptions = TracingOptions())

    /** Stops recording and returns the trace JSON, or null when no trace was active. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun stop(): ByteArray?
}

/** V8 CPU profiling (CDP only). */
public interface Profiler {
    /** Starts the CPU profiler. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun start()

    /** Stops the profiler and returns the profile JSON, or null when it was not running. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun stop(): String?
}

/** Video recording of the page (agent-browser `record`; CDP only, encoding requires ffmpeg). */
public interface Video {
    /** Starts recording to [RecordingOptions.path]. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun start(options: RecordingOptions)

    /** Stops recording and returns the written file. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun stop(): RecordingResult
}

/**
 * A download started by the page, like Playwright's `Download`.
 *
 * @property url the downloaded resource.
 * @property suggestedFilename the file name proposed by the server or the `download` attribute.
 */
public class Download internal constructor(
    public val url: String,
    public val suggestedFilename: String,
    private val pathProvider: suspend () -> String?,
    private val copy: suspend (from: String, to: String) -> Unit
) {
    /** Waits for the download to finish and returns the saved file, or null when it was not saved to disk. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun path(): String? = pathProvider()

    /** Waits for the download to finish and copies it to [path]. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun saveAs(path: String) {
        val source = path() ?: throw KmpwrightException("Download of $url was not saved to disk; set a download path first")
        copy(source, path)
    }

    override fun toString(): String = "Download(url=$url, suggestedFilename=$suggestedFilename)"
}

/**
 * A native file chooser opened by the page, like Playwright's `FileChooser`.
 *
 * @property isMultiple whether the input accepts several files.
 */
public class FileChooser internal constructor(
    internal val handle: String,
    public val isMultiple: Boolean,
    private val apply: suspend (FileChooser, List<String>) -> Unit
) {
    /** Selects [files]; an empty list cancels the selection. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun setFiles(files: List<String>): Unit = apply(this, files)

    override fun toString(): String = "FileChooser(isMultiple=$isMultiple)"
}

/** Options for [Page.goto]. [timeoutMs] null uses [TimeoutConfig.navigation]. */
public data class GotoOptions(
    val waitUntil: LoadState = LoadState.Load,
    val timeoutMs: Long? = null
)

/** Options for [Page.setContent]. [timeoutMs] null uses [TimeoutConfig.navigation]. */
public data class SetContentOptions(
    val waitUntil: LoadState = LoadState.Load,
    val timeoutMs: Long? = null
)

/** Options for [Page.reload]. [timeoutMs] null uses [TimeoutConfig.navigation]. */
public data class ReloadOptions(
    val waitUntil: LoadState = LoadState.Load,
    val timeoutMs: Long? = null
)

/** Options for [Page.goBack] and [Page.goForward]. [timeoutMs] null uses [TimeoutConfig.navigation]. */
public data class HistoryNavigationOptions(
    val waitUntil: LoadState = LoadState.Load,
    val timeoutMs: Long? = null
)

/** Options for [Page.waitForURL]. [timeoutMs] null uses [TimeoutConfig.navigation]. */
public data class WaitForURLOptions(
    val waitUntil: LoadState = LoadState.Load,
    val timeoutMs: Long? = null
)

/**
 * Options for [Page.waitForNavigation]. [url] is a Playwright URL glob (exact URL when it has no glob
 * characters; `regex:` prefix for a regular expression); [timeoutMs] null uses [TimeoutConfig.navigation].
 * Same-document navigations (pushState, replaceState, hash changes) count, as in Playwright.
 */
public data class WaitForNavigationOptions(
    val url: String? = null,
    val waitUntil: LoadState? = null,
    val timeoutMs: Long? = null
)

/** Options for [Page.waitForEvent]. [timeoutMs] null uses [TimeoutConfig.default]. */
public data class WaitForEventOptions(
    val predicate: ((Any?) -> Boolean)? = null,
    val timeoutMs: Long? = null
)

/** Document lifecycle states. */
public enum class LoadState {
    DOMContentLoaded,
    Load,
    NetworkIdle
}

/**
 * Outcome of a navigation.
 *
 * @property url the final URL (after redirects).
 * @property status the HTTP status of the final response (200 for local documents such as `setContent`,
 *   0 when the navigation failed).
 * @property ok like Playwright's `response.ok()`: the navigation committed and [status] is in 200..299.
 *   A 404 or 500 page is a committed navigation with `ok == false` and no [error].
 * @property error why the navigation failed (network error, policy, timeout); null when a document was committed.
 */
public data class NavigationResult(
    val url: String,
    val status: Int,
    val ok: Boolean,
    val error: String? = null
)

/** Whether a document was committed (any HTTP status), as opposed to a failed or refused navigation. */
internal val NavigationResult.committed: Boolean get() = error == null && (ok || status > 0)

/** [NavigationResult] for a committed document with [status] (`ok` follows Playwright: 200..299). */
internal fun committedNavigation(url: String, status: Int): NavigationResult = NavigationResult(url, status, status in 200..299)

/**
 * Result of [Page.evaluate]. [value] is the string itself for strings and JSON for every other value
 * (`"null"` for null/undefined). [type] is the JavaScript `typeof` (`"null"` for null, `"array"` for arrays).
 */
public data class JsResult(
    val value: String,
    val type: String
)

/**
 * Options for [Page.screenshot] and [Locator.screenshot].
 *
 * - [clip] is in CSS pixels relative to the top-left of the viewport (Playwright semantics); it
 *   cannot be combined with [fullPage], and a clip entirely outside the viewport is an error.
 * - [format] and [quality] (JPEG/WEBP only, 0..100) are honored on every backend.
 * - [omitBackground] makes the default white page background transparent; it needs CDP (PNG or
 *   WEBP) and throws [KmpwrightUnsupportedException] on Android and iOS WebViews, which cannot
 *   render without their own background.
 *
 * @throws IllegalArgumentException when [fullPage] and [clip] are both set.
 */
public data class ScreenshotOptions @Throws(IllegalArgumentException::class) public constructor(
    val fullPage: Boolean = false,
    val clip: Rectangle? = null,
    val omitBackground: Boolean = false,
    val quality: Int = 90,
    val format: ScreenshotFormat = ScreenshotFormat.PNG,
    val scale: Double = 1.0,
    /** Draw snapshot refs on the image (agent-browser `screenshot --annotate`); page screenshots only. */
    val annotate: Boolean = false,
    /** null uses [TimeoutConfig.default]. */
    val timeoutMs: Long? = null
) {
    init {
        require(!(fullPage && clip != null)) { "fullPage and clip are mutually exclusive" }
    }
}

/** Image formats for [ScreenshotOptions]. */
public enum class ScreenshotFormat {
    PNG,
    JPEG,
    WEBP
}

/**
 * A captured image. [annotations] lists the refs drawn by `ScreenshotOptions(annotate = true)`.
 */
public data class ScreenshotResult(
    val bytes: ByteArray,
    val width: Int,
    val height: Int,
    val annotations: List<Annotation> = emptyList()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ScreenshotResult) return false
        return bytes.contentEquals(other.bytes) && width == other.width && height == other.height && annotations == other.annotations
    }

    override fun hashCode(): Int {
        var result = bytes.contentHashCode()
        result = 31 * result + width
        result = 31 * result + height
        result = 31 * result + annotations.hashCode()
        return result
    }
}

/** A snapshot ref drawn on an annotated screenshot; [box] is in viewport CSS pixels. */
public data class Annotation(
    val ref: String,
    val role: String,
    val name: String,
    val box: Rectangle
)

/** A rectangle in CSS pixels. */
public data class Rectangle(
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double
)

/** A viewport size in CSS pixels. */
public data class ViewportSize(
    val width: Int,
    val height: Int
)

/** A geolocation override for [Page.setGeolocation]. */
public data class Geolocation(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Double = 0.0
)

/** HTTP authentication credentials for [Page.setHTTPCredentials]. */
public data class HttpCredentials(
    val username: String,
    val password: String
) {
    /** Never prints the password. */
    override fun toString(): String = "HttpCredentials(username=$username, password=***)"
}

/**
 * Network throttling for [Page.emulateNetworkConditions]. [latencyMs] is the added round-trip time;
 * throughputs are in bytes per second (0 = unthrottled).
 */
public data class NetworkConditions(
    val offline: Boolean = false,
    val latencyMs: Long = 0,
    val downloadThroughput: Long = 0,
    val uploadThroughput: Long = 0
) {
    /** Common presets. */
    public companion object {
        /** 750 kbit/s down, 250 kbit/s up, 100 ms latency. */
        public val Slow3G: NetworkConditions = NetworkConditions(latencyMs = 100, downloadThroughput = 750_000 / 8, uploadThroughput = 250_000 / 8)

        /** 1.6 Mbit/s down, 750 kbit/s up, 56 ms latency. */
        public val Fast3G: NetworkConditions = NetworkConditions(latencyMs = 56, downloadThroughput = 1_600_000 / 8, uploadThroughput = 750_000 / 8)

        /** Every request fails. */
        public val Offline: NetworkConditions = NetworkConditions(offline = true)
    }
}

/** Directions for [Page.scroll]. */
public enum class ScrollDirection {
    Up,
    Down,
    Left,
    Right
}

/**
 * Hot, non-replaying event streams, the Kotlin form of Playwright's `page.on(...)`. Subscribe *before*
 * triggering the action you want to observe, or use the action-scoped helpers such as
 * `page.waitForNavigation { click(...) }`.
 */
public interface PageEvents {
    /** Console messages. */
    public val console: SharedFlow<ConsoleMessage>

    /** Committed navigations. */
    public val navigation: SharedFlow<NavigationEvent>

    /** Lifecycle states reached by the document. */
    public val loadState: SharedFlow<LoadState>

    /**
     * JavaScript dialogs that reach automation. Collecting this flow does not register a listener:
     * on Android a dialog is only emitted while a [Page.onDialog] handler or a `waitForDialog` is
     * active (otherwise the host WebChromeClient receives it).
     */
    public val dialog: SharedFlow<DialogEvent>

    /** Requests issued by the page. */
    public val request: SharedFlow<Request>

    /** Responses received; a failed request is reported with status 0 and the error as [Response.statusText]. */
    public val response: SharedFlow<Response>

    /** Downloads started by the page. */
    public val download: SharedFlow<Download>

    /** File choosers opened by the page. */
    public val fileChooser: SharedFlow<FileChooser>

    /** Uncaught exceptions and unhandled promise rejections (Playwright's `pageerror`). */
    public val pageError: SharedFlow<PageError>
}

/** Which stored messages [Page.consoleMessages] and [Page.pageErrors] return. */
public enum class PageMessageFilter {
    /** Only messages after the last committed main-frame navigation (Playwright's default). */
    SinceNavigation,

    /** Every stored message, across navigations. */
    All
}

/**
 * An uncaught exception or unhandled promise rejection. [name] is empty for thrown non-Error values.
 * [location] is where it was thrown when the backend reports it.
 */
public data class PageError(
    val name: String,
    val message: String,
    val stack: String? = null,
    val location: SourceLocation? = null
) {
    /** `"Name: message"`, or just the message when there is no name. */
    val text: String get() = if (name.isEmpty()) message else "$name: $message"
}

/**
 * A console message. [type] is one of Playwright's `ConsoleMessage.type()` values: `log`, `debug`,
 * `info`, `error`, `warning`, `dir`, `dirxml`, `table`, `trace`, `clear`, `startGroup`,
 * `startGroupCollapsed`, `endGroup`, `assert`, `profile`, `profileEnd`, `count`, `timeEnd`.
 */
public data class ConsoleMessage(
    val type: String,
    val text: String,
    val location: SourceLocation?
)

/** Where a console message was logged. */
public data class SourceLocation(
    val url: String,
    val lineNumber: Int,
    val columnNumber: Int
)

/** A committed navigation. */
public data class NavigationEvent(
    val url: String,
    val status: Int,
    val navigationType: NavigationType
)

/** Whether a navigation loaded a new document. */
public enum class NavigationType {
    SameDocument,
    NewDocument
}

/** A JavaScript dialog. */
public data class DialogEvent(
    val type: DialogType,
    val message: String,
    val defaultPrompt: String? = null
)

/** JavaScript dialog kinds. */
public enum class DialogType {
    Alert,
    Confirm,
    Prompt,
    /** A `beforeunload` confirmation; [DialogResponse.Accept] leaves the page, Dismiss stays. */
    BeforeUnload
}

/** How a dialog is answered (Playwright `dialog.accept()` / `dialog.dismiss()`). */
public sealed class DialogResponse {
    /** Accepts the dialog. */
    public data object Accept : DialogResponse()

    /** Dismisses the dialog. */
    public data object Dismiss : DialogResponse()

    /** Accepts a prompt with [text]. */
    public data class Text(val text: String) : DialogResponse()
}

/** Runtime metrics of a document. */
public data class PageMetrics(
    val documents: Int? = null,
    val frames: Int? = null,
    val jsHeapUsedSize: Long? = null,
    val jsHeapTotalSize: Long? = null,
    val resources: Int? = null,
    val networkIdle: Boolean = true
)

/** A service worker registration. */
public class ServiceWorker(
    public val url: String,
    public val state: String,
    public val scriptUrl: String,
    private val unregisterAction: suspend () -> Boolean
) {
    /** Unregisters the worker; true on success. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun unregister(): Boolean = unregisterAction()
}

/**
 * Options for [Page.pdf]. The defaults work on every backend: [format] null means Letter on CDP and
 * Android and the content canvas ([PdfFormat.Content]) on iOS, and margins default to zero like
 * Playwright. [printBackground] defaults to true (Playwright: false) because WebView backends
 * always print backgrounds. Options a backend cannot honor throw [KmpwrightUnsupportedException].
 */
public data class PdfOptions(
    /** Paper size; null uses the backend default (Letter, or [PdfFormat.Content] on iOS). */
    val format: PdfFormat? = null,
    val landscape: Boolean = false,
    val displayHeaderFooter: Boolean = false,
    val headerTemplate: String = "",
    val footerTemplate: String = "",
    val printBackground: Boolean = true,
    val scale: Double = 1.0,
    val width: Double? = null,
    val height: Double? = null,
    val margin: PdfMargin = PdfMargin(),
    val pageRanges: String = "",
    /** null uses [TimeoutConfig.default]. */
    val timeoutMs: Long? = null
)

/** PDF page margins as CSS lengths (`in`, `cm`, `mm`, `px`, `pt`); zero by default, like Playwright. */
public data class PdfMargin(
    val top: String = "0",
    val bottom: String = "0",
    val left: String = "0",
    val right: String = "0"
)

/** Paper formats for [PdfOptions]. */
public enum class PdfFormat {
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
    A6,
    /** WKWebView's unpaginated content canvas; physical print layout options are unavailable. */
    Content
}

/** A rendered PDF. */
public data class PdfResult(
    val bytes: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PdfResult) return false
        return bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int = bytes.contentHashCode()
}

/** Options for [Page.addScriptTag]. */
public data class ScriptTagOptions(
    val url: String? = null,
    val content: String? = null,
    val type: String = "text/javascript",
    val id: String = "",
    val async: Boolean = false,
    val defer: Boolean = false
)

/** Options for [Page.addStyleTag]. */
public data class StyleTagOptions(
    val url: String? = null,
    val content: String? = null,
    val id: String = ""
)

/** Values for `prefers-color-scheme`. */
public enum class ColorScheme {
    Light,
    Dark,
    NoPreference
}

/** Download handling for [Page.setDownloadBehavior]. */
public enum class DownloadBehavior {
    SaveToDownloadPath,
    Deny,
    Allow
}

/** Options for [Tracing.start]. */
public data class TracingOptions(
    /** Comma-separated trace event categories. */
    val categories: String = "blink,devtools.timeline,v8,benchmark",
    /** Whether to include screenshots in the trace. */
    val screenshots: Boolean = false,
    /** File the trace is also written to when stopped. */
    val path: String? = null
)
