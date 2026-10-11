package com.bernaferrari.kmpwright.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** The JVM backend's complete capability advertisement is executable contract data. */
class JvmPageCapabilitiesContractTest {
    @Test
    fun `every advertised JVM support level is explicit`() {
        val capabilities = jvmCdpPageCapabilities()
        assertEquals(BackendKind.JvmCdp, capabilities.backend)
        assertEquals(
            mapOf(
                PageCapability.RouteInterception to SupportLevel.Supported,
                PageCapability.TouchInput to SupportLevel.Partial,
                PageCapability.MouseInput to SupportLevel.Supported,
                PageCapability.KeyboardInput to SupportLevel.Supported,
                PageCapability.Pdf to SupportLevel.Partial,
                PageCapability.BrowserContexts to SupportLevel.Supported,
                PageCapability.ServiceWorkers to SupportLevel.Supported,
                PageCapability.DomainFiltering to SupportLevel.Supported,
                PageCapability.NetworkEmulation to SupportLevel.Supported,
                PageCapability.HarRecording to SupportLevel.Supported,
                PageCapability.Downloads to SupportLevel.Supported,
                PageCapability.FileChooser to SupportLevel.Partial,
                PageCapability.Tracing to SupportLevel.Supported,
                PageCapability.Profiling to SupportLevel.Supported,
                PageCapability.Tabs to SupportLevel.Supported,
                PageCapability.Cdp to SupportLevel.Supported,
                PageCapability.InitScripts to SupportLevel.Supported,
                PageCapability.WebMcp to SupportLevel.Partial,
                PageCapability.AccessibilityAudit to SupportLevel.Supported,
                PageCapability.WebVitals to SupportLevel.Supported,
                PageCapability.ReactInspection to SupportLevel.Supported,
                PageCapability.HttpRead to SupportLevel.Supported,
                PageCapability.NetworkHistory to SupportLevel.Partial,
                PageCapability.VideoRecording to SupportLevel.Supported,
            PageCapability.Screencast to SupportLevel.Supported
            ),
            capabilities.supportLevels()
        )
        assertEquals(PageCapability.values().size, capabilities.supportLevels().size)
        assertTrue(capabilities.notes.isNotEmpty())
    }

    @Test
    fun `page preflight prevents JVM workflow from starting`() = runBlocking {
        val page = TestPage()
        var started = false

        try {
            page.withRequiredCapabilities(PageCapability.Cdp.require()) {
                started = true
            }
        } catch (error: KmpwrightException) {
            assertTrue(error.message.orEmpty().contains("Cdp"))
        } finally {
            page.close()
        }

        assertEquals(false, started)
    }
}
