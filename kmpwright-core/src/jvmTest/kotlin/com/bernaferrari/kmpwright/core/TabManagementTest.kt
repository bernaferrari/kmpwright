package com.bernaferrari.kmpwright.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TabManagementTest {

    private lateinit var runtime: RecordingTabRuntime
    private lateinit var page: TestPage

    @Before
    fun setup() {
        runtime = RecordingTabRuntime()
        page = TestPage(MockWebViewController(), runtime)
    }

    @Test
    fun `openNewTab delegates to runtime`() {
        runBlocking {
            page.openNewTab()
            assertEquals(page, runtime.openSource)
            assertEquals(null, runtime.openUrl)
        }
    }

    @Test
    fun `openNewTab with URL forwards specified URL`() {
        runBlocking {
            page.openNewTab("https://example.com")
            assertEquals("https://example.com", runtime.openUrl)
        }
    }

    @Test
    fun `openNewTab returns runtime navigation result`() {
        runBlocking {
            val result = page.openNewTab("https://example.com")
            assertEquals("https://example.com", result.url)
            assertTrue(result.ok)
        }
    }

    private class RecordingTabRuntime : PageTabRuntime {
        var openSource: Page? = null
        var openUrl: String? = null
        var closedPage: Page? = null
        var closeResult: Boolean = true

        override suspend fun openTab(source: Page, url: String?): NavigationResult {
            openSource = source
            openUrl = url
            return NavigationResult(url ?: "about:blank", 200, true)
        }

        override fun pageClosed(page: Page) {
            closedPage = page
        }
    }
}
