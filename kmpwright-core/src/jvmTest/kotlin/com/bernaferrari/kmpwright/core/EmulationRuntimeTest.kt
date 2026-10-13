package com.bernaferrari.kmpwright.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** WebView emulation fallback: one sealed runtime, applied now and registered for later documents. */
class EmulationRuntimeTest {
    private fun page(initScripts: Boolean = true) =
        MockWebViewController().apply { supportsInitScripts = initScripts }.let { it to TestPage(it) }

    @Test
    fun `color scheme overrides matchMedia and persists as a document-start script`() = testBlocking {
        val (controller, page) = page()
        page.emulateMedia(colorScheme = ColorScheme.Dark)
        val applied = controller.executedScripts.last()
        assertTrue(applied.contains("prefers-color-scheme"))
        assertTrue(applied.contains("\"scheme\":\"dark\""))
        assertEquals(listOf(applied), controller.initScripts.values.toList())
    }

    @Test
    fun `changing emulation replaces the previous init script`() = testBlocking {
        val (controller, page) = page()
        page.emulateMedia(colorScheme = ColorScheme.Dark)
        page.setGeolocation(Geolocation(40.7128, -74.006))
        assertEquals(1, controller.initScripts.size)
        val script = controller.initScripts.values.single()
        assertTrue(script.contains("\"scheme\":\"dark\""))
        assertTrue(script.contains("\"latitude\":40.7128"))
        page.emulateMedia(colorScheme = null)
        page.setGeolocation(null)
        assertTrue(controller.initScripts.isEmpty())
        assertTrue(controller.executedScripts.last().contains("\"scheme\":null"))
    }

    @Test
    fun `runtime updates are sealed and its slot is not writable`() = testBlocking {
        val (controller, page) = page()
        page.setOffline(true)
        val script = controller.executedScripts.last()
        assertTrue(script.contains("if (seal !== SEAL) throw new Error('Forbidden')"))
        assertTrue(script.contains("writable: false, enumerable: false, configurable: false"))
        assertTrue(script.contains("\"offline\":true"))
        assertFalse(script.contains("window.__kmpwright_origFetch"))
    }

    @Test
    fun `domain filter fallback locks the patched network APIs`() = testBlocking {
        val (controller, page) = page()
        page.setAllowedDomains(listOf("example.com", "*.cdn.example.com"))
        val script = controller.initScripts.values.single()
        assertTrue(script.contains("\"domains\":[\"example.com\",\"*.cdn.example.com\"]"))
        assertTrue(script.contains("if (state.domains.length) lock();"))
    }

    @Test
    fun `without document-start scripts the current document is still patched`() = testBlocking {
        val (controller, page) = page(initScripts = false)
        page.setGeolocation(Geolocation(1.0, 2.0))
        assertTrue(controller.executedScripts.last().contains("\"latitude\":1.0"))
    }

    @Test
    fun `throttling is rejected honestly on backends without native network emulation`() {
        val (_, page) = page()
        assertThrows(KmpwrightUnsupportedException::class.java) {
            kotlinx.coroutines.runBlocking { page.emulateNetworkConditions(NetworkConditions(latencyMs = 100, downloadThroughput = 1000)) }
        }
    }

    @Test
    fun `invalid geolocation is rejected`() {
        val (_, page) = page()
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { page.setGeolocation(Geolocation(120.0, 0.0)) }
        }
    }
}
