package com.bernaferrari.kmpwright.core

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import android.view.PixelCopy
import android.webkit.WebView
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToInt

class AndroidScreenshotController(
    private val webView: WebView,
    private val config: KmpwrightConfig = KmpwrightConfig()
) : ScreenshotController {
    private val maxBitmapBytes = 128L * 1024L * 1024L

    override suspend fun captureViewport(scale: Double): ScreenshotResult = withContext(Dispatchers.Main) {
        ensureLaidOut()
        val viewportScale = scale.coerceIn(0.1, 5.0)
        val scaledWidth = (webView.width * viewportScale).toInt().coerceAtLeast(1)
        val scaledHeight = (webView.height * viewportScale).toInt().coerceAtLeast(1)
        validateBitmapSize(scaledWidth, scaledHeight, "viewport screenshot")

        val bitmap = captureVisibleViewportBitmap(viewportScale, "viewport screenshot")
        val outputStream = java.io.ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
        bitmap.recycle()

        ScreenshotResult(
            bytes = outputStream.toByteArray(),
            width = scaledWidth,
            height = scaledHeight
        )
    }

    override suspend fun captureFullPage(scale: Double): ScreenshotResult = withContext(Dispatchers.Main) {
        ensureLaidOut()
        val pageDims = queryPageDimensions()
        val pageScale = scale.coerceIn(0.1, 5.0)

        if (pageDims.contentHeight <= 0 || pageDims.contentWidth <= 0) {
            return@withContext captureViewport(scale)
        }

        val scaledWidth = (pageDims.contentWidth * pageScale).toInt().coerceAtLeast(1)
        val scaledHeight = (pageDims.contentHeight * pageScale).toInt().coerceAtLeast(1)
        validateBitmapSize(scaledWidth, scaledHeight, "full-page screenshot")

        val originalScroll = readScrollPosition()
        val originalVerticalScrollbars = webView.isVerticalScrollBarEnabled
        val originalHorizontalScrollbars = webView.isHorizontalScrollBarEnabled

        try {
            webView.isVerticalScrollBarEnabled = false
            webView.isHorizontalScrollBarEnabled = false

            val stitched = createBitmap(scaledWidth, scaledHeight, "stitched full-page screenshot")
            val stitchedCanvas = Canvas(stitched)
            var documentY = 0

            while (documentY < pageDims.contentHeight) {
                var documentX = 0
                var rowCaptureHeight = 0

                while (documentX < pageDims.contentWidth) {
                    // WebView.scrollY can report an offset beyond the renderer’s
                    // clamped viewport. Clamp before computing the final tile crop.
                    scrollToAndWait(
                        documentX.coerceAtMost((pageDims.contentWidth - pageDims.viewportWidth).coerceAtLeast(0)),
                        documentY.coerceAtMost((pageDims.contentHeight - pageDims.viewportHeight).coerceAtLeast(0))
                    )

                    val actualX = webView.scrollX.coerceAtLeast(0)
                    val actualY = webView.scrollY.coerceAtLeast(0)
                    if (documentX > actualX + pageDims.viewportWidth - 1 ||
                        documentY > actualY + pageDims.viewportHeight - 1) {
                        break
                    }
                    val sourceX = (documentX - actualX).coerceIn(0, pageDims.viewportWidth - 1)
                    val sourceY = (documentY - actualY).coerceIn(0, pageDims.viewportHeight - 1)
                    val captureWidth = min(pageDims.viewportWidth - sourceX, pageDims.contentWidth - documentX)
                    val captureHeight = min(pageDims.viewportHeight - sourceY, pageDims.contentHeight - documentY)

                    if (captureWidth <= 0 || captureHeight <= 0) break

                    val viewportBitmap = captureVisibleViewportBitmap(pageScale, "full-page screenshot tile")
                    try {
                        val srcRect = Rect(
                            (sourceX * pageScale).roundToInt(),
                            (sourceY * pageScale).roundToInt(),
                            ((sourceX + captureWidth) * pageScale).roundToInt().coerceAtMost(viewportBitmap.width),
                            ((sourceY + captureHeight) * pageScale).roundToInt().coerceAtMost(viewportBitmap.height)
                        )
                        val dstRect = Rect(
                            (documentX * pageScale).roundToInt(),
                            (documentY * pageScale).roundToInt(),
                            ((documentX + captureWidth) * pageScale).roundToInt().coerceAtMost(stitched.width),
                            ((documentY + captureHeight) * pageScale).roundToInt().coerceAtMost(stitched.height)
                        )
                        if (!srcRect.isEmpty && !dstRect.isEmpty) {
                            stitchedCanvas.drawBitmap(viewportBitmap, srcRect, dstRect, null)
                        }
                    } finally {
                        viewportBitmap.recycle()
                    }

                    rowCaptureHeight = rowCaptureHeight.coerceAtLeast(captureHeight)
                    documentX += captureWidth
                }

                if (rowCaptureHeight <= 0) break
                documentY += rowCaptureHeight
            }

            val outputStream = java.io.ByteArrayOutputStream()
            stitched.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
            stitched.recycle()

            ScreenshotResult(
                bytes = outputStream.toByteArray(),
                width = scaledWidth,
                height = scaledHeight
            )
        } finally {
            webView.isVerticalScrollBarEnabled = originalVerticalScrollbars
            webView.isHorizontalScrollBarEnabled = originalHorizontalScrollbars
            withContext(NonCancellable) { restoreScrollPosition(originalScroll) }
        }
    }

    override suspend fun captureClip(
        x: Double,
        y: Double,
        width: Double,
        height: Double,
        scale: Double
    ): ScreenshotResult = withContext(Dispatchers.Main) {
        ensureLaidOut()
        val clipScale = scale.coerceIn(0.1, 5.0)
        val clipX = (x * clipScale).toInt().coerceAtLeast(0)
        val clipY = (y * clipScale).toInt().coerceAtLeast(0)
        val clipWidth = (width * clipScale).toInt().coerceAtLeast(1)
        val clipHeight = (height * clipScale).toInt().coerceAtLeast(1)

        val viewportWidth = (webView.width * clipScale).toInt().coerceAtLeast(1)
        val viewportHeight = (webView.height * clipScale).toInt().coerceAtLeast(1)
        validateBitmapSize(viewportWidth, viewportHeight, "clip source screenshot")
        validateBitmapSize(clipWidth, clipHeight, "clip screenshot")

        val fullBitmap = captureVisibleViewportBitmap(clipScale, "clip source screenshot")
        val right = (clipX + clipWidth).coerceAtMost(fullBitmap.width)
        val bottom = (clipY + clipHeight).coerceAtMost(fullBitmap.height)
        if (clipX >= right || clipY >= bottom) {
            fullBitmap.recycle()
            return@withContext ScreenshotResult(ByteArray(0), 0, 0)
        }

        val cropped = Bitmap.createBitmap(
            fullBitmap,
            clipX,
            clipY,
            right - clipX,
            bottom - clipY
        )
        fullBitmap.recycle()

        val outputStream = java.io.ByteArrayOutputStream()
        cropped.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
        val croppedWidth = cropped.width
        val croppedHeight = cropped.height
        cropped.recycle()

        ScreenshotResult(
            bytes = outputStream.toByteArray(),
            width = croppedWidth,
            height = croppedHeight
        )
    }

    override fun getPageDimensions(): PageDimensions {
        @Suppress("DEPRECATION")
        val scale = webView.scale.takeIf { it > 0f } ?: 1f
        val contentHeightPx = (webView.contentHeight * scale).toInt().takeIf { it > 0 } ?: webView.height
        val contentWidthPx = webView.width.takeIf { it > 0 } ?: (webView.measuredWidth)

        return PageDimensions(
            contentWidth = contentWidthPx,
            contentHeight = contentHeightPx,
            viewportWidth = webView.width,
            viewportHeight = webView.height
        )
    }

    private fun createBitmap(width: Int, height: Int, label: String): Bitmap {
        validateBitmapSize(width, height, label)
        return try {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        } catch (e: OutOfMemoryError) {
            throw KmpwrightException("Android $label is too large to allocate (${width}x$height).")
        } catch (e: IllegalArgumentException) {
            throw KmpwrightException("Android $label has invalid dimensions (${width}x$height): ${e.message}")
        }
    }

    private fun validateBitmapSize(width: Int, height: Int, label: String) {
        val bytes = width.toLong() * height.toLong() * 4L
        if (bytes > maxBitmapBytes) {
            throw KmpwrightException(
                "Android $label is too large (${width}x$height, ${bytes / (1024L * 1024L)} MiB). " +
                    "Use a smaller scale or a clipped screenshot."
            )
        }
    }

    internal suspend fun measurePageDimensions(): PageDimensions {
        ensureLaidOut()
        val dimensions = queryPageDimensions()
        if (webView.isAttachedToWindow) withTimeoutOrNull(500) { awaitVisualState() }
        awaitFrame()
        return dimensions
    }

    internal suspend fun scrollForCapture(x: Int, y: Int) = scrollToAndWait(x, y)

    internal suspend fun readScrollPosition(): Pair<Double, Double> {
        val raw = evaluateJavaScript("JSON.stringify({x: window.scrollX, y: window.scrollY})")
            ?: throw KmpwrightException("Unable to read scroll position before capture")
        val position = JSONObject(raw)
        return position.getDouble("x") to position.getDouble("y")
    }

    internal suspend fun restoreScrollPosition(position: Pair<Double, Double>) {
        scrollCssAndWait(position.first, position.second)
    }

    private suspend fun queryPageDimensions(): PageDimensions {
        val fallback = getPageDimensions()
        val raw = evaluateJavaScript(DIMENSIONS_SCRIPT) ?: return fallback
        return try {
            val parsed = JSONObject(raw)
            val scrollWidth = parsed.optDouble("scrollWidth", fallback.contentWidth.toDouble())
            val scrollHeight = parsed.optDouble("scrollHeight", fallback.contentHeight.toDouble())
            val innerWidth = parsed.optDouble("innerWidth", webView.width.toDouble())
            @Suppress("DEPRECATION")
            val scale = webView.scale.takeIf { it > 0f }?.toDouble() ?: 1.0
            val widthScale = if (innerWidth > 0.0 && webView.width > 0) {
                webView.width.toDouble() / innerWidth
            } else {
                scale
            }
            val heightScale = if (scrollHeight > 0.0 && webView.contentHeight > 0) {
                (webView.contentHeight * scale) / scrollHeight
            } else {
                scale
            }
            PageDimensions(
                contentWidth = ceil(scrollWidth * widthScale).toInt().coerceAtLeast(fallback.contentWidth),
                contentHeight = ceil(scrollHeight * heightScale).toInt().coerceAtLeast(fallback.contentHeight),
                viewportWidth = fallback.viewportWidth,
                viewportHeight = fallback.viewportHeight
            )
        } catch (_: Exception) {
            fallback
        }
    }

    private suspend fun evaluateJavaScript(script: String): String? =
        suspendCancellableCoroutine { cont ->
            webView.evaluateJavascript(script) { result ->
                if (cont.isActive) cont.resume(normalizeJsResult(result))
            }
        }

    private fun ensureLaidOut() {
        if (webView.width > 0 && webView.height > 0) return
        val viewport = config.viewport ?: Viewport.DEFAULT
        val width = webView.layoutParams?.width?.takeIf { it > 0 }
            ?: webView.measuredWidth.takeIf { it > 0 }
            ?: viewport.width
        val height = webView.layoutParams?.height?.takeIf { it > 0 }
            ?: webView.measuredHeight.takeIf { it > 0 }
            ?: viewport.height
        val widthSpec = android.view.View.MeasureSpec.makeMeasureSpec(width, android.view.View.MeasureSpec.EXACTLY)
        val heightSpec = android.view.View.MeasureSpec.makeMeasureSpec(height, android.view.View.MeasureSpec.EXACTLY)
        webView.measure(widthSpec, heightSpec)
        webView.layout(0, 0, width, height)
    }

    private suspend fun scrollToAndWait(x: Int, y: Int) {
        @Suppress("DEPRECATION")
        val pixelScale = webView.scale.takeIf { it > 0 } ?: 1f
        scrollCssAndWait(x / pixelScale.toDouble(), y / pixelScale.toDouble())
    }

    private suspend fun scrollCssAndWait(x: Double, y: Double) {
        evaluateJavaScript("""
            (function() {
                window.scrollTo({left: $x,
                    top: $y, behavior: 'instant'});
                return true;
            })()
        """.trimIndent())
        webView.invalidate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && webView.isAttachedToWindow) {
            withTimeoutOrNull(500) {
                awaitVisualState()
            }
        }
        awaitFrame()
    }

    private suspend fun awaitVisualState() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        suspendCancellableCoroutine { cont ->
            webView.postVisualStateCallback(
                SystemClock.uptimeMillis(),
                object : WebView.VisualStateCallback() {
                    override fun onComplete(requestId: Long) {
                        if (cont.isActive) cont.resume(Unit)
                    }
                }
            )
        }
    }

    private suspend fun awaitFrame() {
        suspendCancellableCoroutine { cont ->
            Choreographer.getInstance().postFrameCallback {
                if (cont.isActive) cont.resume(Unit)
            }
        }
    }

    private suspend fun captureVisibleViewportBitmap(scale: Double, label: String): Bitmap {
        val scaledWidth = (webView.width * scale).roundToInt().coerceAtLeast(1)
        val scaledHeight = (webView.height * scale).roundToInt().coerceAtLeast(1)
        val bitmap = createBitmap(scaledWidth, scaledHeight, label)
        if (tryPixelCopy(bitmap)) return bitmap

        val canvas = Canvas(bitmap)
        canvas.scale(scale.toFloat(), scale.toFloat())
        webView.draw(canvas)
        return bitmap
    }

    private suspend fun tryPixelCopy(destination: Bitmap): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        if (!webView.isAttachedToWindow || webView.width <= 0 || webView.height <= 0) return false
        val activity = webView.context.findActivity() ?: return false
        val location = IntArray(2)
        webView.getLocationInWindow(location)
        val srcRect = Rect(
            location[0],
            location[1],
            location[0] + webView.width,
            location[1] + webView.height
        )
        if (srcRect.isEmpty) return false

        return withTimeoutOrNull(500) {
            suspendCancellableCoroutine { cont ->
                try {
                    PixelCopy.request(
                        activity.window,
                        srcRect,
                        destination,
                        { result ->
                            if (cont.isActive) cont.resume(result == PixelCopy.SUCCESS)
                        },
                        Handler(Looper.getMainLooper())
                    )
                } catch (_: Exception) {
                    if (cont.isActive) cont.resume(false)
                }
            }
        } ?: false
    }

    private tailrec fun Context.findActivity(): Activity? =
        when (this) {
            is Activity -> this
            is ContextWrapper -> baseContext.findActivity()
            else -> null
        }

    private companion object {
        private const val DIMENSIONS_SCRIPT = """
            (function() {
                var doc = document.documentElement || {};
                var body = document.body || {};
                return JSON.stringify({
                    scrollWidth: Math.max(
                        body.scrollWidth || 0,
                        doc.scrollWidth || 0,
                        body.offsetWidth || 0,
                        doc.offsetWidth || 0,
                        doc.clientWidth || 0
                    ),
                    scrollHeight: Math.max(
                        body.scrollHeight || 0,
                        doc.scrollHeight || 0,
                        body.offsetHeight || 0,
                        doc.offsetHeight || 0,
                        doc.clientHeight || 0
                    ),
                    innerWidth: window.innerWidth || doc.clientWidth || 0,
                    innerHeight: window.innerHeight || doc.clientHeight || 0
                });
            })()
        """
    }
}
