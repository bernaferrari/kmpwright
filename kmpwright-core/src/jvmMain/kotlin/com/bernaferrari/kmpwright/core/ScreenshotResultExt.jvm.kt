package com.bernaferrari.kmpwright.core

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

actual fun ScreenshotResult.toRgbaImage(): RgbaImage? {
    if (bytes.isEmpty()) return null
    return try {
        val image = ImageIO.read(ByteArrayInputStream(bytes)) ?: return null
        val width = image.width
        val height = image.height
        val pixels = ByteArray(width * height * 4)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val argb = image.getRGB(x, y)
                val idx = (y * width + x) * 4
                pixels[idx] = ((argb shr 16) and 0xFF).toByte()     // R
                pixels[idx + 1] = ((argb shr 8) and 0xFF).toByte()  // G
                pixels[idx + 2] = (argb and 0xFF).toByte()          // B
                pixels[idx + 3] = ((argb shr 24) and 0xFF).toByte() // A
            }
        }
        RgbaImage(width, height, pixels)
    } catch (e: Exception) {
        null
    }
}
