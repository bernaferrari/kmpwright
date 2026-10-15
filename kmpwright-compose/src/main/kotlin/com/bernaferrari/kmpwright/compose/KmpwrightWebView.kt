package com.bernaferrari.kmpwright.compose

import android.annotation.SuppressLint
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.bernaferrari.kmpwright.core.AndroidPlatformContext
import com.bernaferrari.kmpwright.core.Browser
import com.bernaferrari.kmpwright.core.KmpwrightConfig
import com.bernaferrari.kmpwright.core.KmpwrightException
import com.bernaferrari.kmpwright.core.NavigationResult
import com.bernaferrari.kmpwright.core.Page
import com.bernaferrari.kmpwright.core.androidWebViewState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * An Android [WebView] driven by Kmpwright.
 *
 * @param controller state holder; create it with [rememberKmpwrightController].
 * @param startUrl loaded when the WebView attaches or this value changes; `null` leaves navigation to the caller.
 * @param captureBackPresses when true, system back navigates the WebView history while it can go back.
 * @param onCreated called with the new WebView before Kmpwright attaches; clients installed here keep
 *   receiving every callback.
 */
@Composable
public fun KmpwrightWebView(
    controller: KmpwrightWebViewController,
    modifier: Modifier = Modifier,
    startUrl: String? = null,
    captureBackPresses: Boolean = true,
    onCreated: (WebView) -> Unit = {}
) {
    val currentOnCreated by rememberUpdatedState(onCreated)
    BackHandler(enabled = captureBackPresses && controller.canGoBack) { controller.goBack() }

    // A crashed renderer cannot be reused: a new generation recreates the view.
    key(controller, controller.viewGeneration) {
        AndroidView(
            factory = { context ->
                WebView(context).apply {
                    // Automation is implemented with page scripts.
                    @SuppressLint("SetJavaScriptEnabled")
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    currentOnCreated(this)
                    controller.attach(this)
                }
            },
            modifier = modifier,
            onRelease = { view ->
                controller.detach(view)
                view.destroy()
            }
        )
        val page = controller.page
        LaunchedEffect(page, startUrl) {
            if (page != null && startUrl != null) controller.loadStartUrl(startUrl)
        }
    }
}

/**
 * Remembers a [KmpwrightWebViewController]. [config] is read once; the
 * controller is not recreated when the configuration value changes.
 */
@Composable
public fun rememberKmpwrightController(config: KmpwrightConfig = KmpwrightConfig()): KmpwrightWebViewController =
    remember { KmpwrightWebViewController(config) }

/**
 * State holder for [KmpwrightWebView]. All members must be used on the main
 * thread; the observable properties are Compose snapshot state.
 */
@Stable
public class KmpwrightWebViewController(private val config: KmpwrightConfig = KmpwrightConfig()) {
    private var webView: WebView? = null
    private var browser: Browser? = null
    private var scope = newScope()

    /** Becomes available when the composable attaches its WebView. */
    public var page: Page? by mutableStateOf(null)
        private set

    /** The committed URL. */
    public var url: String? by mutableStateOf(null)
        private set

    /** The document title. */
    public var title: String? by mutableStateOf(null)
        private set

    /** Whether a navigation is in progress. */
    public var isLoading: Boolean by mutableStateOf(false)
        private set

    /** Whether [goBack] has a history entry to go to. */
    public var canGoBack: Boolean by mutableStateOf(false)
        private set

    /** Whether [goForward] has a history entry to go to. */
    public var canGoForward: Boolean by mutableStateOf(false)
        private set

    /** The last failure of a fire-and-forget operation or a renderer crash. */
    public var lastError: Throwable? by mutableStateOf(null)
        private set

    internal var viewGeneration by mutableIntStateOf(0)
        private set

    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    internal fun attach(webView: WebView) {
        if (this.webView === webView) return
        release()
        this.webView = webView
        config.userAgent?.let { webView.settings.userAgentString = it }
        val attached = Browser(AndroidPlatformContext(webView.context, webView = webView), config)
        browser = attached
        val attachedPage = attached.page()
        page = attachedPage
        val state = attachedPage.androidWebViewState() ?: return
        scope.launch {
            state.collect { snapshot ->
                url = snapshot.url
                title = snapshot.title
                isLoading = snapshot.isLoading
                canGoBack = snapshot.canGoBack
                canGoForward = snapshot.canGoForward
                if (snapshot.rendererGone && this@KmpwrightWebViewController.webView === webView) {
                    lastError = KmpwrightException("Page crashed: the WebView renderer process is gone; recreating the WebView")
                    viewGeneration++
                }
            }
        }
    }

    internal fun detach(view: WebView) {
        if (webView === view) release()
    }

    private fun release() {
        scope.cancel()
        scope = newScope()
        browser?.dispose()
        browser = null
        page = null
        webView = null
        url = null
        title = null
        isLoading = false
        canGoBack = false
        canGoForward = false
    }

    private fun attachedPage(): Page = page ?: throw KmpwrightException("KMPwright WebView is not attached")

    /** Navigates the attached page (Playwright `page.goto`) and suspends until the navigation completes. */
    public suspend fun goto(url: String): NavigationResult = attachedPage().goto(url)

    /** Evaluates [script] in the page and returns its JSON-normalized value. */
    public suspend fun evaluate(script: String): String = attachedPage().evaluate(script).value

    /** Clears [lastError]. */
    public fun clearError() {
        lastError = null
    }

    private suspend fun reportOperation(block: suspend () -> Unit) {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            lastError = failure
        }
    }

    private fun launchOperation(block: suspend () -> Unit) {
        scope.launch { reportOperation(block) }
    }

    // Keep the effect's cancellation, but dispatch WebView continuations independently of Compose's
    // frame clock. A paused composition must not hold the page's navigation mutex.
    internal suspend fun loadStartUrl(url: String): Unit = withContext(Dispatchers.Main.immediate) {
        reportOperation {
            val result = goto(url)
            if (!result.ok) throw KmpwrightException(result.error ?: "Navigation to $url failed with status ${result.status}")
        }
    }

    /** Fire-and-forget navigation; failures are reported through [lastError]. */
    public fun loadUrl(url: String): Unit = launchOperation { loadStartUrl(url) }

    /** Fire-and-forget reload; failures are reported through [lastError]. */
    public fun reload(): Unit = launchOperation { attachedPage().reload() }

    /** Fire-and-forget history back (Playwright `page.goBack`); failures are reported through [lastError]. */
    public fun goBack(): Unit = launchOperation { attachedPage().goBack() }

    /** Fire-and-forget history forward (Playwright `page.goForward`); failures are reported through [lastError]. */
    public fun goForward(): Unit = launchOperation { attachedPage().goForward() }
}
