package com.bernaferrari.kmpwright.core

internal actual fun authProfileDir(): String {
    val home = System.getProperty("user.home") ?: "/data/local/tmp"
    return "$home/.kmpwright/auth"
}
