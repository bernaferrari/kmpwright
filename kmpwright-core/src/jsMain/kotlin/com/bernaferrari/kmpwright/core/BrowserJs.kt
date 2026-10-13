package com.bernaferrari.kmpwright.core

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.w3c.dom.HTMLIFrameElement
import org.w3c.dom.url.URL
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.js.Promise

/**
 * Browser host for a [Browser]: the `<iframe>` to drive. Without one, each page creates a hidden
 * iframe of the configured viewport size. Only **same-origin** documents can be automated — browser
 * security keeps page JavaScript out of cross-origin frames — so use `setContent` or URLs served by
 * the hosting site.
 */
public class WebPlatformContext(public val iframe: HTMLIFrameElement? = null) : PlatformContext

/**
 * Drives a same-origin iframe from the embedding page. Everything runs on the browser's single
 * thread. Scripts that KMPwright installs at document start on the native backends (diagnostics,
 * dialog handling, init scripts) are installed as soon as the new document appears, which can be
 * after its first inline scripts ran.
 */
internal class IframeWebViewController(
    private val config: KmpwrightConfig,
    providedFrame: HTMLIFrameElement?,
) : WebViewController {
    private val ownsFrame = providedFrame == null
    private val frame: HTMLIFrameElement = providedFrame ?: createHiddenFrame()
    private val nonce = secureRandomBytes(12).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    private val hostKey = "__kmpwright_$nonce"
    private var disposed = false
    private var navigationCallback: ((NavigationResult) -> Unit)? = null
    private var navigationGuard: ((String) -> Boolean)? = null
    private var lifecycleListener: ((PageLifecycleEvent) -> Unit)? = null
    private val dialogListeners = mutableListOf<DialogListener>()
    private val diagnosticsListeners = mutableListOf<PageDiagnosticsListener>()
    private val initScripts = linkedMapOf<String, String>()
    private var initScriptCounter = 0
    private var reportedUrl: String? = null
    private var reportedTitle: String? = null
    private var watchTimer: Int? = null
    private val onLoad: (dynamic) -> Unit = { handleLoad() }

    init {
        // One host object per controller; injected scripts reach it through window.parent.
        val host: dynamic = js("({})")
        host.diagnostic = { json: String -> handleDiagnostic(json) }
        host.state = { title: String -> handleState(title) }
        host.dialog = { type: String, message: String, defaultPrompt: String? -> handleDialog(type, message, defaultPrompt) }
        host.unload = { watchForNextDocument() }
        window.asDynamic()[hostKey] = host
        frame.addEventListener("load", onLoad)
        if (frameDocumentOrNull() != null) installIntoCurrentDocument()
    }

    private fun createHiddenFrame(): HTMLIFrameElement {
        val created = document.createElement("iframe") as HTMLIFrameElement
        val width = config.viewport?.width ?: 1280
        val height = config.viewport?.height ?: 800
        created.setAttribute("aria-hidden", "true")
        created.setAttribute("style", "position:fixed;left:-20000px;top:0;width:${width}px;height:${height}px;border:0;")
        document.body?.appendChild(created) ?: throw KmpwrightException("The browser backend needs a document body")
        return created
    }

    private fun frameWindow(): dynamic = frame.contentWindow?.asDynamic()

    /** The frame's document, or null while it is cross-origin (or not yet created). */
    private fun frameDocumentOrNull(): dynamic = try {
        frameWindow()?.document
    } catch (_: dynamic) {
        null
    }

    private fun requireFrameWindow(): dynamic {
        if (disposed) throw KmpwrightException("Page is closed")
        val win = frameWindow() ?: throw KmpwrightException("The page frame is not attached to a document")
        try {
            win.document
        } catch (_: dynamic) {
            throw KmpwrightException("The page left this site's origin; the browser backend can only automate same-origin documents")
        }
        return win
    }

    // ------------------------------------------------------------------ document lifecycle

    /**
     * Polls for the document that replaces the current one so the KMPwright scripts are installed
     * while it is still parsing. Stops at the load event, which installs them as a fallback.
     */
    private fun watchForNextDocument() {
        val previous = frameDocumentOrNull()
        watchTimer?.let(window::clearInterval)
        watchTimer = window.setInterval({
            val current = frameDocumentOrNull()
            if (current != null && current != previous && current[hostKey] != true) installIntoCurrentDocument()
        }, 4)
    }

    private fun stopWatching() {
        watchTimer?.let(window::clearInterval)
        watchTimer = null
    }

    private fun handleLoad() {
        if (disposed) return
        stopWatching()
        val doc = frameDocumentOrNull()
        if (doc != null && doc[hostKey] != true) installIntoCurrentDocument()
        val url = getUrl() ?: frame.src
        reportedUrl = url
        reportedTitle = getTitle()
        val result = if (doc == null) {
            NavigationResult(url, 0, false, "The page left this site's origin; the browser backend can only automate same-origin documents")
        } else {
            NavigationResult(url, 200, true)
        }
        lifecycleListener?.invoke(PageLifecycleEvent.TitleChanged(reportedTitle))
        navigationCallback?.invoke(result)
    }

    private fun installIntoCurrentDocument() {
        val win = frameWindow() ?: return
        val doc = frameDocumentOrNull() ?: return
        doc[hostKey] = true
        val host = "window.parent[${jsStringLiteral(hostKey)}]"
        val bootstrap = """
            (function(){
              var host = $host;
              if (!host) return;
              window.addEventListener('pagehide', function() { try { host.unload(); } catch (e) {} }, true);
              window.alert = function(message) { host.dialog('alert', String(message === undefined ? '' : message), null); };
              window.confirm = function(message) { return host.dialog('confirm', String(message === undefined ? '' : message), null) === true; };
              window.prompt = function(message, value) {
                var answer = host.dialog('prompt', String(message === undefined ? '' : message), value === undefined ? '' : String(value));
                return typeof answer === 'string' ? answer : null;
              };
              var queued = false;
              function ping() {
                if (queued) return; queued = true;
                setTimeout(function() { queued = false; try { host.state(String(document.title || '')); } catch (e) {} }, 0);
              }
              ['pushState', 'replaceState'].forEach(function(name) {
                var original = history[name];
                history[name] = function() { var result = original.apply(this, arguments); ping(); return result; };
              });
              window.addEventListener('hashchange', ping, true);
              window.addEventListener('popstate', ping, true);
              try { new MutationObserver(ping).observe(document, {subtree: true, childList: true, characterData: true}); } catch (e) {}
              // Scrolling an element into view (actions do) also scrolls every ancestor of the frame in the
              // hosting page. Keep it inside the frame: restore the host's scroll positions afterwards.
              var frameElement = window.frameElement;
              function hostScrollers() {
                var saved = [];
                try {
                  for (var node = frameElement && frameElement.parentElement; node; node = node.parentElement) saved.push([node, node.scrollLeft, node.scrollTop]);
                  var root = frameElement && frameElement.ownerDocument.scrollingElement;
                  if (root) saved.push([root, root.scrollLeft, root.scrollTop]);
                } catch (e) {}
                return saved;
              }
              function containScroll(proto, name) {
                var original = proto && proto[name];
                if (typeof original !== 'function') return;
                proto[name] = function() {
                  var saved = hostScrollers();
                  try { return original.apply(this, arguments); }
                  finally {
                    saved.forEach(function(entry) {
                      var node = entry[0];
                      if (node.scrollLeft !== entry[1] || node.scrollTop !== entry[2]) node.scrollTo({left: entry[1], top: entry[2], behavior: 'instant'});
                    });
                  }
                };
              }
              containScroll(Element.prototype, 'scrollIntoView');
              containScroll(Element.prototype, 'scrollIntoViewIfNeeded');
              containScroll(HTMLElement.prototype, 'focus');
              containScroll(SVGElement.prototype, 'focus');
            })();
        """.trimIndent()
        runInFrame(win, bootstrap)
        runInFrame(win, buildPageDiagnosticsScript("(function(h){ return h && function(s){ h.diagnostic(s); }; })($host)", nonce, wrapConsole = true))
        initScripts.values.forEach { runInFrame(win, it) }
        val url = getUrl()
        if (url != null) lifecycleListener?.invoke(PageLifecycleEvent.Committed(url))
    }

    private fun runInFrame(win: dynamic, script: String) {
        try {
            win.eval(script)
        } catch (error: dynamic) {
            kotlin.js.console.warn("KMPwright browser script failed: ${jsErrorMessage(error)}")
        }
    }

    private fun handleDiagnostic(json: String) {
        if (disposed) return
        when (val diagnostic = parsePageDiagnosticPayload(json, nonce)) {
            is PageDiagnostic.Console -> diagnosticsListeners.toList().forEach { it.onConsoleMessage(diagnostic.message) }
            is PageDiagnostic.Error -> diagnosticsListeners.toList().forEach { it.onPageError(diagnostic.error) }
            PageDiagnostic.DocumentStarted -> diagnosticsListeners.toList().forEach { it.onMainFrameDocumentCommitted() }
            null -> Unit
        }
    }

    private fun handleState(title: String) {
        if (disposed) return
        val reported = title.take(1024)
        if (reported != reportedTitle) {
            reportedTitle = reported
            lifecycleListener?.invoke(PageLifecycleEvent.TitleChanged(reported))
        }
        val url = getUrl() ?: return
        if (url != reportedUrl && frameDocumentOrNull()?.readyState == "complete") {
            reportedUrl = url
            lifecycleListener?.invoke(PageLifecycleEvent.SameDocument(url))
        }
    }

    /**
     * Page dialogs are synchronous, but dialog handlers are suspend functions, and a web page cannot
     * block its own thread on a coroutine. Handlers therefore observe every dialog, and the page gets
     * the Playwright default answer: alerts close, confirms are dismissed (false), prompts return null.
     */
    private fun handleDialog(type: String, message: String, defaultPrompt: String?): Any? {
        val kind = when (type) {
            "confirm" -> DialogType.Confirm
            "prompt" -> DialogType.Prompt
            else -> DialogType.Alert
        }
        dialogListeners.toList().forEach { it.onDialogObserved(kind, message, defaultPrompt) }
        return null
    }

    override fun setLifecycleListener(listener: ((PageLifecycleEvent) -> Unit)?) {
        lifecycleListener = listener
    }

    override val reportsHttpStatus: Boolean get() = false

    // ------------------------------------------------------------------ navigation

    override fun setNavigationCallback(callback: ((NavigationResult) -> Unit)?) {
        navigationCallback = callback
    }

    override fun setNavigationGuard(guard: ((String) -> Boolean)?) {
        navigationGuard = guard
    }

    override fun loadUrl(url: String) {
        if (url == "about:blank") {
            beginNavigation()
            frame.removeAttribute("srcdoc")
            frame.src = "about:blank"
            return
        }
        val resolved = try {
            URL(url, window.location.href)
        } catch (_: Throwable) {
            navigationCallback?.invoke(NavigationResult(url, 0, false, "Invalid URL: $url"))
            return
        }
        if (resolved.origin != window.location.origin) {
            navigationCallback?.invoke(NavigationResult(url, 0, false,
                "The browser backend can only open pages of this site (${window.location.origin}); browsers keep page " +
                    "JavaScript out of other origins. Use setContent, or the Android, iOS or JVM backend for $url"))
            return
        }
        if (config.allowedOriginRules.isNotEmpty() && config.allowedOriginRules.none { originRuleMatches(it, resolved.href) }) {
            navigationCallback?.invoke(NavigationResult(url, 0, false, "Navigation origin is not allowed by KmpwrightConfig: $url"))
            return
        }
        if (navigationGuard?.invoke(resolved.href) == false) {
            navigationCallback?.invoke(NavigationResult(url, 0, false, "Navigation denied by action policy: $url"))
            return
        }
        beginNavigation()
        frame.removeAttribute("srcdoc")
        frame.src = resolved.href
    }

    override fun loadHtml(content: String, baseUrl: String?) {
        beginNavigation()
        val html = if (baseUrl != null) "<base href=\"${baseUrl.replace("\"", "&quot;")}\">$content" else content
        frame.srcdoc = html
    }

    private fun beginNavigation() {
        if (disposed) throw KmpwrightException("Page is closed")
        watchForNextDocument()
    }

    override fun goBack() {
        watchForNextDocument()
        requireFrameWindow().history.back()
    }

    override fun goForward() {
        watchForNextDocument()
        requireFrameWindow().history.forward()
    }

    override fun reload() {
        watchForNextDocument()
        requireFrameWindow().location.reload()
    }

    override fun getUrl(): String? = try {
        frameWindow()?.location?.href as? String
    } catch (_: dynamic) {
        null
    }

    override fun getTitle(): String? = (frameDocumentOrNull()?.title as? String)?.takeIf { it.isNotEmpty() } ?: reportedTitle

    // ------------------------------------------------------------------ scripts

    override val supportsPromiseEvaluation: Boolean get() = true
    override fun normalizeJavaScriptResult(raw: String?): String? = raw

    override fun evaluateJavaScript(script: String, callback: (String?) -> Unit) {
        val win = try { requireFrameWindow() } catch (_: Throwable) { callback(null); return }
        try {
            val value = win.eval(script)
            if (value != null && jsTypeOf(value.then) == "function") {
                (value.unsafeCast<Promise<Any?>>()).then({ callback(serialize(win, it)) }, { callback(null) })
            } else {
                callback(serialize(win, value))
            }
        } catch (_: dynamic) {
            callback(null)
        }
    }

    override fun injectJavaScript(script: String, callback: ((String?) -> Unit)?) {
        evaluateJavaScript(script) { callback?.invoke(it) }
    }

    /**
     * Same semantics as the other backends: the script is tried as an awaited expression, then (if it
     * does not parse as one) as a classic program whose completion value is returned, then as an async
     * function body (top-level `return`/`await`). A stage only runs after the previous one failed to
     * *parse*, so side effects never run twice. Strings are returned as-is, objects as JSON.
     */
    override suspend fun evaluateJavaScriptAsync(script: String): String? {
        val win = requireFrameWindow()
        val expression = script.trim().removeSuffix(";").removePrefix("return ")
        val value: dynamic = try {
            // The newline keeps a trailing `// comment` from swallowing the paren.
            evalParsed(win, "(async function() { return await ($expression\n); })()")
        } catch (_: ParseFailure) {
            if (parsesAsProgram(win, script)) evalProgram(win, script)
            else try {
                evalParsed(win, "(async function() {\n$script\n})()")
            } catch (failure: ParseFailure) {
                throw KmpwrightException("JavaScript evaluation failed: ${failure.message}")
            }
        }
        val settled = if (value != null && jsTypeOf(value.then) == "function") settle(value) else value
        return serialize(win, settled)
    }

    /** Awaits a page Promise. Rejection values come from the frame's realm, so they are read dynamically. */
    private suspend fun settle(promise: dynamic): dynamic = suspendCancellableCoroutine { continuation ->
        promise.then(
            { result: dynamic -> if (continuation.isActive) continuation.resume(result) },
            { error: dynamic ->
                if (continuation.isActive) {
                    continuation.resumeWithException(KmpwrightException("JavaScript evaluation failed: ${jsErrorMessage(error)}"))
                }
            },
        )
    }

    private class ParseFailure(message: String) : Exception(message)

    /** Evaluates [source]; a syntax error (thrown before anything ran) becomes [ParseFailure]. */
    private fun evalParsed(win: dynamic, source: String): dynamic = try {
        win.eval(source)
    } catch (error: dynamic) {
        val name = error?.name as? String
        if (name == "SyntaxError") throw ParseFailure(jsErrorMessage(error))
        throw KmpwrightException("JavaScript evaluation failed: ${jsErrorMessage(error)}")
    }

    /** Parses [script] as a classic program without running it (static blocks reject `return`/`await`). */
    private fun parsesAsProgram(win: dynamic, script: String): Boolean = try {
        win.eval("(function() { return; class KmpwrightProgramCheck { static {\n$script\n} } })")
        true
    } catch (_: dynamic) {
        false
    }

    private fun evalProgram(win: dynamic, script: String): dynamic = try {
        win.eval(script)
    } catch (error: dynamic) {
        throw KmpwrightException("JavaScript evaluation failed: ${jsErrorMessage(error)}")
    }

    private fun serialize(win: dynamic, value: dynamic): String? {
        if (value == null || value == undefined) return null
        if (jsTypeOf(value) == "string") return value as String
        val json = try { win.JSON.stringify(value) } catch (_: dynamic) { null }
        return json as? String
    }

    /** Errors may come from the frame's realm (or be any thrown value), so read them dynamically. */
    private fun jsErrorMessage(error: dynamic): String {
        val message = (if (error != null) error.message else null) as? String
        return message?.takeIf { it.isNotEmpty() } ?: "$error"
    }

    override suspend fun addInitScript(script: String): String {
        val id = "init-${++initScriptCounter}"
        initScripts[id] = script
        return id
    }

    override suspend fun removeInitScript(id: String) {
        initScripts.remove(id) ?: throw KmpwrightException("Unknown init-script identifier: $id")
    }

    // ------------------------------------------------------------------ listeners

    override fun addRequestListener(listener: RequestListener) {}
    override fun removeRequestListener(listener: RequestListener) {}
    override fun getPendingRequestCount(): Int = 0
    override fun resetRequestCount() {}
    override fun setRequestIdleTimeout(timeoutMs: Long) {}

    override fun addDialogListener(listener: DialogListener) { dialogListeners += listener }
    override fun removeDialogListener(listener: DialogListener) { dialogListeners -= listener }

    override fun addDiagnosticsListener(listener: PageDiagnosticsListener) { diagnosticsListeners += listener }
    override fun removeDiagnosticsListener(listener: PageDiagnosticsListener) { diagnosticsListeners -= listener }

    override fun addRouteHandler(urlPattern: String, handler: suspend (Route) -> Unit) {
        throw KmpwrightUnsupportedException("Request routing is unavailable in the browser backend")
    }

    override fun removeRouteHandler(urlPattern: String) {}
    override fun removeAllRouteHandlers() {}

    // ------------------------------------------------------------------ cookies (document.cookie of this site)

    private fun cookieDocument(): dynamic = requireFrameWindow().document

    override fun setCookie(cookie: Cookie) {
        val parts = mutableListOf("${cookie.name}=${cookie.value}", "path=${cookie.path}")
        cookie.expires?.let { parts += "expires=${kotlin.js.Date(it * 1000).toUTCString()}" }
        if (cookie.secure) parts += "secure"
        cookie.sameSite?.let { parts += "samesite=$it" }
        cookieDocument().cookie = parts.joinToString("; ")
    }

    override fun getCookie(name: String, domain: String, callback: (Cookie?) -> Unit) {
        getAllCookies { cookies -> callback(cookies.firstOrNull { it.name == name }) }
    }

    override fun getAllCookies(callback: (List<Cookie>) -> Unit) {
        val raw = (cookieDocument().cookie as? String).orEmpty()
        val host = window.location.hostname
        callback(raw.split(";").map { it.trim() }.filter { it.isNotEmpty() }.map { pair ->
            Cookie(pair.substringBefore('='), pair.substringAfter('=', ""), host)
        })
    }

    override fun deleteCookie(name: String, domain: String) {
        cookieDocument().cookie = "$name=; path=/; expires=Thu, 01 Jan 1970 00:00:00 GMT"
    }

    override fun deleteAllCookies() {
        getAllCookies { cookies -> cookies.forEach { deleteCookie(it.name, it.domain) } }
    }

    // ------------------------------------------------------------------ capture and emulation

    override fun takeScreenshot(options: ScreenshotOptions, callback: (ByteArray?, Int, Int) -> Unit) = callback(null, 0, 0)

    override suspend fun captureScreenshot(options: ScreenshotOptions): ScreenshotResult =
        throw KmpwrightUnsupportedException("Screenshots are unavailable in the browser backend: web pages cannot capture rendered pixels")

    override fun generatePdf(options: PdfOptions, callback: (ByteArray?) -> Unit) = callback(null)

    override suspend fun generatePdfAsync(options: PdfOptions): ByteArray =
        throw KmpwrightUnsupportedException("PDF export is unavailable in the browser backend")

    override fun getViewportSize(): Pair<Int, Int> = frame.clientWidth to frame.clientHeight

    override fun setViewportSize(width: Int, height: Int) {
        frame.style.width = "${width}px"
        frame.style.height = "${height}px"
    }

    override fun setZoomScale(scale: Double) {
        throw KmpwrightUnsupportedException("Zoom is unavailable in the browser backend")
    }

    override fun getZoomScale(): Double = 1.0

    override fun setUserAgent(userAgent: String) {
        throw KmpwrightUnsupportedException("The browser backend cannot change the user agent")
    }

    override fun setDeviceScaleFactor(scale: Double) {
        throw KmpwrightUnsupportedException("The browser backend cannot change the device scale factor")
    }

    // ------------------------------------------------------------------ lifecycle

    override fun dispose() {
        if (disposed) return
        disposed = true
        stopWatching()
        frame.removeEventListener("load", onLoad)
        window.asDynamic()[hostKey] = undefined
        navigationCallback = null
        lifecycleListener = null
        dialogListeners.clear()
        diagnosticsListeners.clear()
        if (ownsFrame) frame.remove()
    }

    internal fun createPage(tabRuntime: PageTabRuntime?): WebPage = WebPage(this, config, tabRuntime)
}

