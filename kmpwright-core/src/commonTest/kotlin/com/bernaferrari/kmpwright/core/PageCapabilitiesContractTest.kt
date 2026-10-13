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

        val error = assertFailsWith<KmpwrightUnsupportedException> {
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


    @Test
    fun `supports level evaluates every capability of the receiver`() {
        val none = PageCapabilities()
        val all = PageCapabilities(
            routeInterception = SupportLevel.Supported, touchInput = SupportLevel.Supported, mouseInput = SupportLevel.Supported,
            keyboardInput = SupportLevel.Supported, pdf = SupportLevel.Supported, browserContexts = SupportLevel.Supported,
            serviceWorkers = SupportLevel.Supported, domainFiltering = SupportLevel.Supported, networkEmulation = SupportLevel.Supported,
            harRecording = SupportLevel.Supported, downloads = SupportLevel.Supported, fileChooser = SupportLevel.Supported,
            tracing = SupportLevel.Supported, profiling = SupportLevel.Supported, tabs = SupportLevel.Supported,
            cdp = SupportLevel.Supported, initScripts = SupportLevel.Supported, webMcp = SupportLevel.Supported,
            accessibilityAudit = SupportLevel.Supported, webVitals = SupportLevel.Supported, reactInspection = SupportLevel.Supported,
            httpRead = SupportLevel.Supported, networkHistory = SupportLevel.Supported, screencast = SupportLevel.Supported,
            videoRecording = SupportLevel.Supported, crossOriginFrames = SupportLevel.Supported
        )
        assertTrue(none.supports(SupportLevel.Unsupported))
        assertTrue(!none.supports(SupportLevel.Partial))
        assertTrue(all.supports(SupportLevel.Supported))
        assertTrue(!all.copy(pdf = SupportLevel.Partial).supports(SupportLevel.Supported))
        assertTrue(all.copy(pdf = SupportLevel.Partial).supports(SupportLevel.Partial))
    }

    @Test
    fun `missing capabilities raise the dedicated unsupported exception`() {
        val error = assertFailsWith<KmpwrightUnsupportedException> { PageCapabilities().require(PageCapability.CrossOriginFrames) }
        assertTrue(error.message.orEmpty().contains("CrossOriginFrames"))
    }
}
