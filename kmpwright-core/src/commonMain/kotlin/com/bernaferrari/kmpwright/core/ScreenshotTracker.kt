package com.bernaferrari.kmpwright.core

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException

/** Result of a conditional capture. Unchanged captures omit image bytes. */
public data class ConditionalScreenshotResult(
    val changed: Boolean,
    val image: ScreenshotResult?,
    val mismatchPercentage: Double?
)

/**
 * Caller-owned, bounded screenshot baselines for one page. Viewport, full-page,
 * clip, and encoding options have independent baselines. Threshold is the
 * fraction of pixels allowed to change (0.01 means 1%). A skipped image keeps
 * the last emitted baseline, so small cumulative changes eventually surface.
 */
public class ScreenshotTracker internal constructor(
    private val capture: suspend (ScreenshotOptions) -> ScreenshotResult,
    private val maxScopes: Int = 8
) {
    /** Tracks screenshots of [page], keeping baselines for up to [maxScopes] scopes. */
    public constructor(page: Page, maxScopes: Int = 8) : this({ page.screenshot(it) }, maxScopes)

    init { require(maxScopes > 0) { "maxScopes must be positive" } }

    private val mutex = Mutex()
    private val baselines = linkedMapOf<ScreenshotOptions, ScreenshotResult>()

    /**
     * Captures a screenshot and returns it only when it differs from the previous one of the same scope
     * by more than [threshold] (0..1). The screenshot parameters are those of [Page.screenshot].
     */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun captureIfChanged(
        fullPage: Boolean = false,
        clip: Rectangle? = null,
        omitBackground: Boolean = false,
        quality: Int = 90,
        format: ScreenshotFormat = ScreenshotFormat.Png,
        scale: Double = 1.0,
        annotate: Boolean = false,
        threshold: Double = 0.0,
        timeoutMs: Long? = null
    ): ConditionalScreenshotResult = mutex.withLock {
        require(threshold in 0.0..1.0) { "threshold must be between 0 and 1" }
        val options = ScreenshotOptions(fullPage, clip, omitBackground, quality, format, scale, annotate, timeoutMs)
        val image = capture(options)
        if (image.bytes.isEmpty()) throw KmpwrightException("Screenshot capture returned no image")
        val key = options.copy(timeoutMs = 0)
        val previous = baselines[key]
        var mismatch: Double? = null
        val changed = if (previous == null) true else if (previous == image) {
            mismatch = 0.0
            false
        } else {
            val before = previous.toRgbaImage() ?: throw KmpwrightException("Could not decode screenshot baseline")
            val after = image.toRgbaImage() ?: throw KmpwrightException("Could not decode current screenshot")
            val diff = VisualDiff.compareRgba(before, after, threshold = 0.0, generateDiffImage = false)
            mismatch = diff.mismatchPercentage
            diff.dimensionMismatch != null || diff.mismatchPercentage > threshold * 100
        }
        baselines.remove(key)
        baselines[key] = if (changed) image.copy(bytes = image.bytes.copyOf()) else previous!!
        if (baselines.size > maxScopes) baselines.remove(baselines.keys.first())
        ConditionalScreenshotResult(changed, if (changed) image else null, mismatch)
    }

    /** Forgets every baseline. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun clear(): Unit = mutex.withLock { baselines.clear() }
}
