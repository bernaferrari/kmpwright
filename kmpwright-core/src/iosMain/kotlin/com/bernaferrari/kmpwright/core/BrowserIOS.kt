package com.bernaferrari.kmpwright.core

import com.bernaferrari.kmpwright.core.ios.toByteArrayPinned
import com.bernaferrari.kmpwright.core.ios.toNSData
import kotlinx.atomicfu.update
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.toKString
import kotlinx.cinterop.useContents
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.coroutines.resume
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.ref.WeakReference
import platform.CoreGraphics.CGRectMake
import platform.Foundation.HTTPBody
import platform.Foundation.HTTPMethod
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSError
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSLocalizedDescriptionKey
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSNumber
import platform.Foundation.NSURL
import platform.Foundation.NSURLErrorBadServerResponse
import platform.Foundation.NSURLErrorBadURL
import platform.Foundation.NSURLErrorCancelled
import platform.Foundation.NSURLErrorCannotConnectToHost
import platform.Foundation.NSURLErrorCannotFindHost
import platform.Foundation.NSURLErrorDomain
import platform.Foundation.NSURLErrorNetworkConnectionLost
import platform.Foundation.NSURLErrorNoPermissionsToReadFile
import platform.Foundation.NSURLErrorNotConnectedToInternet
import platform.Foundation.NSURLErrorTimedOut
import platform.Foundation.NSURLErrorUnknown
import platform.Foundation.NSURLResponse
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionDataTask
import platform.Foundation.allHTTPHeaderFields
import platform.Foundation.base64EncodedStringWithOptions
import platform.Foundation.dataTaskWithRequest
import platform.Foundation.distantPast
import platform.Foundation.setValue
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.WebKit.WKContentWorld
import platform.WebKit.WKErrorDomain
import platform.WebKit.WKErrorJavaScriptResultTypeIsUnsupported
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
import platform.WebKit.WKURLSchemeHandlerProtocol
import platform.WebKit.WKURLSchemeTaskProtocol
import platform.WebKit.WKUserContentController
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.WebKit.WKWebsiteDataStore

/** iOS host for a [Browser]: an existing WKWebView to drive, or a website data store for new ones. */
@OptIn(ExperimentalForeignApi::class)
public class IOSPlatformContext(
    public val webView: WKWebView? = null,
    public val websiteDataStore: WKWebsiteDataStore? = null
) : PlatformContext

