package com.bernaferrari.kmpwright.core

interface PageTabRuntime {
    suspend fun openTab(source: Page, url: String?): NavigationResult
    suspend fun closeTab(page: Page): Boolean

    /**
     * Notifies the owning tab registry when a page closes itself.  Browser
     * implementations can remove their strong page reference here; the
     * default keeps custom runtimes source-compatible.
     */
    fun pageClosed(page: Page) {}
}
