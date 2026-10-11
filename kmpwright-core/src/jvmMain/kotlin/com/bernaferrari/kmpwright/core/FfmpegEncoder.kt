package com.bernaferrari.kmpwright.core

import kotlinx.coroutines.*
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Own the process, drain diagnostics continuously, and bound blocked pipe writes. */
internal class FfmpegEncoder private constructor(private val process: Process, private val scope: CoroutineScope) {
    private val writingSince = AtomicLong(0)
    private val failure = AtomicReference<String?>(null)
    private val stderrLock = Any()
    private var stderrTail = ""
    private val diagnostics = scope.launch(Dispatchers.IO) {
        try {
            process.errorStream.bufferedReader().use { reader ->
                val buffer = CharArray(1024)
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    synchronized(stderrLock) { stderrTail = (stderrTail + String(buffer, 0, count)).takeLast(4096) }
                }
            }
        } catch (_: IOException) { }
    }
    private val watchdog = scope.launch(Dispatchers.IO) {
        while (isActive) {
            delay(100)
            val started = writingSince.get()
            if (started != 0L && System.nanoTime() - started > 2_000_000_000L) {
                failure.compareAndSet(null, "Encoder did not consume a frame within two seconds")
                process.destroyForcibly()
                return@launch
            }
        }
    }

    fun write(frame: ByteArray) {
        failure.get()?.let { throw KmpwrightException(it) }
        writingSince.set(System.nanoTime())
        try { process.outputStream.write(frame) }
        catch (error: IOException) { throw KmpwrightException("Video encoder failed: ${diagnostic().ifEmpty { error.message.orEmpty() }}") }
        finally { writingSince.set(0) }
    }

    suspend fun finish() = withContext(Dispatchers.IO) {
        writingSince.set(System.nanoTime())
        try { process.outputStream.close() }
        catch (error: IOException) { failure.compareAndSet(null, "Encoder input could not close: ${error.message}") }
        finally { writingSince.set(0); watchdog.cancel() }
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw KmpwrightException("Video encoder did not finish within five seconds")
        }
        withTimeoutOrNull(1_000) { diagnostics.join() }
        failure.get()?.let { throw KmpwrightException(it) }
        if (process.exitValue() != 0) throw KmpwrightException("Video encoder failed: ${diagnostic()}")
    }

    suspend fun abort() = withContext(NonCancellable + Dispatchers.IO) {
        process.destroyForcibly()
        runCatching { process.outputStream.close() }
        process.waitFor(2, TimeUnit.SECONDS)
        watchdog.cancel()
        withTimeoutOrNull(1_000) { diagnostics.join() }
        diagnostics.cancel()
    }

    private fun diagnostic(): String = synchronized(stderrLock) { stderrTail.trim().takeLast(400) }

    companion object {
        suspend fun requireAvailable(executable: String) = withContext(Dispatchers.IO) {
            val process = try { ProcessBuilder(executable, "-version").redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start() }
            catch (error: IOException) { throw KmpwrightException("ffmpeg is required before recording: ${error.message}") }
            try {
                if (!process.waitFor(3, TimeUnit.SECONDS) || process.exitValue() != 0) {
                    throw KmpwrightException("ffmpeg preflight failed")
                }
            } finally { if (process.isAlive) process.destroyForcibly() }
        }

        suspend fun probe(executable: String, path: Path, fps: Int) = coroutineScope {
            val encoder = start(executable, path, 16, 16, fps, this)
            try { encoder.write(ByteArray(16 * 16 * 3)); encoder.finish() }
            finally { encoder.abort() }
        }

        fun start(executable: String, path: Path, width: Int, height: Int, fps: Int, scope: CoroutineScope): FfmpegEncoder {
            val webm = path.toString().endsWith(".webm", true)
            val codec = if (webm) listOf("-c:v", "libvpx", "-deadline", "realtime", "-cpu-used", "6", "-b:v", "8000k")
                else listOf("-c:v", "libx264", "-preset", "veryfast", "-crf", "20", "-pix_fmt", "yuv420p")
            val args = listOf(executable, "-hide_banner", "-loglevel", "error", "-y", "-f", "rawvideo",
                "-pixel_format", "bgr24", "-video_size", "${width}x${height}", "-framerate", fps.toString(),
                "-i", "pipe:0", "-an") + codec + listOf("-threads", if (fps > 30) "4" else "2") +
                (if (path.toString().endsWith(".mp4", true)) listOf("-movflags", "+faststart") else emptyList()) + path.toString()
            val process = try { ProcessBuilder(args).redirectOutput(ProcessBuilder.Redirect.DISCARD).start() }
                catch (error: IOException) { throw KmpwrightException("Could not start ffmpeg: ${error.message}") }
            return FfmpegEncoder(process, scope)
        }
    }
}
