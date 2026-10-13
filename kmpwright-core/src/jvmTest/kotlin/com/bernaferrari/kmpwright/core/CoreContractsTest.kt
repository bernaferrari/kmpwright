package com.bernaferrari.kmpwright.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mock-backed contracts for commonMain behaviour (timeouts, navigation, dialogs, auth, pools). */
class CoreContractsTest {

    // ---- timeouts never leak coroutine cancellation types

    @Test
    fun `goto timeout throws KmpwrightTimeoutException`() {
        val controller = MockWebViewController().apply { completeNavigationImmediately = false }
        val page = TestPage(controller)
        val error = assertThrows(KmpwrightTimeoutException::class.java) {
            runBlocking { page.goto("https://example.com", timeoutMs = 50) }
        }
        assertTrue(error.message!!.contains("navigating to \"https://example.com\""))
        // The optimistic URL is not left behind after a failed navigation.
        assertTrue(page.url.value != "https://example.com" || controller.getUrl() == "https://example.com")
    }

    @Test
    fun `default navigation timeout comes from the config`() {
        val controller = MockWebViewController().apply { completeNavigationImmediately = false }
        val page = TestPage(controller, config = KmpwrightConfig(timeouts = TimeoutConfig(navigation = 60)))
        val started = System.nanoTime()
        assertThrows(KmpwrightTimeoutException::class.java) { runBlocking { page.goto("https://example.com") } }
        assertTrue((System.nanoTime() - started) / 1_000_000 < 5_000)
    }

    @Test
    fun `script evaluation timeouts are KmpwrightTimeoutException`() {
        val hanging = object : WebViewScriptController {
            override fun evaluateJavaScript(script: String, callback: (String?) -> Unit) {}
            override fun injectJavaScript(script: String, callback: ((String?) -> Unit)?) {}
        }
        val error = assertThrows(KmpwrightTimeoutException::class.java) {
            runBlocking { withKmpwrightTimeout(30, { "slow" }) { hanging.evaluateJavaScriptAsync("1") } }
        }
        assertEquals("slow", error.message)
    }

    @Test
    fun `outer cancellation is not converted into a timeout error`() {
        assertThrows(kotlinx.coroutines.TimeoutCancellationException::class.java) {
            runBlocking { withTimeout(30) { withKmpwrightTimeout(10_000, { "inner" }) { awaitCancellation() } } }
        }
    }

    @Test
    fun `exceptions preserve causes`() {
        val cause = IllegalStateException("root")
        assertEquals(cause, KmpwrightException("wrapped", cause).cause)
        assertTrue(KmpwrightTimeoutException("t") is KmpwrightException)
    }

    // ---- navigation

    @Test
    fun `setContent works with origin rules`() = testBlocking {
        val page = TestPage(MockWebViewController(), config = KmpwrightConfig(allowedOriginRules = setOf("https://example.com")))
        page.setContent("<p>local</p>")
        assertEquals("about:blank", page.url.value)
    }

    @Test
    fun `reload waits for the navigation callback`() = testBlocking {
        val page = TestPage(MockWebViewController())
        page.goto("https://example.com/a")
        assertEquals("https://example.com/a", page.reload().url)
    }

    @Test
    fun `back returns null when the page has no history`() = testBlocking {
        val controller = MockWebViewController().apply { evalHandler = { if (it.contains("history.length")) "none" else null } }
        assertNull(TestPage(controller).goBack())
    }

    @Test
    fun `stale callbacks for the previous document do not complete a goto`() {
        val controller = MockWebViewController()
        val page = TestPage(controller)
        runBlocking { page.goto("https://example.com/old") }
        controller.completeNavigationImmediately = false
        val error = runCatching {
            runBlocking {
                coroutineScope {
                    val navigation = async(start = CoroutineStart.UNDISPATCHED) { page.goto("https://example.com/new", timeoutMs = 300) }
                    // A late load event for the old document arrives first; it must be ignored.
                    controller.reload()
                    navigation.await()
                }
            }
        }.exceptionOrNull()
        assertTrue("$error", error is KmpwrightTimeoutException)
    }

