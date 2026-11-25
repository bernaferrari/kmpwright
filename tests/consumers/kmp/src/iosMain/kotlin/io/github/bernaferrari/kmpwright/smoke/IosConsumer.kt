package io.github.bernaferrari.kmpwright.smoke

import com.bernaferrari.kmpwright.core.captureWebViewDocument
import kotlinx.cinterop.ExperimentalForeignApi
import platform.WebKit.WKWebView

@OptIn(ExperimentalForeignApi::class)
suspend fun captureWithPublishedKlib(webView: WKWebView): ByteArray =
    captureWebViewDocument(webView, maxPixels = 1_000_000, truncateFullPage = true).bytes