/**
 * WKWebView-backed controller.
 *
 * Threading: every WebKit object is touched on the main thread. Suspend APIs
 * switch with `withContext(Dispatchers.Main)`; synchronous APIs use
 * [onMainThread]; callback APIs complete on the main thread.
 *
 * Ownership: WebKit strongly retains URL-scheme handlers (through the
 * configuration) and script-message handlers (through the user content
 * controller). Both are therefore small proxies holding this controller
 * weakly, so a closed page can be collected even if the host keeps the
 * WKWebView alive.
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
internal class IOSWebViewController(
    private val config: KmpwrightConfig = KmpwrightConfig(),
    existingWebView: WKWebView? = null,
    websiteDataStore: WKWebsiteDataStore? = null
) : WebViewController {
    private val initScripts = mutableMapOf<String, platform.WebKit.WKUserScript>()
    private var initScriptCounter = 0L
    private val disposed = kotlinx.atomicfu.atomic(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val previousNavigationDelegate = existingWebView?.navigationDelegate
    private val previousUiDelegate = existingWebView?.UIDelegate
    private var networkUserScript: platform.WebKit.WKUserScript? = null
    private var navigationCallback: ((NavigationResult) -> Unit)? = null
    private var navigationGuard: ((String) -> Boolean)? = null
    private val requestListeners = kotlinx.atomicfu.atomic(emptyList<RequestListener>())
    private val dialogListeners = kotlinx.atomicfu.atomic(emptyList<DialogListener>())
    /** Main thread only. */
    private val pendingRequests = mutableMapOf<String, Request>()
    /** Registration order; the most recently registered matching route runs first. */
    private val routeEntries = kotlinx.atomicfu.atomic(emptyList<IOSRouteEntry>())
    private var schemeHandler: IOSSchemeHandler? = null
    private var schemeRequestCounter = 0L
    private val delegate = IOSWebViewDelegate()
    private val scriptMessageProxy = IOSScriptMessageProxy(this)
    private val httpRequestPolicy = MobileHttpRequestPolicy()
    private val bridgeNonce = createBridgeNonce()
    private val bridgeChannel = "kmpwright_$bridgeNonce"
    private val bridgeLimits = WebKitNetworkBridgeLimits()
    private val bridgeRateLimiter = WebKitNetworkBridgeRateLimiter()
    private val diagnosticsListeners = kotlinx.atomicfu.atomic(emptyList<PageDiagnosticsListener>())
    /** Separate secret and channel from the network bridge: diagnostics come from every frame. */
    private val diagnosticsNonce = createBridgeNonce()
    private val diagnosticsChannel = "kmpwright_diag_$diagnosticsNonce"
    private val diagnosticsRateLimiter = WebKitNetworkBridgeRateLimiter(maxMessagesPerWindow = 2_000)
    private var diagnosticsUserScript: platform.WebKit.WKUserScript? = null
    private var pendingNavigationHttpStatus: Int = 200
    /** Set when this controller cancels a main-frame navigation by policy. */
    private var suppressPolicyCancellationFailure = false
    /** Host allowlist for navigations (null = unrestricted). Main thread only. */
    private var domainFilter: DomainFilter? = null
    /** Installed content-blocker list enforcing [domainFilter] for subresources. Main thread only. */
    private var domainRuleList: platform.WebKit.WKContentRuleList? = null
    private val domainRuleListId = "kmpwright-domain-filter-$bridgeNonce"
    /** Allowed main-frame URL whose navigation is still provisional (a server redirect starts from it). */
    private var provisionalMainFrameUrl: String? = null
    /** Main thread only. */
    private var lifecycleListener: ((PageLifecycleEvent) -> Unit)? = null
    private val stateChannel = "kmpwright_state_$bridgeNonce"
    private var stateUserScript: platform.WebKit.WKUserScript? = null
    private var reportedUrl: String? = null
    private var reportedTitle: String? = null
    private val webView: WKWebView = onMainThread { createWebView(existingWebView, websiteDataStore) }

    override fun setLifecycleListener(listener: ((PageLifecycleEvent) -> Unit)?) {
        onMainThread { lifecycleListener = listener }
    }

    /** decidePolicyForNavigationResponse recorded the main-frame status before DOMContentLoaded. */
    override fun pendingNavigationStatus(): Int? = onMainThread { pendingNavigationHttpStatus }

    /**
     * Same-document navigations and `document.title` changes. A main-frame document-start script pings
     * [stateChannel] after pushState/replaceState, hashchange, popstate and title mutations; the ping
     * carries no data — the controller re-reads the trusted `WKWebView.URL`/`title`, so a page cannot
     * forge either. A URL change while a document is loading belongs to that load, not to the page.
     */
    private fun setupStateObserver(userContentController: WKUserContentController) {
        userContentController.addScriptMessageHandler(scriptMessageProxy, stateChannel)
        val script = """
            (function(){
              var h = window.webkit && window.webkit.messageHandlers && window.webkit.messageHandlers[${jsStringLiteral(stateChannel)}];
              if (!h || typeof h.postMessage !== 'function') return;
              var send = h.postMessage.bind(h), queued = false;
              function ping() { if (queued) return; queued = true; setTimeout(function() { queued = false; try { send(''); } catch (e) {} }, 0); }
              var H = window.history;
              ['pushState', 'replaceState'].forEach(function(name) {
                var original = H[name];
                if (typeof original !== 'function') return;
                H[name] = function() { var result = original.apply(this, arguments); ping(); return result; };
              });
              window.addEventListener('hashchange', ping, true);
              window.addEventListener('popstate', ping, true);
              var last = null;
              function check() { var t = document.title; if (t !== last) { last = t; ping(); } }
              try { new MutationObserver(check).observe(document, {subtree: true, childList: true, characterData: true}); } catch (e) {}
            })();
        """.trimIndent()
        val userScript = platform.WebKit.WKUserScript(script,
            platform.WebKit.WKUserScriptInjectionTime.WKUserScriptInjectionTimeAtDocumentStart, true)
        userContentController.addUserScript(userScript)
        stateUserScript = userScript
    }

    private fun handleStateMessage(scriptMessage: WKScriptMessage) {
        if (scriptMessage.webView?.isEqual(webView) != true || !scriptMessage.frameInfo.isMainFrame()) return
        val title = webView.title
        if (title != reportedTitle) {
            reportedTitle = title
            lifecycleListener?.invoke(PageLifecycleEvent.TitleChanged(title))
        }
        if (webView.loading) return
        val url = webView.URL?.absoluteString ?: return
        if (url != reportedUrl) {
            reportedUrl = url
            lifecycleListener?.invoke(PageLifecycleEvent.SameDocument(url))
        }
    }

    internal val isDisposed: Boolean get() = disposed.value

    private fun createWebView(existingWebView: WKWebView?, websiteDataStore: WKWebsiteDataStore?): WKWebView {
        val view = if (existingWebView != null) {
            if (config.iosSchemeInterception != null) {
                iosLog("iosSchemeInterception is ignored for a host-provided WKWebView; URL scheme handlers can only be registered before the web view is created.")
            }
            existingWebView
        } else {
            val configuration = WKWebViewConfiguration()
            configuration.preferences = WKPreferences()
            configuration.userContentController = WKUserContentController()
            websiteDataStore?.let { configuration.websiteDataStore = it }
            config.iosSchemeInterception?.let { schemeConfig ->
                val handler = IOSSchemeHandler(schemeConfig, this)
                configuration.setURLSchemeHandler(handler, schemeConfig.scheme)
                schemeHandler = handler
            }
            val width = config.viewport?.width ?: 1280
            val height = config.viewport?.height ?: 800
            WKWebView(CGRectMake(0.0, 0.0, width.toDouble(), height.toDouble()), configuration)
        }
        view.navigationDelegate = delegate
        view.UIDelegate = delegate
        setupStateObserver(view.configuration.userContentController)
        config.userAgent?.let { view.customUserAgent = it }
        setupNetworkInterception(view.configuration.userContentController)
        setupDiagnostics(view.configuration.userContentController)
        return view
    }

    override suspend fun addInitScript(script: String): String = withContext(Dispatchers.Main) {
        val id = "init-${++initScriptCounter}"
        val registration = platform.WebKit.WKUserScript(script,
            platform.WebKit.WKUserScriptInjectionTime.WKUserScriptInjectionTimeAtDocumentStart, false)
        webView.configuration.userContentController.addUserScript(registration)
        initScripts[id] = registration
        id
    }

    override suspend fun removeInitScript(id: String) = withContext(Dispatchers.Main) {
        val registration = initScripts.remove(id) ?: throw KmpwrightException("Unknown init-script identifier: $id")
        val content = webView.configuration.userContentController
        val retained = content.userScripts.filterIsInstance<platform.WebKit.WKUserScript>().filter { it != registration }
        content.removeAllUserScripts()
        retained.forEach { content.addUserScript(it) }
    }

    /**
     * WebKit requires every dialog completion handler to be called exactly
     * once. Listeners are bounded by the default timeout; a timed-out,
     * failing, or cancelled listener dismisses the dialog.
     */
    private fun handleDialog(type: DialogType, message: String, defaultPrompt: String?, complete: (DialogResponse) -> Unit) {
        val listener = dialogListeners.value.firstOrNull()
        if (listener == null || disposed.value) {
            complete(DialogResponse.Dismiss)
            return
        }
        launchWithCallback(DialogResponse.Dismiss, complete) {
            try {
                withTimeoutOrNull(config.timeouts.handler) { listener.onDialogAsync(type, message, defaultPrompt) }
                    ?: DialogResponse.Dismiss.also {
                        iosLog("Dialog listener did not answer the $type dialog within ${config.timeouts.handler} ms; dismissing it.")
                    }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                iosLog("Dialog listener failed for the $type dialog; dismissing it: ${error.message}")
                DialogResponse.Dismiss
            }
        }
    }

    /**
     * Runs [block] on the controller scope and delivers its value exactly once
     * on the main thread. Disposal, cancellation, and failures deliver
     * [fallback] so callback-style callers never hang.
     */
    private fun <T> launchWithCallback(fallback: T, callback: (T) -> Unit, block: suspend () -> T) {
        var delivered = false
        fun deliver(value: T) {
            if (delivered) return
            delivered = true
            callback(value)
        }
        val job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val value = try {
                block()
            } catch (_: CancellationException) {
                fallback
            } catch (error: Exception) {
                iosLog(error.message ?: error.toString())
                fallback
            }
            deliver(value)
        }
        job.invokeOnCompletion { if (!delivered) dispatch_async(dispatch_get_main_queue()) { deliver(fallback) } }
    }

    override fun setNavigationCallback(callback: ((NavigationResult) -> Unit)?) {
        navigationCallback = callback
    }

    override fun setNavigationGuard(guard: ((String) -> Boolean)?) {
        navigationGuard = guard
    }

    override fun addRequestListener(listener: RequestListener) {
        requestListeners.update { it + listener }
    }

    override fun removeRequestListener(listener: RequestListener) {
        requestListeners.update { it - listener }
    }

    override fun getPendingRequestCount(): Int = onMainThread { pendingRequests.size }
    override fun resetRequestCount() = onMainThread { pendingRequests.clear() }
    override fun setRequestIdleTimeout(timeoutMs: Long) {}

    override fun addDialogListener(listener: DialogListener) {
        dialogListeners.update { it + listener }
    }
    override fun removeDialogListener(listener: DialogListener) {
        dialogListeners.update { it - listener }
    }

    override fun addDiagnosticsListener(listener: PageDiagnosticsListener) {
        diagnosticsListeners.update { it + listener }
    }

    override fun removeDiagnosticsListener(listener: PageDiagnosticsListener) {
        diagnosticsListeners.update { it - listener }
    }

    override fun loadUrl(url: String) = onMainThread {
        if (!domainFilterAllows(url)) {
            navigationCallback?.invoke(NavigationResult(url, 0, false, domainBlockedMessage(url)))
            return@onMainThread
        }
        if (config.allowedOriginRules.isNotEmpty() && !config.allowedOriginRules.any { originRuleMatches(it, url) }) {
            navigationCallback?.invoke(NavigationResult(url, 0, false, "Navigation origin is not allowed by KmpwrightConfig: $url"))
            return@onMainThread
        }
        if (navigationGuard?.invoke(url) == false) {
            navigationCallback?.invoke(NavigationResult(url, 0, false, "Navigation denied by action policy: $url"))
            return@onMainThread
        }
        val target = NSURL.URLWithString(url)
        if (target == null) {
            navigationCallback?.invoke(NavigationResult(url, 0, false, "Invalid URL: $url"))
            return@onMainThread
        }
        val request = NSMutableURLRequest(target)
        buildRequestHeaders(url).forEach { (key, value) ->
            request.setValue(value, forHTTPHeaderField = key)
        }
        suppressPolicyCancellationFailure = false
        provisionalMainFrameUrl = null
        webView.loadRequest(request)
        Unit
    }

    override fun loadHtml(content: String, baseUrl: String?) = onMainThread {
        suppressPolicyCancellationFailure = false
        webView.loadHTMLString(content, baseURL = baseUrl?.let { NSURL.URLWithString(it) })
        Unit
    }

    override val supportsPromiseEvaluation: Boolean get() = true
    override fun normalizeJavaScriptResult(raw: String?): String? = raw

    /**
     * Callback form of [evaluateJavaScriptAsync]: Promises are awaited and
     * objects are returned as JSON. A failed evaluation is logged and reported
     * as `null` because the callback contract has no error channel; suspend
     * callers receive the [KmpwrightException] instead.
     */
    override fun evaluateJavaScript(script: String, callback: (String?) -> Unit) {
        if (disposed.value) {
            callback(null)
            return
        }
        launchWithCallback(null, callback) { evaluateJavaScriptAsync(script) }
    }

    /**
     * Evaluates [script] with Playwright-like semantics:
     * 1. as an awaited expression (`return ` prefix tolerated);
     * 2. if that does not parse but the script is a classic program, by
     *    awaiting its completion value (`var x = 1; x + 1` yields `2`);
     * 3. otherwise (top-level `return`/`await`) as an async function body.
     * Values are JSON-serialized in the page: strings are returned decoded,
     * objects and arrays as JSON text, `undefined`/`null` as `null`.
     * Each stage only runs after the previous one failed to *parse*, so a
     * script's side effects never execute twice.
     */
    override suspend fun evaluateJavaScriptAsync(script: String): String? = withContext(Dispatchers.Main) {
        if (disposed.value) throw KmpwrightException("Page is closed")
        val expression = script.trim().removeSuffix(";").removePrefix("return ")
        // The newline keeps a trailing `// comment` from swallowing the paren.
        when (val attempt = callSerialized("return JSON.stringify({ value: await ($expression\n) });")) {
            is Serialized.Completed -> attempt.value
            is Serialized.SyntaxError -> if (parsesAsProgram(script)) evaluateProgram(script) else evaluateFunctionBody(script)
        }
    }

    private suspend fun evaluateFunctionBody(script: String): String? =
        when (val body = callSerialized("return JSON.stringify({ value: await (async function() {\n$script\n})() });")) {
            is Serialized.Completed -> body.value
            is Serialized.SyntaxError -> throw KmpwrightException("JavaScript evaluation failed: ${body.message}")
        }

    /**
     * Program completion values are only reachable through `eval`. Indirect
     * eval runs in global scope like a classic script and lets a Promise
     * completion value be awaited. Pages whose CSP forbids eval fall back to
     * WebKit's native script evaluation, which keeps the completion value but
     * cannot return Promises or other non-serializable values (reported as
     * `null`, like `undefined`).
     */
    private suspend fun evaluateProgram(script: String): String? {
        val evaluated = callSerialized(
            """
            var evaluate = (0, eval);
            try { evaluate('0'); } catch (blocked) { return JSON.stringify({ evalBlocked: true }); }
            return JSON.stringify({ value: await evaluate(source) });
            """.trimIndent(),
            arguments = mapOf("source" to script)
        )
        return when {
            evaluated is Serialized.SyntaxError -> throw KmpwrightException("JavaScript evaluation failed: ${evaluated.message}")
            (evaluated as Serialized.Completed).evalBlocked -> evaluateNatively(script)
            else -> evaluated.value
        }
    }

    private sealed class Serialized {
        class Completed(val value: String?, val evalBlocked: Boolean = false) : Serialized()
        /** The wrapper body did not parse; nothing ran. */
        class SyntaxError(val message: String) : Serialized()
    }

    /**
     * Runs [statements] (which return a JSON envelope) as an async function body
     * in the page world. Exceptions are serialized by the wrapper and rethrown
     * as [KmpwrightException]; a body that does not parse yields [Serialized.SyntaxError].
     */
    private suspend fun callSerialized(
        statements: String,
        arguments: Map<String, String> = emptyMap()
    ): Serialized = suspendCancellableCoroutine { continuation ->
        val body = "try {\n$statements\n} catch (error) {\n" +
            "return JSON.stringify({ error: String(error && error.message || error) });\n}"
        webView.callAsyncJavaScript(body, arguments = arguments.mapKeys { it.key as Any? }, inFrame = null,
            inContentWorld = WKContentWorld.pageWorld) { result, error ->
            if (!continuation.isActive) return@callAsyncJavaScript
            if (error != null) {
                // Runtime errors are serialized by the wrapper, so an NSError here
                // means the body did not parse (or WebKit rejected the call).
                val message = error.javaScriptExceptionMessage()
                if (message.contains("SyntaxError")) continuation.resume(Serialized.SyntaxError(message))
                else continuation.resumeWith(Result.failure(KmpwrightException("WebKit JavaScript evaluation failed: $message")))
                return@callAsyncJavaScript
            }
            continuation.resumeWith(runCatching { decodeSerializedResult(result) })
        }
    }

    /** Classic-script evaluation through WebKit, which preserves the completion value. */
    private suspend fun evaluateNatively(script: String): String? = suspendCancellableCoroutine { continuation ->
        webView.evaluateJavaScript(script) { result, error ->
            if (!continuation.isActive) return@evaluateJavaScript
            when {
                error == null -> continuation.resumeWith(runCatching { nativeScriptResult(result) })
                // The script ran; its completion value (Promise, function, node)
                // has no JSON form, which the envelope path reports as undefined.
                error.domain == WKErrorDomain && error.code == WKErrorJavaScriptResultTypeIsUnsupported -> continuation.resume(null)
                else -> continuation.resumeWith(Result.failure(
                    KmpwrightException("JavaScript evaluation failed: ${error.javaScriptExceptionMessage()}")
                ))
            }
        }
    }

    /**
     * Parses [script] as a classic program without executing it: the code is
     * placed after an unconditional `return` inside a class static block, where
     * `return` and `await` are rejected exactly as at script top level. Static
     * blocks are strict-mode code and need iOS 16.4+, so sloppy-only programs
     * (and older WebKit) use the async-function-body fallback instead.
     */
    private suspend fun parsesAsProgram(script: String): Boolean = suspendCancellableCoroutine { continuation ->
        val body = "return;\nclass KmpwrightProgramCheck { static {\n$script\n} }"
        webView.callAsyncJavaScript(body, arguments = emptyMap<Any?, Any?>(), inFrame = null,
            inContentWorld = WKContentWorld.pageWorld) { _, error ->
            if (continuation.isActive) continuation.resume(error == null)
        }
    }

    private fun decodeSerializedResult(result: Any?): Serialized.Completed {
        val decoded = Json.parseToJsonElement(result as? String ?: "{}") as? JsonObject
            ?: throw KmpwrightException("WebKit returned an invalid evaluation result")
        (decoded["error"] as? JsonPrimitive)?.let { throw KmpwrightException("JavaScript evaluation failed: ${it.content}") }
        val value = when (val raw = decoded["value"]) {
            null, JsonNull -> null
            is JsonPrimitive -> raw.content
            else -> raw.toString()
        }
        return Serialized.Completed(value, evalBlocked = (decoded["evalBlocked"] as? JsonPrimitive)?.content == "true")
    }

    override suspend fun canTraverseHistory(delta: Int): Boolean? = withContext(Dispatchers.Main) {
        if (disposed.value) null
        else when {
            delta == -1 -> webView.canGoBack
            delta == 1 -> webView.canGoForward
            else -> webView.backForwardList.itemAtIndex(delta.toLong()) != null
        }
    }

    /**
     * Native `prefers-color-scheme` through the web view's trait collection (`overrideUserInterfaceStyle`).
     * WebKit only forwards the appearance of a view that is in a window, and has no "no-preference"
     * appearance; in those cases this returns false and the page keeps its JavaScript fallback.
     */
    override suspend fun setColorSchemeOverride(scheme: ColorScheme?): Boolean = withContext(Dispatchers.Main) {
        val style = when (scheme) {
            null -> platform.UIKit.UIUserInterfaceStyle.UIUserInterfaceStyleUnspecified
            ColorScheme.Light -> platform.UIKit.UIUserInterfaceStyle.UIUserInterfaceStyleLight
            ColorScheme.Dark -> platform.UIKit.UIUserInterfaceStyle.UIUserInterfaceStyleDark
            ColorScheme.NoPreference -> return@withContext false.also {
                webView.overrideUserInterfaceStyle = platform.UIKit.UIUserInterfaceStyle.UIUserInterfaceStyleUnspecified
            }
        }
        webView.overrideUserInterfaceStyle = style
        if (scheme == null) return@withContext true
        // The appearance reaches the WebContent process asynchronously (and only once the view's trait
        // collection resolves it). Verify it, otherwise undo it and let the page use its JavaScript fallback.
        val query = if (scheme == ColorScheme.Dark) "dark" else "light"
        val deadline = kotlin.time.TimeSource.Monotonic.markNow()
        while (deadline.elapsedNow().inWholeMilliseconds < COLOR_SCHEME_APPLY_TIMEOUT_MS) {
            val matches = runCatching { evaluateJavaScriptAsync("matchMedia('(prefers-color-scheme: $query)').matches") }.getOrNull()
            if (matches == "true") return@withContext true
            kotlinx.coroutines.delay(25)
        }
        webView.overrideUserInterfaceStyle = platform.UIKit.UIUserInterfaceStyle.UIUserInterfaceStyleUnspecified
        false
    }

    // --- Domain filter ---

    private fun domainFilterAllows(url: String): Boolean {
        val filter = domainFilter ?: return true
        val scheme = url.substringBefore(':', "").lowercase()
        val httpUrl = when (scheme) {
            "http", "https" -> url
            "ws" -> "http" + url.substring(2)
            "wss" -> "https" + url.substring(3)
            else -> return true
        }
        val host = canonicalHttpHost(httpUrl) ?: return false
        return filter.isAllowed(host)
    }

    private fun domainBlockedMessage(url: String): String =
        "Blocked by KMPwright domain filter: ${canonicalHttpHost(url) ?: url}"

    /** Navigation policy takes effect immediately; subresource blocking is installed by [setDomainFilterAsync]. */
    override fun setDomainFilter(domains: Set<String>): Boolean {
        IOSDomainFilterRules.encode(domains) // Validates every entry before anything changes.
        onMainThread { domainFilter = domains.takeIf { it.isNotEmpty() }?.let(::DomainFilter) }
        return true
    }

    /**
     * Compiles the allowlist into a WKContentRuleList (WebKit's content blocker) and installs it on the
     * web view, replacing the previous one. Returns once WebKit enforces it for new loads.
     */
    override suspend fun setDomainFilterAsync(domains: Set<String>) {
        setDomainFilter(domains)
        withContext(Dispatchers.Main) {
            val content = webView.configuration.userContentController
            if (domains.isEmpty()) {
                domainRuleList?.let { content.removeContentRuleList(it) }
                domainRuleList = null
                return@withContext
            }
            val store = platform.WebKit.WKContentRuleListStore.defaultStore()
                ?: throw KmpwrightException("WebKit content rule list store is unavailable")
            val compiled = suspendCancellableCoroutine<platform.WebKit.WKContentRuleList> { continuation ->
                store.compileContentRuleListForIdentifier(domainRuleListId, IOSDomainFilterRules.encode(domains)) { list, error ->
                    if (!continuation.isActive) return@compileContentRuleListForIdentifier
                    if (list != null) continuation.resume(list)
                    else continuation.resumeWith(Result.failure(KmpwrightException(
                        "Could not compile the domain filter content rules: ${error?.localizedDescription ?: "unknown error"}")))
                }
            }
            if (disposed.value) return@withContext
            domainRuleList?.let { content.removeContentRuleList(it) }
            content.addContentRuleList(compiled)
            domainRuleList = compiled
        }
    }

    override fun goBack() = onMainThread { webView.goBack(); Unit }
    override fun goForward() = onMainThread { webView.goForward(); Unit }
    override fun reload() = onMainThread { webView.reload(); Unit }

    override fun getUrl(): String? = onMainThread { webView.URL?.absoluteString }
    override fun getTitle(): String? = onMainThread { webView.title }

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
        httpRequestPolicy.setCredentials(username, password, getUrl())
        return true
    }

    override suspend fun setHttpCredentialsAsync(username: String?, password: String?) {
        val url = withContext(Dispatchers.Main) { webView.URL?.absoluteString }
        httpRequestPolicy.setCredentials(username, password, url)
    }

    private fun buildRequestHeaders(url: String): Map<String, String> {
        return httpRequestPolicy.requestHeaders(url) { credentials ->
            credentials.encodeToByteArray()
                .toNSData()
                .base64EncodedStringWithOptions(0u)
        }
    }

    /** Reports the PNG's pixel dimensions (screen scale included). */
    override fun takeScreenshot(options: ScreenshotOptions, callback: (ByteArray?, Int, Int) -> Unit) {
        launchWithCallback<ScreenshotResult?>(null, { result ->
            if (result != null) callback(result.bytes, result.width, result.height) else callback(null, 0, 0)
        }) {
            captureScreenshot(options)
        }
    }

    override suspend fun captureScreenshot(options: ScreenshotOptions): ScreenshotResult =
        IOSScreenshotController(webView).capture(options)

    override fun getViewportSize(): Pair<Int, Int> = onMainThread {
        webView.bounds.useContents { size.width.toInt() to size.height.toInt() }
    }

    override fun setViewportSize(width: Int, height: Int) = onMainThread {
        webView.setFrame(CGRectMake(0.0, 0.0, width.toDouble(), height.toDouble()))
    }

    override fun setZoomScale(scale: Double) = onMainThread {
        webView.scrollView.setZoomScale(scale, animated = true)
    }

    override fun getZoomScale(): Double = onMainThread { webView.scrollView.zoomScale }

    override fun setUserAgent(userAgent: String) = onMainThread {
        webView.customUserAgent = userAgent
    }

    override fun setDeviceScaleFactor(scale: Double) {
        throw KmpwrightUnsupportedException("WKWebView device scale is determined by the host screen")
    }

    override fun injectJavaScript(script: String, callback: ((String?) -> Unit)?) {
        evaluateJavaScript(script) { result -> callback?.invoke(result) }
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

    override fun dispose() = onMainThread {
        if (!disposed.compareAndSet(false, true)) return@onMainThread
        webView.evaluateJavaScript("if (window.__kmpwrightNetworkDispose) window.__kmpwrightNetworkDispose();") { _, _ -> }
        webView.stopLoading()
        lifecycleListener = null
        webView.navigationDelegate = previousNavigationDelegate
        webView.UIDelegate = previousUiDelegate
        val content = webView.configuration.userContentController
        content.removeScriptMessageHandlerForName(bridgeChannel)
        content.removeScriptMessageHandlerForName(diagnosticsChannel)
        content.removeScriptMessageHandlerForName(stateChannel)
        diagnosticsListeners.value = emptyList()
        domainRuleList?.let { content.removeContentRuleList(it) }
        domainRuleList = null
        domainFilter = null
        platform.WebKit.WKContentRuleListStore.defaultStore()?.removeContentRuleListForIdentifier(domainRuleListId) { _ -> }
        val owned = initScripts.values.toSet() + listOfNotNull(networkUserScript, diagnosticsUserScript, stateUserScript)
        stateUserScript = null
        diagnosticsUserScript = null
        val retained = content.userScripts.filterIsInstance<platform.WebKit.WKUserScript>().filter { it !in owned }
        content.removeAllUserScripts()
        retained.forEach { content.addUserScript(it) }
        initScripts.clear()
        networkUserScript = null
        navigationCallback = null
        navigationGuard = null
        requestListeners.value = emptyList()
        dialogListeners.value = emptyList()
        pendingRequests.clear()
        routeEntries.value = emptyList()
        // Fail in-flight custom-scheme loads before the scope that would have
        // completed them is cancelled.
        schemeHandler?.dispose()
        schemeHandler = null
        scope.cancel()
    }

    override fun validatePdfOptions(options: PdfOptions) {
        // The default format (null) means the content canvas here.
        if ((options.format != null && options.format != PdfFormat.Content) || options.landscape || options.width != null || options.height != null ||
            options.displayHeaderFooter || options.headerTemplate.isNotEmpty() || options.footerTemplate.isNotEmpty() ||
            !options.printBackground || options.scale != 1.0 || options.pageRanges.isNotEmpty() ||
            listOf(options.margin.top, options.margin.bottom, options.margin.left, options.margin.right).any { pdfLengthInches(it) != 0.0 }) {
            throw KmpwrightUnsupportedException("WKWebView PDF export supports only the content canvas (format null or Content) with zero margins, no scaling, " +
                "page ranges or header/footer templates, and printed backgrounds; print pagination options are unavailable")
        }
    }

    override fun generatePdf(options: PdfOptions, callback: (ByteArray?) -> Unit) {
        launchWithCallback(null, callback) { generatePdfAsync(options) }
    }

    /** Surfaces WebKit's failure reason instead of the generic callback `null`. */
    override suspend fun generatePdfAsync(options: PdfOptions): ByteArray = withContext(Dispatchers.Main) {
        if (disposed.value) throw KmpwrightException("Page is closed")
        suspendCancellableCoroutine { continuation ->
            webView.createPDFWithConfiguration(WKPDFConfiguration()) { data, error ->
                if (!continuation.isActive) return@createPDFWithConfiguration
                val bytes = data?.toByteArrayPinned()
                if (error != null || bytes == null || bytes.isEmpty()) {
                    continuation.resumeWith(Result.failure(KmpwrightException(
                        "WKWebView PDF export failed: ${error?.localizedDescription ?: "no data returned"}"
                    )))
                } else {
                    continuation.resume(bytes)
                }
            }
        }
    }

    /**
     * Routes follow Playwright precedence: the most recently registered
     * matching handler runs first; `route.fallback()` (or no decision) falls
     * back to the next older matching handler.
     * Re-registering a pattern replaces it and moves it to the front.
     */
    override fun addRouteHandler(urlPattern: String, handler: suspend (Route) -> Unit) {
        val entry = IOSRouteEntry(urlPattern, Regex(urlPattern), handler)
        routeEntries.update { entries -> entries.filter { it.pattern != urlPattern } + entry }
    }

    override fun removeRouteHandler(urlPattern: String) {
        routeEntries.update { entries -> entries.filter { it.pattern != urlPattern } }
    }

    override fun removeAllRouteHandlers() { routeEntries.value = emptyList() }

    override fun setCookie(cookie: Cookie) = onMainThread {
        val nsCookie = cookie.toNSHTTPCookie() ?: throw KmpwrightException("Invalid cookie ${cookie.name}")
        webView.configuration.websiteDataStore.httpCookieStore.setCookie(nsCookie, completionHandler = {})
    }

    override suspend fun setCookieAsync(cookie: Cookie) = withContext(Dispatchers.Main) {
        val nsCookie = cookie.toNSHTTPCookie() ?: throw KmpwrightException("Invalid cookie ${cookie.name}")
        val store = webView.configuration.websiteDataStore.httpCookieStore
        suspendCancellableCoroutine<Unit> { continuation ->
            store.setCookie(nsCookie) { if (continuation.isActive) continuation.resume(Unit) }
        }
    }

    override fun getCookie(name: String, domain: String, callback: (Cookie?) -> Unit) = onMainThread {
        webView.configuration.websiteDataStore.httpCookieStore.getAllCookies { cookies ->
            val match = cookies.toNSHTTPCookies()
                .firstOrNull { it.name == name && (domain.isEmpty() || cookieDomainMatches(it.domain, domain)) }
            callback(match?.toKmpwrightCookie())
        }
    }

    override fun getAllCookies(callback: (List<Cookie>) -> Unit) = onMainThread {
        webView.configuration.websiteDataStore.httpCookieStore.getAllCookies { cookies ->
            callback(cookies.toNSHTTPCookies().map { it.toKmpwrightCookie() })
        }
    }

    override fun deleteCookie(name: String, domain: String) = onMainThread {
        val store = webView.configuration.websiteDataStore.httpCookieStore
        store.getAllCookies { cookies ->
            cookies.toNSHTTPCookies()
                .filter { it.name == name && (domain.isEmpty() || cookieDomainMatches(it.domain, domain)) }
                .forEach { store.deleteCookie(it, completionHandler = {}) }
        }
    }

    override suspend fun deleteCookieAsync(name: String, domain: String) = withContext(Dispatchers.Main) {
        webView.configuration.websiteDataStore.httpCookieStore.deleteCookiesMatching { cookie ->
            cookie.name == name && (domain.isEmpty() || cookieDomainMatches(cookie.domain, domain))
        }
    }

    override fun deleteAllCookies() = onMainThread {
        val store = webView.configuration.websiteDataStore.httpCookieStore
        store.getAllCookies { cookies ->
            cookies.toNSHTTPCookies().forEach { store.deleteCookie(it, completionHandler = {}) }
        }
    }

    override suspend fun deleteAllCookiesAsync() = withContext(Dispatchers.Main) {
        webView.configuration.websiteDataStore.httpCookieStore.deleteCookiesMatching { true }
    }


    internal fun createPage(tabRuntime: PageTabRuntime? = null): IOSPage = IOSPage(this, config, tabRuntime)

    internal fun getWebView(): WKWebView = webView

    private fun setupNetworkInterception(userContentController: WKUserContentController) {
        // The network shim must run in the page world: fetch/XHR prototypes are
        // world-local and cannot be replaced from a WKContentWorld. The native
        // endpoint is therefore treated as hostile and protected by a per-view
        // nonce plus frame/origin and bounded-message checks below. The
        // registered handler is a weak proxy: WKUserContentController retains it.
        userContentController.addScriptMessageHandler(scriptMessageProxy, bridgeChannel)
        val script = buildWebKitNetworkInterceptionScript(bridgeChannel, bridgeNonce)
        val userScript = platform.WebKit.WKUserScript(
            script,
            platform.WebKit.WKUserScriptInjectionTime.WKUserScriptInjectionTimeAtDocumentStart,
            true
        )
        userContentController.addUserScript(userScript)
        networkUserScript = userScript
    }

    /**
     * WKWebView has no console API: a page-world document-start script in every frame wraps the
     * console and hooks uncaught errors, posting through a dedicated handler. Like the network bridge,
     * the handler is treated as hostile input: per-view nonce, web view identity, size and rate
     * bounds, and a strict schema. Frame origin is not restricted, since iframe console output is
     * wanted and the content (console text, thrown errors) is page-controlled anyway.
     */
    private fun setupDiagnostics(userContentController: WKUserContentController) {
        userContentController.addScriptMessageHandler(scriptMessageProxy, diagnosticsChannel)
        val post = "(function(h) { if (!h || typeof h.postMessage !== 'function') return null; " +
            "var send = h.postMessage.bind(h); return function(s) { send(s); }; })(" +
            "window.webkit && window.webkit.messageHandlers && window.webkit.messageHandlers[${jsStringLiteral(diagnosticsChannel)}])"
        val userScript = platform.WebKit.WKUserScript(
            buildPageDiagnosticsScript(post, diagnosticsNonce, wrapConsole = true),
            platform.WebKit.WKUserScriptInjectionTime.WKUserScriptInjectionTimeAtDocumentStart,
            false
        )
        userContentController.addUserScript(userScript)
        diagnosticsUserScript = userScript
    }

    private fun handleDiagnosticsMessage(scriptMessage: WKScriptMessage) {
        val json = scriptMessage.body as? String ?: return
        if (scriptMessage.webView?.isEqual(webView) != true) return
        if (!diagnosticsRateLimiter.allow(currentTimeMillis())) return
        val listeners = diagnosticsListeners.value
        when (val diagnostic = parsePageDiagnosticPayload(json, diagnosticsNonce)) {
            is PageDiagnostic.Console -> listeners.forEach { it.onConsoleMessage(diagnostic.message) }
            is PageDiagnostic.Error -> listeners.forEach { it.onPageError(diagnostic.error) }
            PageDiagnostic.DocumentStarted -> if (scriptMessage.frameInfo.isMainFrame()) listeners.forEach { it.onMainFrameDocumentCommitted() }
            null -> Unit
        }
    }

    internal fun handleScriptMessage(scriptMessage: WKScriptMessage) {
        if (disposed.value) return
        if (scriptMessage.name == diagnosticsChannel) {
            handleDiagnosticsMessage(scriptMessage)
            return
        }
        if (scriptMessage.name == stateChannel) {
            handleStateMessage(scriptMessage)
            return
        }
        val json = scriptMessage.body as? String ?: return
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
                is WebKitNetworkBridgeMessage.RequestFailed -> failPendingRequest(message.id, message.error)
                null -> Unit
            }
        } catch (error: Exception) {
            // Bridge messages are untrusted page input; drop the message but keep
            // a trace of why so a broken shim does not fail silently.
            iosLog("Ignoring malformed network bridge message: ${error.message}")
        }
    }

    private fun handleNetworkRequestStarted(message: WebKitNetworkBridgeMessage.RequestStarted) {
        if (pendingRequests.size >= bridgeLimits.maxInFlightRequests || pendingRequests.containsKey(message.id)) return
        val request = Request(
            url = message.url,
            method = message.method,
            headers = message.headers,
            postData = message.body,
            // The page shim reports which API issued the request: "fetch" or "xhr".
            resourceType = message.kind,
            id = message.id
        )
        pendingRequests[request.id] = request
        requestListeners.value.forEach { it.onRequestStarted(request) }

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
        requestListeners.value.forEach { listener ->
            listener.onResponseReceived(response)
            listener.onRequestFinished(request)
        }
    }

    private fun failPendingRequest(requestId: String, error: String) {
        val request = pendingRequests.remove(requestId) ?: return
        requestListeners.value.forEach { listener ->
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

    /**
     * Resolves the routing decision on the main thread. A handler that throws
     * or exceeds [TimeoutConfig.handler] aborts the request (and is
     * logged) instead of silently letting it through.
     */
    internal suspend fun resolveRouteAction(request: Request): RouteAction = withContext(Dispatchers.Main) {
        val matching = routeEntries.value.asReversed().filter { it.regex.matches(request.url) }
        if (matching.isEmpty()) return@withContext RouteAction.Fallback
        for (entry in matching) {
            val route = RecordingRoute(request)
            val finished = try {
                withTimeoutOrNull(config.timeouts.handler) { entry.handler(route); true }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                iosLog("Route handler for ${entry.pattern} failed; aborting ${request.method} ${request.url}: ${error.message}")
                return@withContext RouteAction.Abort("failed")
            }
            if (finished == null) {
                iosLog("Route handler for ${entry.pattern} did not finish within ${config.timeouts.handler} ms; aborting ${request.method} ${request.url}")
                return@withContext RouteAction.Abort("timedout")
            }
            val decision = route.action
            if (decision != null && decision != RouteAction.Fallback) return@withContext decision
        }
        RouteAction.Fallback
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
                        "bodyBase64" to response.bodyBytes?.toNSData()?.base64EncodedStringWithOptions(0u)
                    )
                )
            }
            is RouteAction.Abort -> {
                sendNetworkResponse(mapOf("id" to request.id, "action" to "abort", "error" to response.errorCode))
            }
            is RouteAction.Continue -> {
                val requestOverride = request.applyOverrides(response)
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
            is RouteAction.Fallback, null -> {
                sendNetworkResponse(mapOf("id" to request.id, "action" to "continue"))
            }
        }
    }

    private fun sendNetworkResponse(payload: Map<String, Any?>) {
        if (disposed.value) return
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

    private fun nextSchemeRequestId(): String {
        schemeRequestCounter += 1
        return "scheme-$schemeRequestCounter"
    }

    private fun mapSchemeUrl(originalUrl: NSURL, config: IosSchemeInterceptionConfig): NSURL {
        val absolute = originalUrl.absoluteString ?: return originalUrl
        val prefix = "${config.scheme}://"
        if (!absolute.startsWith(prefix)) return originalUrl
        val mapped = config.fallbackScheme + "://" + absolute.removePrefix(prefix)
        return NSURL.URLWithString(mapped) ?: originalUrl
    }

    /** Runs on the main thread (WebKit delivers scheme tasks there). */
    internal fun handleSchemeTask(
        record: IOSSchemeTask,
        schemeConfig: IosSchemeInterceptionConfig,
        completed: () -> Unit
    ) {
        val task = record.task
        val originalUrl = task.request.URL
        if (originalUrl == null) {
            record.fail(schemeTaskError(NSURLErrorBadURL, "The intercepted request has no URL"))
            completed()
            return
        }
        val mappedUrl = mapSchemeUrl(originalUrl, schemeConfig)
        val mappedString = mappedUrl.absoluteString ?: originalUrl.absoluteString ?: ""
        val method = task.request.HTTPMethod ?: "GET"
        val headers = task.request.allHTTPHeaderFields?.mapNotNull { (key, value) ->
            if (key is String && value is String) key to value else null
        }?.toMap() ?: emptyMap()
        val bodyData = task.request.HTTPBody
        val body = bodyData?.toByteArrayPinned()?.decodeToString()

        val requestId = nextSchemeRequestId()
        val isDocument = task.request.mainDocumentURL?.absoluteString == originalUrl.absoluteString
        val request = Request(
            url = mappedString,
            method = method,
            headers = headers,
            postData = body,
            resourceType = if (isDocument) "document" else "other",
            id = requestId
        )
        pendingRequests[requestId] = request
        requestListeners.value.forEach { it.onRequestStarted(request) }

        scope.launch {
            val response = resolveRouteAction(request)
            if (!record.active) {
                failPendingRequest(requestId, "Scheme task cancelled")
                completed()
                return@launch
            }
            when (response) {
                is RouteAction.Fulfill -> {
                    val responseHeaders = response.headers
                    val data = response.bytes.toNSData()
                    val headerFields: Map<Any?, *> = responseHeaders.entries.associate { it.key as Any? to it.value as Any? }
                    val httpResponse = NSHTTPURLResponse(
                        uRL = mappedUrl,
                        statusCode = response.status.toLong(),
                        HTTPVersion = "HTTP/1.1",
                        headerFields = headerFields
                    )
                    record.finish(httpResponse, data)
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
                    record.fail(routeAbortError(response.errorCode))
                    completed()
                    failPendingRequest(requestId, response.errorCode)
                }
                is RouteAction.Continue -> {
                    val requestOverride = request.applyOverrides(response)
                    pendingRequests[requestId] = request.copy(
                        url = requestOverride.url,
                        method = requestOverride.method,
                        headers = requestOverride.headers,
                        postData = requestOverride.postData
                    )
                    val targetUrl = NSURL.URLWithString(requestOverride.url)
                    if (targetUrl == null) {
                        record.fail(schemeTaskError(NSURLErrorBadURL, "Invalid continue URL: ${requestOverride.url}"))
                        completed()
                        failPendingRequest(requestId, "Invalid continue URL")
                        return@launch
                    }
                    val mutableRequest = NSMutableURLRequest(uRL = targetUrl)
                    mutableRequest.HTTPMethod = requestOverride.method
                    requestOverride.headers.forEach { (key, value) -> mutableRequest.setValue(value, forHTTPHeaderField = key) }
                    requestOverride.postData?.let { mutableRequest.HTTPBody = it.encodeToByteArray().toNSData() }
                    performSchemeFetch(record, mutableRequest, requestId, requestOverride.url, completed)
                }
                is RouteAction.Fallback -> {
                    val mutableRequest = NSMutableURLRequest(uRL = mappedUrl)
                    mutableRequest.HTTPMethod = method
                    headers.forEach { (key, value) -> mutableRequest.setValue(value, forHTTPHeaderField = key) }
                    if (bodyData != null) mutableRequest.HTTPBody = bodyData
                    performSchemeFetch(record, mutableRequest, requestId, mappedString, completed)
                }
            }
        }
    }

    private fun performSchemeFetch(
        record: IOSSchemeTask,
        request: NSMutableURLRequest,
        requestId: String,
        fallbackUrl: String,
        completed: () -> Unit
    ) {
        val dataTask = NSURLSession.sharedSession.dataTaskWithRequest(request) { data, responseObj, error ->
            dispatch_async(dispatch_get_main_queue()) {
                completed()
                if (!record.active) {
                    failPendingRequest(requestId, "Scheme task cancelled")
                    return@dispatch_async
                }
                if (error != null) {
                    record.fail(error)
                    failPendingRequest(requestId, error.localizedDescription)
                    return@dispatch_async
                }
                if (responseObj == null) {
                    record.fail(schemeTaskError(NSURLErrorBadServerResponse, "No response"))
                    failPendingRequest(requestId, "No response")
                    return@dispatch_async
                }
                record.finish(responseObj, data)
                val httpResponse = responseObj as? NSHTTPURLResponse
                completePendingRequest(
                    requestId = requestId,
                    url = responseObj.URL?.absoluteString ?: fallbackUrl,
                    status = httpResponse?.statusCode?.toInt() ?: 200,
                    statusText = defaultHttpStatusText(httpResponse?.statusCode?.toInt() ?: 200),
                    headers = httpResponse?.allHeaderFields?.toStringMap() ?: emptyMap()
                )
            }
        }
        record.dataTask = dataTask
        dataTask.resume()
    }

    private inner class IOSWebViewDelegate : NSObject(), WKNavigationDelegateProtocol, WKUIDelegateProtocol {
        override fun webView(
            webView: WKWebView,
            decidePolicyForNavigationAction: WKNavigationAction,
            decisionHandler: (WKNavigationActionPolicy) -> Unit
        ) {
            val url = decidePolicyForNavigationAction.request.URL?.absoluteString.orEmpty()
            if (!domainFilterAllows(url)) {
                // Also reached for server redirects and iframe navigations; only the main frame reports.
                if (decidePolicyForNavigationAction.targetFrame?.isMainFrame() == true) {
                    suppressPolicyCancellationFailure = true
                    val origin = provisionalMainFrameUrl
                    provisionalMainFrameUrl = null
                    navigationCallback?.invoke(
                        if (origin != null && origin != url) NavigationResult(origin, 0, false, "${domainBlockedMessage(url)} (redirected to $url)")
                        else NavigationResult(url, 0, false, domainBlockedMessage(url))
                    )
                }
                decisionHandler(WKNavigationActionPolicy.WKNavigationActionPolicyCancel)
                return
            }
            if (isNavigationAllowed(url)) {
                if (decidePolicyForNavigationAction.targetFrame?.isMainFrame() == true && provisionalMainFrameUrl == null) provisionalMainFrameUrl = url
                decisionHandler(WKNavigationActionPolicy.WKNavigationActionPolicyAllow)
                return
            }
            // Only a main-frame denial fails the pending navigation; denied
            // iframes and new-window targets are cancelled silently.
            if (decidePolicyForNavigationAction.targetFrame?.isMainFrame() == true) {
                suppressPolicyCancellationFailure = true
                navigationCallback?.invoke(
                    NavigationResult(url, 0, false, "Navigation origin is not allowed by policy: $url")
                )
            }
            decisionHandler(WKNavigationActionPolicy.WKNavigationActionPolicyCancel)
        }

        override fun webView(webView: WKWebView, decidePolicyForNavigationResponse: WKNavigationResponse, decisionHandler: (WKNavigationResponsePolicy) -> Unit) {
            val response = decidePolicyForNavigationResponse.response as? platform.Foundation.NSHTTPURLResponse
            val responseUrl = decidePolicyForNavigationResponse.response.URL?.absoluteString.orEmpty()
            if (decidePolicyForNavigationResponse.forMainFrame && !isNavigationAllowed(responseUrl)) {
                suppressPolicyCancellationFailure = true
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
            reportedUrl = webView.URL?.absoluteString
            suppressPolicyCancellationFailure = false
            provisionalMainFrameUrl = null
            val url = webView.URL?.absoluteString.orEmpty()
            val originAllowed = config.allowedOriginRules.isEmpty() ||
                (url.isNotBlank() && config.allowedOriginRules.any { originRuleMatches(it, url) })
            val policyAllowed = url.isNotBlank() && navigationGuard?.invoke(url) != false
            if (!originAllowed || !policyAllowed) webView.stopLoading()
            navigationCallback?.invoke(
                if (originAllowed && policyAllowed) {
                    committedNavigation(url, pendingNavigationHttpStatus)
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
            provisionalMainFrameUrl = null
            navigationCallback?.invoke(
                NavigationResult(webView.URL?.absoluteString ?: "", 0, false, withError.localizedDescription)
            )
            pendingNavigationHttpStatus = 200
        }

        @ObjCSignatureOverride
        override fun webView(webView: WKWebView, didFailProvisionalNavigation: WKNavigation?, withError: NSError) {
            provisionalMainFrameUrl = null
            pendingNavigationHttpStatus = 200
            // The policy decision above already reported this navigation.
            if (suppressPolicyCancellationFailure && withError.isPolicyInterruption()) {
                suppressPolicyCancellationFailure = false
                return
            }
            navigationCallback?.invoke(
                NavigationResult(webView.URL?.absoluteString ?: "", 0, false, withError.localizedDescription)
            )
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
    }
}

/** Upper bound for WebKit to report a native appearance change to the document. */
private const val COLOR_SCHEME_APPLY_TIMEOUT_MS = 1_000L

/** Route handlers bound to the native page; mirrors the Android bridge timeout. */

private class IOSRouteEntry(
    val pattern: String,
    val regex: Regex,
    val handler: suspend (Route) -> Unit
)

private fun NSError.javaScriptExceptionMessage(): String =
    userInfo["WKJavaScriptExceptionMessage"]?.toString() ?: localizedDescription

/** WebKitErrorFrameLoadInterruptedByPolicyChange, reported after a policy cancel. */
private fun NSError.isPolicyInterruption(): Boolean = domain == "WebKitErrorDomain" && code == 102L

private fun numberResult(value: Double): String =
    if (value.isFinite() && value.toLong().toDouble() == value) value.toLong().toString() else value.toString()

/** Converts a WebKit completion value with the same shape as the JSON envelope path. */
@OptIn(ExperimentalForeignApi::class)
internal fun nativeScriptResult(result: Any?): String? = when (result) {
    null, is platform.Foundation.NSNull -> null
    is String -> result
    is Boolean -> result.toString()
    is Map<*, *>, is List<*> -> nativeToJson(result).toString()
    else -> when (val json = nativeToJson(result)) {
        is JsonPrimitive -> json.content
        else -> json.toString()
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun nativeToJson(value: Any?): JsonElement = when (value) {
    null, is platform.Foundation.NSNull -> JsonNull
    is String -> JsonPrimitive(value)
    is Boolean -> JsonPrimitive(value)
    is NSNumber -> if (value.objCType?.toKString() == "c") JsonPrimitive(value.boolValue) else jsonNumber(value.doubleValue)
    is Number -> jsonNumber(value.toDouble())
    is Map<*, *> -> JsonObject(value.entries.associate { (key, item) -> key.toString() to nativeToJson(item) })
    is List<*> -> JsonArray(value.map { nativeToJson(it) })
    is NSDate -> JsonPrimitive(formatIso8601(((value.timeIntervalSinceReferenceDate + APPLE_REFERENCE_TO_UNIX_EPOCH_SECONDS) * 1000).toLong()))
    else -> JsonPrimitive(value.toString())
}

private fun jsonNumber(value: Double): JsonPrimitive =
    if (value.isFinite() && value.toLong().toDouble() == value) JsonPrimitive(value.toLong()) else JsonPrimitive(value)

@OptIn(ExperimentalForeignApi::class)
private fun Cookie.toNSHTTPCookie(): platform.Foundation.NSHTTPCookie? {
    val props = mutableMapOf<Any?, Any?>()
    props[platform.Foundation.NSHTTPCookieName] = name
    props[platform.Foundation.NSHTTPCookieValue] = value
    props[platform.Foundation.NSHTTPCookieDomain] = if (hostOnly) domain.removePrefix(".") else ".${domain.removePrefix(".")}"
    props[platform.Foundation.NSHTTPCookiePath] = path
    if (secure) props[platform.Foundation.NSHTTPCookieSecure] = "TRUE"
    if (httpOnly) props["HttpOnly"] = "TRUE"
    expires?.let { props[platform.Foundation.NSHTTPCookieExpires] = NSDate(timeIntervalSinceReferenceDate = it - APPLE_REFERENCE_TO_UNIX_EPOCH_SECONDS) }
    sameSite?.let { props[platform.Foundation.NSHTTPCookieSameSitePolicy] = it.lowercase() }
    return platform.Foundation.NSHTTPCookie.cookieWithProperties(props)
}

private fun List<*>?.toNSHTTPCookies(): List<platform.Foundation.NSHTTPCookie> =
    this?.mapNotNull { it as? platform.Foundation.NSHTTPCookie } ?: emptyList()

/** Deletes every cookie accepted by [filter] and waits for WebKit to confirm. Main thread only. */
private suspend fun WKHTTPCookieStore.deleteCookiesMatching(filter: (platform.Foundation.NSHTTPCookie) -> Boolean) {
    val cookies = suspendCancellableCoroutine<List<platform.Foundation.NSHTTPCookie>> { continuation ->
        getAllCookies { all -> if (continuation.isActive) continuation.resume(all.toNSHTTPCookies().filter(filter)) }
    }
    if (cookies.isEmpty()) return
    suspendCancellableCoroutine<Unit> { continuation ->
        var remaining = cookies.size
        cookies.forEach { cookie ->
            deleteCookie(cookie) {
                remaining -= 1
                if (remaining == 0 && continuation.isActive) continuation.resume(Unit)
            }
        }
    }
}

/** Removes all website data of [store] and waits for completion. Main thread only. */
private suspend fun WKWebsiteDataStore.removeAllData() = suspendCancellableCoroutine<Unit> { continuation ->
    removeDataOfTypes(WKWebsiteDataStore.allWebsiteDataTypes(), modifiedSince = NSDate.distantPast) {
        if (continuation.isActive) continuation.resume(Unit)
    }
}

private fun schemeTaskError(code: Long, description: String): NSError =
    NSError(domain = NSURLErrorDomain, code = code, userInfo = mapOf<Any?, Any?>(NSLocalizedDescriptionKey to description))

/** Maps Playwright abort error codes onto the closest NSURLError. */
private fun routeAbortError(errorCode: String): NSError {
    val code = when (errorCode.lowercase()) {
        "aborted" -> NSURLErrorCancelled
        "timedout" -> NSURLErrorTimedOut
        "accessdenied" -> NSURLErrorNoPermissionsToReadFile
        "addressunreachable", "connectionrefused" -> NSURLErrorCannotConnectToHost
        "connectionaborted", "connectionreset", "connectionclosed" -> NSURLErrorNetworkConnectionLost
        "internetdisconnected" -> NSURLErrorNotConnectedToInternet
        "namenotresolved" -> NSURLErrorCannotFindHost
        "blockedbyclient", "blockedbyresponse" -> NSURLErrorCancelled
        else -> NSURLErrorUnknown
    }
    return schemeTaskError(code, "Request aborted by route: $errorCode")
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

/**
 * One WebKit URL-scheme task. WebKit raises an exception if a task is
 * completed twice or after `stopURLSchemeTask`, so every completion goes
 * through [finish]/[fail], which are no-ops once the task is inactive.
 * Main thread only.
 */
@OptIn(ExperimentalForeignApi::class)
internal class IOSSchemeTask(val task: WKURLSchemeTaskProtocol) {
    var active: Boolean = true
        private set
    var dataTask: NSURLSessionDataTask? = null

    fun finish(response: NSURLResponse, data: NSData?) {
        if (!active) return
        active = false
        dataTask = null
        task.didReceiveResponse(response)
        if (data != null && data.length > 0u) task.didReceiveData(data)
        task.didFinish()
    }

    fun fail(error: NSError) {
        if (!active) return
        active = false
        dataTask?.cancel()
        dataTask = null
        task.didFailWithError(error)
    }

    /** WebKit stopped the task; it must not be messaged again. */
    fun stop() {
        active = false
        dataTask?.cancel()
        dataTask = null
    }
}

/**
 * Retained by the WKWebViewConfiguration, so it references the controller
 * weakly to avoid a configuration -> handler -> controller -> web view cycle.
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
private class IOSSchemeHandler(
    private val config: IosSchemeInterceptionConfig,
    controller: IOSWebViewController
) : NSObject(), WKURLSchemeHandlerProtocol {
    private val controller = WeakReference(controller)
    private val tasks = mutableListOf<IOSSchemeTask>()

    /** Fails every in-flight task; the owning page is closing. */
    fun dispose() {
        val inFlight = tasks.toList()
        tasks.clear()
        inFlight.forEach {
            it.fail(NSError(domain = NSURLErrorDomain, code = NSURLErrorCancelled,
                userInfo = mapOf<Any?, Any?>(NSLocalizedDescriptionKey to "The page was closed")))
        }
    }

    @ObjCSignatureOverride
    override fun webView(webView: WKWebView, startURLSchemeTask: WKURLSchemeTaskProtocol) {
        val record = IOSSchemeTask(startURLSchemeTask)
        val owner = controller.value
        if (owner == null || owner.isDisposed) {
            record.fail(NSError(domain = NSURLErrorDomain, code = NSURLErrorCancelled,
                userInfo = mapOf<Any?, Any?>(NSLocalizedDescriptionKey to "The page was closed")))
            return
        }
        tasks += record
        owner.handleSchemeTask(record, config) { tasks.remove(record) }
    }

    @ObjCSignatureOverride
    override fun webView(webView: WKWebView, stopURLSchemeTask: WKURLSchemeTaskProtocol) {
        val record = tasks.firstOrNull { it.task.isEqual(stopURLSchemeTask) } ?: return
        tasks.remove(record)
        record.stop()
    }
}

/** Retained by WKUserContentController; forwards bridge messages to a weakly held controller. */
@OptIn(ExperimentalNativeApi::class)
private class IOSScriptMessageProxy(controller: IOSWebViewController) : NSObject(), WKScriptMessageHandlerProtocol {
    private val controller = WeakReference(controller)

    override fun userContentController(
        userContentController: WKUserContentController,
        didReceiveScriptMessage: WKScriptMessage
    ) {
        controller.value?.handleScriptMessage(didReceiveScriptMessage)
    }
}

@OptIn(ExperimentalForeignApi::class)
public actual class Browser public actual constructor(
    private val context: PlatformContext,
    private val config: KmpwrightConfig
) {
    private val pages = mutableListOf<Page>()
    private val closed = kotlinx.atomicfu.atomic(false)
    private val iosContext = context as? IOSPlatformContext
    private val backingStore = onMainThread {
        selectBackingStore(
            embeddedStore = iosContext?.webView?.configuration?.websiteDataStore,
            providedStore = iosContext?.websiteDataStore
        ) { WKWebsiteDataStore.nonPersistentDataStore() }
    }
    private val websiteDataStore: WKWebsiteDataStore = backingStore.store
    private val ownsWebsiteDataStore: Boolean = backingStore.owned
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

    private val ownedContexts = mutableListOf<BrowserContext>()
    private var leaseContext: BrowserContext? = null

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

    private val _isConnected = kotlinx.coroutines.flow.MutableStateFlow(true)
    public actual val isConnected: kotlinx.coroutines.flow.StateFlow<Boolean> = _isConnected

    private val _connectionError = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    public actual val connectionError: kotlinx.coroutines.flow.StateFlow<String?> = _connectionError

    init {
        val controller = IOSWebViewController(config, iosContext?.webView, websiteDataStore)
        pages.add(controller.createPage(tabRuntime))
    }

    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public actual suspend fun newPage(): Page = withContext(Dispatchers.Main) {
        check(!closed.value) { "Browser is closed" }
        leaseContext?.let { return@withContext it.newPage() }
        val controller = IOSWebViewController(config, websiteDataStore = websiteDataStore)
        controller.createPage(tabRuntime).also { pages.add(it) }
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
    public actual suspend fun newContext(): BrowserContext = withContext(Dispatchers.Main) {
        check(!closed.value) { "Browser is closed" }
        BrowserContext(
        IOSPlatformContext(websiteDataStore = onMainThread { WKWebsiteDataStore.nonPersistentDataStore() }),
        config
    ).also { ownedContexts.add(it) }
    }
    public actual fun pages(): List<Page> = pages.toList() + ownedContexts.flatMap { it.pages() }
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class)
    public actual fun page(): Page = leaseContext?.let { it.pages().lastOrNull() ?: it.createPage() } ?: pages.lastOrNull() ?: throw IllegalStateException("No page available")

    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public actual suspend fun version(): BrowserVersion = BrowserVersion("Kmpwright", "1.3.0", "Mozilla/5.0 (iPhone; CPU iPhone OS) AppleWebKit/605.1.15")

    public actual fun dispose() {
        if (!closed.compareAndSet(false, true)) return
        onMainThread { finishDispose() }
    }

    private fun finishDispose() {
        _isConnected.value = false
        ownedContexts.toList().forEach { it.dispose() }
        ownedContexts.clear()
        leaseContext = null
        pages.forEach { it.dispose() }
        pages.clear()
        if (ownsWebsiteDataStore) {
            clearOwnedData()
        }
    }

    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public actual suspend fun close(): Unit = withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) {
        if (!closed.compareAndSet(false, true)) return@withContext
        _isConnected.value = false
        try {
            ownedContexts.toList().forEach { it.close() }
            ownedContexts.clear()
            leaseContext = null
            pages.toList().forEach { it.close() }
        } finally { finishDispose() }
    }

    private fun clearOwnedData() = onMainThread {
        websiteDataStore.removeDataOfTypes(WKWebsiteDataStore.allWebsiteDataTypes(), modifiedSince = NSDate.distantPast) {}
    }
}

@OptIn(ExperimentalForeignApi::class)
public actual class BrowserContext public actual constructor(
    private val context: PlatformContext?,
    private val config: KmpwrightConfig
) {
    private val pages = mutableListOf<Page>()
    private val closed = kotlinx.atomicfu.atomic(false)
    private var consumedProvidedWebView = false
    private val iosContext = context as? IOSPlatformContext
    private val backingStore = onMainThread {
        selectBackingStore(
            embeddedStore = iosContext?.webView?.configuration?.websiteDataStore,
            providedStore = iosContext?.websiteDataStore
        ) { WKWebsiteDataStore.nonPersistentDataStore() }
    }
    private val websiteDataStore: WKWebsiteDataStore = backingStore.store
    private val ownsWebsiteDataStore: Boolean = backingStore.owned
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

    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public actual suspend fun newPage(): Page = createPage()

    /** Creates a page synchronously (UI code reaches it through `Browser.page()`). */
    internal fun createPage(): Page = onMainThread {
        check(!closed.value) { "BrowserContext is closed" }
        val existingWebView = if (!consumedProvidedWebView) {
            iosContext?.webView?.also { consumedProvidedWebView = true }
        } else {
            null
        }
        val controller = IOSWebViewController(config, existingWebView, websiteDataStore)
        controller.createPage(tabRuntime).also { pages.add(it) }
    }

    public actual fun pages(): List<Page> = pages.toList()

    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public actual suspend fun clearCookies(): Unit = withContext(Dispatchers.Main) {
        websiteDataStore.httpCookieStore.deleteCookiesMatching { true }
    }

    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public actual suspend fun clearData(): Unit = withContext(Dispatchers.Main) {
        websiteDataStore.removeAllData()
    }

    /** Clears every website data type without waiting (teardown path). */
    private fun clearDataNow() = onMainThread {
        websiteDataStore.removeDataOfTypes(WKWebsiteDataStore.allWebsiteDataTypes(), modifiedSince = NSDate.distantPast) {}
    }

    public actual fun dispose() {
        if (!closed.compareAndSet(false, true)) return
        onMainThread { finishDispose() }
    }

    private fun finishDispose() {
        pages.forEach { it.dispose() }
        pages.clear()
        if (ownsWebsiteDataStore) {
            clearDataNow()
        }
    }

    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public actual suspend fun close(): Unit = withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) {
        if (!closed.compareAndSet(false, true)) return@withContext
        try { pages.toList().forEach { it.close() } } finally { finishDispose() }
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
    override fun createTouch(): Touchscreen {
        if (config.inputMode == InputMode.JsOnly) throw KmpwrightUnsupportedException("Native touch is disabled by InputMode.JsOnly")
        return IOSTouch(controller, config)
    }

    override fun createMouse(): Mouse {
        if (config.inputMode != InputMode.Hybrid) throw KmpwrightUnsupportedException("Native mouse requires InputMode.Hybrid")
        return IOSMouse(controller, config)
    }

    override fun createKeyboard(): Keyboard {
        if (config.inputMode != InputMode.Hybrid) throw KmpwrightUnsupportedException("Native keyboard requires InputMode.Hybrid")
        return IOSKeyboard(controller, config)
    }

    override fun createScreenshotController(): ScreenshotController {
        screenshotControllerFactory?.let { return it() }
        val webView = (controller as? IOSWebViewController)?.getWebView()
            ?: throw IllegalStateException("ScreenshotController requires IOSWebViewController")
        return IOSScreenshotController(webView)
    }

    override suspend fun waitForDownload(url: String?, timeoutMs: Long?): Download {
        throw KmpwrightUnsupportedException("WKWebView does not currently surface download events.")
    }

    override suspend fun waitForFileChooser(timeoutMs: Long?): FileChooser {
        throw KmpwrightUnsupportedException("WKWebView does not currently surface file chooser events.")
    }

    override suspend fun setDownloadPath(path: String) {
        throw KmpwrightUnsupportedException("WKWebView does not currently support download path management.")
    }

    override suspend fun setDownloadBehavior(behavior: DownloadBehavior) {
        throw KmpwrightUnsupportedException("WKWebView does not currently support download behavior management.")
    }
}

internal class IOSLocator(
    controller: WebViewLocatorController,
    selector: String,
    config: KmpwrightConfig,
    options: FilterOptions? = null
) : BaseLocator(controller, selector, config, options) {

    override fun createLocator(newSelector: String, options: FilterOptions?): Locator =
        IOSLocator(controller, newSelector, config, options)

    /** Uses the controller's completion-aware path so Promises and script errors surface. */
    override suspend fun evaluateJavaScriptAsync(script: String): String? =
        controller.normalizeJavaScriptResult(controller.evaluateJavaScriptAsync(script))
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
    domainFiltering = SupportLevel.Supported,
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
    crossOriginFrames = SupportLevel.Unsupported,
    notes = listOf(
        "WKWebView routing covers fetch, XHR, and optional custom-scheme interception, but full browser-network parity remains partial.",
        "iOS input uses touch-first event synthesis with modifier-aware printable keys and DOM text-input fallbacks; hover, drag-and-drop, and low-level key semantics remain partial.",
        "Network emulation is offline-only and implemented in JavaScript (navigator.onLine, fetch, XMLHttpRequest, " +
            "sendBeacon); WKWebView has no latency/throughput throttling, so those parameters are rejected.",
        "Color scheme emulation is native for light and dark when the WKWebView is in a window (overrideUserInterfaceStyle " +
            "drives the CSS prefers-color-scheme media feature, verified after applying); for a window-less web view and for " +
            "\"no-preference\" it falls back to JavaScript (matchMedia and the root color-scheme property only).",
        "Geolocation emulation is a JavaScript fallback that patches navigator.geolocation in every document.",
        "The domain filter is enforced natively: navigations (including server redirects) are cancelled in the navigation " +
            "policy, and every subresource (fetch, XHR, images, iframes, WebSockets, redirects) is blocked by a WKContentRuleList.",
        "Browser contexts use WKWebsiteDataStore isolation, not independent browser processes.",
        "Download and file chooser events are not currently surfaced from WKWebView.",
        "WKUIDelegate exposes no beforeunload callback on iOS, so DialogType.BeforeUnload is never reported; WebKit leaves " +
            "the page without asking.",
        "Network history covers requests issued through fetch/XHR (resourceType \"fetch\"/\"xhr\") and custom-scheme " +
            "interception (\"document\" or \"other\"); other page loads are not observed, and response bodies are unavailable.",
        "Screenshots support PNG and JPEG; WEBP (ImageIO cannot encode it) and omitBackground are unsupported.",
        "PDF export is WKWebView's unpaginated content canvas; page.pdf() defaults to it.",
        "emulateDevice applies the viewport size and user agent; the device scale factor, mobile layout and touch support " +
            "come from the device and are not emulated.",
        "frameLocator reaches same-origin frames only: page JavaScript cannot enter cross-origin iframes."
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
