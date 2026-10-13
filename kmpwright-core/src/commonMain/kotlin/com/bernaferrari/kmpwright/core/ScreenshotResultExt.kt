package com.bernaferrari.kmpwright.core

/** Decodes the screenshot into RGBA pixels for [VisualDiff], or null when the format cannot be decoded. */
public expect fun ScreenshotResult.toRgbaImage(): RgbaImage?
