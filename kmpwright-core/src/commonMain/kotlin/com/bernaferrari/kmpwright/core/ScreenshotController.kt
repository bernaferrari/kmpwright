package com.bernaferrari.kmpwright.core

interface ScreenshotController {
    /** Backends override this when they support encoding/background options. */
    suspend fun capture(options: ScreenshotOptions): ScreenshotResult {
        require(options.scale.isFinite() && options.scale > 0) { "Screenshot scale must be positive" }
        require(options.quality in 0..100) { "Screenshot quality must be between 0 and 100" }
        if (options.format != ScreenshotFormat.PNG || options.omitBackground) {
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

data class PageDimensions(
    val contentWidth: Int,
    val contentHeight: Int,
    val viewportWidth: Int,
    val viewportHeight: Int
)
