package com.bernaferrari.kmpwright.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The in-page runtime is private: no well-known globals, sealed registry, envelope protocol. */
class HelpersInjectionTest {

    @Test
    fun `runtime source installs under a random non-writable slot`() {
        val source = BaseLocator.helpersJsSource()
        assertTrue(source.length > 1000)
        assertTrue(source.contains(PageHelpers.key))
        assertTrue(PageHelpers.key.startsWith("__kmpwright_rt_") && PageHelpers.key.length > 20)
        assertTrue(source.contains("writable: false, enumerable: false, configurable: false"))
        assertTrue(source.contains("O.freeze(H)"))
    }

    @Test
    fun `runtime never publishes well-known helper globals`() {
        val source = BaseLocator.helpersJsSource()
        listOf("window.__kmpwright_queryOne", "window.__kmpwright_resolveAll", "window.__kmpwright_snapshotNodes", "window.__kmpwright_byRole")
            .forEach { assertFalse(it, source.contains("$it =")) }
        assertFalse(source.contains("if (window.__kmpwright_queryOne) return;"))
    }

    @Test
    fun `pre-existing slot that is not a sealed runtime is reported as tampering`() {
        val source = BaseLocator.helpersJsSource()
        assertTrue(source.contains("current.sealed === true"))
        assertTrue(source.contains(PageHelpers.TAMPERED))
    }

    @Test
    fun `snapshot registry writes require the seal`() {
        val source = BaseLocator.helpersJsSource()
        assertTrue(source.contains("commitSnapshot: function(seal, nodes)"))
        assertTrue(source.contains("if (seal !== SEAL) throw new Error('Forbidden')"))
        assertFalse(AccessibilitySnapshotEngine.snapshotJs(SnapshotOptions()).contains("window.__kmpwright_snapshot"))
    }

    @Test
    fun `envelope decoding distinguishes values errors and missing runtime`() {
        assertEquals(PageHelpers.Outcome.Missing, PageHelpers.decode("{\"__kw\":1,\"missing\":1}"))
        assertEquals(PageHelpers.Outcome.Tampered, PageHelpers.decode("{\"__kw\":1,\"tampered\":1}"))
        assertEquals(PageHelpers.Outcome.Error("boom"), PageHelpers.decode("{\"__kw\":1,\"e\":\"boom\"}"))
        val value = PageHelpers.decode("{\"__kw\":1,\"v\":\"text\"}") as PageHelpers.Outcome.Value
        assertEquals("text", PageHelpers.valueAsString(value.value))
        // Bridges without envelopes keep working: plain strings are values.
        val plain = PageHelpers.decode("hello") as PageHelpers.Outcome.Value
        assertEquals("hello", PageHelpers.valueAsString(plain.value))
    }

    @Test
    fun `missing runtime is installed once and the operation retried`() = testBlocking {
        var installed = false
        val controller = MockWebViewController().apply {
            evalHandler = { script ->
                when {
                    script.contains("var KEY = ") -> { installed = true; "ok" }
                    !installed -> "{\"__kw\":1,\"missing\":1}"
                    else -> "{\"__kw\":1,\"v\":3}"
                }
            }
        }
        assertEquals(3, TestLocator("li", controller).count())
        assertEquals(1, controller.executedScripts.count { it.contains("var KEY = ") })
    }

    @Test
    fun `tampered runtime slot fails loudly`() {
        val controller = MockWebViewController().apply { evalHandler = { "{\"__kw\":1,\"tampered\":1}" } }
        val error = org.junit.Assert.assertThrows(KmpwrightException::class.java) {
            kotlinx.coroutines.runBlocking { TestLocator("li", controller).count() }
        }
        assertTrue(error.message!!.contains("refusing to automate"))
    }
}
