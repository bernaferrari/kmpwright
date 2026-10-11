package com.bernaferrari.kmpwright.example.shared

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.bernaferrari.kmpwright.core.IOSPlatformContext
import com.bernaferrari.kmpwright.core.PlatformContext
import kotlinx.cinterop.ExperimentalForeignApi
import platform.WebKit.WKWebView

@Composable
@OptIn(ExperimentalForeignApi::class)
actual fun rememberPlatformContext(): PlatformContext {
    return remember { IOSPlatformContext(WKWebView()) }
}

@OptIn(ExperimentalForeignApi::class)
actual fun disposePlatformContext(context: PlatformContext) {
    (context as IOSPlatformContext).webView?.apply {
        stopLoading()
        removeFromSuperview()
    }
}
