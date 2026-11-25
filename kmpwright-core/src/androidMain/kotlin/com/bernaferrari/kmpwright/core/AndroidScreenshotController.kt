package com.bernaferrari.kmpwright.core

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.Choreographer
import android.webkit.WebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.WeakHashMap
import kotlin.coroutines.resume
import kotlin.math.roundToInt

internal class AndroidScreenshotController(
    private val webView: WebView,
    private val config: KmpwrightConfig = KmpwrightConfig()
) : ScreenshotController {
    override val supportsDocumentLimits: Boolean get() = true
    private val maxBitmapBytes = 128L * 1024L * 1024L
    override suspend fun capture(options: ScreenshotOptions): ScreenshotResult = withWebViewScreenshotLock(webView) {
        require(options.scale.isFinite() && options.scale > 0) { "Screenshot scale must be positive" }
        require(options.quality in 0..100) { "Screenshot quality must be between 0 and 100" }
        if (options.omitBackground) {
            throw KmpwrightUnsupportedException("omitBackground is not supported on Android WebView: it always paints an opaque page background")
        }
        ensureLaidOut()
        val clip = options.clip
        val scale = options.scale.coerceIn(0.1, 5.0)
        when {
            options.fullPage || (clip != null && options.captureBeyondViewport) -> {
                val documentClip = clip?.let {
                    val (x, y) = readScrollPosition()
                    it.copy(x = it.x + x, y = it.y + y)
                }
                captureDocument(
                    DocumentScreenshotOptions(
                        clip = documentClip,
                        pixelsPerCssPixel = nativePixelsPerCss() * scale,
                        maxPixels = options.maxPixels,
                        truncateFullPage = options.truncateFullPage,
                        format = options.format,
                        quality = options.quality,
                        viewportWidthOnly = options.viewportWidthOnly
                    )
                ) {}
            }
            clip != null -> captureVisibleClip(clip.x, clip.y, clip.width, clip.height, scale, options)
            else -> {
                val bitmap = captureVisibleViewportBitmap(scale, "viewport screenshot")
                try {
                    ScreenshotResult(bitmap.encode(options.format, options.quality), bitmap.width, bitmap.height)
                } finally {
                    bitmap.recycle()
                }
            }
        }
    }

    override suspend fun captureViewport(scale: Double): ScreenshotResult = capture(ScreenshotOptions(scale = scale))

    override suspend fun captureFullPage(scale: Double): ScreenshotResult = capture(ScreenshotOptions(fullPage = true, scale = scale))

    override suspend fun captureClip(x: Double, y: Double, width: Double, height: Double, scale: Double): ScreenshotResult =
        capture(ScreenshotOptions(clip = Rectangle(x, y, width, height), scale = scale))

    /** The clip is in CSS pixels (as returned by `Locator.boundingBox`); output is device pixels times [scale]. */
    private suspend fun captureVisibleClip(
        x: Double,
        y: Double,
        width: Double,
        height: Double,
        clipScale: Double,
        options: ScreenshotOptions
    ): ScreenshotResult {
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
                return ScreenshotResult(bytes = cropped.encode(options.format, options.quality), width = cropped.width, height = cropped.height)
            } finally {
                if (cropped !== fullBitmap) cropped.recycle()
            }
        } finally {
            fullBitmap.recycle()
        }
    }

    /**
     * Compression runs off the main thread: encoding a full-page PNG takes long enough to freeze the host app's UI.
     * Drawing stays on Main; the bitmap is only read here, and callers recycle it after this returns
     * ([withContext] waits for the block even when the caller is cancelled).
     */
    private suspend fun Bitmap.encode(outputFormat: ScreenshotFormat, outputQuality: Int): ByteArray {
        val formatName = outputFormat.name
        val (format, quality) = when (outputFormat) {
            ScreenshotFormat.Png -> Bitmap.CompressFormat.PNG to 100
            ScreenshotFormat.Jpeg -> Bitmap.CompressFormat.JPEG to outputQuality
            ScreenshotFormat.Webp -> (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY
                else @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP) to outputQuality
        }
        val bitmap = this
        return withContext(Dispatchers.Default) {
            val outputStream = java.io.ByteArrayOutputStream()
            if (!bitmap.compress(format, quality, outputStream)) {
                throw KmpwrightException("Android screenshot $formatName encoding failed")
            }
            outputStream.toByteArray()
        }
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

    @Suppress("DEPRECATION")
    internal fun nativePixelsPerCss(): Double =
        webView.scale.toDouble().takeIf { it.isFinite() && it > 0 } ?: webView.displayDensity()

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
        val scale = nativePixelsPerCss()
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
            val pixelsPerCss = nativePixelsPerCss()
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
        withKmpwrightTimeout(config.timeouts.default, { "Timed out waiting for screenshot geometry" }) {
            suspendCancellableCoroutine { cont ->
                webView.evaluateJavascript(script) { result ->
                    if (cont.isActive) cont.resume(normalizeJsResult(result))
                }
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
        val pixelsPerCss = nativePixelsPerCss()
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
            val choreographer = Choreographer.getInstance()
            val callback = Choreographer.FrameCallback {
                if (cont.isActive) cont.resume(Unit)
            }
            choreographer.postFrameCallback(callback)
            cont.invokeOnCancellation { choreographer.removeFrameCallback(callback) }
        }
    }

    private suspend fun captureVisibleViewportBitmap(scale: Double, label: String): Bitmap {
        awaitDocumentRendered()
        return drawViewport(scale, label)
    }

    /** Draw the WebView itself so app overlays and window clipping cannot enter the image. */
    private fun drawViewport(scale: Double, label: String): Bitmap {
        val width = (webView.width * scale).roundToInt().coerceAtLeast(1)
        val height = (webView.height * scale).roundToInt().coerceAtLeast(1)
        val bitmap = createBitmap(width, height, label)
        try {
            // Detached or transparent-background WebViews leave unpainted pixels transparent.
            bitmap.eraseColor(android.graphics.Color.WHITE)
            val canvas = Canvas(bitmap)
            canvas.scale(scale.toFloat(), scale.toFloat())
            canvas.translate(-webView.scrollX.toFloat(), -webView.scrollY.toFloat())
            webView.draw(canvas)
            return bitmap
        } catch (error: Throwable) {
            bitmap.recycle()
            throw error
        }
    }

    internal val captureWebView: WebView get() = webView

    internal fun prepareDocumentCapture() = ensureLaidOut()
    internal suspend fun evaluateForDocument(script: String): String? = evaluateJavaScript(script)

    internal suspend fun awaitDocumentRendered() {
        // Chromium acknowledges visual state when the view is attached. Detached views
        // still support software draw after the renderer has had time to process the scroll.
        if (webView.isAttachedToWindow) withTimeoutOrNull(500) { awaitVisualState() }
        awaitFrame()
        awaitFrame()
    }

    internal fun allocateDocumentBitmap(width: Int, height: Int): Bitmap = createBitmap(width, height, "document screenshot")
    internal suspend fun encodeDocumentBitmap(bitmap: Bitmap, options: DocumentScreenshotOptions): ByteArray =
        bitmap.encode(options.format, options.quality)

    internal fun captureDocumentTile(): Bitmap = drawViewport(1.0, "document screenshot tile")

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

// Main owns the map. A weak key keeps the host's WebView lifetime independent of capture.
private val screenshotLocks = WeakHashMap<WebView, Mutex>()

internal suspend fun <T> withWebViewScreenshotLock(webView: WebView, capture: suspend () -> T): T =
    withContext(Dispatchers.Main.immediate) {
        screenshotLocks.getOrPut(webView, ::Mutex).withLock { capture() }
    }
