package com.bernaferrari.kmpwright.core

import kotlinx.coroutines.flow.Flow

/** A frame emitted by [Page.screencast]. */
public data class ScreencastFrame(val bytes: ByteArray, val timestampMs: Double,
    val viewportWidth: Double, val viewportHeight: Double, val pageScaleFactor: Double,
    val scrollOffsetX: Double, val scrollOffsetY: Double, val mimeType: String = "image/jpeg")
