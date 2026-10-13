package com.bernaferrari.kmpwright.core

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.webkit.WebView
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.math.roundToInt

/** Pixel-aligned, bounded tiling. The DOM is restored even when capture is cancelled. */
internal suspend fun AndroidScreenshotController.captureDocument(
    options: DocumentScreenshotOptions,
    afterFirstTile: suspend () -> Unit
): ScreenshotResult {
    prepareDocumentCapture(options)
    val webView = captureWebView
    @Suppress("DEPRECATION") val nativePerCss = webView.scale.toDouble().takeIf { it.isFinite() && it > 0 } ?: 1.0
    val dimensions = JSONObject(evaluateForDocument("JSON.stringify({width:Math.max(document.documentElement.scrollWidth,document.body?document.body.scrollWidth:0,window.innerWidth),height:Math.max(document.documentElement.scrollHeight,document.body?document.body.scrollHeight:0,window.innerHeight)})") ?: throw KmpwrightException("Could not measure the page"))
    // innerWidth is rounded to integer CSS pixels; use the real visible width to avoid a thin extra tile.
    val widthCss = if (options.viewportWidthOnly && options.clip == null) webView.width / nativePerCss else dimensions.getDouble("width")
    val plan = planDocumentCapture(options, widthCss, dimensions.getDouble("height"))
    val nativeWidth = (plan.rect.width * nativePerCss).roundToInt()
    val nativeHeight = (plan.rect.height * nativePerCss).roundToInt()
    require(nativeWidth > 0 && nativeHeight > 0) { "The selected part is too small to capture" }
    val outputScale = options.pixelsPerCssPixel / nativePerCss
    val originalScroll = readScrollPosition()
    val vertical = webView.isVerticalScrollBarEnabled
    val horizontal = webView.isHorizontalScrollBarEnabled
    val output = allocateDocumentBitmap(plan.width, plan.height)
    var transformed = false
    try {
        webView.isVerticalScrollBarEnabled = false
        webView.isHorizontalScrollBarEnabled = false
        if (options.clip != null) {
            transformed = true
            evaluateForDocument("(function(){var e=document.documentElement;window.__kmpwrightCaptureTransform=[e.style.getPropertyValue('transform'),e.style.getPropertyPriority('transform')];var t=getComputedStyle(e).transform;e.style.setProperty('transform','translate(${-plan.rect.x}px,${-plan.rect.y}px) '+(t==='none'?'':t),'important');window.scrollTo({left:0,top:0,behavior:'instant'});return true;})()")
        }
        val canvas = Canvas(output)
        val documentWidth = (dimensions.getDouble("width") * nativePerCss).roundToInt()
        val documentHeight = (dimensions.getDouble("height") * nativePerCss).roundToInt()
        val lastLeft = (documentWidth - webView.width).coerceAtLeast(0)
        val lastTop = (documentHeight - webView.height).coerceAtLeast(0)
        val unit = (nativePerCss * 8).roundToInt().coerceAtLeast(1)
        val stepY = (webView.height / unit * unit).coerceAtLeast(1)
        var y = 0
        var first = true
        while (y < nativeHeight) {
            currentCoroutineContext().ensureActive()
            var x = 0
            var rowHeight = 0
            while (x < nativeWidth) {
                val viewportX = minOf(x, lastLeft)
                val viewportY = minOf(y, lastTop)
                scrollForCapture(viewportX, viewportY)
                awaitDocumentRendered()
                // Position and software draw are atomic on Main. PixelCopy verifies this position again.
                webView.scrollTo(viewportX, viewportY)
                check(webView.scrollX == viewportX && webView.scrollY == viewportY) { "The page changed size while taking its screenshot" }
                val tile = captureDocumentTile(viewportX, viewportY)
                try {
                    val offsetX = x - viewportX
                    val offsetY = y - viewportY
                    val columns = minOf(webView.width - offsetX, nativeWidth - x)
                    val rows = minOf(webView.height - offsetY, nativeHeight - y)
                    check(columns > 0 && rows > 0) { "The page changed size while taking its screenshot" }
                    val left = (x * outputScale).roundToInt()
                    val top = (y * outputScale).roundToInt()
                    val right = minOf(((x + columns) * outputScale).roundToInt(), plan.width)
                    val bottom = minOf(((y + rows) * outputScale).roundToInt(), plan.height)
                    canvas.drawBitmap(tile, Rect(offsetX, offsetY, offsetX + columns, offsetY + rows), Rect(left, top, right, bottom), null)
                    x += columns
                    rowHeight = maxOf(rowHeight, rows)
                } finally { tile.recycle() }
            }
            if (first) {
                afterFirstTile()
                first = false
            }
            check(rowHeight > 0) { "The page changed size while taking its screenshot" }
            y += minOf(stepY, rowHeight)
        }
        return ScreenshotResult(encodeDocumentBitmap(output), plan.width, plan.height, truncated = plan.truncated)
    } finally {
        try {
            withContext(NonCancellable) {
                try {
                    if (transformed) evaluateForDocument("(function(){var e=document.documentElement,s=window.__kmpwrightCaptureTransform;if(s){if(s[0])e.style.setProperty('transform',s[0],s[1]);else e.style.removeProperty('transform');}delete window.__kmpwrightCaptureTransform;return true;})()")
                } finally {
                    webView.isVerticalScrollBarEnabled = vertical
                    webView.isHorizontalScrollBarEnabled = horizontal
                    restoreScrollPosition(originalScroll)
                }
            }
        } finally { output.recycle() }
    }
}