internal class WebPage(
    controller: WebViewController,
    config: KmpwrightConfig,
    tabRuntime: PageTabRuntime?,
) : BasePage(controller, config, tabRuntime) {
    override fun capabilities(): PageCapabilities = webPageCapabilities()

    override fun createPlatformLocator(selector: String): BaseLocator = WebLocator(controller, selector, config)
    override fun createTouch(): Touchscreen = SyntheticTouch(controller, config)
    override fun createMouse(): Mouse = SyntheticMouse(controller, config)
    override fun createKeyboard(): Keyboard = SyntheticKeyboard(controller, config)

    override fun createScreenshotController(): ScreenshotController =
        throw KmpwrightUnsupportedException("Screenshots are unavailable in the browser backend: web pages cannot capture rendered pixels")

    override suspend fun waitForDownload(url: String?, timeoutMs: Long?): Download =
        throw KmpwrightUnsupportedException("Downloads are unavailable in the browser backend")

    override suspend fun waitForFileChooser(timeoutMs: Long?): FileChooser =
        throw KmpwrightUnsupportedException("File choosers are unavailable in the browser backend")

    override suspend fun setDownloadPath(path: String): Unit =
        throw KmpwrightUnsupportedException("Downloads are unavailable in the browser backend")

    override suspend fun setDownloadBehavior(behavior: DownloadBehavior): Unit =
        throw KmpwrightUnsupportedException("Downloads are unavailable in the browser backend")
}

