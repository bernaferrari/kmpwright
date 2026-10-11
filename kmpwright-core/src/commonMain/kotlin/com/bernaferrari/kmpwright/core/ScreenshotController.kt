package com.bernaferrari.kmpwright.core

interface ScreenshotController {
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
