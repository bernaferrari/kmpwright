package com.bernaferrari.kmpwright.core

/**
 * DSL entry points for ergonomic page interactions.
 *
 * Usage:
 * ```kotlin
 * browser.use("https://example.com") {
 *     click("button.submit")
 *     fill("input.email", "user@test.com")
 *     waitForText("Welcome")
 * }
 *
 * browser.use {
 *     click("button.submit")
 * }
 *
 * page.on("https://example.com") {
 *     click("button.submit")
 * }
 * ```
 */

/**
 * Creates a new page, navigates to [url], runs [block] with the page as receiver,
 * then closes the page. Cleanup is guaranteed via try/finally.
 */
suspend fun Browser.use(url: String, block: suspend Page.() -> Unit) {
    val page = newPage()
    try {
        page.goto(url)
        page.block()
    } finally {
        closePage(page)
    }
}

/**
 * Reuses the default page and runs [block] with it as receiver.
 * No lifecycle management — the page is not created or closed.
 */
suspend fun Browser.use(block: suspend Page.() -> Unit) {
    page().block()
}

/**
 * Navigates an existing page to [url], then runs [block] with the page as receiver.
 */
suspend fun Page.on(url: String, block: suspend Page.() -> Unit) {
    goto(url)
    block()
}

// --- Tab convenience ---

/**
 * Returns all open pages with their index and current URL.
 * Mirrors agent-browser's `tab list`.
 */
fun Browser.tabList(): List<TabInfo> = pages().mapIndexed { index, page ->
    TabInfo(index, page.url.value ?: "about:blank", page)
}

/**
 * Switches to the tab at [index] and returns it.
 * Mirrors agent-browser's `tab <n>`.
 * @throws IndexOutOfBoundsException if index is out of range.
 */
fun Browser.switchToTab(index: Int): Page {
    val pages = pages()
    if (index < 0 || index >= pages.size) {
        throw KmpwrightException("Tab index $index out of range (0..${pages.size - 1})")
    }
    return pages[index]
}

/**
 * Closes the tab at [index]. Mirrors agent-browser's `tab close <n>`.
 * @throws IndexOutOfBoundsException if index is out of range.
 */
fun Browser.closeTab(index: Int) {
    val pages = pages()
    if (index < 0 || index >= pages.size) {
        throw KmpwrightException("Tab index $index out of range (0..${pages.size - 1})")
    }
    closePage(pages[index])
}

/**
 * Information about an open tab.
 */
data class TabInfo(
    val index: Int,
    val url: String,
    val page: Page
)