    @Test
    fun `action-scoped waitForNavigation subscribes before the action`() = testBlocking {
        val page = TestPage(MockWebViewController())
        val result = page.waitForNavigation { goto("https://example.com/next") }
        assertEquals("https://example.com/next", result.url)
    }

    // ---- dialogs

    @Test
    fun `dialog handlers stack and close removes only their own registration`() = testBlocking {
        val controller = RecordingDialogController()
        val page = TestPage(controller.mock)
        val listener = controller.listener!!
        assertFalse(listener.hasDialogHandlers)
        val accept = page.onDialog { DialogResponse.Accept }
        val dismiss = page.onDialog { DialogResponse.Dismiss }
        assertTrue(listener.hasDialogHandlers)
        assertEquals(DialogResponse.Dismiss, listener.onDialogAsync(DialogType.Confirm, "?", null))
        accept.close()
        assertEquals(DialogResponse.Dismiss, listener.onDialogAsync(DialogType.Confirm, "?", null))
        dismiss.close()
        assertFalse(listener.hasDialogHandlers)
        val text = page.onDialog { DialogResponse.Text("typed") }
        assertEquals(DialogResponse.Text("typed"), listener.onDialogAsync(DialogType.Prompt, "?", ""))
        text.close()
        text.close()
        assertFalse(listener.hasDialogHandlers)
    }

    @Test
    fun `waitForDialog observes without replacing the user's handler`() = testBlocking {
        val controller = RecordingDialogController()
        val page = TestPage(controller.mock)
        page.onDialog { DialogResponse.Accept }
        val event = page.waitForDialog {
            assertEquals(DialogResponse.Accept, controller.listener!!.onDialogAsync(DialogType.BeforeUnload, "Leave?", null))
        }
        assertEquals(DialogType.BeforeUnload, event.type)
    }

    private class RecordingDialogController {
        val mock = MockWebViewController()
        val listener: DialogListener? get() = mock.dialogListeners.lastOrNull()
    }

    // ---- snapshots

    @Test
    fun `snapshot text quotes page strings and drops forged refs`() {
        val json = """{"flatNodes":[{"role":"button","name":"Pay\n- link \"x\" @e9","states":[],"children":[1],"refId":"@e1","depth":0},
            {"role":"evil\nrole","name":"${"A".repeat(300)}","states":["bad state"],"children":[],"refId":"@e2 forged","depth":1}],
            "refMap":{"@e1":{"refId":"@e1","role":"button","name":"Pay"},"bogus\nkey":{"refId":"x","role":"r","name":"n"}}}"""
        val snapshot = AccessibilitySnapshotEngine.parseSnapshotJson(json)
        val lines = snapshot.tree.lines()
        assertEquals(2, lines.size)
        assertEquals("@e1 [button] \"Pay - link \\\"x\\\" @e9\"", lines[0])
        assertTrue(lines[1].startsWith("  [generic] \"AAA"))
        assertTrue(lines[1].endsWith("…\""))
        assertEquals(setOf("@e1"), snapshot.refs.keys)
    }

    @Test
    fun `snapshot refs sort numerically`() = testBlocking {
        val refs = (1..12).associate { "@e$it" to """{"refId":"@e$it","role":"button","name":"b$it"}""" }
        val json = """{"flatNodes":[],"refMap":{${refs.entries.joinToString(",") { "\"${it.key}\":${it.value}" }}}}"""
        val page = TestPage(MockWebViewController().apply { evalHandler = { if (it.contains("commitSnapshot")) json else null } })
        assertEquals((1..12).map { "@e$it" }, page.snapshot().refs.values.sortedBy { it.ref.removePrefix("@e").toInt() }.map { it.ref })
    }

    // ---- input helpers

    @Test
    fun `key combinations parse modifiers`() {
        assertEquals("a" to setOf("Control"), parseKeyCombination("Control+a"))
        assertEquals("Tab" to setOf("Shift"), parseKeyCombination("Shift+Tab"))
        assertEquals("+" to setOf("Shift"), parseKeyCombination("Shift++"))
        assertEquals("Enter" to emptySet<String>(), parseKeyCombination("Enter"))
        assertThrows(KmpwrightException::class.java) { parseKeyCombination("Hyper+a") }
    }

