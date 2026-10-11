package com.bernaferrari.kmpwright.core

internal fun mouseButtonCode(button: MouseButton): Int = when (button) {
    MouseButton.LEFT -> 0
    MouseButton.MIDDLE -> 1
    MouseButton.RIGHT -> 2
}

internal fun mouseButtonsBitfield(buttons: Set<MouseButton>): Int {
    var mask = 0
    if (MouseButton.LEFT in buttons) mask = mask or 1
    if (MouseButton.RIGHT in buttons) mask = mask or 2
    if (MouseButton.MIDDLE in buttons) mask = mask or 4
    return mask
}
