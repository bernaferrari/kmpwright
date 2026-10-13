package com.bernaferrari.kmpwright.core

internal interface PageTabRuntime {
    suspend fun openTab(source: Page, url: String?): NavigationResult

    /**
     * Notifies the owning tab registry when a page closes itself.  Browser
     * implementations can remove their strong page reference here; the
     * default keeps custom runtimes source-compatible.
     */
    fun pageClosed(page: Page) {}
}
