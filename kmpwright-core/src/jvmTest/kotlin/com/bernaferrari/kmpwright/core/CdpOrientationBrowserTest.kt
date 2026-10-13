package com.bernaferrari.kmpwright.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Real Chromium: mobile viewports report the emulated screen orientation, as in Playwright. */
class CdpOrientationBrowserTest {
    @Test
    fun `mobile viewports emulate portrait and landscape`() = runBlocking<Unit> {
        val executable = System.getenv("KMPWRIGHT_TEST_CHROME")
        assumeTrue("Set KMPWRIGHT_TEST_CHROME for real browser contracts", !executable.isNullOrBlank())
        val launched = launchBrowser(executable!!)
        try {
            val page = launched.browser.newPage()
            page.setContent("<p>orientation</p>")
            page.emulateDevice(Devices.iPhone15)
            assertEquals("portrait-primary", page.evaluate("screen.orientation.type").value)
            page.emulateDevice(Devices.iPhone15.copy(viewport = Devices.iPhone15.viewport.copy(width = 852, height = 393, isLandscape = true)))
            assertEquals("landscape-primary", page.evaluate("screen.orientation.type").value)
        } finally {
            launched.close()
        }
    }
}
