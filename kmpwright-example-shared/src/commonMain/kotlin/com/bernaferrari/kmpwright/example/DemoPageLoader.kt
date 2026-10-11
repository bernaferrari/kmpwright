package com.bernaferrari.kmpwright.example.shared

import com.bernaferrari.kmpwright.core.LoadState
import com.bernaferrari.kmpwright.core.Page
import com.bernaferrari.kmpwright.core.SetContentOptions

suspend fun loadDemoPage(
    page: Page,
    html: String,
    zoom: Double? = null
) {
    page.setContent(html, SetContentOptions(waitUntil = LoadState.DOMContentLoaded))
    if (zoom != null) {
        page.evaluate("document.body.style.zoom = '$zoom'")
    }
    page.evaluate("window.scrollTo(0, 0)")
}
