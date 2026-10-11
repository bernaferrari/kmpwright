package com.bernaferrari.kmpwright.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.test.assertFailsWith

class SelectorAndExpectTest {
    @Test
    fun `generated resolver handles every public attribute selector`() {
        val source = BaseLocator.helpersJsSource()
        listOf("exact_text=", "placeholder=", "alt=", "title=", "testid=").forEach {
            assertTrue("missing resolver branch for $it", source.contains("selector.startsWith('$it'))"))
        }
        assertTrue(source.contains("window.__kmpwright_byAttribute"))
        assertTrue(source.contains("window.__kmpwright_byAlt"))
    }

    @Test
    fun `expect text modes and live values use distinct DOM reads`() = testBlocking {
        val controller = MockWebViewController { onEval { script ->
            when {
                script.contains("el.innerText") -> "Hello world"
                script.contains("el.value") -> "live-value"
                script.contains("resolveAll") -> "true"
                else -> null
            }
        } }
        val locator = TestLocator("input", controller)
        locator.expect().toHaveText("Hello world")
        locator.expect().toContainText("world")
        locator.expect().toHaveValue("live-value")
        assertTrue(controller.executedScripts.any { it.contains("el.value") })
    }

    @Test
    fun `enabled expectation requires an element`() = testBlocking {
        var observerScript: String? = null
        val controller = MockWebViewController { onEval { script ->
            if (script.contains("MutationObserver")) {
                observerScript = script
                "__kmpwright_timeout__"
            } else {
                "false"
            }
        } }
        val locator = TestLocator("missing", controller)

        assertFailsWith<KmpwrightException> {
            locator.expect().toBeEnabled(timeoutMs = 10)
        }
        assertTrue(observerScript?.contains("!!el && !el.disabled") == true)
    }

    @Test
    fun `role and missing name remain scoped instead of falling back to role`() = testBlocking {
        val page = TestPage(MockWebViewController())
        val locator = page.find(role = "button", name = "Absent") as TestLocator
        assertEquals("role=button >> exact_text=Absent", locator.selectorStr)
    }
}
