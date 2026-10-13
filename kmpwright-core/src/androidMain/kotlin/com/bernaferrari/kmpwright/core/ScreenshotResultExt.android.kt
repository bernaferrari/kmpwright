package com.bernaferrari.kmpwright.core

import android.graphics.BitmapFactory

public actual fun ScreenshotResult.toRgbaImage(): RgbaImage? {
    if (bytes.isEmpty()) return null
    val bitmap = try {
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    } catch (_: Exception) {
        null
    } ?: return null
    return try {
        val width = bitmap.width
        val height = bitmap.height
        val argb = IntArray(width * height)
        bitmap.getPixels(argb, 0, width, 0, 0, width, height)
        val pixels = ByteArray(argb.size * 4)
        for (index in argb.indices) {
            val color = argb[index]
            val offset = index * 4
            pixels[offset] = (color shr 16).toByte()     // R
            pixels[offset + 1] = (color shr 8).toByte()  // G
            pixels[offset + 2] = color.toByte()          // B
            pixels[offset + 3] = (color ushr 24).toByte() // A
        }
        RgbaImage(width, height, pixels)
    } catch (_: Exception) {
        null
    } finally {
        bitmap.recycle()
    }
}
