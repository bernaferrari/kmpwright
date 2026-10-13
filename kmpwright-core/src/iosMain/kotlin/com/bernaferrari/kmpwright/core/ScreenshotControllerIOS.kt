package com.bernaferrari.kmpwright.core

import com.bernaferrari.kmpwright.core.ios.toByteArrayPinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.CoreGraphics.CGImageGetHeight
import platform.CoreGraphics.CGImageGetWidth
import platform.CoreGraphics.CGRect
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSNumber
import platform.Foundation.NSError
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import platform.UIKit.UIImagePNGRepresentation
import platform.UIKit.UIScreen
import platform.WebKit.WKSnapshotConfiguration
import platform.WebKit.WKWebView

private const val MAX_SNAPSHOT_PIXELS = 32_000_000.0

@OptIn(ExperimentalForeignApi::class)
internal class IOSScreenshotController(
    private val webView: WKWebView
) : ScreenshotController {

    /**
     * Honors [ScreenshotOptions.format] PNG/JPEG (with [ScreenshotOptions.quality]). ImageIO on iOS
     * cannot encode WebP, and WKWebView always paints the page's base background, so WEBP and
     * [ScreenshotOptions.omitBackground] throw [KmpwrightUnsupportedException].
     */
    override suspend fun capture(options: ScreenshotOptions): ScreenshotResult {
        require(options.scale.isFinite() && options.scale > 0) { "Screenshot scale must be positive" }
        require(options.quality in 0..100) { "Screenshot quality must be between 0 and 100" }
        if (options.omitBackground) {
            throw KmpwrightUnsupportedException(
                "ScreenshotOptions.omitBackground is not supported on iOS WKWebView: it always paints an opaque page background"
            )
        }
        if (options.format == ScreenshotFormat.WEBP) {
            throw KmpwrightUnsupportedException("WEBP screenshots are not supported on iOS (ImageIO cannot encode WebP); use PNG or JPEG")
        }
        return withContext(Dispatchers.Main) {
            val clip = options.clip
            val rect = when {
                options.fullPage -> webView.fullPageSnapshotRect()
                clip != null -> {
                    val (width, height) = webView.bounds.useContents { size.width to size.height }
                    val trimmed = trimScreenshotClip(clip, width, height)
                    CGRectMake(trimmed.x, trimmed.y, trimmed.width, trimmed.height)
                }
                else -> webView.viewportSnapshotRect()
            }
            webView.captureSnapshot(rect, options.scale, options.format, options.quality)
        }
    }

    override suspend fun captureViewport(scale: Double): ScreenshotResult = withContext(Dispatchers.Main) {
        webView.capturePngSnapshot(webView.viewportSnapshotRect(), scale)
    }

    override suspend fun captureFullPage(scale: Double): ScreenshotResult = withContext(Dispatchers.Main) {
        webView.capturePngSnapshot(webView.fullPageSnapshotRect(), scale)
    }

    override suspend fun captureClip(
        x: Double,
        y: Double,
        width: Double,
        height: Double,
        scale: Double
    ): ScreenshotResult = withContext(Dispatchers.Main) {
        webView.capturePngSnapshot(CGRectMake(x, y, width, height), scale)
    }

    override fun getPageDimensions(): PageDimensions = onMainThread {
        val contentSize = webView.scrollView.contentSize
        val contentWidth = contentSize.useContents { width }.toInt().coerceAtLeast(1)
        val contentHeight = contentSize.useContents { height }.toInt().coerceAtLeast(1)
        val bounds = webView.bounds
        val viewportWidth = bounds.useContents { size.width }.toInt().coerceAtLeast(1)
        val viewportHeight = bounds.useContents { size.height }.toInt().coerceAtLeast(1)
        PageDimensions(contentWidth, contentHeight, viewportWidth, viewportHeight)
    }
}

/** The visible viewport in the web view's coordinate space. Main thread only. */
@OptIn(ExperimentalForeignApi::class)
internal fun WKWebView.viewportSnapshotRect(): CValue<CGRect> {
    val (width, height) = bounds.useContents { size.width to size.height }
    return CGRectMake(0.0, 0.0, width, height)
}

