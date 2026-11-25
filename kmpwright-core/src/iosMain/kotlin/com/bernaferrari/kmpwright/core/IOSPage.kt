package com.bernaferrari.kmpwright.core

import kotlinx.cinterop.ExperimentalForeignApi
import platform.WebKit.WKWebView
import platform.WebKit.WKWebsiteDataStore

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
        return SyntheticTouch(controller, config)
    }

    override fun createMouse(): Mouse {
        if (config.inputMode != InputMode.Hybrid) throw KmpwrightUnsupportedException("Native mouse requires InputMode.Hybrid")
        return SyntheticMouse(controller, config)
    }

    override fun createKeyboard(): Keyboard {
        if (config.inputMode != InputMode.Hybrid) throw KmpwrightUnsupportedException("Native keyboard requires InputMode.Hybrid")
        return SyntheticKeyboard(controller, config)
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
