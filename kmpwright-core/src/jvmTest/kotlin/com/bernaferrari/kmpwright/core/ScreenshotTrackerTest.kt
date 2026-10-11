package com.bernaferrari.kmpwright.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

class ScreenshotTrackerTest {
    private fun png(changedPixels: Int = 0): ScreenshotResult {
        val image = BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB)
        repeat(4) { image.setRGB(it % 2, it / 2, if (it < changedPixels) -1 else 0xff000000.toInt()) }
        val bytes = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
        return ScreenshotResult(bytes, 2, 2)
    }

    @Test
    fun `alternating capture scopes retain independent baselines`() = runBlocking {
        val tracker = ScreenshotTracker({ png() })
        assertTrue(tracker.captureIfChanged().changed)
        assertTrue(tracker.captureIfChanged(ScreenshotOptions(fullPage = true)).changed)
        assertFalse(tracker.captureIfChanged().changed)
        assertFalse(tracker.captureIfChanged(ScreenshotOptions(fullPage = true)).changed)
        tracker.clear()
        assertTrue(tracker.captureIfChanged().changed)
    }

    @Test
    fun `threshold compares against emitted baseline and returned bytes cannot corrupt it`() = runBlocking {
        var pixels = 0
        val tracker = ScreenshotTracker({ png(pixels) })
        tracker.captureIfChanged().image!!.bytes.fill(0)
        assertFalse(tracker.captureIfChanged().changed)
        pixels = 1
        val skipped = tracker.captureIfChanged(threshold = 0.25)
        assertFalse(skipped.changed)
        assertNull(skipped.image)
        pixels = 2
        assertTrue(tracker.captureIfChanged(threshold = 0.25).changed)
    }

    @Test
    fun `failed decoding preserves the last good baseline`() = runBlocking {
        var broken = false
        val tracker = ScreenshotTracker({ if (broken) ScreenshotResult(byteArrayOf(1, 2, 3), 2, 2) else png() })
        assertTrue(tracker.captureIfChanged().changed)
        broken = true
        try {
            tracker.captureIfChanged()
            fail("Invalid image should fail decoding")
        } catch (_: KmpwrightException) { }
        broken = false
        assertFalse(tracker.captureIfChanged().changed)
    }
}
