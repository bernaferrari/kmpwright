package com.bernaferrari.kmpwright.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.test.assertFailsWith

class SelectorAndExpectTest {
    @Test
    fun `runtime implements every public selector engine`() {
        val source = BaseLocator.helpersJsSource()
        listOf("ref", "role", "label", "text", "exact_text", "placeholder", "alt", "title", "testid", "xpath").forEach {
            assertTrue("missing selector engine $it", source.contains("case '$it'"))
        }
    }

    @Test
    fun `expect text modes and live values use distinct DOM reads`() = testBlocking {
        val controller = MockWebViewController { onEval { script ->
            when {
                script.contains("return t.value") -> "live-value"
                script.contains("el.textContent") -> "Hello   world"
                else -> null
            }
        } }
        val locator = TestLocator("input", controller)
        expect(locator).toHaveText("Hello world")
        expect(locator).toHaveText(Regex("^Hello w"))
        expect(locator).toContainText("world")
        expect(locator).toHaveValue("live-value")
        expect(locator).not.toHaveText("Goodbye")
        assertTrue(controller.executedScripts.any { it.contains("return t.value") })
    }

    @Test
    fun `enabled expectation requires an element`() = testBlocking {
        val locator = TestLocator("missing", MockWebViewController { onEval { "{\"__kw\":1,\"v\":{\"missing\":1}}" } })
        val error = assertFailsWith<KmpwrightAssertionError> { expect(locator).toBeEnabled(timeoutMs = 50) }
        assertTrue(error.message!!.contains("No element found"))
    }

    @Test
    fun `negated expectation fails when the condition keeps holding`() = testBlocking {
        val locator = TestLocator("p", MockWebViewController { onEval { "Visible text" } })
        assertFailsWith<KmpwrightAssertionError> { expect(locator).not.toContainText("Visible", timeoutMs = 50) }
    }

    @Test
    fun `expectation retries until a throwing condition starts to hold`() = testBlocking {
        var calls = 0
        val locator = TestLocator("p", MockWebViewController { onEval { script ->
            if (script.contains("el.textContent")) {
                if (++calls < 3) "{\"__kw\":1,\"e\":\"not ready\"}" else "Ready"
            } else null
        } })
        expect(locator).toHaveText("Ready", timeoutMs = 2_000)
        assertTrue(calls >= 3)
    }

    @Test
    fun `role and name use accessible-name matching`() = testBlocking {
        val page = TestPage(MockWebViewController())
        assertEquals("role=button[name=\"Absent\"]", (page.getByRole("button", name = "Absent") as TestLocator).selectorStr)
        assertEquals("role=button[name=\"Save \\\"all\\\"\" s]", (page.getByRole("button", name = "Save \"all\"", exact = true) as TestLocator).selectorStr)
    }
}
