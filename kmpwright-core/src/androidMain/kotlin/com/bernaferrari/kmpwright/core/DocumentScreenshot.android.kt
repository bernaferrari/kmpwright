package com.bernaferrari.kmpwright.core

import android.webkit.WebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Capture a bounded document or complete selected region without taking ownership of [webView].
 * The caller prepares and settles the page before capture. Scroll position and temporary DOM changes
 * are restored on failure or cancellation. [afterFirstTile] runs after the first viewport row, so
 * pinned content can be hidden before subsequent rows.
 */
@Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, kotlin.coroutines.cancellation.CancellationException::class)
public suspend fun captureWebViewDocument(
    webView: WebView,
    clip: Rectangle? = null,
    pixelsPerCssPixel: Double = 1.0,
    maxPixels: Long = 32_000_000,
    maxHeight: Int = Int.MAX_VALUE,
    truncateFullPage: Boolean = false,
    format: ScreenshotFormat = ScreenshotFormat.Png,
    quality: Int = 90,
    viewportWidthOnly: Boolean = false,
    afterFirstTile: suspend () -> Unit = {}
): ScreenshotResult = captureWebViewDocument(
    webView,
    DocumentScreenshotOptions(clip, pixelsPerCssPixel, maxPixels, maxHeight, truncateFullPage, format, quality, viewportWidthOnly),
    afterFirstTile
)

internal suspend fun captureWebViewDocument(
    webView: WebView,
    options: DocumentScreenshotOptions,
    afterFirstTile: suspend () -> Unit = {}
): ScreenshotResult = withContext(Dispatchers.Main.immediate) {
    AndroidScreenshotController(webView).captureDocument(options, afterFirstTile)
}
