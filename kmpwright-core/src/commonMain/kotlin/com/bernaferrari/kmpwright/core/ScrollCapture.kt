package com.bernaferrari.kmpwright.core

/** Coordinates used to crop a tile must still describe the frame acquired asynchronously. */
internal suspend fun <T> captureAtScroll(
    expected: Pair<Int, Int>,
    current: () -> Pair<Int, Int>,
    capture: suspend () -> T,
    discard: (T) -> Unit
): T {
    if (current() != expected) throw KmpwrightException("The page scrolled while taking its screenshot")
    val frame = capture()
    try {
        if (current() != expected) throw KmpwrightException("The page scrolled while taking its screenshot")
        return frame
    } catch (error: Throwable) {
        discard(frame)
        throw error
    }
}
