package com.bernaferrari.kmpwright.core

import android.graphics.BitmapFactory

actual fun ScreenshotResult.toRgbaImage(): RgbaImage? {
    if (bytes.isEmpty()) return null
    return try {
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val width = bitmap.width
        val height = bitmap.height
        val pixels = ByteArray(width * height * 4)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val argb = bitmap.getPixel(x, y)
                val idx = (y * width + x) * 4
                pixels[idx] = ((argb shr 16) and 0xFF).toByte()     // R
                pixels[idx + 1] = ((argb shr 8) and 0xFF).toByte()  // G
                pixels[idx + 2] = (argb and 0xFF).toByte()          // B
                pixels[idx + 3] = ((argb shr 24) and 0xFF).toByte() // A
            }
        }
        bitmap.recycle()
        RgbaImage(width, height, pixels)
    } catch (e: Exception) {
        null
    }
}
