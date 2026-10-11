package com.bernaferrari.kmpwright.core

import kotlin.io.encoding.Base64
import kotlin.test.*

class IOSImageDecodeTest {
    @Test
    fun screenshots_decode_to_straight_rgba_in_top_to_bottom_order() {
        val bytes = Base64.decode("iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAYAAABytg0kAAAAEklEQVR4nGP4z8DwHwyBNBgAAEnICff5q7YNAAAAAElFTkSuQmCC")
        val image = assertNotNull(ScreenshotResult(bytes, 99, 99).toRgbaImage())
        assertEquals(2, image.width)
        assertEquals(2, image.height)
        assertContentEquals(byteArrayOf(
            -1, 0, 0, -1, 0, -1, 0, -1,
            0, 0, -1, -1, -1, -1, -1, -1
        ), image.pixels)
        assertNull(ScreenshotResult(byteArrayOf(1, 2, 3), 1, 1).toRgbaImage())
    }
    @Test
    fun decoded_channels_are_not_premultiplied_by_alpha() {
        val bytes = Base64.decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGP4z8DQAAAEgQGALFXOsAAAAABJRU5ErkJggg==")
        val image = assertNotNull(ScreenshotResult(bytes, 1, 1).toRgbaImage())
        assertContentEquals(byteArrayOf(-1, 0, 0, -128), image.pixels)
    }
}