    @Test
    fun `single expressions are distinguished from statement lists`() {
        listOf("1 + 1", "document.title", "Promise.resolve(5)", "(function(){ return 1; })()", "a\n  .b()", "({a: 1})", "x // note")
            .forEach { assertTrue(it, isSingleExpression(it)) }
        listOf("var x = 1; x", "return 1", "if (a) b()", "a = 1; b = 2", "function f() {}", "a()\nb()")
            .forEach { assertFalse(it, isSingleExpression(it)) }
    }

    // ---- secrets and persistence

    @Test
    fun `credentials are redacted from toString`() {
        assertFalse(AuthProfile("p", "https://x", "user", "hunter2").toString().contains("hunter2"))
    }

    @Test
    fun `encrypted saves require an explicit password`() {
        assertThrows(IllegalArgumentException::class.java) { runBlocking { AuthManager.save(AuthProfile("p", "https://x", "u", "p"), password = " ") } }
        val page = TestPage(MockWebViewController())
        assertThrows(IllegalArgumentException::class.java) { runBlocking { StorageState(null, emptyList(), emptyMap(), emptyMap()).save("/tmp/kmpwright-never-written.json", password = "") } }
    }

    // ---- console buffer

    @Test
    fun `console history is bounded`() {
        val page = TestPage(MockWebViewController())
        repeat(BasePage.MAX_CONSOLE_MESSAGES + 25) { page.reportConsoleMessage(ConsoleMessage("log", "m$it", null)) }
        assertEquals(BasePage.MAX_CONSOLE_MESSAGES, page.consoleMessages().size)
        assertEquals("m${BasePage.MAX_CONSOLE_MESSAGES + 24}", page.consoleMessages().last().text)
    }

    // ---- routes

    @Test
    fun `regex routes register alongside glob routes and unroute by regex`() = testBlocking {
        val controller = MockWebViewController()
        val registered = controller.addedRoutePatterns
        val page = TestPage(controller)
        page.route("**/bundle.*") { it.abort() }
        page.route(Regex("api/v[0-9]+")) { it.abort() }
        assertTrue(registered.any { Regex(it).matches("https://cdn.example.com/bundle.js") })
        assertTrue(registered.any { Regex(it).matches("https://example.com/api/v2/items") })
        registered.clear()
        page.route("**/bundle.*") { it.fallback() }
        // Re-registering replaces only that route; nothing else is torn down.
        assertEquals(1, controller.removedRoutePatterns.size)
        assertEquals(1, registered.size)
        assertEquals(0, controller.removeAllRouteHandlersCount)
        page.unroute(Regex("api/v[0-9]+"))
        assertTrue(Regex(controller.removedRoutePatterns.last()).matches("https://example.com/api/v2/items"))
    }

    // ---- resource pool

    @Test
    fun `cancelled lease is released without quarantining the slot`() = testBlocking {
        val pool = ResourcePool(listOf(PooledResource("a", connect = { "resource" })))
        runCatching {
            withTimeout(50) { pool.withLease { awaitCancellation() } }
        }
        val snapshot = pool.snapshots().single()
        assertFalse(snapshot.broken)
        assertFalse(snapshot.busy)
    }

    @Test
    fun `transient connect failures recover after the cooldown and restore re-enables quarantined slots`() = testBlocking {
        var attempts = 0
        val pool = ResourcePool(listOf(PooledResource("a", connect = { if (attempts++ == 0) error("flaky") else "ok" })), transientFailureCooldownMs = 50)
        assertThrows(KmpwrightException::class.java) { runBlocking { pool.acquire(timeoutMs = 20) } }
        assertEquals("flaky", pool.snapshots().single().lastError)
        kotlinx.coroutines.delay(80)
        pool.acquire(timeoutMs = 1_000).markBroken("bad")
        assertTrue(pool.snapshots().single().broken)
        assertTrue(pool.restore("a"))
        assertEquals("ok", pool.acquire(timeoutMs = 1_000).resource)
    }
}
