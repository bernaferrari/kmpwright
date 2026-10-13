package com.bernaferrari.kmpwright.core

internal actual fun authProfileDir(): String =
    java.io.File(AndroidAppStorage.requireFilesDir(), "kmpwright/auth").absolutePath
