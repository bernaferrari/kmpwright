package com.bernaferrari.kmpwright.example

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import com.bernaferrari.kmpwright.core.IOSPlatformContext
import com.bernaferrari.kmpwright.core.PlatformContext
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.UIKit.UIView
import platform.UIKit.endEditing
import platform.UIKit.UIViewAutoresizingFlexibleHeight
import platform.UIKit.UIViewAutoresizingFlexibleWidth
import platform.WebKit.WKWebView

@OptIn(ExperimentalForeignApi::class)
@Composable
internal actual fun rememberPlatformContext(): PlatformContext =
    remember { IOSPlatformContext(WKWebView()) }

@OptIn(ExperimentalForeignApi::class)
internal actual fun disposePlatformContext(context: PlatformContext) {
    (context as IOSPlatformContext).webView?.apply {
        stopLoading()
        removeFromSuperview()
    }
}

@OptIn(ExperimentalForeignApi::class)
@Composable
internal actual fun WebViewHost(platformContext: PlatformContext, modifier: Modifier) {
    val webView = requireNotNull((platformContext as IOSPlatformContext).webView)
    // With native tabs every tab hosts its own Compose controller but there is
    // one WKWebView. Each host owns a container and adopts the web view while
    // its tab is the active one.
    val active = LocalWebViewActive.current
    UIKitView(
        modifier = modifier,
        factory = { UIView() },
        update = { container ->
            if (active && webView.superview != container) {
                webView.removeFromSuperview()
                webView.setFrame(container.bounds)
                webView.autoresizingMask = UIViewAutoresizingFlexibleWidth or UIViewAutoresizingFlexibleHeight
                container.addSubview(webView)
            }
        },
    )
}

@Composable
internal actual fun dynamicColorScheme(dark: Boolean): ColorScheme? = null

@OptIn(ExperimentalForeignApi::class)
internal actual fun readNativeScroll(platformContext: PlatformContext): NativeScroll? {
    val webView = (platformContext as IOSPlatformContext).webView ?: return null
    val width = webView.bounds.useContents { size.width }
    if (width <= 0.0) return null
    return webView.scrollView.contentOffset.useContents { NativeScroll(x.toFloat(), y.toFloat(), width.toFloat()) }
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun releaseWebViewKeyboard(platformContext: PlatformContext) {
    // Only resigns first responders inside the web view; Compose text fields are untouched.
    (platformContext as IOSPlatformContext).webView?.endEditing(true)
}
