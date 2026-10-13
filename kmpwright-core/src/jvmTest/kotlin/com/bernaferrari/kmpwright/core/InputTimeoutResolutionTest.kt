package com.bernaferrari.kmpwright.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Input option timeouts are nullable; null resolves to [TimeoutConfig.action] of the page config. */
class InputTimeoutResolutionTest {
    private class NoopBridge : CdpInputBridge {
        override val inputState = CdpInputState()
        override suspend fun sendCdpCommand(method: String, params: Map<String, Any?>): CdpResponse = CdpResponse(id = 1)
    }

    private val shortAction = KmpwrightConfig(timeouts = TimeoutConfig(action = 50))

    private suspend fun assertTimesOut(expectedMs: Long, block: suspend () -> Unit) {
        try {
            block()
            fail("expected a timeout")
        } catch (timeout: KmpwrightTimeoutException) {
            assertTrue(timeout.message.orEmpty(), timeout.message.orEmpty().contains("${expectedMs}ms"))
        }
    }

    @Test
    fun `null option timeouts use the configured action timeout`() = runBlocking {
        val keyboard = CdpKeyboard(NoopBridge(), shortAction)
        assertTimesOut(50) { keyboard.type("abc", TypeOptions(delayMs = 5_000)) }
        assertTimesOut(50) { keyboard.press("a", PressOptions(delayMs = 5_000)) }
        assertEquals(emptySet<String>(), keyboard.modifiers())
    }

    @Test
    fun `an explicit option timeout wins over the configured action timeout`() = runBlocking {
        val keyboard = CdpKeyboard(NoopBridge(), shortAction)
        assertTimesOut(80) { keyboard.type("abc", TypeOptions(delayMs = 5_000, timeoutMs = 80)) }
        // A generous explicit timeout lets a slow-but-finite action complete.
        CdpKeyboard(NoopBridge(), shortAction).type("ab", TypeOptions(delayMs = 60, timeoutMs = 2_000))
        CdpTouch(NoopBridge(), shortAction).tap(1.0, 2.0, TapOptions(timeoutMs = 2_000))
    }

    @Test
    fun `default config keeps the thirty second action timeout`() {
        assertEquals(30_000L, KmpwrightConfig().actionTimeout(ClickOptions().timeoutMs))
        assertEquals(30_000L, KmpwrightConfig().actionTimeout(FillOptions().timeoutMs))
        assertEquals(30_000L, KmpwrightConfig().actionTimeout(DragOptions().timeoutMs))
        assertEquals(50L, shortAction.actionTimeout(TapOptions().timeoutMs))
        assertEquals(7L, shortAction.actionTimeout(PressOptions(timeoutMs = 7).timeoutMs))
    }
}
