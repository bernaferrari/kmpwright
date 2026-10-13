package com.bernaferrari.kmpwright.core

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Checks element identity in Chromium, rather than inspecting generated scripts. */
class LocatorIdentityRegressionTest {
    @Test fun `query stages preserve identity for reads waits assertions and descendants`() = runBlocking {
        val chrome = System.getenv("KMPWRIGHT_TEST_CHROME")
        assumeTrue("Set KMPWRIGHT_TEST_CHROME to execute browser contracts", !chrome.isNullOrBlank())
        val launched = launchBrowser(BrowserLaunchOptions(chrome!!))
        try {
            val page = launched.browser.newPage()
            page.setContent("""
                <div class="row"><span>First</span><button id="first">Go first</button></div>
                <div class="row"><span>Second</span><button id="second">Go second</button></div>
                <div><span id="nested">Unique text</span></div>
            """.trimIndent())
            val rows = page.locator(".row")
            assertEquals(1, rows.filter(hasText = "Second").count())
            assertEquals(1, rows.filter(hasNotText = "First").count())
            assertEquals(1, rows.filter(has = page.locator("#second")).count())
            assertEquals(1, rows.filter(hasNot = page.locator("#second")).count())
            assertEquals("second", rows.nth(1).locator("button").getAttribute("id"))
            assertEquals("second", rows.filter(hasText = "Second").first().locator("button").getAttribute("id"))
            assertTrue(rows.nth(1).boundingBox()!!.y > rows.first().boundingBox()!!.y)
            try {
                rows.nth(99).waitFor(SelectorState.Attached, 100)
                fail("An out-of-range index must not satisfy Attached")
            } catch (_: KmpwrightException) { }
            try {
                expect(rows.nth(1)).toHaveText(rows.first().innerText(), 100)
                fail("An assertion must not inspect another row")
            } catch (_: KmpwrightAssertionError) { }
            expect(rows.nth(1)).toContainText("Second", 1_000)
            assertEquals("nested", page.locator("text=Unique text").getAttribute("id"))
            val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/") { exchange ->
                val html = if (exchange.requestURI.path == "/next") "<title>Next document</title><h1 id=next>Arrived</h1>"
                    else "<a href='/next'>Follow</a>"
                val body = html.toByteArray()
                exchange.responseHeaders.add("Content-Type", "text/html")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            server.start()
            try {
                val base = "http://127.0.0.1:${server.address.port}"
                assertTrue(page.goto(base).ok)
                val navigation = async(start = CoroutineStart.UNDISPATCHED) { page.waitForNavigation(WaitForNavigationOptions(timeoutMs = 2_000)) }
                page.locator("a").click()
                assertEquals("$base/next", navigation.await()!!.url)
                assertEquals("$base/next", page.url.value)
                assertEquals("Next document", page.title.value)
                assertEquals("Arrived", page.locator("h1").innerText())
                page.localStorage.set("quoted'key", "value")
                page.sessionStorage.set("session", "value")
                assertEquals("value", page.localStorage.get("quoted'key"))
                assertEquals("value", page.sessionStorage.get("session"))
                val expiry = (System.currentTimeMillis() / 1000 + 3600).toDouble()
                page.addCookies(listOf(Cookie("persistent", "saved", "127.0.0.1", expires = expiry, httpOnly = true)))
                page.addCookies(listOf(Cookie("session", "saved", "127.0.0.1")))
                assertEquals("saved", page.cookie("persistent")!!.value)
                val roundtrip = page.storageState()
                page.clearCookies()
                page.localStorage.clear()
                page.setStorageState(roundtrip)
                val restored = page.cookie("persistent")!!
                assertEquals(expiry, restored.expires!!, 1.0)
                assertTrue(restored.httpOnly)
                assertNull(page.cookie("session")!!.expires)
                assertEquals("value", page.localStorage.get("quoted'key"))
                page.localStorage.clear()
                page.sessionStorage.clear()
                val empty = page.storageState()
                page.localStorage.set("later", "data")
                page.sessionStorage.set("later", "data")
                page.setStorageState(empty)
                assertTrue(page.localStorage.entries().isEmpty())
                assertTrue(page.sessionStorage.entries().isEmpty())
                val context = launched.browser.newContext()
                try {
                    val isolated = context.newPage()
                    assertTrue(isolated.goto(base, GotoOptions(timeoutMs = 2_000)).ok)
                    assertNull(isolated.cookie("persistent"))
                } finally { context.close() }
                page.setViewportSize(320, 240)
                page.setUserAgent("KMPwright-contract")
                assertEquals("KMPwright-contract", page.evaluate("navigator.userAgent").value)
                val jpeg = page.screenshot(ScreenshotOptions(format = ScreenshotFormat.JPEG, quality = 70, scale = 0.5))
                assertEquals(160, jpeg.width)
                assertEquals(120, jpeg.height)
                assertEquals(0xff, jpeg.bytes[0].toInt() and 0xff)
                assertEquals(0xd8, jpeg.bytes[1].toInt() and 0xff)
                val transparent = page.screenshot(ScreenshotOptions(omitBackground = true))
                val decoded = javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(transparent.bytes))
                assertEquals(0, decoded.getRGB(0, 0).ushr(24))
                val oldLocator = page.locator("h1")
                page.setActionPolicy(ActionPolicy(deniedActions = setOf(ActionType.Click)))
                val denied = runCatching { oldLocator.click() }.exceptionOrNull()
                assertTrue("Existing locators must consult the current policy", denied is KmpwrightException)
                val styleId = "style'id"
                assertEquals(styleId, page.addStyleTag(StyleTagOptions(content = "body { color: black }", id = styleId)))
                assertEquals(styleId, page.evaluate("document.querySelector('style').id").value)
                verifyWebKitNetworkShim(page, base)
                verifyIndependentLeases(launched.browser, base)
            } finally { server.stop(0) }
        } finally { launched.close() }
    }
    private suspend fun verifyIndependentLeases(browser: Browser, base: String) {
        val farm = BrowserFarm(listOf(browserFarmSlot("fixture", connect = { browser }, close = {})))
        try {
            val first = farm.acquire()
            val firstPage = first.resource.newPage()
            assertTrue(firstPage.goto(base).ok)
            firstPage.addCookies(listOf(Cookie("borrower", "private", "127.0.0.1")))
            firstPage.localStorage.set("borrower", "private")
            firstPage.addInitScript("window.borrower = 'private'")
            first.resource.newContext().newPage().goto(base)
            first.release()
            val second = farm.acquire()
            try {
                val page = second.resource.newPage()
                assertTrue(page.goto(base).ok)
                assertNull(page.cookie("borrower"))
                assertNull(page.localStorage.get("borrower"))
                assertEquals("undefined", page.evaluate("typeof window.borrower").value)
            } finally { second.release() }
        } finally { farm.close() }
    }

    /** Executes the shipped WebKit shim in a JS engine with a recording native channel. */
    private suspend fun verifyWebKitNetworkShim(page: Page, base: String) {
        val result = page.evaluate("""
            (async function() {
                var seen = [];
                window.fetch = async function(input, init) {
                    var request = new Request(input, init);
                    var bytes = new Uint8Array(await request.arrayBuffer());
                    return new Response(Array.from(bytes).join(','));
                };
                window.webkit = { messageHandlers: { probe: { postMessage: function(raw) {
                    var msg = JSON.parse(raw);
                    if (msg.type !== 'fetch') return;
                    seen.push(msg);
                    if (msg.url.endsWith('/timeout')) return;
                    window.__kmpwrightNetworkRespond(msg.url.endsWith('/no-content')
                        ? { id: msg.id, action: 'fulfill', status: 204, body: 'ignored' }
                        : { id: msg.id, action: 'continue' });
                } } } };
                ${buildWebKitNetworkInterceptionScript("probe", "nonce", bridgeTimeoutMs = 100)}
                var urlResponse = await fetch(new URL('$base/url'));
                if (seen[0].url !== '$base/url') throw Error('URL object lost');
                var form = new FormData(); form.append('name', 'Kotlin');
                await fetch('$base/form', { method: 'POST', body: form });
                if (seen[1].body.indexOf('Kotlin') === -1) throw Error('FormData lost');
                var binary = await fetch('$base/binary', { method: 'POST', body: new Uint8Array([0, 128, 255]) });
                if (await binary.text() !== '0,128,255') throw Error('Binary body changed');
                var empty = await fetch('$base/no-content');
                if (empty.status !== 204 || await empty.text() !== '') throw Error('204 failed');
                var timedOut = false;
                try { await fetch('$base/timeout'); } catch (e) { timedOut = e.message.indexOf('timed out') !== -1; }
                if (!timedOut) throw Error('Rejected bridge message stranded fetch');
                var aborted = new AbortController(); aborted.abort();
                var cancelled = false;
                try { await fetch('$base/abort', { signal: aborted.signal }); } catch (e) { cancelled = e.name === 'AbortError'; }
                if (!cancelled) throw Error('Abort signal ignored');
                return 'passed';
            })()
        """.trimIndent())
        assertEquals("passed", result.value)
    }

}
