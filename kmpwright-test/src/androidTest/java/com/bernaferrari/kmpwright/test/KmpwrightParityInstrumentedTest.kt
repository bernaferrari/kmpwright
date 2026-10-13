package com.bernaferrari.kmpwright.test

import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bernaferrari.kmpwright.core.AndroidPlatformContext
import com.bernaferrari.kmpwright.core.Browser
import com.bernaferrari.kmpwright.core.ClickOptions
import com.bernaferrari.kmpwright.core.GotoOptions
import com.bernaferrari.kmpwright.core.KmpwrightAssertionError
import com.bernaferrari.kmpwright.core.KmpwrightTimeoutException
import com.bernaferrari.kmpwright.core.LoadState
import com.bernaferrari.kmpwright.core.Page
import com.bernaferrari.kmpwright.core.PressOptions
import com.bernaferrari.kmpwright.core.SetContentOptions
import com.bernaferrari.kmpwright.core.ActionOptions
import com.bernaferrari.kmpwright.core.expect
import com.bernaferrari.kmpwright.core.waitForNavigation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.TimeSource

/**
 * Playwright-parity behaviour of the Android WebView backend: keyboard combinations and per-character
 * typing, Playwright delay semantics, scoped frame locators, refs in chains, same-document navigation,
 * DOMContentLoaded and real HTTP statuses.
 */
@RunWith(AndroidJUnit4::class)
class KmpwrightParityInstrumentedTest {
    @get:Rule
    val host = ActivityScenarioRule(WebViewHarnessActivity::class.java)

    private lateinit var browser: Browser
    private lateinit var page: Page
    private lateinit var server: LocalHttpServer

    @Before
    fun setUp() {
        server = LocalHttpServer { request ->
            when {
                request.path.startsWith("/status/") -> {
                    val status = request.path.removePrefix("/status/").toInt()
                    LocalHttpServer.FixtureResponse(status, "Status", "<title>Status $status</title><p>status $status</p>")
                }
                request.path == "/slow.png" -> { Thread.sleep(3_000); LocalHttpServer.FixtureResponse(404, "Not Found", "") }
                request.path == "/slow-load" -> LocalHttpServer.FixtureResponse(200, "OK", "<title>Slow</title><p id=ready>ready</p><img src=/slow.png>")
                else -> LocalHttpServer.FixtureResponse(200, "OK", "<title>Root</title><p id=path>${request.path}</p>")
            }
        }
        host.scenario.onActivity { activity ->
            browser = Browser(AndroidPlatformContext(activity, activity.resetWebView()))
            page = browser.page()
        }
    }

