package com.bernaferrari.kmpwright.compose

import android.content.Context
import android.webkit.WebView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.bernaferrari.kmpwright.core.*

@Composable
fun KmpwrightWebView(
    controller: KmpwrightWebViewController,
    modifier: Modifier = Modifier,
    startUrl: String = "about:blank"
) {
    var webView by remember { mutableStateOf<WebView?>(null) }

    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    allowFileAccess = false
                    allowContentAccess = false
                    mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                }
                loadUrl(startUrl)
                webView = this
                controller.attach(this)
            }
        },
        modifier = modifier.fillMaxSize(),
        update = { view ->
            webView = view
            controller.attach(view)
        }
    )

    DisposableEffect(Unit) {
        onDispose {
            controller.attach(null)
        }
    }
}

class KmpwrightWebViewController(
    private val context: Context,
    private val config: KmpwrightConfig = KmpwrightConfig()
) {
    private var webView: WebView? = null

    fun attach(webView: WebView?) {
        this.webView = webView
    }

    fun loadUrl(url: String) {
        webView?.loadUrl(url)
    }

    fun evaluateJavaScript(script: String, callback: (String?) -> Unit) {
        webView?.evaluateJavascript(script, callback)
    }

    fun reload() {
        webView?.reload()
    }

    fun goBack() {
        webView?.goBack()
    }

    fun goForward() {
        webView?.goForward()
    }

    fun canGoBack(): Boolean = webView?.canGoBack() == true
    fun canGoForward(): Boolean = webView?.canGoForward() == true
}

@Composable
fun rememberKmpwrightController(
    context: Context,
    config: KmpwrightConfig = KmpwrightConfig()
): KmpwrightWebViewController {
    return remember { KmpwrightWebViewController(context, config) }
}
