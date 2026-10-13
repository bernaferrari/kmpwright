package com.bernaferrari.kmpwright.core

import kotlinx.serialization.Serializable
import kotlin.math.sqrt
import kotlin.coroutines.cancellation.CancellationException

/** Outcome of a pixel comparison. */
public data class DiffResult(
    val match: Boolean,
    val mismatchPercentage: Double,
    val differentPixels: Long,
    val totalPixels: Long,
    val diffImage: ByteArray?,
    val dimensionMismatch: DimensionMismatch? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DiffResult) return false
        if (match != other.match) return false
        if (mismatchPercentage != other.mismatchPercentage) return false
        if (differentPixels != other.differentPixels) return false
        if (totalPixels != other.totalPixels) return false
        if (diffImage != null && other.diffImage != null) {
            if (!diffImage.contentEquals(other.diffImage)) return false
        } else if (diffImage !== other.diffImage) return false
        if (dimensionMismatch != other.dimensionMismatch) return false
        return true
    }

    override fun hashCode(): Int {
        var result = match.hashCode()
        result = 31 * result + mismatchPercentage.hashCode()
        result = 31 * result + differentPixels.hashCode()
        result = 31 * result + totalPixels.hashCode()
        result = 31 * result + (diffImage?.contentHashCode() ?: 0)
        result = 31 * result + (dimensionMismatch?.hashCode() ?: 0)
        return result
    }
}

/** The compared images had different sizes. */
@Serializable
public data class DimensionMismatch(
    val expectedWidth: Int,
    val expectedHeight: Int,
    val actualWidth: Int,
    val actualHeight: Int
)

/** Decoded RGBA pixels. */
public data class RgbaImage @Throws(IllegalArgumentException::class) public constructor(
    val width: Int,
    val height: Int,
    val pixels: ByteArray
) {
    init {
        require(width >= 0 && height >= 0) { "Dimensions must be non-negative" }
        require(pixels.size == width * height * 4) { "Pixel array size must equal width * height * 4" }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RgbaImage) return false
        if (width != other.width || height != other.height) return false
        return pixels.contentEquals(other.pixels)
    }

    override fun hashCode(): Int {
        var result = width
        result = 31 * result + height
        result = 31 * result + pixels.contentHashCode()
        return result
    }
}

/**
 * Compares two already-captured images pixel by pixel (agent-browser `diff screenshot`); it takes
 * no screenshot itself. Capture both with [Page.screenshot] or [Locator.screenshot] (PNG is the
 * portable choice). Images of different sizes report a [DiffResult.dimensionMismatch]. [threshold] and
 * [generateDiffImage] work as in [VisualDiff.compareRgba].
 *
 * @throws KmpwrightException when an image cannot be decoded on this platform, for example WEBP on
 *   the JVM (ImageIO has no WebP reader), or when the bytes are empty or corrupt.
 */
@Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
public suspend fun Page.visualDiff(
    expected: ScreenshotResult,
    actual: ScreenshotResult,
    threshold: Double = 0.1,
    generateDiffImage: Boolean = true
): DiffResult {
    val expectedRgba = expected.toRgbaImage() ?: throw undecodableImage("expected", expected.bytes)
    val actualRgba = actual.toRgbaImage() ?: throw undecodableImage("actual", actual.bytes)
    return VisualDiff.compareRgba(expectedRgba, actualRgba, threshold, generateDiffImage)
}

/** The container format of encoded image bytes, from their magic number. */
internal fun imageFormatOf(bytes: ByteArray): String? {
    fun at(index: Int) = bytes.getOrNull(index)?.toInt()?.and(0xFF)
    return when {
        at(0) == 0x89 && at(1) == 0x50 && at(2) == 0x4E && at(3) == 0x47 -> "PNG"
        at(0) == 0xFF && at(1) == 0xD8 -> "JPEG"
        bytes.size >= 12 && bytes.copyOfRange(0, 4).decodeToString() == "RIFF" &&
            bytes.copyOfRange(8, 12).decodeToString() == "WEBP" -> "WEBP"
        else -> null
    }
}

