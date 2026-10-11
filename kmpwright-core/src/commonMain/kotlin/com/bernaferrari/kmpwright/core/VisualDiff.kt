package com.bernaferrari.kmpwright.core

import kotlinx.serialization.Serializable
import kotlin.math.sqrt

data class DiffOptions(
    val threshold: Double = 0.1,
    val generateDiffImage: Boolean = true
)

data class DiffResult(
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

@Serializable
data class DimensionMismatch(
    val expectedWidth: Int,
    val expectedHeight: Int,
    val actualWidth: Int,
    val actualHeight: Int
)

data class RgbaImage(
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

suspend fun Page.visualDiff(
    expected: ScreenshotResult,
    actual: ScreenshotResult,
    options: DiffOptions = DiffOptions()
): DiffResult {
    val expectedRgba = expected.toRgbaImage()
    val actualRgba = actual.toRgbaImage()
    if (expectedRgba == null || actualRgba == null) {
        return DiffResult(
            match = false,
            mismatchPercentage = 100.0,
            differentPixels = 0,
            totalPixels = 0,
            diffImage = null
        )
    }
    return VisualDiff.compareRgba(expectedRgba, actualRgba, options)
}

object VisualDiff {

    private val MAX_DISTANCE = 255.0 * sqrt(3.0)

    fun compareRgba(expected: RgbaImage, actual: RgbaImage, options: DiffOptions = DiffOptions()): DiffResult {
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
        val thresholdDistance = options.threshold * MAX_DISTANCE

        val diffPixels = if (options.generateDiffImage) ByteArray(expected.pixels.size) else null

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
