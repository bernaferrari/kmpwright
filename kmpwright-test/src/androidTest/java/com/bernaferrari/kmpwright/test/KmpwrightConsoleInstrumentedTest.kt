package com.bernaferrari.kmpwright.test

import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bernaferrari.kmpwright.core.AndroidPlatformContext
import com.bernaferrari.kmpwright.core.Browser
import com.bernaferrari.kmpwright.core.Page
import com.bernaferrari.kmpwright.core.PageError
import com.bernaferrari.kmpwright.core.PageMessageFilter
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/** Console messages and page errors from a real Android WebView (Playwright `console` / `pageerror`). */
@RunWith(AndroidJUnit4::class)
class KmpwrightConsoleInstrumentedTest {
    @get:Rule
    val host = ActivityScenarioRule(WebViewHarnessActivity::class.java)

    private lateinit var browser: Browser
    private lateinit var page: Page
    /** Every message the host app's own WebChromeClient received. */
    private val hostMessages = CopyOnWriteArrayList<String>()

    @Before
    fun setUp() {
        host.scenario.onActivity { activity ->
            val webView = activity.resetWebView()
            webView.webChromeClient = object : android.webkit.WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage?): Boolean {
                    consoleMessage?.message()?.let(hostMessages::add)
                    return true
                }
            }
            browser = Browser(AndroidPlatformContext(activity, webView))
            page = browser.page()
        }
    }

    @After
    fun tearDown() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            if (this::browser.isInitialized) browser.dispose()
        }
    }

    private suspend fun <T> eventually(read: () -> T, done: (T) -> Boolean): T = withTimeout(10_000) {
        var value = read()
        while (!done(value)) { delay(25); value = read() }
        value
    }

    @Test
    fun consoleMessagesLoggedWhileLoadingAreCapturedWithTypes() = runBlocking<Unit>(Dispatchers.Main) {
        val first = async(start = CoroutineStart.UNDISPATCHED) { page.events.console.first() }
        page.setContent("<script>console.log('loaded', 42); console.warn('careful'); console.error('logged error'); console.info('fyi')</script>")
        val messages = eventually({ page.consoleMessages() }) { it.size >= 4 }
        assertEquals(listOf("log", "warning", "error"), messages.map { it.type }.take(3))
        // WebView reports console.info at LOG level (TIP maps to "info").
        assertTrue(messages[3].type in setOf("log", "info"))
        assertEquals("fyi", messages[3].text)
        assertEquals("loaded 42", messages[0].text)
        assertEquals("careful", messages[1].text)
        assertEquals("logged error", messages[2].text)
        assertEquals("loaded 42", first.await().text)
        // console.error is a console message, not a page error.
        assertTrue(page.pageErrors().toString(), page.pageErrors().isEmpty())
    }

    @Test
    fun uncaughtErrorsAndRejectionsArePageErrorsWithStacks() = runBlocking<Unit>(Dispatchers.Main) {
        val firstError = async(start = CoroutineStart.UNDISPATCHED) { page.events.pageError.first() }
        page.setContent("""<script>
            setTimeout(function() { throw new TypeError('kaboom'); }, 0);
            setTimeout(function() { Promise.reject(new Error('rejected')); }, 20);
            </script>""")
        val errors = eventually({ page.pageErrors() }) { it.size >= 2 }
        assertEquals(listOf("TypeError: kaboom", "Error: rejected"), errors)
        val structured: PageError = firstError.await()
        assertTrue(structured.stack.orEmpty(), structured.stack.orEmpty().contains("kaboom"))
        // Chromium's own "Uncaught ..." console reports are not console messages (Playwright semantics) ...
        assertTrue(page.consoleMessages().toString(), page.consoleMessages().none { it.text.startsWith("Uncaught") })
        // ... but the host app still sees them, and never sees KMPwright's internal sentinel traffic.
        eventually({ hostMessages.toList() }) { list -> list.count { it.startsWith("Uncaught") } >= 2 }
        assertFalse(hostMessages.toString(), hostMessages.any { it.contains("\"kind\"") || it.contains("nonce") })
    }

    @Test
    fun hostClientStillReceivesRealConsoleMessages() = runBlocking<Unit>(Dispatchers.Main) {
        page.setContent("<script>console.log('for the host')</script>")
        eventually({ page.consoleMessages() }) { it.isNotEmpty() }
        eventually({ hostMessages.toList() }) { "for the host" in it }
        assertFalse(hostMessages.toString(), hostMessages.any { it.contains("\"kind\"") || it.contains("nonce") })
    }

    @Test
    fun historySurvivesNavigationButTheDefaultViewStartsAtTheLastDocument() = runBlocking<Unit>(Dispatchers.Main) {
        page.setContent("<script>console.log('first document'); setTimeout(function() { throw new Error('first') }, 0)</script>")
        eventually({ page.pageErrors() }) { it.isNotEmpty() }
        page.setContent("<script>console.log('second document')</script>")
        eventually({ page.consoleMessages() }) { list -> list.any { it.text == "second document" } }
        assertEquals(listOf("second document"), page.consoleMessages().map { it.text })
        assertEquals(listOf("first document", "second document"), page.consoleMessages(PageMessageFilter.All).map { it.text })
        assertTrue(page.pageErrors().isEmpty())
        assertEquals(listOf("Error: first"), page.pageErrors(PageMessageFilter.All))
    }

    @Test
    fun iframeConsoleOutputIsCaptured() = runBlocking<Unit>(Dispatchers.Main) {
        page.setContent("<iframe srcdoc=\"<script>console.log('from frame')</script>\"></iframe>")
        eventually<List<String>>({ page.consoleMessages().map { it.text } }) { "from frame" in it }
    }
}
