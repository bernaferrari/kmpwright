package com.bernaferrari.kmpwright.core

internal fun mouseButtonCode(button: MouseButton): Int = when (button) {
    MouseButton.Left -> 0
    MouseButton.Middle -> 1
    MouseButton.Right -> 2
}

internal fun mouseButtonsBitfield(buttons: Set<MouseButton>): Int {
    var mask = 0
    if (MouseButton.Left in buttons) mask = mask or 1
    if (MouseButton.Right in buttons) mask = mask or 2
    if (MouseButton.Middle in buttons) mask = mask or 4
    return mask
}
