package com.bernaferrari.kmpwright.example

import android.annotation.SuppressLint
import android.os.Build
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.bernaferrari.kmpwright.core.AndroidPlatformContext
import com.bernaferrari.kmpwright.core.PlatformContext

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal actual fun rememberPlatformContext(): PlatformContext {
    val context = LocalContext.current
    return remember(context) {
        val webView = WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        AndroidPlatformContext(context = context, webView = webView)
    }
}

internal actual fun disposePlatformContext(context: PlatformContext) {
    (context as AndroidPlatformContext).webView?.apply {
        (parent as? ViewGroup)?.removeView(this)
        destroy()
    }
}

@Composable
internal actual fun WebViewHost(platformContext: PlatformContext, modifier: Modifier) {
    val webView = requireNotNull((platformContext as AndroidPlatformContext).webView)
    AndroidView(
        modifier = modifier,
        factory = {
            // The same WebView moves between layouts (phone ↔ tablet); detach it first.
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView
        },
        onRelease = { (it.parent as? ViewGroup)?.removeView(it) },
    )
}

@Composable
internal actual fun dynamicColorScheme(dark: Boolean): ColorScheme? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
    val context = LocalContext.current
    return if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
}

internal actual fun readNativeScroll(platformContext: PlatformContext): NativeScroll? {
    val webView = (platformContext as AndroidPlatformContext).webView ?: return null
    if (webView.width == 0) return null
    return NativeScroll(webView.scrollX.toFloat(), webView.scrollY.toFloat(), webView.width.toFloat())
}

internal actual fun releaseWebViewKeyboard(platformContext: PlatformContext) {
    val webView = (platformContext as AndroidPlatformContext).webView ?: return
    if (!webView.hasFocus()) return
    val imm = webView.context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
    imm.hideSoftInputFromWindow(webView.windowToken, 0)
}
