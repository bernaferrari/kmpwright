package com.bernaferrari.kmpwright.core

import platform.Foundation.NSHomeDirectory

internal actual fun authProfileDir(): String {
    return "${NSHomeDirectory()}/.kmpwright/auth"
}
