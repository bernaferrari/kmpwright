package com.bernaferrari.kmpwright.core

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * A mock [WebViewController] with sensible defaults and configurable hooks.
 *
 * Usage:
 * ```kotlin
 * val controller = MockWebViewController {
 *     onEval { script ->
 *         when {
 *             script.contains("getItem") -> "\"value\""
 *             else -> null
 *         }
 *     }
 *     onScreenshot { ScreenshotResult(ByteArray(0), 375, 667) }
 * }
 * val page = TestPage(controller)
 * ```
 */
class MockWebViewController : WebViewController {

    var evalHandler: ((String) -> String?)? = null
    var waitResultOverride: String? = null
    var completeNavigationImmediately = true
    private var committedUrl = "about:blank"
    var screenshotHandler: ((ScreenshotOptions) -> ScreenshotResult)? = null
    private var _navigationCallback: ((NavigationResult) -> Unit)? = null
    val executedScripts: MutableList<String> = mutableListOf()
    val cookies: MutableList<Cookie> = mutableListOf()
    var disposed: Boolean = false
    var disposeCount: Int = 0
    var navigationCallbackSetCount: Int = 0
    var removeRequestListenerCount: Int = 0
    var removeDialogListenerCount: Int = 0
    var removeAllRouteHandlersCount: Int = 0
    var throwOnNavigationCallback: Boolean = false
    var throwOnRemoveRequestListener: Boolean = false
    var throwOnRemoveDialogListener: Boolean = false
    var throwOnRemoveAllRouteHandlers: Boolean = false
    var throwOnDispose: Boolean = false
    var recordedExtraHttpHeaders: Map<String, String> = emptyMap()
    var recordedHttpCredentials: Pair<String, String>? = null

    constructor()

    constructor(configure: Builder.() -> Unit) {
        Builder().apply(configure).applyTo(this)
    }

    override fun loadUrl(url: String) {
        committedUrl = url
        if (completeNavigationImmediately) _navigationCallback?.invoke(NavigationResult(url, 200, true))
    }

    override fun loadHtml(content: String, baseUrl: String?) {
        committedUrl = baseUrl ?: "about:blank"
        _navigationCallback?.invoke(NavigationResult(baseUrl ?: "about:blank", 200, true))
    }

    override fun evaluateJavaScript(script: String, callback: (String?) -> Unit) {
        executedScripts.add(script)
        val result = (if (script.contains("new Promise")) waitResultOverride else null) ?: evalHandler?.invoke(script)
            ?: if (script.contains("document.readyState")) "complete" else null
        callback(result)
    }

    override fun goBack() {}

    override fun goForward() {}

    override fun reload() {}

    override fun getUrl(): String? = committedUrl

    override fun getTitle(): String? = null

    override fun takeScreenshot(options: ScreenshotOptions, callback: (ByteArray?, Int, Int) -> Unit) {
        val result = screenshotHandler?.invoke(options) ?: ScreenshotResult(ByteArray(0), 375, 667)
        callback(result.bytes, result.width, result.height)
    }

    override fun getViewportSize(): Pair<Int, Int> = 375 to 667

    override fun setViewportSize(width: Int, height: Int) {}

    override fun setZoomScale(scale: Double) {}

    override fun getZoomScale(): Double = 1.0

    override fun setUserAgent(userAgent: String) {}

    override fun setDeviceScaleFactor(scale: Double) {}

    override fun injectJavaScript(script: String, callback: ((String?) -> Unit)?) {
        executedScripts.add(script)
        callback?.invoke(evalHandler?.invoke(script))
    }

    override fun dispose() {
        disposed = true
        disposeCount += 1
        if (throwOnDispose) throw IllegalStateException("synthetic dispose failure")
    }

    override fun setNavigationCallback(callback: ((NavigationResult) -> Unit)?) {
        navigationCallbackSetCount += 1
        if (throwOnNavigationCallback) throw IllegalStateException("synthetic navigation cleanup failure")
        _navigationCallback = callback
    }

    override fun generatePdf(options: PdfOptions, callback: (ByteArray?) -> Unit) {}

    override fun addRequestListener(listener: RequestListener) {}

    override fun removeRequestListener(listener: RequestListener) {
        removeRequestListenerCount += 1
        if (throwOnRemoveRequestListener) throw IllegalStateException("synthetic request cleanup failure")
    }

    override fun getPendingRequestCount(): Int = 0

    override fun resetRequestCount() {}

    override fun setRequestIdleTimeout(timeoutMs: Long) {}

    override fun addDialogListener(listener: DialogListener) {}

    override fun removeDialogListener(listener: DialogListener) {
        removeDialogListenerCount += 1
        if (throwOnRemoveDialogListener) throw IllegalStateException("synthetic dialog cleanup failure")
    }

