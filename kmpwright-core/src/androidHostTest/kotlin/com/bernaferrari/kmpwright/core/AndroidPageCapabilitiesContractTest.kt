package com.bernaferrari.kmpwright.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Android's published support matrix is checked on the Android host target. */
class AndroidPageCapabilitiesContractTest {
    @Test
    fun `every advertised Android support level is explicit`() {
        val capabilities = androidPageCapabilities()
        assertEquals(BackendKind.AndroidWebView, capabilities.backend)
        assertEquals(
            mapOf(
                PageCapability.RouteInterception to SupportLevel.Partial,
                PageCapability.TouchInput to SupportLevel.Supported,
                PageCapability.MouseInput to SupportLevel.Partial,
                PageCapability.KeyboardInput to SupportLevel.Supported,
                PageCapability.Pdf to SupportLevel.Supported,
                PageCapability.BrowserContexts to SupportLevel.Unsupported,
                PageCapability.ServiceWorkers to SupportLevel.Unsupported,
                PageCapability.DomainFiltering to SupportLevel.Partial,
                PageCapability.NetworkEmulation to SupportLevel.Partial,
                PageCapability.HarRecording to SupportLevel.Partial,
                PageCapability.Downloads to SupportLevel.Unsupported,
                PageCapability.FileChooser to SupportLevel.Unsupported,
                PageCapability.Tracing to SupportLevel.Unsupported,
                PageCapability.Profiling to SupportLevel.Unsupported,
                PageCapability.Tabs to SupportLevel.Supported,
                PageCapability.Cdp to SupportLevel.Unsupported,
                PageCapability.InitScripts to SupportLevel.Partial,
                PageCapability.WebMcp to SupportLevel.Unsupported,
                PageCapability.AccessibilityAudit to SupportLevel.Partial,
                PageCapability.WebVitals to SupportLevel.Partial,
                PageCapability.ReactInspection to SupportLevel.Supported,
                PageCapability.HttpRead to SupportLevel.Supported,
                PageCapability.NetworkHistory to SupportLevel.Partial,
                PageCapability.VideoRecording to SupportLevel.Unsupported,
            PageCapability.Screencast to SupportLevel.Unsupported
            ),
            capabilities.supportLevels()
        )
        assertEquals(PageCapability.values().size, capabilities.supportLevels().size)
        assertTrue(capabilities.notes.isNotEmpty())
    }
}
