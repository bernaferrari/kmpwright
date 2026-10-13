@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.bernaferrari.kmpwright.core

import kotlinx.cinterop.useContents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSNumber
import platform.WebKit.WKWebView
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Capture document CSS coordinates without taking ownership of [webView] or its navigation delegate.
 * The caller prepares and settles the page before capture. Scroll position is restored on failure or
 * cancellation. [afterFirstTile] runs after the first viewport row on Android and after the snapshot
 * on iOS; it can hide pinned content before subsequent Android rows.
 */
@Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, kotlin.coroutines.cancellation.CancellationException::class)
public suspend fun captureWebViewDocument(
    webView: WKWebView,
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
    webView: WKWebView,
    options: DocumentScreenshotOptions,
    afterFirstTile: suspend () -> Unit = {}
): ScreenshotResult = withContext(Dispatchers.Main) {
    if (options.format == ScreenshotFormat.Webp) throw KmpwrightUnsupportedException("WEBP screenshots are not supported on iOS; use PNG or JPEG")
    val cssWidth = webView.captureViewportCssWidth()
    val widthPoints = webView.bounds.useContents { size.width }
    require(cssWidth > 0 && widthPoints > 0) { "Could not measure the page viewport" }
    val pointsPerCss = widthPoints / cssWidth
    val documentWidth = webView.captureNumber("Math.max(document.documentElement.scrollWidth,document.body?document.body.scrollWidth:0,window.innerWidth)")
    val documentHeight = webView.captureNumber("Math.max(document.documentElement.scrollHeight,document.body?document.body.scrollHeight:0,window.innerHeight)")
    val plan = planDocumentCapture(options, if (options.viewportWidthOnly && options.clip == null) cssWidth else documentWidth, documentHeight)
    val originalX = webView.captureNumber("window.scrollX")
    val originalY = webView.captureNumber("window.scrollY")
    try {
        webView.captureEvaluate("window.scrollTo({left:${plan.rect.x},top:${plan.rect.y},behavior:'instant'});0")
        webView.layoutIfNeeded()
        delay(32)
        val actualX = webView.captureNumber("window.scrollX")
        val actualY = webView.captureNumber("window.scrollY")
        val rect = CGRectMake((plan.rect.x - actualX) * pointsPerCss, (plan.rect.y - actualY) * pointsPerCss, plan.rect.width * pointsPerCss, plan.rect.height * pointsPerCss)
        val scale = options.pixelsPerCssPixel / (pointsPerCss * webView.snapshotScreenScale())
        val shot = webView.captureSnapshot(rect, scale, options.format, options.quality)
        check(shot.width == plan.width && kotlin.math.abs(shot.height - plan.height) <= 1) { "The page changed size while taking its screenshot" }
        afterFirstTile()
        shot.copy(truncated = plan.truncated)
    } finally {
        withContext(NonCancellable) {
            webView.captureEvaluate("window.scrollTo({left:$originalX,top:$originalY,behavior:'instant'});0")
            webView.layoutIfNeeded()
            delay(32)
        }
    }
}

internal suspend fun WKWebView.captureEvaluate(script: String): Any? = suspendCancellableCoroutine { continuation ->
    evaluateJavaScript(script) { value, error ->
        if (continuation.isActive) {
            if (error != null) continuation.resumeWithException(KmpwrightException("Could not measure or position the page: ${error.localizedDescription}"))
            else continuation.resume(value)
        }
    }
}

internal suspend fun WKWebView.captureNumber(script: String): Double =
    (captureEvaluate(script) as? NSNumber)?.doubleValue?.takeIf { it.isFinite() }
        ?: throw KmpwrightException("Could not measure the page")

/** The visible CSS viewport can be narrower than innerWidth when the page is zoomed. */
internal suspend fun WKWebView.captureViewportCssWidth(): Double =
    captureNumber("window.visualViewport ? window.visualViewport.width : window.innerWidth")
