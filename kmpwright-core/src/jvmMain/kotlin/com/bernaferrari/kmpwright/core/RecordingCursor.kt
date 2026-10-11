package com.bernaferrari.kmpwright.core

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics2D
import java.awt.geom.Path2D
import kotlinx.serialization.json.*

internal class RecordingCursorTimeline {
    data class Sample(val timestampMs: Double, val x: Double, val y: Double, val buttons: Int, val visible: Boolean = true)
    private var enabled = false
    private val samples = ArrayDeque<Sample>()
    @Synchronized fun start() { samples.clear(); enabled = true }
    @Synchronized fun stop() { enabled = false; samples.clear() }
    @Synchronized fun record(params: Map<String, Any?>) {
        if (!enabled || params["type"] == "mouseWheel") return
        val x = (params["x"] as? Number)?.toDouble() ?: return
        val y = (params["y"] as? Number)?.toDouble() ?: return
        val now = currentTimeMillis().toDouble()
        samples.addLast(Sample(maxOf(now, samples.lastOrNull()?.timestampMs ?: now), x, y,
            (params["buttons"] as? Number)?.toInt() ?: 0, params["visible"] != false))
        while (samples.size > 2048 || (samples.size > 2 && now - samples.first().timestampMs > 10_000)) samples.removeFirst()
    }
    @Synchronized fun at(timestampMs: Double): Sample? = samples.lastOrNull { it.timestampMs <= timestampMs }
}

/** Cursor pixels are added after contact-sheet change detection, never to the page DOM. */
internal fun drawRecordingCursor(graphics: Graphics2D, sample: RecordingCursorTimeline.Sample?,
    viewportWidth: Double, viewportHeight: Double, width: Int, height: Int, timestampMs: Double) {
    if (sample == null || !sample.visible || viewportWidth <= 0 || viewportHeight <= 0) return
    val scaleX = width / viewportWidth
    val scaleY = height / viewportHeight
    val g = graphics.create() as Graphics2D
    try {
        g.translate(sample.x * scaleX, sample.y * scaleY)
        g.scale(scaleX, scaleY)
        if (sample.buttons != 0 && timestampMs - sample.timestampMs in 0.0..400.0) {
            val progress = (timestampMs - sample.timestampMs) / 400.0
            val radius = 4 + 28 * progress
            g.color = Color(96, 165, 250, ((1-progress)*100).toInt().coerceIn(0,255))
            g.fillOval((-radius).toInt(), (-radius).toInt(), (radius*2).toInt(), (radius*2).toInt())
        }
        if (sample.buttons != 0) g.scale(0.8, 0.8)
        val pointer = Path2D.Double().apply { moveTo(0.0,0.0); lineTo(14.0,8.5); lineTo(7.5,10.0); lineTo(4.0,16.0); closePath() }
        g.color = Color.WHITE; g.fill(pointer)
        g.color = Color.BLACK; g.stroke = BasicStroke(1.5f); g.draw(pointer)
    } finally { g.dispose() }
}
