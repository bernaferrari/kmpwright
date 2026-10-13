package com.bernaferrari.kmpwright.core

import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.nio.file.Files

/**
 * Real-Chromium contracts for the Playwright-shaped public API: `getBy*` finders, locator composition,
 * web-first assertions, `waitForURL`, routing, cookies, downloads and storage state.
 * Requires KMPWRIGHT_TEST_CHROME=/path/to/chrome; skipped otherwise.
 */
class PlaywrightApiBrowserTest {
    companion object {
        private var launched: LaunchedBrowser? = null
        private var server: com.sun.net.httpserver.HttpServer? = null
        private val origin get() = "http://127.0.0.1:${server!!.address.port}"

        @BeforeClass @JvmStatic
        fun startChrome() {
            val executable = System.getenv("KMPWRIGHT_TEST_CHROME")
            if (executable.isNullOrBlank()) return
            server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/") { exchange ->
                    val path = exchange.requestURI.path
                    val (type, body) = when (path) {
                        "/a" -> "text/html" to "<title>Page A</title><a id=next href=/b>Next</a><a id=dl href=/file.txt download=report.txt>Get</a>"
                        "/b" -> "text/html" to "<title>Page B</title><p id=here>B page</p>"
                        "/file.txt" -> "text/plain" to "downloaded contents"
                        "/echo" -> "application/json" to """{"header":"${exchange.requestHeaders.getFirst("X-Test").orEmpty()}"}"""
                        else -> "text/html" to "<title>Root</title><p>$path</p>"
                    }
                    val bytes = body.encodeToByteArray()
                    exchange.responseHeaders.add("Content-Type", "$type; charset=utf-8")
                    if (path == "/file.txt") exchange.responseHeaders.add("Content-Disposition", "attachment; filename=report.txt")
                    runCatching {
                        exchange.sendResponseHeaders(200, bytes.size.toLong())
                        exchange.responseBody.use { it.write(bytes) }
                    }
                }
                executor = java.util.concurrent.Executors.newCachedThreadPool()
                start()
            }
            runBlocking { launched = launchBrowser(BrowserLaunchOptions(executable)) }
        }

        @AfterClass @JvmStatic
        fun stopChrome() {
            runBlocking { runCatching { launched?.close() } }
            server?.stop(0)
        }
    }

    private fun browserTest(block: suspend (Page) -> Unit) {
        assumeTrue("Set KMPWRIGHT_TEST_CHROME for real browser contracts", launched != null)
        runBlocking {
            val page = launched!!.browser.newPage()
            try { block(page) } finally { runCatching { page.close() } }
        }
    }

    @Test
    fun `getBy finders follow Playwright matching rules`() = browserTest { page ->
        page.setContent("""
            <h2>Account</h2>
            <button id=save>Save</button><button id=saveAll>Save all</button>
            <label for=email>Email address</label><input id=email placeholder="you@example.com">
            <img id=logo alt="Company logo" src="data:image/gif;base64,R0lGODlhAQABAAAAACw=">
            <span id=tip title="More help">?</span>
            <div data-testid=card-1 id=card>Card</div>
            <p id=greeting>Hello   World</p>
        """.trimIndent())
        assertEquals(2, page.getByRole("button", name = "save").count())
        assertEquals("save", page.getByRole("button", name = "Save", exact = true).getAttribute("id"))
        assertEquals("saveAll", page.getByRole("button", name = Regex("all$")).getAttribute("id"))
        assertEquals(1, page.getByRole("heading", level = 2).count())
        assertEquals("greeting", page.getByText("hello world").getAttribute("id"))
        assertEquals(0, page.getByText("hello world", exact = true).count())
        assertEquals("greeting", page.getByText("Hello World", exact = true).getAttribute("id"))
        assertEquals("greeting", page.getByText(Regex("hello\\s+world", RegexOption.IGNORE_CASE)).getAttribute("id"))
        assertEquals("email", page.getByLabel("email").getAttribute("id"))
        assertEquals("email", page.getByPlaceholder("example.com").getAttribute("id"))
        assertEquals("logo", page.getByAltText("Company logo", exact = true).getAttribute("id"))
        assertEquals("tip", page.getByTitle("help").getAttribute("id"))
        assertEquals("card", page.getByTestId("card-1").getAttribute("id"))
        assertEquals("card", page.getByTestId(Regex("card-\\d")).getAttribute("id"))
    }

    @Test
    fun `locator composition filters, combines and lists matches`() = browserTest { page ->
        page.setContent("""
            <ul>
              <li class=row><span>Apple</span><button>Buy</button></li>
              <li class=row><span>Banana</span></li>
              <li class="row special"><span>Cherry</span><button>Buy</button></li>
            </ul>
        """.trimIndent())
        val rows = page.locator("li.row")
        assertEquals(2, rows.filter(has = page.getByRole("button")).count())
        assertEquals(listOf("Banana"), rows.filter(hasNot = page.locator("button")).allInnerTexts())
        assertEquals(1, rows.filter(hasText = Regex("^ch", RegexOption.IGNORE_CASE)).count())
        assertEquals(1, rows.and(page.locator(".special")).count())
        assertEquals(5, page.locator("span").or(page.getByRole("button")).count())
        assertEquals(listOf("Apple", "Banana", "Cherry"), page.locator("span").allTextContents())
        assertEquals(listOf("Apple", "Banana", "Cherry"), page.locator("span").all().map { it.innerText() })
        assertEquals("Cherry", rows.getByText("Cherry").innerText())
    }

    @Test
    fun `locator actions and queries use Playwright names`() = browserTest { page ->
        page.setContent("""
            <input id=name value=prefilled><input id=agree type=checkbox><button id=off disabled>Off</button>
            <div id=hidden style="display:none">x</div><div id=out></div>
            <script>
              document.getElementById('name').addEventListener('blur', function() { document.getElementById('out').textContent = 'blurred'; });
              document.getElementById('out').addEventListener('custom-ping', function() { document.getElementById('out').dataset.pinged = 'yes'; });
            </script>
        """.trimIndent())
        val name = page.locator("#name")
        name.clear()
        assertEquals("", name.inputValue())
        name.pressSequentially("abc")
        assertEquals("abc", name.evaluate("el => el.value").value)
        assertEquals("number", name.evaluate("el => el.value.length").type)
        name.focus()
        name.blur()
        assertEquals("blurred", page.locator("#out").textContent())
        page.locator("#agree").setChecked(true)
        assertTrue(page.locator("#agree").isChecked())
        page.locator("#agree").setChecked(false)
        assertFalse(page.locator("#agree").isChecked())
        assertTrue(page.locator("#off").isDisabled())
        assertTrue(page.locator("#hidden").isHidden())
        page.locator("#out").dispatchEvent("custom-ping")
        assertEquals("yes", page.locator("#out").getAttribute("data-pinged"))
    }

    @Test
    fun `top level expect asserts locators and pages`() = browserTest { page ->
        page.goto("$origin/a")
        expect(page).toHaveTitle("Page A")
        expect(page).toHaveURL(Regex("/a$"))
        expect(page).not.toHaveTitle(Regex("B"), timeoutMs = 200)
        page.setContent("""<p id=p class="note  primary" data-x="42">Hello there</p><input id=empty><div id=gone style="display:none"></div>""")
        expect(page.locator("#p")).toBeAttached()
        expect(page.locator("#p")).toHaveClass("note primary")
        expect(page.locator("#p")).toHaveClass(Regex("primary"))
        expect(page.locator("#p")).toHaveAttribute("data-x")
        expect(page.locator("#p")).toHaveAttribute("data-x", "42")
        expect(page.locator("#p")).toHaveAttribute("data-x", Regex("^4"))
        expect(page.locator("#p")).toContainText(Regex("there$"))
        expect(page.locator("#empty")).toBeEmpty()
        expect(page.locator("#gone")).toBeHidden()
        expect(page.locator("#gone")).not.toBeVisible()
        assertThrows(KmpwrightAssertionError::class.java) {
            runBlocking { expect(page.locator("#p")).toHaveText("nope", timeoutMs = 100) }
        }
    }

    @Test
    fun `waitForURL, waitForRequest and waitForResponse match strings, regexes and predicates`() = browserTest { page ->
        page.goto("$origin/a")
        page.waitForURL("**/b") { click("#next") }
        assertEquals("$origin/b", page.url.value)
        page.waitForURL(Regex("/b$"))
        page.waitForURL { it.endsWith("/b") }
        val request = page.waitForRequest(Regex("/echo$")) {
            evaluate("fetch('/echo').then(function(r) { return r.text(); })")
        }
        assertEquals("GET", request.method)
        page.evaluate("setTimeout(function() { fetch('/echo'); }, 200)")
        val response = page.waitForResponse { it.url.endsWith("/echo") }
        assertEquals(200, response.status)
        assertTrue(response.ok)
    }

    @Test
    fun `route fulfills with json, falls back and resumes with overrides`() = browserTest { page ->
        page.goto("$origin/a")
        page.route("**/echo") { route -> route.resume(headers = route.request.headers + ("X-Test" to "resumed")) }
        page.route(Regex("/echo$")) { route -> route.fallback() }
        assertEquals("""{"header":"resumed"}""", page.evaluate("fetch('/echo').then(function(r) { return r.text(); })").value)
        page.route(Regex("/api/items$")) { route -> route.fulfill(json = """{"items":[1,2]}""") }
        assertEquals("2", page.evaluate("fetch('/api/items').then(function(r) { return r.json(); }).then(function(j) { return j.items.length; })").value)
        assertEquals("application/json", page.evaluate("fetch('/api/items').then(function(r) { return r.headers.get('content-type'); })").value)
        page.route("**/blocked") { route -> route.abort() }
        assertEquals("failed", page.evaluate("fetch('/blocked').then(function() { return 'ok'; }, function() { return 'failed'; })").value)
        page.unrouteAll()
        assertEquals("""{"header":""}""", page.evaluate("fetch('/echo').then(function(r) { return r.text(); })").value)
    }

    @Test
    fun `cookies filter by url and clearCookies removes by name`() = browserTest { page ->
        page.goto("$origin/a")
        page.addCookies(listOf(
            Cookie("root", "1", "127.0.0.1", path = "/"),
            Cookie("scoped", "2", "127.0.0.1", path = "/admin"),
            Cookie("secure", "3", "127.0.0.1", secure = true)
        ))
        val visible = page.cookies(listOf("$origin/a")).map { it.name }.toSet()
        assertTrue("root" in visible)
        assertFalse("scoped" in visible)
        assertFalse("secure" in visible)
        assertTrue(page.cookies(listOf("$origin/admin/users")).any { it.name == "scoped" })
        page.clearCookies(name = "root")
        assertFalse(page.cookies().any { it.name == "root" })
        assertTrue(page.cookies().any { it.name == "scoped" })
        page.clearCookies()
        assertTrue(page.cookies().isEmpty())
    }

    @Test
    fun `downloads expose path and saveAs`() = browserTest { page ->
        val directory = Files.createTempDirectory("kmpwright-downloads")
        try {
            page.setDownloadPath(directory.toString())
            page.setDownloadBehavior(DownloadBehavior.Allow)
            page.goto("$origin/a")
            val download = page.waitForDownload { click("#dl") }
            assertEquals("report.txt", download.suggestedFilename)
            val path = download.path()
            assertNotNull(path)
            assertEquals("downloaded contents", Files.readString(java.nio.file.Path.of(path!!)))
            val copy = directory.resolve("copy/report-copy.txt")
            download.saveAs(copy.toString())
            assertEquals("downloaded contents", Files.readString(copy))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `storage state saves encrypted files and restores them`() = browserTest { page ->
        val file = Files.createTempFile("kmpwright-state", ".json")
        try {
            page.goto("$origin/a")
            page.addCookies(listOf(Cookie("sid", "abc", "127.0.0.1")))
            page.localStorage.set("theme", "dark")
            page.sessionStorage.set("tab", "1")
            page.storageState().save(file.toString(), password = "secret")
            assertFalse(Files.readString(file).contains("theme"))
            page.clearCookies()
            page.localStorage.clear()
            page.sessionStorage.clear()
            assertThrows(KmpwrightException::class.java) { runBlocking { StorageState.load(file.toString(), password = "wrong") } }
            page.setStorageState(StorageState.load(file.toString(), password = "secret"))
            assertEquals("abc", page.cookies().single { it.name == "sid" }.value)
            assertEquals(mapOf("theme" to "dark"), page.localStorage.entries())
            assertEquals("1", page.sessionStorage.get("tab"))
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun `scroll moves by the requested direction and amount`() = browserTest { page ->
        page.setContent("""<div style="height:4000px;width:4000px"></div>""")
        page.scroll(ScrollDirection.Down, 250.0)
        assertEquals("250", page.evaluate("Math.round(scrollY)").value)
        page.scroll(ScrollDirection.Right, 40.0)
        assertEquals("40", page.evaluate("Math.round(scrollX)").value)
        page.scrollToBottom()
        assertTrue(page.evaluate("scrollY").value.toDouble() > 3000)
        page.scrollToTop()
        assertEquals("0", page.evaluate("Math.round(scrollY)").value)
    }
}
