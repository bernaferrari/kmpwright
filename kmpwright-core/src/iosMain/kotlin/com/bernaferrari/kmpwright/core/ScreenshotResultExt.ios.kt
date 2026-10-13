package com.bernaferrari.kmpwright.core

import com.bernaferrari.kmpwright.core.ios.toNSData
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.CoreGraphics.*
import platform.UIKit.UIImage

@OptIn(ExperimentalForeignApi::class)
public actual fun ScreenshotResult.toRgbaImage(): RgbaImage? {
    if (bytes.isEmpty()) return null
    val image = UIImage.imageWithData(bytes.toNSData()) ?: return null
    val cgImage = CGImageRetain(image.CGImage ?: return null) ?: return null
    try {
        val imageWidth = CGImageGetWidth(cgImage).toLong()
        val imageHeight = CGImageGetHeight(cgImage).toLong()
        if (imageWidth <= 0 || imageHeight <= 0 || imageWidth > 32_000_000 ||
            imageHeight > 32_000_000 || imageWidth * imageHeight > 32_000_000) return null
        val pixels = ByteArray((imageWidth * imageHeight * 4).toInt())
        val colorSpace = CGColorSpaceCreateDeviceRGB() ?: return null
        try {
            val decoded = pixels.usePinned { pinned ->
                val context = CGBitmapContextCreate(
                    pinned.addressOf(0), imageWidth.toULong(), imageHeight.toULong(),
                    8u, (imageWidth * 4).toULong(), colorSpace,
                    CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value or kCGBitmapByteOrder32Big
                ) ?: return@usePinned false
                try {
                    CGContextDrawImage(context, CGRectMake(0.0, 0.0, imageWidth.toDouble(), imageHeight.toDouble()), cgImage)
                } finally { CGContextRelease(context) }
                true
            }
            if (!decoded) return null
        } finally { CGColorSpaceRelease(colorSpace) }
        // CoreGraphics writes premultiplied channels. The shared diff API uses
        // straight RGBA, matching Android Bitmap.getPixel and JVM ImageIO.
        for (offset in pixels.indices step 4) {
            val alpha = pixels[offset + 3].toInt() and 255
            if (alpha in 1..254) {
                for (channel in 0..2) {
                    val value = pixels[offset + channel].toInt() and 255
                    pixels[offset + channel] = ((value * 255 + alpha / 2) / alpha).coerceAtMost(255).toByte()
                }
            }
        }
        return RgbaImage(imageWidth.toInt(), imageHeight.toInt(), pixels)
    } finally { CGImageRelease(cgImage) }
}
