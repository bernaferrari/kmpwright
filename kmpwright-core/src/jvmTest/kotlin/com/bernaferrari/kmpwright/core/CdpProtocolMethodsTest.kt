package com.bernaferrari.kmpwright.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class CdpProtocolMethodsTest {

    @Test
    fun `download behavior uses browser domain`() {
        assertEquals("Browser.setDownloadBehavior", CdpMethods.BROWSER_SET_DOWNLOAD_BEHAVIOR)
    }

    @Test
    fun `viewport resize uses emulation domain`() {
        assertEquals("Emulation.setDeviceMetricsOverride", CdpMethods.EMULATION_SET_DEVICE_METRICS_OVERRIDE)
    }

    @Test
    fun `service worker cleanup can detach cdp target sessions`() {
        assertEquals("Target.detachFromTarget", CdpMethods.TARGET_DETACH_FROM_TARGET)
    }

    @Test
    fun `cdp scrollbar hiding follows headless config and explicit show override`() {
        assertTrue(shouldHideCdpScrollbars(KmpwrightConfig(cdp = CdpConfig())))
        assertFalse(shouldHideCdpScrollbars(KmpwrightConfig(cdp = CdpConfig(headless = false))))
        assertFalse(shouldHideCdpScrollbars(KmpwrightConfig(cdp = CdpConfig(hideScrollbars = false))))
        assertFalse(shouldHideCdpScrollbars(KmpwrightConfig(cdp = CdpConfig(browserArgs = listOf("--show-scrollbars")))))
    }

    @Test
    fun `fetch fulfill params encode string body and content type`() {
        val params = buildFetchFulfillParams(
            requestId = "req-1",
            options = FulfillOptions(
                status = 201,
                body = """{"ok":true}""",
                contentType = "application/json"
            )
        )

        assertEquals("req-1", params["requestId"])
        assertEquals(201, params["responseCode"])
        assertEquals(
            Base64.getEncoder().encodeToString("""{"ok":true}""".encodeToByteArray()),
            params["body"]
        )
        assertEquals(
            listOf(mapOf("name" to "Content-Type", "value" to "application/json")),
            params["responseHeaders"]
        )
    }

    @Test
    fun `fetch continue params omit nulls and encode post data`() {
        val params = buildFetchContinueParams(
            requestId = "req-2",
            options = ContinueOptions(
                url = "https://example.com/next",
                method = "POST",
                headers = mapOf("X-Test" to "1"),
                postData = "hello"
            )
        )

        assertEquals("req-2", params["requestId"])
        assertEquals("https://example.com/next", params["url"])
        assertEquals("POST", params["method"])
        assertEquals(listOf(mapOf("name" to "X-Test", "value" to "1")), params["headers"])
        assertEquals(Base64.getEncoder().encodeToString("hello".encodeToByteArray()), params["postData"])

        val minimal = buildFetchContinueParams("req-3", ContinueOptions())
        assertEquals(setOf("requestId"), minimal.keys)
        assertFalse(minimal.containsKey("headers"))
    }

    @Test
    fun `fetch fail params normalize common route abort names`() {
        assertEquals("Aborted", buildFetchFailParams("req-4", "aborted")["errorReason"])
        assertEquals("TimedOut", buildFetchFailParams("req-4", "timeout")["errorReason"])
        assertEquals("BlockedByClient", buildFetchFailParams("req-4", "blocked_by_client")["errorReason"])
        assertEquals("Failed", buildFetchFailParams("req-4", "not-a-cdp-reason")["errorReason"])
    }
}
