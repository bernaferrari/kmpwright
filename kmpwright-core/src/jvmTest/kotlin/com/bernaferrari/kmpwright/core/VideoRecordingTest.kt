package com.bernaferrari.kmpwright.core

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.flow
import org.junit.Test
import org.junit.Assert.*
import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.file.Files
import javax.imageio.ImageIO

class VideoRecordingTest {
    @Test fun `missing encoder fails before capture or output directory creation`() = runBlocking<Unit> {
        val directory = Files.createTempDirectory("kmpwright-preflight-")
        var captured = false
        try {
            val source = flow<ScreencastFrame> { captured = true }
            try {
                CdpVideoRecorder.start(source, RecordingOptions(directory.resolve("absent/capture.mp4").toString(),
                    ffmpegExecutable = directory.resolve("missing-ffmpeg").toString()), RecordingCursorTimeline())
                fail("Missing encoder must fail")
            } catch (error: KmpwrightException) { assertTrue(error.message!!.contains("ffmpeg")) }
            assertFalse(captured)
            assertFalse(Files.exists(directory.resolve("absent")))
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test fun `sixty fps webm preserves fixed timing and existing outputs`() = runBlocking<Unit> {
        val directory = Files.createTempDirectory("kmpwright-webm-")
        val output = directory.resolve("capture.webm")
        val ffmpeg = System.getenv("KMPWRIGHT_TEST_FFMPEG") ?: "ffmpeg"
        try {
            val image = BufferedImage(81, 45, BufferedImage.TYPE_INT_RGB)
            val graphics = image.createGraphics()
            try { graphics.color = Color.BLUE; graphics.fillRect(0, 0, image.width, image.height) }
            finally { graphics.dispose() }
            val bytes = java.io.ByteArrayOutputStream().also { ImageIO.write(image, "jpeg", it) }.toByteArray()
            val source = flow {
                emit(ScreencastFrame(bytes, currentTimeMillis().toDouble(), 81.0, 45.0, 1.0, 0.0, 0.0))
                kotlinx.coroutines.awaitCancellation()
            }
            val options = RecordingOptions(output.toString(), fps = 60, ffmpegExecutable = ffmpeg, cursor = false)
            val recorder = CdpVideoRecorder.start(source, options, RecordingCursorTimeline())
            kotlinx.coroutines.delay(350)
            val result = recorder.stop()
            assertTrue(result.frameCount >= 15)
            assertEquals(60, result.fps)
            assertEquals(82, result.width)
            assertEquals(46, result.height)
            val original = Files.readAllBytes(output)
            try { CdpVideoRecorder.start(source, options, RecordingCursorTimeline()); fail("Existing output must be preserved") }
            catch (error: KmpwrightException) { assertTrue(error.message!!.contains("already exists")) }
            assertArrayEquals(original, Files.readAllBytes(output))
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test fun `unexpected capture completion aborts without publishing output`() = runBlocking<Unit> {
        val directory = Files.createTempDirectory("kmpwright-ended-")
        val output = directory.resolve("capture.mp4")
        try {
            try {
                CdpVideoRecorder.start(flow<ScreencastFrame> { }, RecordingOptions(output.toString(),
                    ffmpegExecutable = System.getenv("KMPWRIGHT_TEST_FFMPEG") ?: "ffmpeg"), RecordingCursorTimeline())
                fail("Ended capture must fail")
            } catch (error: KmpwrightException) { assertTrue(error.message!!.contains("ended")) }
            assertFalse(Files.exists(output))
            assertEquals(0L, Files.list(directory).use { it.count() })
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test fun `contact selection keeps first and final clean frames within its budget`() {
        val directory = Files.createTempDirectory("kmpwright-contact-")
        try {
            val sheet = RecordingContactSheet(0.05, 3)
            val image = BufferedImage(160, 90, BufferedImage.TYPE_INT_RGB)
            listOf(Color.BLUE, Color.BLUE, Color.RED, Color.GREEN, Color.YELLOW).forEachIndexed { index, color ->
                val graphics = image.createGraphics()
                try { graphics.color = color; graphics.fillRect(0, 0, image.width, image.height) }
                finally { graphics.dispose() }
                sheet.observe(image, index.toLong(), index * 100L)
            }
            val output = directory.resolve("sheet.png")
            val selected = sheet.finish(output)
            assertEquals(listOf(0L, 2L, 4L), selected.map { it.index })
            assertTrue(ImageIO.read(output.toFile()).width < 3000)
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test fun `extreme aspect contact frames stay within fixed pixel budget`() {
        val directory = Files.createTempDirectory("kmpwright-contact-aspect-")
        try {
            val sheet = RecordingContactSheet(0.05, 1)
            sheet.observe(BufferedImage(1, 4096, BufferedImage.TYPE_INT_RGB), 0, 0)
            val output = directory.resolve("sheet.png")
            sheet.finish(output)
            assertTrue(ImageIO.read(output.toFile()).height <= 400)
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test fun `recording options reject invalid budgets and ambiguous outputs`() {
        listOf<() -> RecordingOptions>(
            { RecordingOptions("movie", fps = 30) },
            { RecordingOptions("movie.mp4", fps = 0) },
            { RecordingOptions("movie.mp4", fps = 61) },
            { RecordingOptions("movie.mp4", maxWidth = 1) },
            { RecordingOptions("movie.mp4", contactSheetThreshold = Double.NaN) },
            { RecordingOptions("movie.mp4", maxContactSheetFrames = 101) },
            { RecordingOptions("movie.mp4", contactSheetPath = "sheet.jpg") }
        ).forEach { create ->
            try { create(); fail("Invalid recording options accepted") }
            catch (_: IllegalArgumentException) { }
        }
    }
}
