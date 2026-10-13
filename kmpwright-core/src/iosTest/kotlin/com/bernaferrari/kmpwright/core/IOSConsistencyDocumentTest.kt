package com.bernaferrari.kmpwright.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Cross-backend consistency contracts on a real WKWebView (screenshots, PDF defaults, dialogs,
 * route fulfillment and network metadata). Needs an app host: run with
 * `scripts/run-ios-webkit-tests.sh` (excluded from the bare `iosSimTest`).
 */
class IOSConsistencyDocumentTest {

    @Test
    fun screenshot_formats_and_unsupported_options_fail_loudly() = runWebKitTest {
        requireWebKitDocuments()
        val page = IOSPage(IOSWebViewController(KmpwrightConfig(viewport = Viewport(320, 480))))
        try {
            page.setContent("<body style='margin:0'><div id=box style='width:100px;height:50px;background:#ff0000'></div></body>")
            val jpeg = page.screenshot(format = ScreenshotFormat.Jpeg, quality = 60)
            assertEquals("JPEG", imageFormatOf(jpeg.bytes))
            val element = page.locator("#box").screenshot(format = ScreenshotFormat.Jpeg)
            assertEquals("JPEG", imageFormatOf(element.bytes))
            assertEquals("PNG", imageFormatOf(page.locator("#box").screenshot().bytes))
            assertFailsWith<KmpwrightUnsupportedException> { page.screenshot(format = ScreenshotFormat.Webp) }
            assertFailsWith<KmpwrightUnsupportedException> { page.screenshot(omitBackground = true) }
            assertFailsWith<KmpwrightUnsupportedException> { page.locator("#box").screenshot(omitBackground = true) }
            assertFailsWith<KmpwrightException> { page.screenshot(clip = Rectangle(5_000.0, 5_000.0, 10.0, 10.0)) }
        } finally { page.close() }
    }

    @Test
    fun default_pdf_options_work() = runWebKitTest {
        requireWebKitDocuments()
        val page = IOSPage(IOSWebViewController())
        try {
            page.setContent("<p>PDF</p>")
            assertTrue(page.pdf().bytes.decodeToString(0, 5) == "%PDF-")
            assertTrue(page.pdf(format = PdfFormat.Content, timeoutMs = 20_000).bytes.isNotEmpty())
            assertFailsWith<KmpwrightUnsupportedException> { page.pdf(format = PdfFormat.A4) }
        } finally { page.close() }
    }

    @Test
    fun wait_for_dialog_receives_and_dismisses_without_a_handler() = runWebKitTest {
        requireWebKitDocuments()
        val page = IOSPage(IOSWebViewController())
        try {
            page.setContent("<p>dialogs</p>")
            val dialog = page.waitForDialog { evaluate("setTimeout(function() { window.answer = confirm('Sure?'); }, 0); 1") }
            assertEquals(DialogType.Confirm, dialog.type)
            assertEquals("Sure?", dialog.message)
            var answer = "undefined"
            kotlinx.coroutines.withTimeout(5_000) {
                while (answer == "undefined") { kotlinx.coroutines.delay(20); answer = page.evaluate("String(window.answer)").value }
            }
            assertEquals("false", answer)
        } finally { page.close() }
    }

    @Test
    fun route_fulfill_content_type_and_resource_types_match_other_backends() = runWebKitTest {
        requireWebKitDocuments()
        val page = IOSPage(IOSWebViewController())
        try {
            page.setContent("<p>network</p>")
            page.route(Regex(".*/api/override")) { it.fulfill(headers = mapOf("Content-Type" to "text/plain"), body = "x", contentType = "text/html") }
            page.route(Regex(".*/api/json")) { it.fulfill(json = """{"ok":true}""") }
            page.route(Regex(".*/api/header")) { it.fulfill(headers = mapOf("Content-Type" to "text/csv"), body = "a,b") }
            suspend fun contentType(path: String) =
                page.evaluate("fetch('https://fixture.invalid$path').then(function(r) { return String(r.headers.get('content-type')); })").value
            assertEquals("text/html", contentType("/api/override"))
            assertEquals("application/json", contentType("/api/json"))
            assertEquals("text/csv", contentType("/api/header"))
            val record = page.network.requests("/api/json").first()
            assertEquals("fetch", record.request.resourceType)
            assertEquals(200, record.response?.status)
            assertNull(Request("https://x").postDataJSON())
        } finally { page.close() }
    }
}
