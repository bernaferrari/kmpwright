package com.bernaferrari.kmpwright.test

import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bernaferrari.kmpwright.core.AndroidPlatformContext
import com.bernaferrari.kmpwright.core.Browser
import com.bernaferrari.kmpwright.core.Page
import com.bernaferrari.kmpwright.core.PageCapability
import com.bernaferrari.kmpwright.core.SupportLevel
import com.bernaferrari.kmpwright.core.supportLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The Android domain filter is enforced at the WebView request layer (reported Partial only because
 * subresource redirects, WebSockets and service-worker requests are invisible to those hooks). "127.0.0.1" and "localhost" reach
 * the same fixture server, so a request to the disallowed host would succeed (and be recorded by the
 * server) unless the filter blocks it.
 */
@RunWith(AndroidJUnit4::class)
class KmpwrightDomainFilterInstrumentedTest {
    @get:Rule
    val host = ActivityScenarioRule(WebViewHarnessActivity::class.java)

    private lateinit var browser: Browser
    private lateinit var page: Page
    private val served = CopyOnWriteArrayList<String>()
    private lateinit var server: LocalHttpServer

    private val svg = "<svg xmlns='http://www.w3.org/2000/svg' width='2' height='2'><rect width='2' height='2'/></svg>"

    @Before
    fun setUp() {
        server = LocalHttpServer { request ->
            served += request.path
            when {
                request.path.startsWith("/redirect") -> LocalHttpServer.FixtureResponse(302, "Found", "",
                    headers = mapOf("Location" to "http://localhost:${port()}/blocked-redirect"))
                request.path.endsWith(".svg") -> LocalHttpServer.FixtureResponse(200, "OK", svg, "image/svg+xml")
                request.path.startsWith("/data") -> LocalHttpServer.FixtureResponse(200, "OK", "{\"ok\":true}", "application/json")
                else -> LocalHttpServer.FixtureResponse(200, "OK", "<title>${request.path}</title><p id=p>${request.path}</p>")
            }
        }
        host.scenario.onActivity { activity ->
            browser = Browser(AndroidPlatformContext(activity, activity.resetWebView()))
            page = browser.page()
        }
    }

    private fun port(): String = server.origin.substringAfterLast(':')

    @After
    fun tearDown() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            if (this::browser.isInitialized) browser.dispose()
        }
        server.close()
    }

    private val allowed get() = server.origin
    private val blocked get() = "http://localhost:${port()}"

    @Test
    fun disallowedHostsFailAtTheRequestLayerWhileAllowedHostsWork() = runBlocking(Dispatchers.Main) {
        val capabilities = page.capabilities()
        assertEquals(SupportLevel.Partial, capabilities.supportLevel(PageCapability.DomainFiltering))
        assertTrue(capabilities.notes.any { it.contains("domain filter is enforced natively") })
        page.setAllowedDomains(listOf("127.0.0.1"))

        val start = page.goto("$allowed/start")
        assertTrue("allowed navigation: $start", start.ok)

        // fetch: no-cors would resolve (opaque) for an unfiltered cross-origin request.
        page.evaluate("""
            window.results = {};
            fetch('/data?allowed').then(function(r) { return r.json(); })
              .then(function(j) { results.allowedFetch = 'ok:' + j.ok; }, function(e) { results.allowedFetch = 'error'; });
            fetch('$blocked/data?blocked-fetch', {mode: 'no-cors'})
              .then(function() { results.blockedFetch = 'ok'; }, function(e) { results.blockedFetch = 'error'; });
            var good = new Image(); good.onload = function() { results.allowedImg = 'load'; }; good.onerror = function() { results.allowedImg = 'error'; };
            good.src = '/allowed.svg';
            var bad = new Image(); bad.onload = function() { results.blockedImg = 'load'; }; bad.onerror = function() { results.blockedImg = 'error'; };
            bad.src = '$blocked/blocked-img.svg';
            var frame = document.createElement('iframe'); frame.src = '$blocked/blocked-frame'; document.body.appendChild(frame);
            var okFrame = document.createElement('iframe'); okFrame.src = '/allowed-frame';
            okFrame.onload = function() { try { results.allowedFrame = okFrame.contentDocument.title; } catch (e) { results.allowedFrame = 'error'; } };
            document.body.appendChild(okFrame);
            true
        """.trimIndent())
        page.waitForFunction("['allowedFetch','blockedFetch','allowedImg','blockedImg','allowedFrame'].every(function(k) { return results[k] !== undefined; })", 15_000)
        assertEquals("ok:true", page.evaluate("results.allowedFetch").value)
        assertEquals("error", page.evaluate("results.blockedFetch").value)
        assertEquals("load", page.evaluate("results.allowedImg").value)
        assertEquals("error", page.evaluate("results.blockedImg").value)
        assertEquals("/allowed-frame", page.evaluate("results.allowedFrame").value)

        // Navigation through the API and from page script.
        val navigation = page.goto("$blocked/blocked-nav")
        assertFalse("disallowed navigation must fail: $navigation", navigation.ok)
        assertTrue(navigation.error.orEmpty(), navigation.error.orEmpty().contains("domain filter"))
        page.evaluate("location.href = '$blocked/blocked-script-nav'; true")
        delay(1_000)
        assertEquals("/start", page.evaluate("document.title").value)

        // Not covered (documented in the capability notes): WebView exposes no hook for server redirects of
        // subresource requests, so fetch('/redirect') on an allowed host still reaches the redirect target.

        // A main-frame server redirect to a disallowed host is cancelled before it is requested.
        val redirected = page.goto("$allowed/redirect")
        assertFalse("redirect to a disallowed host must fail: $redirected", redirected.ok)
        assertTrue(redirected.error.orEmpty(), redirected.error.orEmpty().contains("redirected to"))

        delay(500)
        val reached = served.filter { it.contains("blocked") }
        assertTrue("disallowed requests must never reach the server: $reached", reached.isEmpty())
        assertTrue(served.contains("/redirect"))

        val after = page.goto("$allowed/after")
        assertTrue("allowed navigation still works: $after", after.ok)
        assertEquals("/after", page.evaluate("document.title").value)

        // Clearing the filter restores access.
        page.setAllowedDomains(emptyList())
        assertTrue(page.goto("$blocked/unblocked").ok)
        assertTrue(served.contains("/unblocked"))
    }

    @Test
    fun historyTraversalUsesWebViewHistory() = runBlocking(Dispatchers.Main) {
        assertTrue(page.goto("$allowed/one").ok)
        val started = System.nanoTime()
        assertNull(page.goBack())
        assertNull(page.goForward())
        assertTrue("no-history traversal must not wait for a probe", (System.nanoTime() - started) / 1_000_000 < 1_000)
        assertTrue(page.goto("$allowed/two").ok)
        assertTrue(page.goBack()!!.url.endsWith("/one"))
        assertTrue(page.goForward()!!.url.endsWith("/two"))
        assertNull(page.goForward())
    }
}
