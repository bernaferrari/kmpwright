package com.bernaferrari.kmpwright.core

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

/**
 * Bounded document capture for embedded WebViews. [clip] is in document CSS pixels, not viewport
 * coordinates. Selected regions are always complete or fail; only an explicitly bounded full-page
 * capture may be truncated. [pixelsPerCssPixel] makes output independent of device density.
 */
internal data class DocumentScreenshotOptions(
    val clip: Rectangle? = null,
    val pixelsPerCssPixel: Double = 1.0,
    val maxPixels: Long = 32_000_000,
    val maxHeight: Int = Int.MAX_VALUE,
    val truncateFullPage: Boolean = false,
    val format: ScreenshotFormat = ScreenshotFormat.Png,
    val quality: Int = 90,
    /** Restrict an unselected document to its visible CSS width; never crop a selected region. */
    val viewportWidthOnly: Boolean = false
) {
    init {
        require(pixelsPerCssPixel.isFinite() && pixelsPerCssPixel > 0) { "Screenshot scale must be positive" }
        require(maxPixels > 0 && maxHeight > 0) { "Screenshot limits must be positive" }
        require(quality in 0..100) { "Screenshot quality must be between 0 and 100" }
    }
}

internal data class DocumentCapturePlan(val rect: Rectangle, val width: Int, val height: Int, val truncated: Boolean)

internal fun planDocumentCapture(options: DocumentScreenshotOptions, documentWidth: Double, documentHeight: Double): DocumentCapturePlan {
    require(documentWidth.isFinite() && documentHeight.isFinite() && documentWidth > 0 && documentHeight > 0) { "Invalid document dimensions" }
    val rect = options.clip ?: Rectangle(0.0, 0.0, documentWidth, documentHeight)
    require(listOf(rect.x, rect.y, rect.width, rect.height).all { it.isFinite() } && rect.x >= 0 && rect.y >= 0 && rect.width > 0 && rect.height > 0) { "The selected part is outside the page" }
    // DOM scroll sizes are integer CSS pixels, while bounding boxes and scroll offsets can be fractional.
    require(rect.x + rect.width <= documentWidth + 1.0 && rect.y + rect.height <= documentHeight + 1.0) { "The selected part is outside the captured page" }
    val scaledWidth = rect.width * options.pixelsPerCssPixel
    val scaledHeight = rect.height * options.pixelsPerCssPixel
    require(scaledWidth >= 1 && scaledHeight >= 1 && scaledWidth < Int.MAX_VALUE) { "The selected part is too small or too wide to capture" }
    val width = scaledWidth.roundToInt()
    val rows = minOf(options.maxHeight.toLong(), options.maxPixels / width).toInt()
    require(rows > 0) { "The selected area is too wide for a screenshot" }
    val truncated = scaledHeight >= rows.toDouble() + 0.5
    if (truncated && (options.clip != null || !options.truncateFullPage)) {
        throw KmpwrightException("The selected area is too large for a screenshot. Choose a smaller part.")
    }
    val height = if (truncated) rows else scaledHeight.roundToInt()
    val captured = if (truncated) rect.copy(height = height / options.pixelsPerCssPixel) else rect
    return DocumentCapturePlan(captured, width, height, truncated)
}

/** WebKit rectangles are native points; DOM bounding boxes are CSS pixels. */
internal fun cssRectangleToNative(rect: Rectangle, cssViewportWidth: Double, nativeViewportWidth: Double): Rectangle {
    require(cssViewportWidth.isFinite() && nativeViewportWidth.isFinite() && cssViewportWidth > 0 && nativeViewportWidth > 0)
    val scale = nativeViewportWidth / cssViewportWidth
    return Rectangle(rect.x * scale, rect.y * scale, rect.width * scale, rect.height * scale)
}

/** Restoring a dead renderer must neither delay cancellation indefinitely nor mask a capture failure. */
internal suspend fun restoreScreenshotState(logFailure: (Exception?) -> Unit = {}, restore: suspend () -> Unit) {
    withContext(NonCancellable) {
        try {
            if (withTimeoutOrNull(2_000) { restore(); true } == null) logFailure(null)
        } catch (error: Exception) {
            logFailure(error)
        }
    }
}

/**
 * Captures a full page in an Android or iOS WebView using a caller-supplied [maxPixels] budget.
 * Output uses the native page scale multiplied by [scale]. Set [truncateFullPage] to permit an
 * oversized page to be shortened and inspect [ScreenshotResult.truncated]. [viewportWidthOnly]
 * captures the visible viewport width of the document. Use [Page.screenshot] on other backends.
 */
@Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, kotlin.coroutines.cancellation.CancellationException::class)
public suspend fun Page.screenshotDocument(
    maxPixels: Long,
    truncateFullPage: Boolean = false,
    viewportWidthOnly: Boolean = false,
    format: ScreenshotFormat = ScreenshotFormat.Png,
    quality: Int = 90,
    scale: Double = 1.0,
    timeoutMs: Long? = null,
): ScreenshotResult {
    require(maxPixels > 0) { "Screenshot pixel budget must be positive" }
    val page = this as? BasePage ?: throw KmpwrightUnsupportedException("Bounded document screenshots require an Android or iOS WebView")
    return page.screenshotWithOptions(
        ScreenshotOptions(
            fullPage = true,
            maxPixels = maxPixels,
            truncateFullPage = truncateFullPage,
            viewportWidthOnly = viewportWidthOnly,
            format = format,
            quality = quality,
            scale = scale,
            timeoutMs = timeoutMs
        ),
        boundedDocument = true
    )
}
