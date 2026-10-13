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
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToInt

internal class AndroidScreenshotController(
    private val webView: WebView,
    private val config: KmpwrightConfig = KmpwrightConfig()
) : ScreenshotController {
    private val maxBitmapBytes = 128L * 1024L * 1024L
    /** Output encoding for this capture; set by [capture] (instances are per capture). */
    private var outputFormat: ScreenshotFormat = ScreenshotFormat.Png
    private var outputQuality: Int = 90

    /**
     * Honors [ScreenshotOptions.format] and [ScreenshotOptions.quality]. Android WebView always
     * paints its own background, so [omitBackground] is rejected.
     */
    override suspend fun capture(options: ScreenshotOptions): ScreenshotResult {
        require(options.scale.isFinite() && options.scale > 0) { "Screenshot scale must be positive" }
        require(options.quality in 0..100) { "Screenshot quality must be between 0 and 100" }
        if (options.omitBackground) {
            throw KmpwrightUnsupportedException(
                "omitBackground is not supported on Android WebView: it always paints an opaque page background"
            )
        }
        outputFormat = options.format
        outputQuality = options.quality
        val clip = options.clip
        return when {
            options.captureBeyondViewport && clip != null -> {
                val position = readScrollPosition()
                @Suppress("DEPRECATION") val nativePerCss = webView.scale.toDouble().takeIf { it.isFinite() && it > 0 } ?: 1.0
                captureDocument(DocumentScreenshotOptions(clip.copy(x = clip.x + position.first, y = clip.y + position.second), nativePerCss * options.scale.coerceIn(0.1, 5.0), format = options.format, quality = options.quality), {})
            }
            options.fullPage -> captureFullPage(options.scale)
            clip != null -> captureClip(clip.x, clip.y, clip.width, clip.height, options.scale)
            else -> captureViewport(options.scale)
        }
    }

    override suspend fun captureViewport(scale: Double): ScreenshotResult = withContext(Dispatchers.Main) {
        ensureLaidOut()
        val viewportScale = scale.coerceIn(0.1, 5.0)
        val scaledWidth = (webView.width * viewportScale).toInt().coerceAtLeast(1)
        val scaledHeight = (webView.height * viewportScale).toInt().coerceAtLeast(1)
        validateBitmapSize(scaledWidth, scaledHeight, "viewport screenshot")

        val bitmap = captureVisibleViewportBitmap(viewportScale, "viewport screenshot")
        try {
            ScreenshotResult(bytes = bitmap.encode(), width = scaledWidth, height = scaledHeight)
        } finally {
            bitmap.recycle()
        }
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
            try {
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

                        val viewportBitmap = captureVisibleViewportBitmap(pageScale, "full-page screenshot tile", actualX to actualY)
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

                ScreenshotResult(bytes = stitched.encode(), width = scaledWidth, height = scaledHeight)
            } finally {
                stitched.recycle()
            }
        } finally {
            webView.isVerticalScrollBarEnabled = originalVerticalScrollbars
            webView.isHorizontalScrollBarEnabled = originalHorizontalScrollbars
            withContext(NonCancellable) { restoreScrollPosition(originalScroll) }
        }
    }

    /** The clip is in CSS pixels (as returned by `Locator.boundingBox`); output is device pixels times [scale]. */
    override suspend fun captureClip(
        x: Double,
        y: Double,
        width: Double,
        height: Double,
        scale: Double
    ): ScreenshotResult = withContext(Dispatchers.Main) {
        ensureLaidOut()
        val clipScale = scale.coerceIn(0.1, 5.0)
        val transform = webView.cssToViewTransform()
        // Playwright semantics: trim to the viewport, and fail when nothing of the clip is visible.
        val trimmed = trimScreenshotClip(
            Rectangle(x, y, width, height),
            webView.width / transform.pixelsPerCssPixel + transform.visualOffsetX,
            webView.height / transform.pixelsPerCssPixel + transform.visualOffsetY
        )
        @Suppress("NAME_SHADOWING") val x = trimmed.x
        @Suppress("NAME_SHADOWING") val y = trimmed.y
        @Suppress("NAME_SHADOWING") val width = trimmed.width
        @Suppress("NAME_SHADOWING") val height = trimmed.height
        val clipX = (transform.x(x) * clipScale).roundToInt().coerceAtLeast(0)
        val clipY = (transform.y(y) * clipScale).roundToInt().coerceAtLeast(0)
        val clipWidth = (transform.length(width) * clipScale).roundToInt().coerceAtLeast(1)
        val clipHeight = (transform.length(height) * clipScale).roundToInt().coerceAtLeast(1)

        val viewportWidth = (webView.width * clipScale).toInt().coerceAtLeast(1)
        val viewportHeight = (webView.height * clipScale).toInt().coerceAtLeast(1)
        validateBitmapSize(viewportWidth, viewportHeight, "clip source screenshot")
        validateBitmapSize(clipWidth, clipHeight, "clip screenshot")

        val fullBitmap = captureVisibleViewportBitmap(clipScale, "clip source screenshot")
        try {
            val right = (clipX + clipWidth).coerceAtMost(fullBitmap.width)
            val bottom = (clipY + clipHeight).coerceAtMost(fullBitmap.height)
            if (clipX >= right || clipY >= bottom) {
                throw KmpwrightException("Clipped area is either empty or outside the viewport")
            }
            val cropped = Bitmap.createBitmap(fullBitmap, clipX, clipY, right - clipX, bottom - clipY)
            try {
                ScreenshotResult(bytes = cropped.encode(), width = cropped.width, height = cropped.height)
            } finally {
                if (cropped !== fullBitmap) cropped.recycle()
            }
        } finally {
            fullBitmap.recycle()
        }
    }

    private fun Bitmap.encode(): ByteArray {
        val outputStream = java.io.ByteArrayOutputStream()
        val (format, quality) = when (outputFormat) {
            ScreenshotFormat.Png -> Bitmap.CompressFormat.PNG to 100
            ScreenshotFormat.Jpeg -> Bitmap.CompressFormat.JPEG to outputQuality
            ScreenshotFormat.Webp -> (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY
                else @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP) to outputQuality
        }
        if (!compress(format, quality, outputStream)) {
            throw KmpwrightException("Android screenshot ${outputFormat.name} encoding failed")
        }
        return outputStream.toByteArray()
    }

    /** Device pixels. Main thread only; [captureFullPage] refines this with renderer measurements. */
    override fun getPageDimensions(): PageDimensions {
        val contentHeightPx = (webView.contentHeight * webView.displayDensity()).toInt().takeIf { it > 0 } ?: webView.height
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

    internal suspend fun scrollToEndForCapture() = evaluateScrollAndWait("""
        (function() {
            var root = document.scrollingElement || document.documentElement;
            window.scrollTo({left: root.scrollWidth, top: root.scrollHeight, behavior: 'instant'});
            return true;
        })()
    """.trimIndent())

    internal suspend fun readScrollPosition(): Pair<Double, Double> {
        val raw = evaluateJavaScript("JSON.stringify({x: window.scrollX, y: window.scrollY})")
            ?: throw KmpwrightException("Unable to read scroll position before capture")
        val position = JSONObject(raw)
        return position.getDouble("x") to position.getDouble("y")
    }

    internal suspend fun restoreScrollPosition(position: Pair<Double, Double>) {
        scrollCssAndWait(position.first, position.second)
    }

    internal suspend fun scrollPixelsForCapture(roundDown: Boolean = false): Pair<Int, Int> {
        val css = readScrollPosition()
        @Suppress("DEPRECATION")
        val scale = webView.scale.toDouble().takeIf { it.isFinite() && it > 0 } ?: webView.displayDensity()
        fun pixel(value: Double): Int = if (roundDown) kotlin.math.floor(value * scale).toInt() else (value * scale).roundToInt()
        return pixel(css.first) to pixel(css.second)
    }

    private suspend fun queryPageDimensions(): PageDimensions {
        val fallback = getPageDimensions()
        val raw = evaluateJavaScript(DIMENSIONS_SCRIPT) ?: return fallback
        return try {
            val parsed = JSONObject(raw)
            val scrollWidth = parsed.optDouble("scrollWidth", fallback.contentWidth.toDouble())
            val scrollHeight = parsed.optDouble("scrollHeight", fallback.contentHeight.toDouble())
            // Same CSS→device mapping as input and clip capture (devicePixelRatio × page zoom).
            val pixelsPerCss = webView.cssToViewTransform().pixelsPerCssPixel
            // Snap sub-pixel rounding to the viewport so it never yields a 1px extra tile.
            fun toDevice(css: Double, viewport: Int): Int {
                val pixels = (css * pixelsPerCss).roundToInt().coerceAtLeast(1)
                return if (kotlin.math.abs(pixels - viewport) <= 1) viewport else pixels
            }
            PageDimensions(
                contentWidth = toDevice(scrollWidth, fallback.viewportWidth),
                contentHeight = toDevice(scrollHeight, fallback.viewportHeight),
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

    /** [x]/[y] are device pixels. */
    private suspend fun scrollToAndWait(x: Int, y: Int) {
        // scrollX/Y and bitmap tiles are native layout pixels. visualViewport.scale
        // can be rounded differently and accumulates several pixels of error on
        // long documents; use WebView's own layout scale for this conversion.
        @Suppress("DEPRECATION")
        val pixelsPerCss = webView.scale.toDouble().takeIf { it.isFinite() && it > 0 } ?: webView.displayDensity()
        scrollCssAndWait(x / pixelsPerCss, y / pixelsPerCss)
    }

    private suspend fun scrollCssAndWait(x: Double, y: Double) {
        evaluateScrollAndWait("""
            (function() {
                window.scrollTo({left: $x,
                    top: $y, behavior: 'instant'});
                return true;
            })()
        """.trimIndent())
    }

    private suspend fun evaluateScrollAndWait(script: String) {
        evaluateJavaScript(script)
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

    private suspend fun captureVisibleViewportBitmap(scale: Double, label: String, expectedScroll: Pair<Int, Int>? = null): Bitmap {
        val scaledWidth = (webView.width * scale).roundToInt().coerceAtLeast(1)
        val scaledHeight = (webView.height * scale).roundToInt().coerceAtLeast(1)
        if (expectedScroll != null) {
            return captureAtScroll(expectedScroll, { webView.scrollX to webView.scrollY }, {
                captureVisibleViewportBitmap(scale, label)
            }, { it.recycle() })
        }
        tryStablePixelCopy(scaledWidth, scaledHeight, label)?.let { return it }

        // Always draw into a fresh bitmap: a timed-out PixelCopy may still
        // write into the bitmap it was given.
        val bitmap = createBitmap(scaledWidth, scaledHeight, label)
        try {
            val canvas = Canvas(bitmap)
            canvas.scale(scale.toFloat(), scale.toFloat())
            // Direct View.draw uses document coordinates; PixelCopy already reads the viewport.
            canvas.translate(-webView.scrollX.toFloat(), -webView.scrollY.toFloat())
            webView.draw(canvas)
            return bitmap
        } catch (error: Throwable) {
            bitmap.recycle()
            throw error
        }
    }

    /**
     * PixelCopy reads the last composited frame. Chromium can composite a
     * scrolled or freshly loaded document before its tiles are rasterized
     * (they show as background-colored checkerboard, even after the visual
     * state callback). Copy until two consecutive frames at an unchanged
     * scroll position are identical, so a capture never contains half-
     * rasterized tiles.
     */
    private suspend fun tryStablePixelCopy(width: Int, height: Int, label: String): Bitmap? {
        var previous = tryPixelCopy(width, height, label) ?: return null
        var previousScroll = webView.scrollX to webView.scrollY
        try {
            repeat(MAX_STABILITY_COPIES) {
                awaitRendered()
                val next = tryPixelCopy(width, height, label) ?: return previous
                val scroll = webView.scrollX to webView.scrollY
                val stable = try {
                    scroll == previousScroll && next.sameAs(previous)
                } catch (error: Throwable) {
                    next.recycle()
                    throw error
                }
                previous.recycle()
                previous = next
                previousScroll = scroll
                if (stable) return next
            }
            android.util.Log.w("Kmpwright", "Android $label did not settle after $MAX_STABILITY_COPIES frames; using the latest frame")
            return previous
        } catch (error: Throwable) {
            // This completed frame belongs to us, unlike a pending PixelCopy destination.
            previous.recycle()
            throw error
        }
    }

    /** A visual-state round trip plus two vsync frames. */
    private suspend fun awaitRendered() {
        if (webView.isAttachedToWindow) withTimeoutOrNull(500) { awaitVisualState() }
        awaitFrame()
        awaitFrame()
    }

    /** Returns the copied bitmap, or null when PixelCopy is unavailable, failed or timed out. */
    private suspend fun tryPixelCopy(width: Int, height: Int, label: String): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        if (!webView.isAttachedToWindow || webView.width <= 0 || webView.height <= 0) return null
        val activity = webView.context.findActivity() ?: return null
        val location = IntArray(2)
        webView.getLocationInWindow(location)
        val srcRect = Rect(
            location[0],
            location[1],
            location[0] + webView.width,
            location[1] + webView.height
        )
        if (srcRect.isEmpty) return null

        val destination = createBitmap(width, height, label)
        val copied = withTimeoutOrNull(500) {
            suspendCancellableCoroutine { cont ->
                try {
                    PixelCopy.request(
                        activity.window,
                        srcRect,
                        destination,
                        { result -> if (cont.isActive) cont.resume(result == PixelCopy.SUCCESS) },
                        Handler(Looper.getMainLooper())
                    )
                } catch (_: Exception) {
                    if (cont.isActive) cont.resume(false)
                }
            }
        }
        return when (copied) {
            true -> destination
            false -> {
                destination.recycle()
                null
            }
            // Timed out: PixelCopy may still own the destination, so it is
            // neither reused nor recycled; the GC reclaims it afterwards.
            null -> null
        }
    }

    internal val captureWebView: WebView get() = webView

    internal fun prepareDocumentCapture(options: DocumentScreenshotOptions) {
        ensureLaidOut()
        outputFormat = options.format
        outputQuality = options.quality
    }
    internal suspend fun evaluateForDocument(script: String): String? = evaluateJavaScript(script)
    internal suspend fun awaitDocumentRendered() {
        // Detached WebViews still need Chromium's DOM/display-list acknowledgement before software draw.
        if (!webView.isAttachedToWindow && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            withTimeout(5_000) { awaitVisualState() }
        }
        awaitRendered()
    }
    internal fun allocateDocumentBitmap(width: Int, height: Int): Bitmap = createBitmap(width, height, "document screenshot")
    internal fun encodeDocumentBitmap(bitmap: Bitmap): ByteArray = bitmap.encode()
    internal suspend fun captureDocumentTile(x: Int, y: Int): Bitmap {
        if (webView.isAttachedToWindow) return captureVisibleViewportBitmap(1.0, "document screenshot tile", x to y)
        val bitmap = createBitmap(webView.width, webView.height, "document screenshot tile")
        try {
            bitmap.eraseColor(android.graphics.Color.WHITE)
            val canvas = Canvas(bitmap)
            canvas.translate(-x.toFloat(), -y.toFloat())
            webView.draw(canvas)
            return bitmap
        } catch (error: Throwable) { bitmap.recycle(); throw error }
    }

    private tailrec fun Context.findActivity(): Activity? =
        when (this) {
            is Activity -> this
            is ContextWrapper -> baseContext.findActivity()
            else -> null
        }

    private companion object {
        private const val MAX_STABILITY_COPIES = 10
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
