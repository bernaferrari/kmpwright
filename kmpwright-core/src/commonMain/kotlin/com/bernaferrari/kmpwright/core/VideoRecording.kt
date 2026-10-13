package com.bernaferrari.kmpwright.core

/** A frame chosen for the contact sheet of a [RecordingResult]. */
public data class RecordingContactFrame(val timestampMs: Long, val index: Long)
/** The video written by [Video.stop]. */
public data class RecordingResult(val path: String, val fps: Int, val frameCount: Long,
    val durationMs: Long, val width: Int, val height: Int, val contactSheetPath: String? = null,
    val contactFrames: List<RecordingContactFrame> = emptyList())
