package com.bernaferrari.kmpwright.core

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.assertFailsWith

class ReviewRepairTest {
    @Test fun `action deadlines include policy callbacks`(): Unit = runBlocking {
        val config = KmpwrightConfig(timeouts = TimeoutConfig(action = 75))
        for (kind in 0..8) {
            val locator: BaseLocator = if (kind >= 7) CdpLocator(MockWebViewController(), "button", config)
            else object : BaseLocator(MockWebViewController(), "button", config) {
                override fun createLocator(newSelector: String, options: FilterOptions?): Locator = error("Unused")
                override suspend fun evaluateJavaScriptAsync(script: String): String? = error("Policy has not completed")
            }
            locator.policyChecker = { delay(60_000) }
            withTimeout(1_000) {
                assertFailsWith<KmpwrightTimeoutException>("Operation $kind") {
                    when (kind) {
                        0, 7 -> locator.click(timeoutMs = 75)
                        1, 8 -> locator.dblclick(timeoutMs = 75)
                        2 -> locator.tap(timeoutMs = 75)
                        3 -> locator.fill("value", timeoutMs = 75)
                        4 -> locator.hover(timeoutMs = 75)
                        5 -> locator.scrollIntoViewIfNeeded(timeoutMs = 75)
                        else -> locator.focus(timeoutMs = 75)
                    }
                }
            }
        }
    }

    @Test fun `locator waits and reads bound delayed bridge replies`(): Unit = runBlocking {
        val config = KmpwrightConfig(timeouts = TimeoutConfig(action = 75, waitForSelector = 75))
        val locator = object : BaseLocator(MockWebViewController(), "button", config) {
            override fun createLocator(newSelector: String, options: FilterOptions?): Locator = error("Unused")
            override suspend fun evaluateJavaScriptAsync(script: String): String? {
                delay(60_000)
                return """{"__kw":1,"v":true}"""
            }
        }
        withTimeout(1_000) { assertFailsWith<KmpwrightTimeoutException> { locator.waitFor(timeoutMs = 75) } }
        withTimeout(1_000) { assertFailsWith<KmpwrightTimeoutException> { locator.innerText() } }
    }

    @Test fun `multi stage locators share one action deadline`(): Unit = runBlocking {
        val config = KmpwrightConfig(timeouts = TimeoutConfig(action = 100, waitForSelector = 100))
        val controller = MockWebViewController()
        fun locator(selector: String): BaseLocator = object : BaseLocator(controller, selector, config) {
            override fun createLocator(newSelector: String, options: FilterOptions?): Locator = locator(newSelector)
            override suspend fun evaluateJavaScriptAsync(script: String): String? {
                delay(60)
                val value = if (script.contains("var rect = el.getBoundingClientRect()"))
                    JsonPrimitive("""{"x":0,"y":0,"width":10,"height":10}""").toString() else "true"
                return """{"__kw":1,"v":$value}"""
            }
        }
        for (kind in 0..6) {
            val source = locator("button")
            withTimeout(1_000) {
                assertFailsWith<KmpwrightTimeoutException>("Operation $kind") {
                    when (kind) {
                        0 -> source.blur(timeoutMs = 100)
                        1 -> source.dispatchEvent("click", timeoutMs = 100)
                        2 -> source.evaluate("el => el.id", timeoutMs = 100)
                        3 -> source.setChecked(true, timeoutMs = 100)
                        4 -> source.setInputFiles(emptyList(), timeoutMs = 100)
                        5 -> source.dragTo(locator(".target"), timeoutMs = 100)
                        else -> source.screenshot(timeoutMs = 100)
                    }
                }
            }
        }
    }

    @Test fun `restore refusal preserves existing cookies for every policy stage`() = runBlocking {
        for (denied in listOf(ActionType.DeleteCookie, ActionType.SetCookie, ActionType.Navigate, ActionType.Storage)) {
            val controller = MockWebViewController()
            val original = Cookie("old", "session", "example.com")
            controller.cookies.add(original)
            val page = TestPage(controller)
            page.setActionPolicy(ActionPolicy(deniedActions = setOf(denied)))
            assertFailsWith<KmpwrightException> {
                page.setStorageState(StorageState("https://example.com", listOf(Cookie("new", "session", "example.com")), emptyMap(), emptyMap()))
            }
            assertEquals("Refusal at $denied", listOf(original), controller.cookies)
            page.close()
        }
    }

    @Test fun `origin refusal preserves cookies and confirmations are not repeated`() = runBlocking {
        val controller = MockWebViewController { onEval { script -> if (script.contains("Saved state origin")) "true" else null } }
        val original = Cookie("old", "session", "example.com")
        controller.cookies.add(original)
        val page = TestPage(controller, config = KmpwrightConfig(allowedOriginRules = setOf("https://example.com")))
        assertFailsWith<KmpwrightException> {
            page.setStorageState(StorageState("https://other.example", emptyList(), emptyMap(), emptyMap()))
        }
        assertEquals(listOf(original), controller.cookies)
        val confirmations = mutableListOf<ActionType>()
        page.setActionPolicy(ActionPolicy(defaultAction = PolicyResult.Confirm))
        page.setPolicyConfirmCallback { action, _ -> confirmations.add(action); confirmations.count { it == action } == 1 }
        page.setStorageState(StorageState("https://example.com", emptyList(), emptyMap(), emptyMap()))
        assertEquals(setOf(ActionType.DeleteCookie, ActionType.SetCookie, ActionType.Navigate, ActionType.Storage), confirmations.toSet())
        assertEquals(4, confirmations.size)
        page.close()
    }