internal class WebLocator(
    controller: WebViewLocatorController,
    selector: String,
    config: KmpwrightConfig,
    options: FilterOptions? = null,
) : BaseLocator(controller, selector, config, options) {
    override fun createLocator(newSelector: String, options: FilterOptions?): Locator =
        WebLocator(controller, newSelector, config, options)

    override suspend fun evaluateJavaScriptAsync(script: String): String? =
        controller.normalizeJavaScriptResult(controller.evaluateJavaScriptAsync(script))
}

internal fun webPageCapabilities(): PageCapabilities = PageCapabilities(
    backend = BackendKind.Browser,
    touchInput = SupportLevel.Partial,
    mouseInput = SupportLevel.Partial,
    keyboardInput = SupportLevel.Partial,
    browserContexts = SupportLevel.Partial,
    tabs = SupportLevel.Partial,
    initScripts = SupportLevel.Partial,
    accessibilityAudit = SupportLevel.Partial,
    webVitals = SupportLevel.Partial,
    reactInspection = SupportLevel.Supported,
    notes = listOf(
        "The browser backend drives a same-origin <iframe>: setContent documents and pages of the hosting site. " +
            "Browsers keep page JavaScript out of other origins, so other websites cannot be opened.",
        "Input is synthesized as DOM events (untrusted events); there is no native input injection.",
        "Scrolling an element into view (and focusing it) stays inside the frame; the hosting page does not scroll.",
        "KMPwright scripts and init scripts are installed as soon as a new document appears, possibly after its first inline scripts.",
        "Dialogs are reported to handlers but always answered with the default: confirm() is false, prompt() is null.",
        "Request routing, network history, screenshots, PDF, downloads and user agent changes are unavailable.",
        "Cookies are the hosting site's document.cookie; HttpOnly cookies are not visible.",
    ),
)

