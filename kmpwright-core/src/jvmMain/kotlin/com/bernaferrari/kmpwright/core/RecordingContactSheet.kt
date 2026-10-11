package com.bernaferrari.kmpwright.core

import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.nio.file.Path
import java.util.Locale
import javax.imageio.ImageIO

/** Keep clean, reduced frames; the live cursor must never trigger change selection. */
internal class RecordingContactSheet(private val threshold: Double, private val capacity: Int) {
    private data class Selected(val info: RecordingContactFrame, val image: BufferedImage)
    private val selected = mutableListOf<Selected>()
    private var previous: BufferedImage? = null
    private var latest: Selected? = null

    fun observe(image: BufferedImage, index: Long, timestampMs: Long) {
        val reduced = resize(image, 640)
        val entry = Selected(RecordingContactFrame(timestampMs, index), reduced)
        latest = entry
        val old = previous
        if (old == null || changedRatio(old, reduced) >= threshold) {
            add(entry)
            previous = reduced
        }
    }

    private fun add(frame: Selected) {
        if (selected.lastOrNull()?.info?.index == frame.info.index) return
        if (selected.size == capacity) selected[selected.lastIndex] = frame else selected += frame
    }

    fun finish(path: Path): List<RecordingContactFrame> {
        latest?.let(::add)
        check(selected.isNotEmpty()) { "No contact-sheet frames" }
        val cellWidth = 640
        val cellHeight = selected.maxOf { it.image.height * cellWidth / it.image.width }
        val columns = minOf(4, selected.size)
        val gap = 8
        val rows = (selected.size + columns - 1) / columns
        val sheet = BufferedImage(columns * (cellWidth + gap) + gap, rows * (cellHeight + 24 + gap) + gap, BufferedImage.TYPE_INT_RGB)
        val graphics = sheet.createGraphics()
        try {
            graphics.color = Color(24, 24, 24); graphics.fillRect(0, 0, sheet.width, sheet.height)
            graphics.font = Font(Font.SANS_SERIF, Font.PLAIN, 16)
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            selected.forEachIndexed { index, frame ->
                val x = gap + (index % columns) * (cellWidth + gap)
                val y = gap + (index / columns) * (cellHeight + 24 + gap)
                graphics.drawImage(frame.image, x, y, cellWidth, frame.image.height * cellWidth / frame.image.width, null)
                graphics.color = Color.WHITE
                graphics.drawString(String.format(Locale.ROOT, "%.2f s · frame %d", frame.info.timestampMs / 1000.0, frame.info.index), x + 4, y + cellHeight + 18)
            }
        } finally { graphics.dispose() }
        check(ImageIO.write(sheet, "png", path.toFile())) { "PNG writer is unavailable" }
        return selected.map { it.info }
    }

    private fun resize(image: BufferedImage, width: Int): BufferedImage {
        val result = BufferedImage(width, width * 9 / 16, BufferedImage.TYPE_INT_RGB)
        val scale = minOf(result.width.toDouble()/image.width, result.height.toDouble()/image.height)
        val drawWidth = maxOf(1, (image.width * scale).toInt())
        val drawHeight = maxOf(1, (image.height * scale).toInt())
        val g = result.createGraphics()
        try { g.drawImage(image, (width-drawWidth)/2, (result.height-drawHeight)/2, drawWidth, drawHeight, null) }
        finally { g.dispose() }
        return result
    }

    private fun changedRatio(old: BufferedImage, next: BufferedImage): Double {
        if (old.width != next.width || old.height != next.height) return 1.0
        var changed = 0
        for (y in 0 until next.height) for (x in 0 until next.width) {
            val a = old.getRGB(x, y); val b = next.getRGB(x, y)
            if (kotlin.math.abs((a and 255) - (b and 255)) > 32 ||
                kotlin.math.abs(((a shr 8) and 255) - ((b shr 8) and 255)) > 32 ||
                kotlin.math.abs(((a shr 16) and 255) - ((b shr 16) and 255)) > 32) changed++
        }
        return changed.toDouble() / (next.width * next.height)
    }
}
