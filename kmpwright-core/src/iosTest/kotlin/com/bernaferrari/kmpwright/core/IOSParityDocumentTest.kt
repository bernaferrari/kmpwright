package com.bernaferrari.kmpwright.core

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Playwright-parity behaviour of the WKWebView backend: keyboard combinations and per-character typing,
 * Playwright delay semantics, scoped frame locators, refs in chains, same-document navigation, title
 * updates and DOMContentLoaded. Needs an app host: run with `scripts/run-ios-webkit-tests.sh`.
 */
class IOSParityDocumentTest {
    private val eventLogger = """<script>window.log=[];window.times={};
        ['mousedown','mouseup','click','dblclick','keydown','keyup','input'].forEach(function(t){
          document.addEventListener(t, function(e){
            log.push(t + (/mouse|click/.test(t) ? ':' + e.detail : '') + (e.key ? ':' + e.key : ''));
            (times[t] = times[t] || []).push(performance.now());
          }, true);
        });</script>"""

    private suspend fun Page.js(expression: String) = evaluate(expression).value

    @Test
    fun keyboard_combinations_and_typing_follow_playwright() = runWebKitTest {
        requireWebKitDocuments()
        val page = IOSPage(IOSWebViewController())
        try {
            page.setContent("<input id=i value='old text'>$eventLogger")
            page.locator("#i").focus()
            page.keyboard.press("ControlOrMeta+A")
            page.keyboard.type("hi")
            assertEquals("hi", page.locator("#i").inputValue())
            assertEquals("keydown:h,input,keyup:h,keydown:i,input,keyup:i",
                page.js("log.filter(function(x){return /^(keydown|keyup|input)/.test(x) && !/Meta|Control/.test(x)}).slice(-6).join(',')"))
            page.keyboard.press("Space")
            page.keyboard.press("Shift+KeyB")
            assertEquals("hi B", page.locator("#i").inputValue())
            page.keyboard.press("Meta+A")
            page.keyboard.press("Backspace")
            assertEquals("", page.locator("#i").inputValue())
            // "Control+A" must dispatch a real Control chord, never the literal text "Control+A".
            page.keyboard.type("abc")
            page.keyboard.press("Control+A")
            assertEquals("abc", page.locator("#i").inputValue())
        } finally { page.close() }
    }

    @Test
    fun click_press_delays_and_dblclick_sequence() = runWebKitTest {
        requireWebKitDocuments()
        val page = IOSPage(IOSWebViewController())
        try {
            page.setContent("<button id=b>B</button><input id=i>$eventLogger")
            page.locator("#b").dblclick()
            assertEquals("mousedown:1,mouseup:1,click:1,mousedown:2,mouseup:2,click:2,dblclick:2", page.js("log.join(',')"))
            page.locator("#b").click(ClickOptions(delayMs = 300))
            assertTrue(page.js("times.mouseup[2] - times.mousedown[2]").toDouble() >= 250)
            page.locator("#i").press("a", PressOptions(delayMs = 300))
            assertTrue(page.js("times.keyup[times.keyup.length-1] - times.keydown[times.keydown.length-1]").toDouble() >= 250)
        } finally { page.close() }
    }

    @Test
    fun frame_locators_are_scoped_and_refs_work_in_chains() = runWebKitTest {
        requireWebKitDocuments()
        val page = IOSPage(IOSWebViewController())
        try {
            page.setContent("""
                <div id=first><iframe srcdoc="<p>first frame</p>"></iframe></div>
                <div id=second><iframe srcdoc="<p>second frame</p>"></iframe></div>
                <div id=box><button>Inside</button></div>
            """.trimIndent())
            page.waitForFunction("document.querySelectorAll('iframe')[1].contentDocument.querySelector('p')")
            assertEquals("second frame", page.locator("#second").frameLocator("iframe").locator("p").innerText())
            assertEquals("first frame", page.locator("#first").frameLocator("iframe").locator("p").innerText())
            val ref = page.snapshot().refs.values.first { it.name == "Inside" }.ref
            assertEquals("Inside", page.locator("#box").locator(ref).innerText())
            assertEquals("Inside", page.locator("#box").locator("ref=" + ref.removePrefix("@")).innerText())
        } finally { page.close() }
    }

    @Test
    fun same_document_navigations_update_url_and_title() = runWebKitTest {
        requireWebKitDocuments()
        val controller = IOSWebViewController()
        val page = IOSPage(controller)
        try {
            controller.loadHtmlAsync("<title>Start</title><p>start</p>", "https://fixture.invalid/start", 10_000)
            val pushed = page.waitForNavigation { evaluate("history.pushState({}, '', '/pushed')") }
            assertEquals("https://fixture.invalid/pushed", pushed.url)
            assertEquals("https://fixture.invalid/pushed", page.url.value)
            page.waitForNavigation { evaluate("location.hash = 'section'") }
            assertEquals("https://fixture.invalid/pushed#section", page.url.value)
            page.evaluate("document.title = 'Renamed'")
            withTimeout(3_000) { while (page.title.value != "Renamed") delay(20) }
        } finally { page.close() }
    }

    @Test
    fun set_content_resolves_at_dom_content_loaded_and_assertions_fail_as_assertion_errors() = runWebKitTest {
        requireWebKitDocuments()
        val page = IOSPage(IOSWebViewController())
        try {
            page.setContent("<p id=c>content</p><img src='https://10.255.255.1/slow.png'>", SetContentOptions(waitUntil = LoadState.DOMContentLoaded))
            assertEquals("content", page.locator("#c").innerText())
            assertTrue(page.js("document.readyState") != "loading")
            page.setContent("""<script>setTimeout(function(){ var c = document.createElement('input'); c.type = 'checkbox'; c.id = 'c';
                document.body.appendChild(c); }, 300);</script>""")
            page.locator("#c").check()
            assertTrue(page.locator("#c").isChecked())
            val error = runCatching { expect(page.locator("#missing")).toBeEmpty(timeoutMs = 200) }.exceptionOrNull()
            assertTrue(error is KmpwrightAssertionError, "$error")
        } finally { page.close() }
    }
}
