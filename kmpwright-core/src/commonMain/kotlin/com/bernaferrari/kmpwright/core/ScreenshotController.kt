package com.bernaferrari.kmpwright.core

internal interface ScreenshotController {
    /** Backends override this when they support encoding/background options. */
    suspend fun capture(options: ScreenshotOptions): ScreenshotResult {
        require(options.scale.isFinite() && options.scale > 0) { "Screenshot scale must be positive" }
        require(options.quality in 0..100) { "Screenshot quality must be between 0 and 100" }
        if (options.format != ScreenshotFormat.Png || options.omitBackground) {
            throw KmpwrightException("This backend supports PNG screenshots with the page background only")
        }
        return when {
            options.fullPage -> captureFullPage(options.scale)
            options.clip != null -> captureClip(options.clip.x, options.clip.y, options.clip.width, options.clip.height, options.scale)
            else -> captureViewport(options.scale)
        }
    }
    suspend fun captureViewport(scale: Double = 1.0): ScreenshotResult
    suspend fun captureFullPage(scale: Double = 1.0): ScreenshotResult
    suspend fun captureClip(x: Double, y: Double, width: Double, height: Double, scale: Double = 1.0): ScreenshotResult
    fun getPageDimensions(): PageDimensions
}

/**
 * Trims a viewport-relative [clip] to the viewport like Playwright (`trimClipToSize`).
 * @throws KmpwrightException when nothing of the clip is inside the viewport.
 */
internal fun trimScreenshotClip(clip: Rectangle, viewportWidth: Double, viewportHeight: Double): Rectangle {
    val left = maxOf(clip.x, 0.0)
    val top = maxOf(clip.y, 0.0)
    val right = minOf(clip.x + clip.width, viewportWidth)
    val bottom = minOf(clip.y + clip.height, viewportHeight)
    if (right - left < 1e-6 || bottom - top < 1e-6) {
        throw KmpwrightException(
            "Clipped area is either empty or outside the viewport: clip=(${clip.x}, ${clip.y}, ${clip.width}x${clip.height}), " +
                "viewport=${viewportWidth}x$viewportHeight"
        )
    }
    return Rectangle(left, top, right - left, bottom - top)
}

internal data class PageDimensions(
    val contentWidth: Int,
    val contentHeight: Int,
    val viewportWidth: Int,
    val viewportHeight: Int
)
