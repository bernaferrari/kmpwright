@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.bernaferrari.kmpwright.core

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlin.test.*

class IOSReviewRepairDocumentTest {
    @Test fun storage_restores_literal_keys_and_clears_empty_maps_in_a_real_document() = runWebKitTest {
        requireWebKitDocuments()
        val controller = IOSWebViewController()
        val page = IOSPage(controller)
        try {
            controller.loadHtml("<p id='ready'>State</p>", "https://fixture.invalid/state")
            page.waitForFunction("document.getElementById('ready') !== null")
            page.localStorage.set("__proto__", "literal")
            page.sessionStorage.set("tab", "one")
            val saved = page.storageState()
            assertEquals("https://fixture.invalid/state", saved.url)
            page.localStorage.clear()
            page.sessionStorage.clear()
            page.setStorageState(saved)
            assertEquals("literal", page.localStorage.get("__proto__"))
            assertEquals("one", page.sessionStorage.get("tab"))
            page.setStorageState(saved.copy(localStorage = emptyMap(), sessionStorage = emptyMap()))
            assertTrue(page.localStorage.entries().isEmpty())
            assertTrue(page.sessionStorage.entries().isEmpty())
        } finally { page.close() }
    }

    @Test fun workflows_survive_document_replacement_and_policy_timeouts() = runWebKitTest {
        requireWebKitDocuments()
        val page = IOSPage(IOSWebViewController())
        try {
            repeat(4) { round ->
                page.setContent("""<label for=name>Name</label><input id=name>
                    <button onclick="document.querySelector('[role=status]').textContent='Hello, '+document.getElementById('name').value">Greet</button>
                    <p role=status></p>""")
                page.getByLabel("Name").fill("Ada $round")
                page.getByRole("button", name = "Greet").click()
                expect(page.getByRole("status")).toHaveText("Hello, Ada $round")
                page.waitForLoadState(LoadState.Load, timeoutMs = 1_000)
            }
            page.setActionPolicy(ActionPolicy(confirmActions = setOf(ActionType.Type)))
            page.setPolicyConfirmCallback { _, _ -> delay(5_000); true }
            withTimeout(2_000) {
                assertFailsWith<KmpwrightTimeoutException> { page.keyboard.type("unapproved", timeoutMs = 100) }
            }
            page.setActionPolicy(ActionPolicy())
            page.getByLabel("Name").fill("Still usable")
            assertEquals("Still usable", page.getByLabel("Name").inputValue())
            coroutineScope {
                val wait = async(start = CoroutineStart.UNDISPATCHED) { runCatching { page.waitForEvent("console", 60_000) } }
                page.close()
                val failure = withTimeout(1_000) { wait.await() }.exceptionOrNull()
                assertTrue(failure is KmpwrightException && failure.message.orEmpty().contains("closed"))
            }
        } finally { page.close() }
    }

    @Test fun wheel_obeys_preventDefault() = runWebKitTest {
        requireWebKitDocuments()
        val page = IOSPage(IOSWebViewController(KmpwrightConfig(viewport = Viewport(320, 480))))
        try {
            page.setContent("<body style='margin:0'><div id=scroll style='height:100px;overflow:auto'><div style='height:1000px'>scroll</div></div></body>")
            page.mouse.move(20.0, 20.0)
            page.evaluate("document.getElementById('scroll').addEventListener('wheel', function(e){e.preventDefault()}, {passive:false}); 1")
            page.mouse.wheel(0.0, 100.0)
            assertEquals("0", page.evaluate("document.getElementById('scroll').scrollTop").value)
        } finally { page.close() }
    }

    @Test fun touch_identity_target_and_cancellation_stay_consistent() = runWebKitTest {
        requireWebKitDocuments()
        val page = IOSPage(IOSWebViewController(KmpwrightConfig(viewport = Viewport(320, 480))))
        try {
            page.setContent("<body style='margin:0'><div id=a style='width:100px;height:100px;float:left'>A</div><div id=b style='width:100px;height:100px;float:left'>B</div></body>")
            page.evaluate("""window.events=[]; ['touchstart','touchmove','touchend','touchcancel'].forEach(function(k){document.addEventListener(k,function(e){var t=e.changedTouches[0];events.push({kind:k,id:t.identifier,target:e.target.id,finger:t.target.id});});}); 1""")
            page.touchscreen.swipe(20.0, 20.0, 150.0, 20.0, speed = 2_000.0)
            assertEquals("true", page.evaluate("events.length >= 3 && events[events.length-1].kind === 'touchend' && events.every(function(e){return e.id===events[0].id && e.target==='a' && e.finger==='a';})").value)
            page.evaluate("events=[]; 1")
            coroutineScope {
                val gesture = launch(start = CoroutineStart.UNDISPATCHED) { page.touchscreen.longTap(20.0, 20.0, durationMs = 60_000) }
                page.waitForFunction("events.length > 0", timeoutMs = 2_000)
                gesture.cancelAndJoin()
            }
            assertEquals("touchcancel", page.evaluate("events[events.length-1].kind").value)
            page.evaluate("document.body.insertAdjacentHTML('beforeend','<input id=editor>'); window.keys=[]; ['keydown','keyup'].forEach(function(k){document.addEventListener(k,function(e){keys.push(k+':'+e.key);});}); 1")
            coroutineScope {
                page.locator("#editor").focus()
                val press = launch(start = CoroutineStart.UNDISPATCHED) { page.keyboard.press("Shift+A", delayMs = 60_000) }
                page.waitForFunction("keys.includes('keydown:A')", timeoutMs = 2_000)
                press.cancelAndJoin()
            }
            assertEquals("true", page.evaluate("keys.includes('keyup:A') && keys.includes('keyup:Shift')").value)
        } finally { page.close() }
    }

    @Test fun ambiguous_frames_and_closed_owners_are_rejected() = runWebKitTest {
        requireWebKitDocuments()
        val browser = Browser(IOSPlatformContext())
        val context = browser.newContext()
        try {
            val page = browser.page()
            page.setContent("<div id=frames><iframe srcdoc='<button>First</button>'></iframe><iframe srcdoc='<button>Second</button>'></iframe></div>")
            assertEquals(2, page.locator("iframe").count())
            for (frame in listOf(page.frameLocator("iframe"), page.locator("#frames").frameLocator("iframe"))) {
                val failure = assertFailsWith<KmpwrightException> { frame.locator("button").click(timeoutMs = 2_000) }
                assertTrue(failure.message.orEmpty().contains("Strict", ignoreCase = true), failure.message)
            }
            context.close()
            assertFailsWith<IllegalStateException> { context.newPage() }
            browser.close()
            assertFailsWith<IllegalStateException> { browser.newPage() }
            assertFailsWith<IllegalStateException> { browser.newContext() }
            assertFalse(browser.isConnected.value)
        } finally { context.close(); browser.close() }
    }
}