    @Test fun `close immediately ends navigation and event waiters`() = runBlocking {
        for (event in listOf(false, true)) {
            val page = TestPage()
            val pending = async(start = CoroutineStart.UNDISPATCHED) {
                assertFailsWith<KmpwrightException> {
                    if (event) page.waitForEvent("console", timeoutMs = 60_000)
                    else page.waitForNavigation(timeoutMs = 60_000)
                }
            }
            page.close()
            withTimeout(1_000) { pending.await() }
        }
    }

    @Test fun `close ends every request and response waiter including calls after close`() = runBlocking {
        for (kind in 0..5) {
            val page = TestPage()
            suspend fun wait() {
                when (kind) {
                    0 -> page.waitForRequest("**/missing", 60_000)
                    1 -> page.waitForRequest(Regex("missing"), 60_000)
                    2 -> page.waitForRequest(60_000) { false }
                    3 -> page.waitForResponse("**/missing", 60_000)
                    4 -> page.waitForResponse(Regex("missing"), 60_000)
                    else -> page.waitForResponse(60_000) { false }
                }
            }
            val pending = async(start = CoroutineStart.UNDISPATCHED) { assertFailsWith<KmpwrightException> { wait() } }
            page.close()
            withTimeout(1_000) { pending.await() }
            withTimeout(1_000) { assertFailsWith<KmpwrightException> { wait() } }
        }
    }

    @Test fun `locator action bounds a slow runtime response`(): Unit = runBlocking {
        val controller = MockWebViewController()
        val locator = object : BaseLocator(controller, "button", KmpwrightConfig()) {
            override fun createLocator(newSelector: String, options: FilterOptions?): Locator = error("Unused")
            override suspend fun evaluateJavaScriptAsync(script: String): String? {
                delay(60_000)
                return """{"__kw":1,"v":{"state":"done","result":true}}"""
            }
        }
        withTimeout(1_000) {
            assertFailsWith<KmpwrightTimeoutException> { locator.click(timeoutMs = 75) }
        }
    }

    @Test fun `mouse click deadline still releases its button`() = runBlocking {
        val events = mutableListOf<String>()
        val bridge = object : CdpInputBridge {
            override val inputState = CdpInputState()
            override suspend fun sendCdpCommand(method: String, params: Map<String, Any?>): CdpResponse {
                events += params["type"].toString()
                return CdpResponse(id = 1)
            }
        }
        withTimeout(1_000) {
            assertFailsWith<KmpwrightTimeoutException> { CdpMouse(bridge).click(10.0, 10.0, delayMs = 60_000, timeoutMs = 75) }
        }
        assertTrue(events.toString(), "mousePressed" in events)
        assertEquals("mouseReleased", events.last())
    }

    @Test fun `cancelled mobile keyboard press releases its key`() = runBlocking {
        val controller = MockWebViewController { onEval { "{\"__kw\":1,\"v\":true}" } }
        val input = FocusedElementInput(controller, KmpwrightConfig())
        val job = launch(start = CoroutineStart.UNDISPATCHED) { input.press("Shift+A", 60_000, emptySet()) }
        job.cancelAndJoin()
        assertEquals(2, controller.executedScripts.size)
        assertTrue(controller.executedScripts.last().contains("pressUp"))
    }

    @Test fun `remote uploads fail explicitly before reading local files`() = runBlocking {
        val locator = CdpLocator(MockWebViewController(), "input", KmpwrightConfig(cdp = CdpConfig(websocketUrl = "wss://remote.invalid/devtools")))
        val failure = assertFailsWith<KmpwrightUnsupportedException> { locator.setInputFiles(listOf("/not-a-local-file")) }
        assertTrue(failure.message!!.contains("shared filesystem"))
    }

    @Test fun `frame input deadline retains the blocker and sends no click`() = runBlocking {
        var probes = 0
        var inputCommands = 0
        val engine = MockWebViewController { onEval {
            probes++
            if (probes == 1) "{\"__kw\":1,\"v\":{\"state\":\"done\",\"result\":{\"x\":10,\"y\":10}}}"
            else "{\"__kw\":1,\"v\":{\"state\":\"unstable\",\"rect\":{},\"d\":\"button\"}}"
        } }
        val controller = object : WebViewLocatorController by engine, CdpInputBridge {
            override val inputState = CdpInputState()
            override suspend fun pointerInRoot(x: Double, y: Double, force: Boolean): Pair<Double, Double> {
                delay(125)
                throw KmpwrightException("Frame pointer is intercepted")
            }
            override suspend fun sendCdpCommand(method: String, params: Map<String, Any?>): CdpResponse {
                inputCommands++
                return CdpResponse(id = 1)
            }
        }
        val failure = assertFailsWith<KmpwrightTimeoutException> {
            CdpLocator(controller, "button", KmpwrightConfig()).click(timeoutMs = 200)
        }
        assertTrue(failure.message!!, failure.message!!.contains("intercepted"))
        assertEquals(0, inputCommands)
    }
}
