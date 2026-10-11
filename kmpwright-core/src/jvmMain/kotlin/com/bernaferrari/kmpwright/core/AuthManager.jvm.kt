package com.bernaferrari.kmpwright.core

internal actual fun authProfileDir(): String {
    val home = System.getProperty("user.home") ?: "/tmp"
    return "$home/.kmpwright/auth"
}
