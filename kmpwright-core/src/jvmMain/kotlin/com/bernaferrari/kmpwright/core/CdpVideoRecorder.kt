package com.bernaferrari.kmpwright.core

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.awt.image.DataBufferByte
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.imageio.ImageIO

/** Page-owned recording: bounded changed-frame queue, fixed-rate output, atomic final publication. */
internal class CdpVideoRecorder private constructor(private val options: RecordingOptions,
    private val cursor: RecordingCursorTimeline, private val output: Path, private val temporary: Path,
    private val contactOutput: Path?, private val contactTemporary: Path?) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queueLock = Any()
    private data class Captured(val frame: ScreencastFrame, val receivedAtNanos: Long)
    private val frames = ArrayDeque<Captured>()
    private val first = CompletableDeferred<Captured>()
    private val ready = CompletableDeferred<Unit>()
    private val failure = AtomicReference<Throwable?>(null)
    private val stopping = AtomicBoolean(false)
    private var capture: Job? = null
    private var writer: Job? = null
    private var encoder: FfmpegEncoder? = null
    private var frameCount = 0L
    private var width = 0
    private var height = 0
    private val contacts = contactOutput?.let { RecordingContactSheet(options.contactSheetThreshold, options.maxContactSheetFrames) }

    private fun fail(error: Throwable) {
        failure.compareAndSet(null, error)
        first.completeExceptionally(error)
        ready.completeExceptionally(error)
        stopping.set(true)
    }

    private suspend fun begin(source: Flow<ScreencastFrame>) {
        if (options.cursor) cursor.start()
        capture = scope.launch {
            try {
                source.collect { frame ->
                    val sample = Captured(frame, System.nanoTime())
                    synchronized(queueLock) {
                        if (frames.size >= 16) throw KmpwrightException("Recording frame queue exceeded its 16-frame budget")
                        frames.addLast(sample)
                    }
                    first.complete(sample)
                }
                if (!stopping.get()) fail(KmpwrightException("Screencast ended during recording"))
            } catch (error: Throwable) { if (!stopping.get()) fail(error) }
        }
        writer = scope.launch {
            try { encode(first.await()) }
            catch (error: Throwable) { fail(error) }
        }
        try {
            withTimeoutOrNull(FIRST_FRAME_TIMEOUT_MS) { ready.await() }
                ?: throw KmpwrightTimeoutException(
                    "Video recording did not receive its first screencast frame within ${FIRST_FRAME_TIMEOUT_MS}ms; " +
                        "the page may be hidden or not painting"
                )
        } catch (error: Throwable) {
            abort()
            throw if (error is KmpwrightException || error is kotlinx.coroutines.CancellationException) error
                else KmpwrightException("Video recording failed to start: ${error.message ?: error::class.simpleName}", error)
        }
    }

    private suspend fun encode(initial: Captured) {
        var current = initial.frame
        var decoded = decode(current)
        width = (decoded.width + 1) / 2 * 2
        height = (decoded.height + 1) / 2 * 2
        val canvas = BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR)
        val g = canvas.createGraphics()
        val process = FfmpegEncoder.start(options.ffmpegExecutable, temporary, width, height, options.fps, scope)
        encoder = process
        // Browser capture clocks (including remote clock adjustments) cannot schedule
        // local output. Anchor after the first write so encoder/font startup does not
        // consume the live backlog budget; later frames retain the 500ms bound.
        var started = System.nanoTime()
        var cursorEpoch = currentTimeMillis().toDouble()
        try {
            while (!stopping.get()) {
                val scheduled = started + frameCount * 1_000_000_000L / options.fps
                val remaining = scheduled - System.nanoTime()
                if (remaining > 0) delay(maxOf(1, remaining / 1_000_000))
                if (frameCount > 0 && System.nanoTime() - scheduled > 500_000_000L) throw KmpwrightException("Video encoder fell more than 500 ms behind capture")
                val timestamp = cursorEpoch + frameCount * 1000.0 / options.fps
                var next: Captured? = null
                synchronized(queueLock) {
                    while (frames.isNotEmpty() && frames.first().receivedAtNanos <= scheduled) next = frames.removeFirst()
                }
                if (next != null && next.frame !== current) { current = next.frame; decoded = decode(current) }
                g.color = Color.BLACK; g.fillRect(0, 0, width, height)
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                val scale = minOf(width.toDouble() / decoded.width, height.toDouble() / decoded.height)
                val drawWidth = maxOf(1, (decoded.width * scale).toInt())
                val drawHeight = maxOf(1, (decoded.height * scale).toInt())
                val x = (width - drawWidth) / 2; val y = (height - drawHeight) / 2
                g.drawImage(decoded, x, y, drawWidth, drawHeight, null)
                contacts?.observe(canvas, frameCount, (frameCount * 1000.0 / options.fps).toLong())
                if (options.cursor) {
                    val translated = g.create() as java.awt.Graphics2D
                    try {
                        translated.translate(x, y)
                        drawRecordingCursor(translated, cursor.at(timestamp), current.viewportWidth, current.viewportHeight,
                            drawWidth, drawHeight, timestamp)
                    } finally { translated.dispose() }
                }
                process.write((canvas.raster.dataBuffer as DataBufferByte).data)
                if (frameCount == 0L) {
                    started = System.nanoTime()
                    cursorEpoch = currentTimeMillis().toDouble()
                    ready.complete(Unit)
                }
                frameCount++
            }
        } finally { g.dispose() }
    }

    private fun decode(frame: ScreencastFrame): BufferedImage {
        val reader = ImageIO.getImageReadersByFormatName("jpeg").asSequence().firstOrNull()
            ?: throw KmpwrightException("JPEG decoder is unavailable")
        try {
            ImageIO.createImageInputStream(ByteArrayInputStream(frame.bytes)).use { stream ->
                reader.input = stream
                val w = reader.getWidth(0); val h = reader.getHeight(0)
                if (w !in 1..4096 || h !in 1..4096 || w.toLong() * h > 16_777_216) {
                    throw KmpwrightException("Recording frame exceeds its pixel budget")
                }
                return reader.read(0)
            }
        } finally { reader.dispose() }
    }

    suspend fun stop(): RecordingResult = withContext(NonCancellable + Dispatchers.IO) {
        stopping.set(true)
        capture?.cancel()
        try {
            withTimeoutOrNull(3_000) { capture?.join(); writer?.join() }
                ?: throw KmpwrightException("Recording workers did not stop within three seconds")
            failure.get()?.let { throw it }
            if (frameCount == 0L) throw KmpwrightException("Recording captured no frames")
            requireNotNull(encoder).finish()
            val selected = if (contacts != null && contactTemporary != null) contacts.finish(contactTemporary) else emptyList()
            publish(temporary, output, options.overwrite)
            if (contactTemporary != null && contactOutput != null) publish(contactTemporary, contactOutput, options.overwrite)
            RecordingResult(output.toString(), options.fps, frameCount, (frameCount * 1000.0 / options.fps).toLong(),
                width, height, contactOutput?.toString(), selected)
        } catch (error: Throwable) {
            encoder?.abort()
            throw error
        } finally {
            cursor.stop()
            scope.cancel()
            Files.deleteIfExists(temporary)
            contactTemporary?.let { Files.deleteIfExists(it) }
            synchronized(queueLock) { frames.clear() }
        }
    }

    suspend fun abort() = withContext(NonCancellable + Dispatchers.IO) {
        stopping.set(true)
        capture?.cancel(); writer?.cancel()
        encoder?.abort()
        withTimeoutOrNull(3_000) { capture?.join(); writer?.join() }
        cursor.stop(); scope.cancel()
        Files.deleteIfExists(temporary)
        contactTemporary?.let { Files.deleteIfExists(it) }
    }

    companion object {
        private const val FIRST_FRAME_TIMEOUT_MS = 10_000L

        suspend fun start(source: Flow<ScreencastFrame>, options: RecordingOptions, cursor: RecordingCursorTimeline): CdpVideoRecorder =
            withContext(Dispatchers.IO) {
                FfmpegEncoder.requireAvailable(options.ffmpegExecutable)
                val output = Path.of(options.path).toAbsolutePath().normalize()
                val contact = options.contactSheetPath?.let { Path.of(it).toAbsolutePath().normalize() }
                require(output != contact) { "Video and contact sheet paths must differ" }
                for (path in listOfNotNull(output, contact)) {
                    if (Files.exists(path) && !options.overwrite) throw KmpwrightException("Output already exists: $path")
                    Files.createDirectories(path.parent)
                }
                val extension = output.fileName.toString().substringAfterLast('.').lowercase()
                val temporary = Files.createTempFile(output.parent, ".kmpwright-recording-", ".$extension")
                var contactTemporary: Path? = null
                try {
                    FfmpegEncoder.probe(options.ffmpegExecutable, temporary, options.fps)
                    contactTemporary = contact?.let { Files.createTempFile(it.parent, ".kmpwright-contact-", ".png") }
                    CdpVideoRecorder(options, cursor, output, temporary, contact, contactTemporary).also { it.begin(source) }
                } catch (error: Throwable) {
                    Files.deleteIfExists(temporary)
                    contactTemporary?.let { Files.deleteIfExists(it) }
                    throw error
                }
            }

        private fun publish(temporary: Path, destination: Path, overwrite: Boolean) {
            if (overwrite) Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            else {
                // An atomic link creation preserves files created by another caller during recording.
                Files.createLink(destination, temporary)
                Files.delete(temporary)
            }
        }
    }
}
