package com.bernaferrari.kmpwright.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Shared executable contracts for capability declarations and workflow preflight. */
class PageCapabilitiesContractTest {
    @Test
    fun `support level map contains every advertised capability`() {
        val capabilities = PageCapabilities(
            routeInterception = SupportLevel.Supported,
            touchInput = SupportLevel.Partial,
            mouseInput = SupportLevel.Unsupported,
            keyboardInput = SupportLevel.Supported,
            pdf = SupportLevel.Partial,
            browserContexts = SupportLevel.Unsupported,
            serviceWorkers = SupportLevel.Supported,
            domainFiltering = SupportLevel.Partial,
            networkEmulation = SupportLevel.Supported,
            harRecording = SupportLevel.Unsupported,
            downloads = SupportLevel.Supported,
            fileChooser = SupportLevel.Unsupported,
            tracing = SupportLevel.Supported,
            profiling = SupportLevel.Supported,
            tabs = SupportLevel.Partial,
            cdp = SupportLevel.Unsupported
        )

        assertEquals(PageCapability.values().toSet(), capabilities.supportLevels().keys)
        assertEquals(SupportLevel.Supported, capabilities.supportLevel(PageCapability.RouteInterception))
        assertEquals(SupportLevel.Partial, capabilities.supportLevel(PageCapability.TouchInput))
        assertEquals(SupportLevel.Unsupported, capabilities.supportLevel(PageCapability.Cdp))
    }

    @Test
    fun `partial support satisfies partial but not supported requirement`() {
        val capabilities = PageCapabilities(touchInput = SupportLevel.Partial)

        assertTrue(capabilities.missing(listOf(PageCapability.TouchInput.require(SupportLevel.Partial))).isEmpty())
        assertEquals(
            listOf(SupportLevel.Partial),
            capabilities.missing(listOf(PageCapability.TouchInput.require())).map { it.actual }
        )
    }

    @Test
    fun `require fails before workflow body and reports every missing capability`() {
        val capabilities = PageCapabilities(backend = BackendKind.AndroidWebView)
        var workflowStarted = false

        val error = assertFailsWith<KmpwrightException> {
            capabilities.require(
                PageCapability.RouteInterception.require(SupportLevel.Supported, "needs fulfill"),
                PageCapability.Cdp.require()
            )
            workflowStarted = true
        }

        assertTrue(!workflowStarted)
        assertTrue(error.message.orEmpty().contains("RouteInterception"))
        assertTrue(error.message.orEmpty().contains("Cdp"))
        assertTrue(error.message.orEmpty().contains("needs fulfill"))
    }

}
