package com.bernaferrari.kmpwright.test

import androidx.compose.ui.graphics.ImageBitmap
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bernaferrari.kmpwright.core.AndroidPlatformContext
import com.bernaferrari.kmpwright.core.Browser
import com.bernaferrari.kmpwright.example.ConsoleEntry
import com.bernaferrari.kmpwright.example.StudioState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.ClassRule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StudioStateInstrumentedTest {
    companion object {
        @ClassRule @JvmField val host = ActivityScenarioRule(WebViewHarnessActivity::class.java)
    }
    private val scope = MainScope()
    private lateinit var state: StudioState

    @Before fun open() {
        host.scenario.onActivity { activity ->
            state = StudioState(Browser(AndroidPlatformContext(activity, activity.resetWebView())), scope)
        }
    }

    @After fun close() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { state.dispose(); scope.cancel() }
    }

    @Test fun directPageChangesRefreshNamesAndGeometry() = runBlocking(Dispatchers.Main) {
        state.page.setContent("<button>Before</button>")
        withTimeout(15_000) { while (state.refs.none { it.name == "Before" }) delay(25) }
        val before = state.refs.first { it.name == "Before" }.box.x
        state.page.evaluate("document.querySelector('button').style.marginLeft='80px'; document.querySelector('button').textContent='After'")
        withTimeout(15_000) { while (state.refs.none { it.name == "After" && it.box.x >= before + 70 }) delay(25) }
        state.page.setContent("<button>New document</button>")
        withTimeout(15_000) { while (state.refs.none { it.name == "New document" }) delay(25) }
        assertFalse(state.refs.any { it.name == "After" })
    }

    @Test fun consoleRetentionIsBoundedAndDisposedSessionsDoNotAcceptWork() = runBlocking(Dispatchers.Main) {
        repeat(125) { state.run("get title") }
        withTimeout(15_000) { while (state.console.any { it.status == ConsoleEntry.Status.Running }) delay(25) }
        assertEquals(100, state.console.size)
        state.dispose()
        state.run("get title")
        assertTrue(state.console.isEmpty())
    }

    @Test fun screenshotHistoryHasADecodedMemoryBudget() = runBlocking(Dispatchers.Main) {
        repeat(5) { id -> state.console.add(ConsoleEntry(id.toLong(), "screenshot", status = ConsoleEntry.Status.Ok, image = ImageBitmap(1024, 2048))) }
        state.run("get title")
        withTimeout(15_000) { while (state.console.any { it.status == ConsoleEntry.Status.Running && it.command == "get title" }) delay(25) }
        val bytes = state.console.sumOf { it.image?.let { image -> image.width.toLong() * image.height * 4 } ?: 0 }
        assertTrue("Retained $bytes bytes", bytes <= 32L * 1024 * 1024)
        assertTrue(state.console.count { it.image != null } < 5)
    }
}
