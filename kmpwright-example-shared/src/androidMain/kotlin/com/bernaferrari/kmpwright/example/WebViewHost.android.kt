package com.bernaferrari.kmpwright.example.shared

import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.bernaferrari.kmpwright.core.AndroidPlatformContext
import com.bernaferrari.kmpwright.core.PlatformContext

@Composable
actual fun KmpwrightWebViewHost(
    modifier: Modifier,
    platformContext: PlatformContext
) {
    val webView = requireNotNull((platformContext as AndroidPlatformContext).webView)
    AndroidView(
        modifier = modifier,
        factory = {
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView
        }
    )
}
