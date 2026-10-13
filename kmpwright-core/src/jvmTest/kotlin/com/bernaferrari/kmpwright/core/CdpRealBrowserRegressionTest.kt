package com.bernaferrari.kmpwright.core

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

/** Real Chromium regressions for the JVM backend; run with KMPWRIGHT_TEST_CHROME=/path/to/chrome. */
class CdpRealBrowserRegressionTest {
    @Test
    fun `navigation screenshots pdf input and launch cleanup behave like Playwright`() = runBlocking<Unit> {
        val executable = System.getenv("KMPWRIGHT_TEST_CHROME")
        assumeTrue("Set KMPWRIGHT_TEST_CHROME for real browser contracts", !executable.isNullOrBlank())
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val body = "<title>Fixture</title><div id=top>Top</div><a id=anchor name=section>Section</a>".encodeToByteArray()
            exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        val launched = launchBrowser(executable!!)
        try {
            val page = launched.browser.newPage()
            val origin = "http://127.0.0.1:${server.address.port}"

            // Network failures arrive in Page.navigate's errorText, not as protocol errors.
            val failed = page.goto("http://127.0.0.1:1/unreachable")
            assertFalse("connection refused must not report success: $failed", failed.ok)
            assertTrue(failed.error.orEmpty(), failed.error.orEmpty().startsWith("net::"))

            assertTrue(page.goto("$origin/").ok)
            // Same-document navigations never fire a load event; they must not time out.
            val fragment = withTimeout(5_000) { page.goto("$origin/#section", timeoutMs = 4_000) }
            assertTrue("fragment navigation failed: $fragment", fragment.ok)

            page.setContent("<title>Set content</title><p id=p>Hello</p>")
            assertEquals("Hello", page.locator("#p").textContent())

            // dblclick dispatches click, click, dblclick like a real user.
            page.setContent("""<button id=b style="position:absolute;left:10px;top:10px;width:100px;height:40px">B</button>
                <script>window.events=[];for(const t of ['click','dblclick'])b.addEventListener(t,()=>events.push(t))</script>""")
            page.mouse.dblclick(50.0, 30.0)
            assertEquals("click,click,dblclick", page.evaluate("events.join(',')").value)

            // Scaled full-page captures must include content below the fold.
            page.setContent("""<style>body{margin:0}</style><div style="height:3000px;background:#fff"></div>
                <div style="height:200px;background:rgb(255,0,0)"></div>""")
            val shot = page.screenshot(fullPage = true, scale = 0.5)
            val image = ImageIO.read(ByteArrayInputStream(shot.bytes))
            assertEquals(image.width to image.height, shot.width to shot.height)
            assertTrue("full-page height ${shot.height} must cover the document", shot.height >= 1550)
            val bottom = image.getRGB(image.width / 2, image.height - 20) and 0xFFFFFF
            assertEquals("content below the fold must be painted", 0xFF0000, bottom)

            val pdf = page.pdf()
            assertTrue(pdf.bytes.decodeToString(0, 5) == "%PDF-")

            // Context clearData removes origin storage, not only cookies.
            val context = launched.browser.newContext()
            try {
                val contextPage = context.newPage()
                assertTrue(contextPage.goto("$origin/").ok)
                contextPage.evaluate("localStorage.setItem('kept', 'value')")
                context.clearData()
                assertEquals("null", contextPage.evaluate("String(localStorage.getItem('kept'))").value)
            } finally {
                context.close()
            }
        } finally {
            withTimeout(15_000) { launched.close() }
            server.stop(0)
        }
    }
}
