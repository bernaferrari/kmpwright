package com.bernaferrari.kmpwright.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WaitForObserverTest {

    @Test
    fun `a bridge that cannot await promises must still time out for a missing element`() {
        val controller = MockWebViewController().apply {
            evalHandler = { script -> if (script.contains("new Promise")) "{}" else "false" }
        }
        org.junit.Assert.assertThrows(KmpwrightException::class.java) {
            kotlinx.coroutines.runBlocking { TestLocator("#missing", controller).waitFor(timeoutMs = 60) }
        }
    }

    @Test
    fun `a bridge that cannot await promises polls until the element appears`() = kotlinx.coroutines.runBlocking {
        var polls = 0
        val controller = MockWebViewController().apply {
            evalHandler = { script ->
                when {
                    script.contains("new Promise") -> "{}"
                    script.contains("/*kmpwright:waitFor*/") -> (++polls >= 2).toString()
                    else -> null
                }
            }
        }
        TestLocator("#later", controller).waitFor(timeoutMs = 500)
        assertTrue(polls >= 2)
    }

    @Test
    fun `JS output contains MutationObserver`() {
        val js = BaseLocator.waitForConditionJs("true", 5000)
        assertNotNull(js)
        assertTrue(js.contains("MutationObserver"))
        assertTrue(js.contains("childList: true"))
        assertTrue(js.contains("attributes: true"))
        assertTrue(js.contains("characterData: true"))
        assertTrue(js.contains("subtree: true"))
    }

    @Test
    fun `JS output contains timeout handling`() {
        val js = BaseLocator.waitForConditionJs("true", 5000)
        assertTrue(js.contains("5000"))
        assertTrue(js.contains("__kmpwright_timeout__"))
    }

    @Test
    fun `JS output contains observer disconnect on timeout`() {
        val js = BaseLocator.waitForConditionJs("true", 3000)
        assertTrue(js.contains("observer.disconnect()"))
    }

    @Test
    fun `JS output checks condition immediately first`() {
        val condition = "(function() { return true; })()"
        val js = BaseLocator.waitForConditionJs(condition, 5000)
        // Condition appears exactly once in the __ww_check function definition
        val count = js.split(condition).size - 1
        assertEquals("Condition should appear exactly once in __ww_check", 1, count)
        // __ww_check() is called by the fallback loop, initial observer,
        // mutation callback, periodic timer, and timeout callback.
        val callCount = js.split("__ww_check()").size - 1
        assertEquals("__ww_check() should be called 5 times", 5, callCount)
    }

    @Test
    fun `JS output has MutationObserver fallback for old browsers`() {
        val js = BaseLocator.waitForConditionJs("true", 5000)
        assertTrue(js.contains("typeof MutationObserver === 'undefined'"))
        assertTrue(js.contains("setTimeout(check, 50)"))
    }

    @Test
    fun `MutationObserver path includes low frequency in-page recheck`() {
        val js = BaseLocator.waitForConditionJs("true", 5000)
        assertTrue(js.contains("setInterval"))
        assertTrue(js.contains("}, 100);"))
        assertTrue(js.contains("clearInterval(recheckId)"))
    }

    @Test
    fun `JS output is a self-executing function returning a Promise`() {
        val js = BaseLocator.waitForConditionJs("true", 5000)
        assertTrue(js.trim().startsWith("(function()"))
        assertTrue(js.contains("new Promise"))
        assertTrue(js.contains("resolve"))
    }

    @Test
    fun `JS output contains the condition expression`() {
        val condition = "(function() { return true; })()"
        val js = BaseLocator.waitForConditionJs(condition, 5000)
        assertTrue(js.contains(condition))
    }

    @Test
    fun `JS output uses correct timeout value`() {
        val js1 = BaseLocator.waitForConditionJs("true", 1000)
        assertTrue(js1.contains("1000"))

        val js2 = BaseLocator.waitForConditionJs("true", 30000)
        assertTrue(js2.contains("30000"))
    }

    @Test
    fun `condition for Visible SelectorState contains visibility check`() {
        val parts = "['.my-element']"
        val condition = "(function() { var list = window.__kmpwright_resolveAll($parts); if (!list || list.length === 0) return false; var el = list[0]; var rect = el.getBoundingClientRect(); return rect.width > 0 && rect.height > 0 && window.getComputedStyle(el).visibility !== 'hidden'; })()"
        val js = BaseLocator.waitForConditionJs(condition, 5000)
        assertTrue(js.contains("getBoundingClientRect"))
        assertTrue(js.contains("visibility"))
    }

    @Test
    fun `condition for Attached SelectorState checks element presence`() {
        val parts = "['.my-element']"
        val condition = "(function() { var list = window.__kmpwright_resolveAll($parts); return list && list.length > 0; })()"
        val js = BaseLocator.waitForConditionJs(condition, 5000)
        assertTrue(js.contains("__kmpwright_resolveAll"))
        assertTrue(js.contains("list.length > 0"))
    }

    @Test
    fun `condition for Detached SelectorState checks absence`() {
        val parts = "['.my-element']"
        val condition = "(function() { var list = window.__kmpwright_resolveAll($parts); return !list || list.length === 0; })()"
        val js = BaseLocator.waitForConditionJs(condition, 5000)
        assertTrue(js.contains("!list || list.length === 0"))
    }
}
