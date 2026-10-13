@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.bernaferrari.kmpwright.core

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlin.test.*

class IOSReviewRepairDocumentTest {
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
            page.touchscreen.swipe(20.0, 20.0, 150.0, 20.0, SwipeOptions(speed = 2_000.0))
            assertEquals("true", page.evaluate("events.length >= 3 && events[events.length-1].kind === 'touchend' && events.every(function(e){return e.id===events[0].id && e.target==='a' && e.finger==='a';})").value)
            page.evaluate("events=[]; 1")
            coroutineScope {
                val gesture = launch(start = CoroutineStart.UNDISPATCHED) { page.touchscreen.longTap(20.0, 20.0, LongTapOptions(durationMs = 60_000)) }
                page.waitForFunction("events.length > 0", timeoutMs = 2_000)
                gesture.cancelAndJoin()
            }
            assertEquals("touchcancel", page.evaluate("events[events.length-1].kind").value)
            page.evaluate("document.body.insertAdjacentHTML('beforeend','<input id=editor>'); window.keys=[]; ['keydown','keyup'].forEach(function(k){document.addEventListener(k,function(e){keys.push(k+':'+e.key);});}); 1")
            coroutineScope {
                page.locator("#editor").focus()
                val press = launch(start = CoroutineStart.UNDISPATCHED) { page.keyboard.press("Shift+A", PressOptions(delayMs = 60_000)) }
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
            for (frame in listOf(page.frameLocator("iframe"), page.locator("#frames").frameLocator("iframe"))) {
                assertFailsWith<KmpwrightException> { frame.locator("button").click(ClickOptions(timeoutMs = 300)) }
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
