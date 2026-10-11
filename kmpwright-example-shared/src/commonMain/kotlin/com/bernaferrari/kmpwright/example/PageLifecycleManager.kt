package com.bernaferrari.kmpwright.example.shared

import androidx.compose.runtime.staticCompositionLocalOf
import com.bernaferrari.kmpwright.core.Browser

internal class CloseOnce(private val closeAction: () -> Unit) {
    private var closed = false

    fun close() {
        if (closed) return
        closed = true
        closeAction()
    }
}

class PageLifecycleManager(private val browser: Browser) {
    val page = browser.page()
    private val closeOnce = CloseOnce(browser::close)

    fun close() = closeOnce.close()
}

val LocalPageManager = staticCompositionLocalOf<PageLifecycleManager> {
    error("LocalPageManager not provided")
}
