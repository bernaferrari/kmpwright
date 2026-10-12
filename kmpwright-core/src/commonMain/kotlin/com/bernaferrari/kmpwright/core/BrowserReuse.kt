package com.bernaferrari.kmpwright.core

fun browserFarmSlot(
    id: String,
    metadata: Map<String, String> = emptyMap(),
    connect: suspend () -> Browser,
    prepare: suspend (Browser) -> Unit = {},
    close: suspend (Browser) -> Unit = { it.close() }
): BrowserFarmSlot {
    return PooledResource(
        id = id,
        metadata = metadata,
        connect = connect,
        prepare = { browser -> browser.beginIndependentLease(); prepare(browser) },
        cleanup = { browser -> browser.endIndependentLease() },
        close = close
    )
}

suspend fun resetBrowserForReuse(browser: Browser) {
    val pages = browser.pages()
    val primary = pages.lastOrNull() ?: browser.newPage()

    pages.dropLast(1).forEach { page ->
        browser.closePage(page)
    }

    resetPageForReuse(primary)
}

/** Clears the active origin for same-owner reuse; does not establish tenant isolation. */
suspend fun resetPageForReuse(page: Page) {
    page.unroute()
    page.setDomainFilter(emptySet())
    page.clearHTTPCredentials()
    page.setExtraHTTPHeaders(emptyMap())
    page.deleteAllCookies()
    page.clearLocalStorage()
    page.clearSessionStorage()

    val navigation = page.goto("about:blank")
    if (!navigation.ok) {
        throw KmpwrightException(
            "Failed to navigate page to about:blank during reset: ${navigation.error ?: "unknown error"}"
        )
    }
}
