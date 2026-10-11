package com.bernaferrari.kmpwright.example.shared

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import android.webkit.WebView
import com.bernaferrari.kmpwright.core.AndroidPlatformContext
import com.bernaferrari.kmpwright.core.PlatformContext

@Composable
actual fun rememberPlatformContext(): PlatformContext {
    val context = LocalContext.current
    return remember(context) {
        AndroidPlatformContext(
            context = context,
            webView = WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            }
        )
    }
}

actual fun disposePlatformContext(context: PlatformContext) {
    (context as AndroidPlatformContext).webView?.destroy()
}