    @After
    fun tearDown() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            if (this::browser.isInitialized) browser.dispose()
        }
        server.close()
    }

    private fun pageTest(block: suspend () -> Unit) = runBlocking(Dispatchers.Main) { block() }

    private suspend fun js(expression: String) = page.evaluate(expression).value

    private val eventLogger = """<script>window.log=[];window.times={};
        ['mousedown','mouseup','click','dblclick','keydown','keyup','input'].forEach(function(t){
          document.addEventListener(t, function(e){
            log.push(t + (/mouse|click/.test(t) ? ':' + e.detail : '') + (e.key ? ':' + e.key : ''));
            (times[t] = times[t] || []).push(performance.now());
          }, true);
        });</script>"""

    @Test
    fun pageKeyboardPressSupportsCombinationsAndSpace() = pageTest {
        page.setContent("<input id=i value='old text'>")
        page.locator("#i").focus()
        page.keyboard.press("Control+A")
        page.keyboard.type("new")
        assertEquals("new", page.locator("#i").inputValue())
        page.keyboard.press("ControlOrMeta+A")
        page.keyboard.press("Backspace")
        assertEquals("", page.locator("#i").inputValue())
        page.keyboard.press("KeyA")
        page.keyboard.press("Space")
        page.keyboard.press("Shift+KeyB")
        assertEquals("a B", page.locator("#i").inputValue())
    }

    @Test
    fun keyboardTypeSendsKeyEventsForEveryCharacter() = pageTest {
        page.setContent("<input id=i>$eventLogger")
        page.locator("#i").focus()
        page.keyboard.type("hi")
        assertEquals("hi", page.locator("#i").inputValue())
        assertEquals("keydown:h,input,keyup:h,keydown:i,input,keyup:i",
            js("log.filter(function(x){return /^(keydown|keyup|input)/.test(x)}).join(',')"))
        page.keyboard.insertText("!")
        assertEquals("hi!", page.locator("#i").inputValue())
    }

    @Test
    fun delaysAndDoubleClickFollowPlaywright() = pageTest {
        page.setContent("<button id=b>B</button><input id=i>$eventLogger")
        page.locator("#b").dblclick()
        assertEquals("mousedown:1,mouseup:1,click:1,mousedown:2,mouseup:2,click:2,dblclick:2", js("log.join(',')"))
        page.locator("#b").click(ClickOptions(delayMs = 300))
        assertTrue(js("times.mouseup[2] - times.mousedown[2]").toDouble() >= 250)
        page.locator("#i").press("a", PressOptions(delayMs = 300))
        assertTrue(js("times.keyup[times.keyup.length-1] - times.keydown[times.keydown.length-1]").toDouble() >= 250)
        page.locator("#i").press("ControlOrMeta+A")
        page.locator("#i").press("x")
        assertEquals("x", page.locator("#i").inputValue())
    }

    @Test
    fun frameLocatorsAreScopedAndRefsWorkInChains() = pageTest {
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
        assertEquals("Inside", page.locator("#box").locator(ref.removePrefix("@")).innerText())
    }

    @Test
    fun checkWaitsAndSetInputFilesHonoursTheTimeout() = pageTest {
        page.setContent("""<script>setTimeout(function(){ var c = document.createElement('input'); c.type = 'checkbox'; c.id = 'c';
            document.body.appendChild(c); }, 300);</script>""")
        page.locator("#c").check()
        assertTrue(page.locator("#c").isChecked())
        val missing = runCatching { page.locator("#file").setInputFiles(emptyList(), ActionOptions(timeoutMs = 300)) }.exceptionOrNull()
        assertTrue("$missing", missing is KmpwrightTimeoutException)
        val empty = runCatching { expect(page.locator("#missing")).toBeEmpty(timeoutMs = 200) }.exceptionOrNull()
        assertTrue("$empty", empty is KmpwrightAssertionError)
    }

    @Test
    fun sameDocumentNavigationsUpdateUrlAndTitle() = pageTest {
        page.goto("${server.origin}/start")
        val pushed = page.waitForNavigation { evaluate("history.pushState({}, '', '/pushed')") }
        assertEquals("${server.origin}/pushed", pushed.url)
        assertEquals("${server.origin}/pushed", page.url.value)
        page.waitForNavigation { evaluate("location.hash = 'section'") }
        assertEquals("${server.origin}/pushed#section", page.url.value)
        page.evaluate("document.title = 'Renamed'")
        withTimeout(3_000) { while (page.title.value != "Renamed") delay(20) }
    }

    @Test
    fun domContentLoadedResolvesBeforeLoadAndStatusesAreReal() = pageTest {
        val started = TimeSource.Monotonic.markNow()
        val result = page.goto("${server.origin}/slow-load", GotoOptions(waitUntil = LoadState.DOMContentLoaded))
        assertTrue("goto(DOMContentLoaded) took ${started.elapsedNow()}", started.elapsedNow().inWholeMilliseconds < 2_500)
        assertTrue(result.ok)
        assertEquals("ready", page.locator("#ready").innerText())
        page.waitForLoadState(LoadState.Load)
        val content = TimeSource.Monotonic.markNow()
        page.setContent("<p id=c>content</p><img src='${server.origin}/slow.png'>", SetContentOptions(waitUntil = LoadState.DOMContentLoaded))
        assertTrue("setContent(DOMContentLoaded) took ${content.elapsedNow()}", content.elapsedNow().inWholeMilliseconds < 2_500)
        assertEquals("content", page.locator("#c").innerText())
        page.waitForLoadState(LoadState.Load)

        val notFound = page.goto("${server.origin}/status/404")
        assertEquals(404, notFound.status)
        assertFalse(notFound.ok)
        assertNull(notFound.error)
        val created = page.goto("${server.origin}/status/201")
        assertEquals(201, created.status)
        assertTrue(created.ok)
    }
}
