package com.bernaferrari.kmpwright.core

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files

class BrowserLaunchTest {
    @Test fun `owned launcher reuses caller profiles and closes its pages and process`() = runBlocking<Unit> {
        val executable = System.getenv("KMPWRIGHT_TEST_CHROME")
        assumeTrue("Real Chrome executable required", !executable.isNullOrBlank())
        val directory = Files.createTempDirectory("kmpwright-profile-test-")
        Files.writeString(directory.resolve("owner.txt"), "preserve caller files")
        try {
            repeat(2) {
                val launched = launchBrowser(BrowserLaunchOptions(executable!!, userDataDirectory = directory.toString()))
                try {
                    val page = launched.browser.newPageAsync()
                    page.setContent("<button>Owned launch</button>")
                    assertEquals("Owned launch", page.locator("button").textContent())
                    assertTrue(launched.browser.versionAsync().version.first().isDigit())
                    val output = directory.resolve("close-recording-$it.mp4")
                    page.startRecording(RecordingOptions(output.toString(), maxWidth = 320, maxHeight = 240, cursor = false))
                    kotlinx.coroutines.delay(100)
                    page.closeAsync()
                    assertTrue("Closing the page must finalize its active recording", Files.size(output) > 100)
                } finally { withTimeout(10_000) { launched.closeAsync() }; launched.closeAsync() }
                assertFalse(Files.exists(directory.resolve("DevToolsActivePort")))
                assertEquals("preserve caller files", Files.readString(directory.resolve("owner.txt")))
            }
            val available = ChromeProfiles.list(directory.toString())
            assertTrue("Launched Chrome should publish its profile", available.isNotEmpty())
            val original = Files.readAllBytes(directory.resolve("Local State"))
            val copied = launchBrowser(BrowserLaunchOptions(executable!!, profileSnapshot = ChromeProfileSource(directory.toString(), available.first().directory)))
            try { copied.browser.newPageAsync().setContent("<h1>Snapshot launch</h1>") }
            finally { copied.closeAsync() }
            assertArrayEquals(original, Files.readAllBytes(directory.resolve("Local State")))
        } finally { directory.toFile().deleteRecursively() }
    }
    @Test fun `invalid executable preserves a caller provided directory`() = runBlocking<Unit> {
        val directory = Files.createTempDirectory("kmpwright-launch-failure-")
        try {
            Files.writeString(directory.resolve("owner.txt"), "keep")
            try { launchBrowser(BrowserLaunchOptions(directory.resolve("missing-browser").toString(), userDataDirectory = directory.toString())); fail("Invalid executable accepted") }
            catch (error: KmpwrightException) { assertTrue(error.message!!.contains("launch")) }
            assertEquals("keep", Files.readString(directory.resolve("owner.txt")))
        } finally { directory.toFile().deleteRecursively() }
    }
    @Test fun `launch arguments cannot redirect the owned endpoint or profile`() {
        for (argument in listOf("--remote-debugging-port=9222", "--user-data-dir=/tmp/foreign")) {
            try { BrowserLaunchOptions("chrome", arguments = listOf(argument)); fail("Ownership override accepted") }
            catch (_: IllegalArgumentException) { }
        }
    }
}