public actual class Browser public actual constructor(
    private val context: PlatformContext,
    private val config: KmpwrightConfig,
) {
    private val pages = mutableListOf<Page>()
    private val ownedContexts = mutableListOf<BrowserContext>()
    private var leaseContext: BrowserContext? = null
    private var closed = false
    private var consumedProvidedFrame = false
    private val tabRuntime = object : PageTabRuntime {
        override suspend fun openTab(source: Page, url: String?): NavigationResult {
            val page = newPage()
            try { (source as? BasePage)?.inheritSetupTo(page) } catch (error: Throwable) { page.close(); throw error }
            return page.goto(url ?: "about:blank")
        }

        override fun pageClosed(page: Page) {
            pages.remove(page)
        }
    }

    private val _isConnected = MutableStateFlow(true)
    public actual val isConnected: StateFlow<Boolean> = _isConnected

    private val _connectionError = MutableStateFlow<String?>(null)
    public actual val connectionError: StateFlow<String?> = _connectionError

    init {
        pages += createPage()
    }

    private fun createPage(): Page {
        val provided = (context as? WebPlatformContext)?.iframe?.takeIf { !consumedProvidedFrame }
        if (provided != null) consumedProvidedFrame = true
        return IframeWebViewController(config, provided).createPage(tabRuntime)
    }

    internal actual suspend fun beginIndependentLease() {
        check(leaseContext == null) { "Browser already has an active lease" }
        endIndependentLease()
        leaseContext = newContext()
    }

    internal actual suspend fun endIndependentLease() {
        ownedContexts.toList().forEach { it.close() }
        ownedContexts.clear()
        leaseContext = null
        pages.toList().forEach { it.close() }
        pages.clear()
    }

    public actual suspend fun newPage(): Page {
        check(!closed) { "Browser is closed" }
        leaseContext?.let { return it.newPage() }
        return createPage().also { pages += it }
    }

    public actual suspend fun targets(): List<BrowserTarget> = throw KmpwrightUnsupportedException("Browser target discovery requires CDP")
    public actual suspend fun attachTarget(targetId: String): Page = throw KmpwrightUnsupportedException("Browser target attachment requires CDP")
    public actual suspend fun activateTarget(targetId: String): Unit = throw KmpwrightUnsupportedException("Browser target activation requires CDP")
    public actual suspend fun closeTarget(targetId: String): Boolean = throw KmpwrightUnsupportedException("Browser target closing requires CDP")
    public actual suspend fun newWindow(): Page = throw KmpwrightUnsupportedException("Browser windows require CDP")

    /** Contexts get their own frames; cookies and storage are still the hosting site's. */
    public actual suspend fun newContext(): BrowserContext {
        check(!closed) { "Browser is closed" }
        return BrowserContext(null, config).also { ownedContexts += it }
    }

    public actual fun pages(): List<Page> = pages.toList() + ownedContexts.flatMap { it.pages() }

    public actual fun page(): Page =
        leaseContext?.let { it.pages().lastOrNull() ?: it.createPage() } ?: pages.lastOrNull() ?: throw IllegalStateException("No page available")

    public actual suspend fun version(): BrowserVersion =
        BrowserVersion("Browser", "iframe", window.navigator.userAgent)

    public actual fun dispose() {
        if (closed) return
        closed = true
        _isConnected.value = false
        ownedContexts.toList().forEach { it.dispose() }
        ownedContexts.clear()
        leaseContext = null
        pages.toList().forEach { it.dispose() }
        pages.clear()
    }

    public actual suspend fun close() {
        if (closed) return
        try {
            ownedContexts.toList().forEach { it.close() }
            pages.toList().forEach { it.close() }
        } finally {
            dispose()
        }
    }
}

