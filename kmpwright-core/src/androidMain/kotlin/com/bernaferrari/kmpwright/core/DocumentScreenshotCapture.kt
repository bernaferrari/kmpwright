package com.bernaferrari.kmpwright.core

import android.graphics.Canvas
import android.graphics.Rect
import android.util.Log
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.roundToInt

/** Tile the document in its own coordinates, preserving the page's styles and original scroll. */
internal suspend fun AndroidScreenshotController.captureDocument(
    options: DocumentScreenshotOptions,
    afterFirstTile: suspend () -> Unit
): ScreenshotResult {
    prepareDocumentCapture()
    val webView = captureWebView
    val nativePerCss = nativePixelsPerCss()
    val dimensions = JSONObject(evaluateForDocument(DOCUMENT_SIZE) ?: throw KmpwrightException("Could not measure the page"))
    val visibleWidthCss = webView.width / nativePerCss
    val visibleHeightCss = webView.height / nativePerCss
    val documentWidthCss = maxOf(dimensions.getDouble("width"), visibleWidthCss)
    val documentHeightCss = maxOf(dimensions.getDouble("height"), visibleHeightCss)
    val widthCss = if (options.viewportWidthOnly && options.clip == null) visibleWidthCss else documentWidthCss
    val plan = planDocumentCapture(options, widthCss, documentHeightCss)
    val bounds = documentNativeBounds(plan.rect, documentWidthCss, documentHeightCss, webView.width, webView.height, nativePerCss)
    val originX = bounds.originX
    val originY = bounds.originY
    val nativeWidth = bounds.width
    val nativeHeight = bounds.height
    val lastLeft = bounds.lastLeft
    val lastTop = bounds.lastTop
    val originalScroll = readScrollPosition()
    val vertical = webView.isVerticalScrollBarEnabled
    val horizontal = webView.isHorizontalScrollBarEnabled
    val output = allocateDocumentBitmap(plan.width, plan.height)

    try {
        webView.isVerticalScrollBarEnabled = false
        webView.isHorizontalScrollBarEnabled = false
        val canvas = Canvas(output)
        val viewportWidth = webView.width
        val viewportHeight = webView.height
        var y = 0
        while (y < nativeHeight) {
            var x = 0
            var rowHeight = minOf(viewportHeight, nativeHeight - y)
            while (x < nativeWidth) {
                currentCoroutineContext().ensureActive()
                val viewportX = minOf(originX + x, lastLeft)
                val viewportY = minOf(originY + y, lastTop)
                scrollForCapture(viewportX, viewportY)
                awaitDocumentRendered()
                check(webView.width == viewportWidth && webView.height == viewportHeight) { LAYOUT_CHANGED }
                // WebView's renderer can clamp the final tile a pixel earlier than DOM geometry predicts.
                webView.scrollTo(viewportX, viewportY)
                val actualX = webView.scrollX
                val actualY = webView.scrollY
                check(abs(actualX - viewportX) <= 1 && abs(actualY - viewportY) <= 1) { LAYOUT_CHANGED }
                val columns = checkNotNull(documentTileSlice(originX + x, actualX, viewportWidth, nativeWidth - x)) { LAYOUT_CHANGED }
                val rows = checkNotNull(documentTileSlice(originY + y, actualY, viewportHeight, nativeHeight - y)) { LAYOUT_CHANGED }
                if (x == 0) rowHeight = rows.count
                check(rows.count == rowHeight) { LAYOUT_CHANGED }
                val source = Rect(columns.source.first, rows.source.first, columns.source.last + 1, rows.source.last + 1)
                val destination = Rect(
                    (x.toDouble() * plan.width / nativeWidth).roundToInt(),
                    (y.toDouble() * plan.height / nativeHeight).roundToInt(),
                    ((x + columns.count).toDouble() * plan.width / nativeWidth).roundToInt(),
                    ((y + rowHeight).toDouble() * plan.height / nativeHeight).roundToInt()
                )
                if (!destination.isEmpty) {
                    val tile = captureDocumentTile()
                    try {
                        canvas.drawBitmap(tile, source, destination, null)
                    } finally {
                        tile.recycle()
                    }
                }
                x += columns.count
            }
            if (y == 0 && rowHeight < nativeHeight) afterFirstTile()
            y += rowHeight
        }
        return ScreenshotResult(encodeDocumentBitmap(output, options), plan.width, plan.height, truncated = plan.truncated)
    } finally {
        try {
            output.recycle()
            webView.isVerticalScrollBarEnabled = vertical
            webView.isHorizontalScrollBarEnabled = horizontal
        } finally {
            restoreScreenshotState({ Log.w("Kmpwright", "Could not restore screenshot scroll position", it) }) {
                restoreScrollPosition(originalScroll)
            }
        }
    }
}

private const val LAYOUT_CHANGED = "The page changed size while taking its screenshot"
private const val DOCUMENT_SIZE = """
    (function() {
        var root = document.documentElement, body = document.body;
        return JSON.stringify({
            width: Math.max(root.scrollWidth, body ? body.scrollWidth : 0),
            height: Math.max(root.scrollHeight, body ? body.scrollHeight : 0)
        });
    })()
"""

internal data class DocumentNativeBounds(
    val originX: Int,
    val originY: Int,
    val width: Int,
    val height: Int,
    val lastLeft: Int,
    val lastTop: Int
)

/** Clamp fractional CSS clip rounding to the native document extent so high-DPI clips cannot overrun the final tile. */
internal fun documentNativeBounds(
    rect: Rectangle,
    documentWidthCss: Double,
    documentHeightCss: Double,
    viewportWidth: Int,
    viewportHeight: Int,
    nativePerCss: Double
): DocumentNativeBounds {
    val documentNativeWidth = (documentWidthCss * nativePerCss).roundToInt().coerceAtLeast(viewportWidth)
    val documentNativeHeight = (documentHeightCss * nativePerCss).roundToInt().coerceAtLeast(viewportHeight)
    val originX = (rect.x * nativePerCss).roundToInt().coerceIn(0, (documentNativeWidth - 1).coerceAtLeast(0))
    val originY = (rect.y * nativePerCss).roundToInt().coerceIn(0, (documentNativeHeight - 1).coerceAtLeast(0))
    val width = (rect.width * nativePerCss).roundToInt().coerceAtMost(documentNativeWidth - originX)
    val height = (rect.height * nativePerCss).roundToInt().coerceAtMost(documentNativeHeight - originY)
    require(width > 0 && height > 0) { "The selected part is too small to capture" }
    return DocumentNativeBounds(
        originX = originX,
        originY = originY,
        width = width,
        height = height,
        lastLeft = (documentNativeWidth - viewportWidth).coerceAtLeast(0),
        lastTop = (documentNativeHeight - viewportHeight).coerceAtLeast(0)
    )
}

internal data class DocumentTileSlice(val source: IntRange, val count: Int)

/** Chromium may round the final scroll extent down by one native pixel. Extend that last pixel. */
internal fun documentTileSlice(position: Int, viewportStart: Int, viewportSize: Int, remaining: Int): DocumentTileSlice? {
    val offset = position - viewportStart
    if (offset < 0 || offset > viewportSize || (offset == viewportSize && remaining != 1)) return null
    if (offset == viewportSize) return DocumentTileSlice(viewportSize - 1 until viewportSize, 1)
    val count = minOf(viewportSize - offset, remaining)
    return DocumentTileSlice(offset until (offset + count), count)
}