/**
 * The scrollable document. WKWebView renders tiles outside the visible
 * viewport for snapshot requests, so no scroll-and-stitch pass is required.
 * Main thread only.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun WKWebView.fullPageSnapshotRect(): CValue<CGRect> {
    val (width, height) = scrollView.contentSize.useContents { width to height }
    return if (width <= 0.0 || height <= 0.0) viewportSnapshotRect() else CGRectMake(0.0, 0.0, width, height)
}

/** Screen pixels per point used by WebKit when rasterizing snapshots. Main thread only. */
@OptIn(ExperimentalForeignApi::class)
internal fun WKWebView.snapshotScreenScale(): Double =
    (window?.screen?.scale ?: UIScreen.mainScreen.scale).takeIf { it > 0.0 } ?: 1.0

/**
 * Captures [rect] (points) as PNG. `scale` multiplies the logical size; the
 * PNG is rasterized at the screen scale on top of that, and the reported
 * width/height are the actual pixel dimensions of the encoded image.
 * Must be called on the main thread.
 */
@OptIn(ExperimentalForeignApi::class)
internal suspend fun WKWebView.capturePngSnapshot(rect: CValue<CGRect>, scale: Double): ScreenshotResult =
    captureSnapshot(rect, scale, ScreenshotFormat.PNG, 100)

/** Like [capturePngSnapshot], encoding PNG or JPEG ([quality] 0..100 for JPEG). */
@OptIn(ExperimentalForeignApi::class)
internal suspend fun WKWebView.captureSnapshot(rect: CValue<CGRect>, scale: Double, format: ScreenshotFormat, quality: Int): ScreenshotResult {
    val safeScale = scale.takeIf { it > 0.0 && it.isFinite() } ?: 1.0
    val logicalWidth = rect.useContents { size.width }
    val logicalHeight = rect.useContents { size.height }
    if (logicalWidth <= 0.0 || logicalHeight <= 0.0) {
        throw KmpwrightException("iOS snapshot failed: the capture area is empty (${logicalWidth}x$logicalHeight).")
    }
    val screenScale = snapshotScreenScale()
    validateSnapshotSize(logicalWidth * safeScale * screenScale, logicalHeight * safeScale * screenScale)

    val configuration = WKSnapshotConfiguration().apply {
        this.rect = rect
        if (safeScale != 1.0) snapshotWidth = NSNumber(logicalWidth * safeScale)
    }
    return suspendCancellableCoroutine { continuation ->
        takeSnapshotWithConfiguration(configuration) { image: UIImage?, error: NSError? ->
            if (!continuation.isActive) return@takeSnapshotWithConfiguration
            if (error != null || image == null) {
                continuation.resumeWith(Result.failure(KmpwrightException(
                    "iOS snapshot failed: ${error?.localizedDescription ?: "no image returned"}"
                )))
                return@takeSnapshotWithConfiguration
            }
            val pngData = when (format) {
                ScreenshotFormat.JPEG -> UIImageJPEGRepresentation(image, quality / 100.0)
                else -> UIImagePNGRepresentation(image)
            }
            if (pngData == null) {
                continuation.resumeWith(Result.failure(KmpwrightException("iOS snapshot failed: ${format.name} encoding returned no data.")))
                return@takeSnapshotWithConfiguration
            }
            val (pixelWidth, pixelHeight) = image.pixelSize()
            continuation.resumeWith(Result.success(ScreenshotResult(pngData.toByteArrayPinned(), pixelWidth, pixelHeight)))
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun UIImage.pixelSize(): Pair<Int, Int> {
    val cgImage = CGImage
    if (cgImage != null) return CGImageGetWidth(cgImage).toInt() to CGImageGetHeight(cgImage).toInt()
    val (width, height) = size.useContents { width to height }
    return (width * scale).toInt() to (height * scale).toInt()
}

private fun validateSnapshotSize(pixelWidth: Double, pixelHeight: Double) {
    val pixels = pixelWidth * pixelHeight
    if (pixels > MAX_SNAPSHOT_PIXELS) {
        throw KmpwrightException(
            "iOS snapshot is too large (${pixelWidth.toInt()}x${pixelHeight.toInt()} px, ${(pixels / 1_000_000.0).toInt()} MP). " +
                "Use a smaller scale or a clipped screenshot."
        )
    }
}