public actual class BrowserContext public actual constructor(
    private val context: PlatformContext?,
    private val config: KmpwrightConfig,
) {
    private val pages = mutableListOf<Page>()
    private var closed = false
    private var consumedProvidedFrame = false
    private val tabRuntime = object : PageTabRuntime {
        override suspend fun openTab(source: Page, url: String?): NavigationResult {
            val page = newPage()
            try { (source as? BasePage)?.inheritSetupTo(page) } catch (error: Throwable) { page.close(); throw error }
            return page.goto(url ?: "about:blank")
        }

        override fun pageClosed(page: Page) {
            pages.remove(page)
        }
    }

    public actual suspend fun newPage(): Page = createPage()

    internal fun createPage(): Page {
        check(!closed) { "BrowserContext is closed" }
        val provided = (context as? WebPlatformContext)?.iframe?.takeIf { !consumedProvidedFrame }
        if (provided != null) consumedProvidedFrame = true
        return IframeWebViewController(config, provided).createPage(tabRuntime).also { pages += it }
    }

    public actual fun pages(): List<Page> = pages.toList()

    public actual suspend fun clearCookies() {
        pages.firstOrNull()?.let { page -> page.clearCookies() }
    }

    /** Frames share the hosting site's storage, so clearing it would wipe the site's own data. */
    public actual suspend fun clearData(): Unit =
        throw KmpwrightUnsupportedException("clearData is unavailable in the browser backend: frames share the hosting site's storage")

    public actual fun dispose() {
        if (closed) return
        closed = true
        pages.toList().forEach { it.dispose() }
        pages.clear()
    }

    public actual suspend fun close() {
        if (closed) return
        try { pages.toList().forEach { it.close() } } finally { dispose() }
    }
}
