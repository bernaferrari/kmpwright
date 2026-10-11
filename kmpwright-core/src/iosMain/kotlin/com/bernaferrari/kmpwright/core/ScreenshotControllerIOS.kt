package com.bernaferrari.kmpwright.core

import com.bernaferrari.kmpwright.core.ios.toByteArrayPinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSNumber
import platform.Foundation.NSError
import platform.UIKit.UIImage
import platform.UIKit.UIImagePNGRepresentation
import platform.WebKit.WKSnapshotConfiguration
import platform.WebKit.WKWebView

@OptIn(ExperimentalForeignApi::class)
class IOSScreenshotController(
    private val webView: WKWebView
) : ScreenshotController {
    private val maxSnapshotPixels = 32_000_000.0

    override suspend fun captureViewport(scale: Double): ScreenshotResult = withContext(Dispatchers.Main) {
        val bounds = webView.bounds.useContents { this }
        val rect = CGRectMake(0.0, 0.0, bounds.size.width, bounds.size.height)
        takeSnapshot(rect, scale)
    }

    override suspend fun captureFullPage(scale: Double): ScreenshotResult = withContext(Dispatchers.Main) {
        val contentSize = webView.scrollView.contentSize
        val width = contentSize.useContents { width }
        val height = contentSize.useContents { height }
        if (width <= 0.0 || height <= 0.0) {
            return@withContext captureViewport(scale)
        }
        val rect = CGRectMake(0.0, 0.0, width, height)
        takeSnapshot(rect, scale)
    }

    override suspend fun captureClip(
        x: Double,
        y: Double,
        width: Double,
        height: Double,
        scale: Double
    ): ScreenshotResult = withContext(Dispatchers.Main) {
        val rect = CGRectMake(x, y, width, height)
        takeSnapshot(rect, scale)
    }

    override fun getPageDimensions(): PageDimensions {
        val contentSize = webView.scrollView.contentSize
        val contentWidth = contentSize.useContents { width }.toInt().coerceAtLeast(1)
        val contentHeight = contentSize.useContents { height }.toInt().coerceAtLeast(1)
        val bounds = webView.bounds
        val viewportWidth = bounds.useContents { size.width }.toInt().coerceAtLeast(1)
        val viewportHeight = bounds.useContents { size.height }.toInt().coerceAtLeast(1)
        return PageDimensions(contentWidth, contentHeight, viewportWidth, viewportHeight)
    }

    private suspend fun takeSnapshot(rect: kotlinx.cinterop.CValue<platform.CoreGraphics.CGRect>, scale: Double): ScreenshotResult =
        withContext(Dispatchers.Main) {
            val safeScale = scale.takeIf { it > 0.0 } ?: 1.0
            val logicalWidth = rect.useContents { size.width }.coerceAtLeast(1.0)
            val logicalHeight = rect.useContents { size.height }.coerceAtLeast(1.0)
            val outputWidth = logicalWidth * safeScale
            val outputHeight = logicalHeight * safeScale
            validateSnapshotSize(outputWidth, outputHeight)

            val config = WKSnapshotConfiguration().apply {
                this.rect = rect
                if (safeScale != 1.0) {
                    snapshotWidth = NSNumber(outputWidth)
                }
            }

            suspendCancellableCoroutine { cont ->
                webView.takeSnapshotWithConfiguration(config) { image: UIImage?, error: NSError? ->
                    if (error != null || image == null) {
                        cont.resumeWith(Result.failure(KmpwrightException("iOS snapshot failed: ${error?.localizedDescription ?: "no image returned"}")))
                        return@takeSnapshotWithConfiguration
                    }
                    val pngData = UIImagePNGRepresentation(image)
                    if (pngData == null) {
                        cont.resumeWith(Result.failure(KmpwrightException("iOS snapshot failed: PNG encoding returned no data.")))
                        return@takeSnapshotWithConfiguration
                    }
                    val bytes = pngData.toByteArrayPinned()
                    cont.resumeWith(Result.success(ScreenshotResult(bytes, outputWidth.toInt(), outputHeight.toInt())))
                }
            }
        }

    private fun validateSnapshotSize(width: Double, height: Double) {
        val pixels = width * height
        if (pixels > maxSnapshotPixels) {
            throw KmpwrightException(
                "iOS snapshot is too large (${width.toInt()}x${height.toInt()}, ${(pixels / 1_000_000.0).toInt()} MP). " +
                    "Use a smaller scale or a clipped screenshot."
            )
        }
    }
}
