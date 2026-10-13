package com.bernaferrari.kmpwright.core

/** Video-only capture of the current page. Encoding requires ffmpeg on the JVM host. */
public data class RecordingOptions @Throws(IllegalArgumentException::class) public constructor(val path: String, val fps: Int = 30, val quality: Int = 85,
    val maxWidth: Int = 1920, val maxHeight: Int = 1080, val cursor: Boolean = true,
    val contactSheetPath: String? = null, val contactSheetThreshold: Double = 0.05,
    val maxContactSheetFrames: Int = 100, val overwrite: Boolean = false,
    val ffmpegExecutable: String = "ffmpeg") {
    init {
        require(fps in 1..60) { "Recording FPS must be between 1 and 60" }
        require(quality in 0..100) { "Recording JPEG quality must be between 0 and 100" }
        require(maxWidth in 2..4096 && maxHeight in 2..4096) { "Recording dimensions must be between 2 and 4096" }
        require(contactSheetThreshold.isFinite() && contactSheetThreshold in 0.0..1.0) { "Contact sheet threshold must be between 0 and 1" }
        require(maxContactSheetFrames in 1..100) { "Contact sheet frame limit must be between 1 and 100" }
        require(path.substringAfterLast('/').substringAfterLast('\\').substringAfterLast('.', "").isNotEmpty()) {
            "Recording output must have a file extension (for example .mp4 or .webm)"
        }
        require(ffmpegExecutable.isNotBlank()) { "ffmpeg executable must not be blank" }
        require(contactSheetPath == null || contactSheetPath.endsWith(".png", true)) { "Contact sheets require a .png path" }
    }
}

/** A frame chosen for the contact sheet of a [RecordingResult]. */
public data class RecordingContactFrame(val timestampMs: Long, val index: Long)
/** The video written by [Video.stop]. */
public data class RecordingResult(val path: String, val fps: Int, val frameCount: Long,
    val durationMs: Long, val width: Int, val height: Int, val contactSheetPath: String? = null,
    val contactFrames: List<RecordingContactFrame> = emptyList())
