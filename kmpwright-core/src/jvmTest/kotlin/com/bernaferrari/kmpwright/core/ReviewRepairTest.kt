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
import kotlin.test.assertFailsWith

class ReviewRepairTest {
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
        val controller = MockWebViewController()
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
                    if (event) page.waitForEvent("console", WaitForEventOptions(timeoutMs = 60_000))
                    else page.waitForNavigation(WaitForNavigationOptions(timeoutMs = 60_000))
                }
            }
            page.close()
            withTimeout(1_000) { pending.await() }
        }
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
            CdpLocator(controller, "button", KmpwrightConfig()).click(ClickOptions(timeoutMs = 200))
        }
        assertTrue(failure.message!!, failure.message!!.contains("intercepted"))
        assertEquals(0, inputCommands)
    }
}
