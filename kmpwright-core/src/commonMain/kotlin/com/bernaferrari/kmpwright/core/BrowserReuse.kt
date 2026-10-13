package com.bernaferrari.kmpwright.core

import kotlin.coroutines.cancellation.CancellationException

/** A [BrowserFarmSlot] that leases [connect]'s browser to one owner at a time. */
public fun browserFarmSlot(
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

/** Closes every page but one and resets it with [resetPageForReuse]. */
@Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
public suspend fun resetBrowserForReuse(browser: Browser) {
    val pages = browser.pages()
    val primary = pages.lastOrNull() ?: browser.newPage()

    pages.dropLast(1).forEach { page ->
        page.close()
    }

    resetPageForReuse(primary)
}

/** Clears the active origin for same-owner reuse; does not establish tenant isolation. */
@Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
public suspend fun resetPageForReuse(page: Page) {
    page.unrouteAll()
    page.setAllowedDomains(emptyList())
    page.setHTTPCredentials(null)
    page.setExtraHTTPHeaders(emptyMap())
    page.clearCookies()
    page.localStorage.clear()
    page.sessionStorage.clear()

    val navigation = page.goto("about:blank")
    if (!navigation.committed) {
        throw KmpwrightException(
            "Failed to navigate page to about:blank during reset: ${navigation.error ?: "unknown error"}"
        )
    }
}
