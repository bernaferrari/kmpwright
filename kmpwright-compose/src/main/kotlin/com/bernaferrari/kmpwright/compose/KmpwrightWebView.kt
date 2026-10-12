package com.bernaferrari.kmpwright.compose

import android.content.Context
import android.webkit.WebView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.bernaferrari.kmpwright.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Owns the view; the controller owns its configured Browser/Page attachment. */
@Composable
fun KmpwrightWebView(
    controller: KmpwrightWebViewController,
    modifier: Modifier = Modifier,
    startUrl: String = "about:blank"
) {
    key(controller) {
        AndroidView(
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    controller.attach(this)
                }
            },
            modifier = modifier.fillMaxSize(),
            onReset = null,
            onRelease = { view ->
                controller.detach(view)
                view.removeAllViews()
                view.destroy()
            },
            update = { view -> controller.attach(view) }
        )
        LaunchedEffect(controller, startUrl) { controller.navigate(startUrl) }
        DisposableEffect(controller) { onDispose { controller.attach(null) } }
    }
}

class KmpwrightWebViewController(
    private val context: Context,
    private val config: KmpwrightConfig = KmpwrightConfig()
) {
    private var webView: WebView? = null
    private var browser: Browser? = null
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _error = MutableStateFlow<Throwable?>(null)
    val error: StateFlow<Throwable?> = _error

    /** Becomes available when the composable attaches its WebView. */
    var page: Page? by mutableStateOf(null)
        private set

    fun attach(webView: WebView?) {
        if (this.webView === webView) return
        scope.cancel()
        browser?.close()
        browser = null
        page = null
        this.webView = webView
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        if (webView != null) {
            browser = Browser(AndroidPlatformContext(context, webView = webView), config)
            page = browser!!.page()
        }
    }

    internal fun detach(view: WebView) {
        if (webView === view) attach(null)
    }

    private fun attachedPage(): Page = page ?: error("KMPwright WebView is not attached")

    /** Suspends until the configured automation navigation completes. */
    suspend fun navigate(url: String): NavigationResult {
        val page = attachedPage()
        config.viewport?.let { page.setViewportSize(it.width, it.height) }
        config.userAgent?.let { page.setUserAgent(it) }
        return page.goto(url)
    }

    private fun launchOperation(block: suspend () -> Unit) {
        scope.launch {
            try { block(); _error.value = null }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Throwable) { _error.value = failure }
        }
    }

    fun loadUrl(url: String) = launchOperation {
        val result = navigate(url)
        if (!result.ok) throw KmpwrightException(result.error ?: "Navigation failed")
    }

    fun evaluateJavaScript(script: String, callback: (String?) -> Unit) = launchOperation {
        callback(attachedPage().evaluate(script).value)
    }

    fun reload() = launchOperation { attachedPage().reload() }
    fun goBack() = launchOperation { attachedPage().back() }
    fun goForward() = launchOperation { attachedPage().forward() }
    fun canGoBack(): Boolean = webView?.canGoBack() == true
    fun canGoForward(): Boolean = webView?.canGoForward() == true
}

@Composable
fun rememberKmpwrightController(
    context: Context,
    config: KmpwrightConfig = KmpwrightConfig()
): KmpwrightWebViewController = remember(context, config) { KmpwrightWebViewController(context, config) }
