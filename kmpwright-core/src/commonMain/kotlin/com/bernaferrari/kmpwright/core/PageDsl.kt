package com.bernaferrari.kmpwright.core

import kotlin.coroutines.cancellation.CancellationException

/**
 * Creates a new page, navigates to [url], runs [block] with the page as receiver and closes the page
 * afterwards, even when [block] throws.
 *
 * ```kotlin
 * browser.use("https://example.com") {
 *     click("button.submit")
 *     waitForText("Welcome")
 * }
 * ```
 */
@Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
public suspend fun Browser.use(url: String, block: suspend Page.() -> Unit) {
    val page = newPage()
    try {
        page.goto(url)
        page.block()
    } finally {
        page.close()
    }
}

/** Runs [block] with the default page as receiver; the page is neither created nor closed. */
@Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
public suspend fun Browser.use(block: suspend Page.() -> Unit) {
    page().block()
}

// --- Tab convenience (agent-browser `tab`) ---

/** Open pages with their index and URL (agent-browser `tab list`). */
public fun Browser.tabList(): List<TabInfo> = pages().mapIndexed { index, page ->
    TabInfo(index, page.url.value ?: "about:blank", page)
}

/**
 * The page at [index] (agent-browser `tab <n>`).
 * @throws KmpwrightException when [index] is out of range.
 */
@Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class)
public fun Browser.switchToTab(index: Int): Page {
    val pages = pages()
    if (index < 0 || index >= pages.size) {
        throw KmpwrightException("Tab index $index out of range (0..${pages.size - 1})")
    }
    return pages[index]
}

/**
 * Closes the page at [index] (agent-browser `tab close <n>`).
 * @throws KmpwrightException when [index] is out of range.
 */
@Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
public suspend fun Browser.closeTab(index: Int) {
    switchToTab(index).close()
}

/** An open tab reported by [tabList]. */
public data class TabInfo(
    val index: Int,
    val url: String,
    val page: Page
)
