package com.bernaferrari.kmpwright.core

// Internal parameter bundles passed between the public API (named parameters) and the backends.
// They are not part of the public API: every public method takes these values as named parameters.

/** Parameters of [Page.screenshot] and [Locator.screenshot]. */
internal data class ScreenshotOptions(
    val fullPage: Boolean = false,
    val clip: Rectangle? = null,
    val omitBackground: Boolean = false,
    val quality: Int = 90,
    val format: ScreenshotFormat = ScreenshotFormat.Png,
    val scale: Double = 1.0,
    val annotate: Boolean = false,
    val timeoutMs: Long? = null,
    /** Native element capture includes the complete bounding box beyond the viewport. */
    val captureBeyondViewport: Boolean = false
) {
    init {
        require(!(fullPage && clip != null)) { "fullPage and clip are mutually exclusive" }
    }
}

/** [Page.screenshot] with a parameter bundle. */
internal suspend fun Page.screenshot(options: ScreenshotOptions): ScreenshotResult = with(options) {
    screenshot(fullPage, clip, omitBackground, quality, format, scale, annotate, timeoutMs)
}

/** Parameters of [Page.pdf]. */
internal data class PdfOptions(
    val format: PdfFormat? = null,
    val landscape: Boolean = false,
    val displayHeaderFooter: Boolean = false,
    val headerTemplate: String = "",
    val footerTemplate: String = "",
    val printBackground: Boolean = true,
    val scale: Double = 1.0,
    val width: Double? = null,
    val height: Double? = null,
    val margin: PdfMargin = PdfMargin(),
    val pageRanges: String = "",
    val timeoutMs: Long? = null
)

/** Parameters of [Page.snapshot]. */
internal data class SnapshotOptions(
    val interactive: Boolean = false,
    val compact: Boolean = false,
    val maxDepth: Int? = null,
    val selector: String? = null,
    val cursor: Boolean = false,
    val includeHidden: Boolean = false,
    val urls: Boolean = false
)

/** Parameters of [Page.read] and [HttpReader.read]. */
internal data class ReadOptions(
    val raw: Boolean = false,
    val requireMarkdown: Boolean = false,
    val llms: LlmsReadMode? = null,
    val outline: Boolean = false,
    val filter: String? = null,
    val timeoutMs: Long? = null,
    val headers: Map<String, String> = emptyMap(),
    val allowedDomains: Set<String> = emptySet(),
    val maxBytes: Int = 2 * 1024 * 1024,
    val caCertificatePath: String? = null,
    val useSystemCa: Boolean = true
)

/** Parameters of [Page.webVitals]. */
internal data class WebVitalsOptions(
    val url: String? = null,
    val reload: Boolean = true,
    val settleMs: Long = 3_000,
    val timeoutMs: Long? = null
)

/** Parameters of [Page.accessibilityAudit]. */
internal data class AccessibilityAuditOptions(
    val tags: Set<String> = emptySet(),
    val selector: String? = null,
    val timeoutMs: Long? = null,
    val maxNodesPerRule: Int = 10
)

/** Parameters of [Page.screencast]. */
internal data class ScreencastOptions(
    val maxFps: Int = 30,
    val quality: Int = 80,
    val maxWidth: Int = 1920,
    val maxHeight: Int = 1080
) {
    init {
        require(maxFps in 1..60) { "Screencast FPS must be between 1 and 60" }
        require(quality in 0..100) { "JPEG quality must be between 0 and 100" }
        require(maxWidth in 1..8192 && maxHeight in 1..8192) { "Screencast dimensions must be between 1 and 8192" }
    }
}

/** Parameters of [Video.start]. */
internal data class RecordingOptions(
    val path: String,
    val fps: Int = 30,
    val quality: Int = 85,
    val maxWidth: Int = 1920,
    val maxHeight: Int = 1080,
    val cursor: Boolean = true,
    val contactSheetPath: String? = null,
    val contactSheetThreshold: Double = 0.05,
    val maxContactSheetFrames: Int = 100,
    val overwrite: Boolean = false,
    val ffmpegExecutable: String = "ffmpeg"
) {
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

/** Parameters of [Tracing.start]. */
internal data class TracingOptions(
    val categories: String = "blink,devtools.timeline,v8,benchmark",
    val screenshots: Boolean = false,
    val path: String? = null
)

/** [Page.snapshot] with a parameter bundle. */
internal suspend fun Page.snapshot(options: SnapshotOptions): Snapshot = with(options) {
    snapshot(interactive, compact, maxDepth, selector, cursor, includeHidden, urls)
}
