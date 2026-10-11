package com.bernaferrari.kmpwright.example.shared

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import com.bernaferrari.kmpwright.core.IOSPlatformContext
import com.bernaferrari.kmpwright.core.PlatformContext
import kotlinx.cinterop.ExperimentalForeignApi

@Composable
@OptIn(ExperimentalForeignApi::class)
actual fun KmpwrightWebViewHost(
    modifier: Modifier,
    platformContext: PlatformContext
) {
    val webView = requireNotNull((platformContext as IOSPlatformContext).webView)
    UIKitView(
        modifier = modifier,
        factory = {
            webView.removeFromSuperview()
            webView
        }
    )
}
