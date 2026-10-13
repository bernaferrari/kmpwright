package com.bernaferrari.kmpwright.core

import kotlinx.coroutines.flow.Flow

/** Chrome emits changed frames; maxFps limits delivery rather than forcing synthetic duplicates. */
public data class ScreencastOptions @Throws(IllegalArgumentException::class) public constructor(val maxFps: Int = 30, val quality: Int = 80,
    val maxWidth: Int = 1920, val maxHeight: Int = 1080) {
    init {
        require(maxFps in 1..60) { "Screencast FPS must be between 1 and 60" }
        require(quality in 0..100) { "JPEG quality must be between 0 and 100" }
        require(maxWidth in 1..8192 && maxHeight in 1..8192) { "Screencast dimensions must be between 1 and 8192" }
    }
}

/** A frame emitted by [Page.screencast]. */
public data class ScreencastFrame(val bytes: ByteArray, val timestampMs: Double,
    val viewportWidth: Double, val viewportHeight: Double, val pageScaleFactor: Double,
    val scrollOffsetX: Double, val scrollOffsetY: Double, val mimeType: String = "image/jpeg")
