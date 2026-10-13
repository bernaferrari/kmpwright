package com.bernaferrari.kmpwright.core

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * WKWebView console and page-error capture through the document-start bridge script. Needs an app
 * host: run with `scripts/run-ios-webkit-tests.sh` (excluded from the bare `iosSimTest`).
 */
class IOSConsoleDocumentTest {

    private suspend fun <T> eventually(read: () -> T, done: (T) -> Boolean): T = withTimeout(10_000) {
        var value = read()
        while (!done(value)) { delay(25); value = read() }
        value
    }

    @Test
    fun console_calls_are_reported_with_playwright_types_and_formatted_arguments() = runWebKitTest {
        requireWebKitDocuments()
        val page = IOSPage(IOSWebViewController())
        try {
            page.setContent("""<script>
                var cyclic = {name: 'c'}; cyclic.self = cyclic;
                console.log('loaded', 1, {a: 1, b: 'x'}, [1, 2], null, undefined);
                console.warn('careful');
                console.error('logged error');
                console.debug('dbg'); console.info('inf');
                console.log(cyclic);
                console.assert(false, 'asserted');
                console.assert(true, 'not reported');
                </script>""")
            val messages = eventually({ page.consoleMessages() }) { it.size >= 7 }
            assertEquals(listOf("log", "warning", "error", "debug", "info", "log", "assert"), messages.map { it.type })
            assertEquals("""loaded 1 {"a":1,"b":"x"} [1,2] null undefined""", messages[0].text)
            assertEquals("""{"name":"c","self":"[Circular]"}""", messages[5].text)
            assertEquals("asserted", messages[6].text)
            assertTrue(page.pageErrors().isEmpty(), "console.error is not a page error")
        } finally { page.close() }
    }

    @Test
    fun uncaught_errors_and_rejections_are_page_errors() = runWebKitTest {
        requireWebKitDocuments()
        val controller = IOSWebViewController()
        val page = IOSPage(controller)
        val script = """<script>
            setTimeout(function() { throw new TypeError('kaboom'); }, 0);
            setTimeout(function() { Promise.reject(new Error('rejected')); }, 20);
            setTimeout(function() { Promise.reject('plain'); }, 40);
            </script>"""
        try {
            // A document with a real origin: WebKit exposes the thrown error object.
            controller.loadHtmlAsync(script, "https://fixture.invalid/", 10_000)
            val errors = eventually({ page.pageErrors() }) { it.size >= 3 }
            assertEquals(listOf("TypeError: kaboom", "Error: rejected", "plain"), errors)
            assertTrue(page.consoleMessages().isEmpty(), page.consoleMessages().toString())
            // about:blank (setContent without a base URL) is opaque: WebKit sanitizes uncaught errors to
            // "Script error.", but they are still reported; rejections keep their reason.
            page.setContent(script)
            val opaque = eventually({ page.pageErrors() }) { it.size >= 3 }
            assertEquals(listOf("Script error.", "Error: rejected", "plain"), opaque)
        } finally { page.close() }
    }

    @Test
    fun iframes_report_and_history_starts_at_the_last_main_frame_document() = runWebKitTest {
        requireWebKitDocuments()
        val page = IOSPage(IOSWebViewController())
        try {
            page.setContent("<script>console.log('first document')</script>")
            eventually({ page.consoleMessages() }) { it.isNotEmpty() }
            page.setContent("<iframe srcdoc=\"<script>console.log('from frame')</script>\"></iframe>")
            eventually({ page.consoleMessages() }) { list -> list.any { it.text == "from frame" } }
            assertEquals(listOf("from frame"), page.consoleMessages().map { it.text })
            assertEquals(listOf("first document", "from frame"), page.consoleMessages(PageMessageFilter.All).map { it.text })
        } finally { page.close() }
    }
}