private fun undecodableImage(which: String, bytes: ByteArray): KmpwrightException {
    val format = imageFormatOf(bytes)
    val reason = when {
        bytes.isEmpty() -> "it is empty"
        format == "WEBP" -> "WEBP cannot be decoded on this platform (the JVM's ImageIO has no WebP reader); capture PNG or JPEG for visual diffs"
        format == null -> "it is not a PNG, JPEG or WEBP image"
        else -> "the $format data is corrupt"
    }
    return KmpwrightException("Cannot decode the $which image for visualDiff: $reason")
}

/** Pixel-level image comparison. */
public object VisualDiff {

    private val MAX_DISTANCE = 255.0 * sqrt(3.0)

    /**
     * Compares two RGBA images. A pixel differs when its RGBA distance exceeds [threshold] (0..1, a
     * fraction of the largest possible distance); with [generateDiffImage] the result carries an image
     * that marks the differing pixels.
     */
    public fun compareRgba(expected: RgbaImage, actual: RgbaImage, threshold: Double = 0.1, generateDiffImage: Boolean = true): DiffResult {
        if (expected.width != actual.width || expected.height != actual.height) {
            return DiffResult(
                match = false,
                mismatchPercentage = 100.0,
                differentPixels = 0,
                totalPixels = 0,
                diffImage = null,
                dimensionMismatch = DimensionMismatch(
                    expectedWidth = expected.width,
                    expectedHeight = expected.height,
                    actualWidth = actual.width,
                    actualHeight = actual.height
                )
            )
        }

        val totalPixels = expected.width.toLong() * expected.height.toLong()
        var differentPixels = 0L
        val thresholdDistance = threshold * MAX_DISTANCE

        val diffPixels = if (generateDiffImage) ByteArray(expected.pixels.size) else null

        val pixelCount = expected.pixels.size
        var i = 0
        while (i < expected.pixels.size) {
            val er = (expected.pixels[i].toInt() and 0xFF)
            val eg = (expected.pixels[i + 1].toInt() and 0xFF)
            val eb = (expected.pixels[i + 2].toInt() and 0xFF)
            val ea = (expected.pixels[i + 3].toInt() and 0xFF)

            val ar = (actual.pixels[i].toInt() and 0xFF)
            val ag = (actual.pixels[i + 1].toInt() and 0xFF)
            val ab = (actual.pixels[i + 2].toInt() and 0xFF)
            val aa = (actual.pixels[i + 3].toInt() and 0xFF)

            val dr = (er - ar).toDouble()
            val dg = (eg - ag).toDouble()
            val db = (eb - ab).toDouble()
            val da = (ea - aa).toDouble()
            val distance = sqrt(dr * dr + dg * dg + db * db + da * da)

            if (distance > thresholdDistance) {
                differentPixels++
            }

            if (diffPixels != null) {
                if (distance > thresholdDistance) {
                    // Red pixel for mismatch
                    diffPixels[i] = 255.toByte()
                    diffPixels[i + 1] = 0.toByte()
                    diffPixels[i + 2] = 0.toByte()
                    diffPixels[i + 3] = 255.toByte()
                } else {
                    // Dimmed grayscale
                    val gray = ((er + eg + eb) / 3 * 0.5).toInt().toByte()
                    diffPixels[i] = gray
                    diffPixels[i + 1] = gray
                    diffPixels[i + 2] = gray
                    diffPixels[i + 3] = 255.toByte()
                }
            }
            i += 4
        }

        val mismatchPercentage = if (totalPixels > 0) {
            (differentPixels.toDouble() / totalPixels) * 100.0
        } else {
            0.0
        }

        return DiffResult(
            match = differentPixels == 0L,
            mismatchPercentage = mismatchPercentage,
            differentPixels = differentPixels,
            totalPixels = totalPixels,
            diffImage = diffPixels
        )
    }
}