    override fun addRouteHandler(urlPattern: String, handler: suspend (Route) -> RouteHandler) {}

    override fun removeRouteHandler(urlPattern: String) {}

    override fun removeAllRouteHandlers() {
        removeAllRouteHandlersCount += 1
        if (throwOnRemoveAllRouteHandlers) throw IllegalStateException("synthetic route cleanup failure")
    }

    override fun fulfillRoute(requestId: String, options: FulfillOptions) {}

    override fun abortRoute(requestId: String, errorCode: String) {}

    override fun continueRoute(requestId: String, options: ContinueOptions) {}

    override fun setCookie(cookie: Cookie) {
        cookies.removeAll { it.name == cookie.name && it.domain == cookie.domain }
        cookies.add(cookie)
    }

    override fun getCookie(name: String, domain: String, callback: (Cookie?) -> Unit) {
        callback(cookies.find { it.name == name && cookieDomainMatches(it.domain, domain) })
    }

    override fun getAllCookies(callback: (List<Cookie>) -> Unit) {
        callback(cookies.toList())
    }

    override fun deleteCookie(name: String, domain: String) {
        cookies.removeAll { it.name == name && cookieDomainMatches(it.domain, domain) }
    }

    override fun deleteAllCookies() {
        cookies.clear()
    }

    override fun setExtraHttpHeaders(headers: Map<String, String>): Boolean {
        recordedExtraHttpHeaders = headers.toMap()
        return true
    }

    override fun setHttpCredentials(username: String?, password: String?): Boolean {
        recordedHttpCredentials = if (username != null && password != null) {
            username to password
        } else {
            null
        }
        return true
    }

    override fun interceptRequest(
        url: String,
        method: String,
        headers: Map<String, String>,
        onResult: (RouteAction?) -> Unit
    ) {}

    class Builder {
        private var evalHandler: ((String) -> String?)? = null
        private var screenshotHandler: ((ScreenshotOptions) -> ScreenshotResult)? = null

        fun onEval(handler: (String) -> String?) {
            evalHandler = handler
        }

        fun onScreenshot(handler: (ScreenshotOptions) -> ScreenshotResult) {
            screenshotHandler = handler
        }

        internal fun applyTo(controller: MockWebViewController) {
            controller.evalHandler = evalHandler
            controller.screenshotHandler = screenshotHandler
        }
    }
}

/**
 * A reusable test [Page] implementation backed by [MockWebViewController].
 *
 * Usage:
 * ```kotlin
 * // All defaults
 * val page = TestPage()
 *
 * // With custom eval
 * val page = TestPage {
 *     onEval { script ->
 *         when {
 *             script.contains("getItem") -> "\"value\""
 *             else -> null
 *         }
 *     }
 * }
 * ```
 */
class TestPage : BasePage {

    val evalController: MockWebViewController

    constructor() : this(MockWebViewController())

    internal constructor(
        controller: MockWebViewController,
        tabRuntime: PageTabRuntime? = null,
        config: KmpwrightConfig = KmpwrightConfig()
    ) : super(controller, config, tabRuntime) {
        evalController = controller
    }

    constructor(configure: MockWebViewController.Builder.() -> Unit) : super(
        MockWebViewController().also { MockWebViewController.Builder().apply(configure).applyTo(it) },
        KmpwrightConfig()
    ) {
        @Suppress("UNCHECKED_CAST")
        evalController = controller as MockWebViewController
    }

    override fun createPlatformLocator(selector: String): BaseLocator =
        TestLocator(selector, controller, config)

    override fun createTouch(): Touch = throw UnsupportedOperationException("Touch not supported in TestPage")

    override fun createMouse(): Mouse = throw UnsupportedOperationException("Mouse not supported in TestPage")

    override fun createKeyboard(): Keyboard = throw UnsupportedOperationException("Keyboard not supported in TestPage")
}

/**
 * A test [Locator] backed by [MockWebViewController].
 */
class TestLocator(
    selector: String,
    controller: WebViewLocatorController,
    config: KmpwrightConfig = KmpwrightConfig()
) : BaseLocator(controller, selector, config) {

    constructor(selector: String) : this(selector, defaultController())

    val selectorStr: String get() = this.selector

    override fun createLocator(newSelector: String, options: FilterOptions?): Locator =
        TestLocator(newSelector, controller, config)

    override suspend fun evaluateJavaScriptAsync(script: String): String? {
        return suspendCancellableCoroutine { cont ->
            controller.evaluateJavaScript(script) { result ->
                cont.resume(result)
            }
        }
    }

    companion object {
        private fun defaultController(): WebViewController = MockWebViewController()
    }
}
