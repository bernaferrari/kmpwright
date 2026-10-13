package com.bernaferrari.kmpwright.core

/** Auth profiles live with session state under Application Support (see [platformHomeDir]). */
internal actual fun authProfileDir(): String {
    return "${platformHomeDir()}/.kmpwright/auth"
}
