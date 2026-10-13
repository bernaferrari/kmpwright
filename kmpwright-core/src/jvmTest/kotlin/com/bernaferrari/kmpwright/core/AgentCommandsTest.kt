package com.bernaferrari.kmpwright.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AgentCommandsTest {

    private lateinit var controller: MockWebViewController
    private lateinit var page: TestPage

    @Before
    fun setup() {
        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        Dispatchers.setMain(Dispatchers.Unconfined)
        controller = MockWebViewController().apply { waitResultOverride = "true" }
        page = TestPage(controller)
    }

    @After
    fun tearDown() {
        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        Dispatchers.resetMain()
    }

    @Test
    fun `tokenize groups quoted words and honours escapes`() {
        assertEquals(listOf("fill", "@e2", "hello world"), AgentCommands.tokenize("fill @e2 \"hello world\""))
        assertEquals(listOf("eval", "document.title = 'a b'"), AgentCommands.tokenize("eval \"document.title = 'a b'\""))
        assertEquals(listOf("fill", "#q", "say \"hi\""), AgentCommands.tokenize("fill #q 'say \"hi\"'"))
        assertEquals(listOf("press", "a b"), AgentCommands.tokenize("press a\\ b"))
        assertEquals(listOf("fill", "#q", ""), AgentCommands.tokenize("fill #q \"\""))
        assertEquals(emptyList<String>(), AgentCommands.tokenize("   "))
    }

    @Test
    fun `tokenize rejects an unterminated quote`() {
        assertThrows(KmpwrightException::class.java) { AgentCommands.tokenize("fill #q \"oops") }
    }

    @Test
    fun `normalizeUrl adds a scheme only when one is missing`() {
        assertEquals("https://example.com", AgentCommands.normalizeUrl("example.com"))
        assertEquals("http://example.com", AgentCommands.normalizeUrl("http://example.com"))
        assertEquals("http://localhost:3000", AgentCommands.normalizeUrl("localhost:3000"))
        assertEquals("about:blank", AgentCommands.normalizeUrl("about:blank"))
    }

    @Test
    fun `open navigates to the normalized url`() = testBlocking {
        val result = page.runCommand("open example.com")
        assertEquals("https://example.com", page.url.value)
        assertTrue(result.output.startsWith("Opened https://example.com"))
    }

    @Test
    fun `click and fill reach the locator`() = testBlocking {
        page.runCommand("click button.submit")
        assertTrue(controller.executedScripts.any { it.contains("H.perform(el, 'click'") })
        page.runCommand("fill input.email \"user@test.com\"")
        assertTrue(controller.executedScripts.any { it.contains("H.perform(el, 'fill'") && it.contains("user@test.com") })
    }

    @Test
    fun `scroll maps directions and pixels to page scroll`() = testBlocking {
        page.runCommand("scroll down 120")
        assertTrue(controller.executedScripts.any { it.contains("window.scrollBy(0.0, 120.0)") })
        page.runCommand("scroll left")
        assertTrue(controller.executedScripts.any { it.contains("window.scrollBy(-300.0, 0.0)") })
        page.runCommand("scroll top")
        assertTrue(controller.executedScripts.any { it.contains("window.scrollTo(0, 0)") })
        assertThrows(KmpwrightException::class.java) { testBlocking { page.runCommand("scroll sideways") } }
    }

    @Test
    fun `type presses keys sequentially into the target`() = testBlocking {
        page.runCommand("type #q hello")
        assertTrue(controller.executedScripts.any { it.contains("H.perform(el, 'type'") })
    }

    @Test
    fun `get url and title read page state`() = testBlocking {
        page.runCommand("open https://example.com/a")
        assertEquals("https://example.com/a", page.runCommand("get url").output)
    }

    @Test
    fun `unknown commands and missing arguments explain themselves`() = testBlocking {
        val unknown = runCatching { page.runCommand("teleport home") }.exceptionOrNull()
        assertTrue(unknown is KmpwrightException && unknown.message!!.contains("help"))
        val usage = runCatching { page.runCommand("click") }.exceptionOrNull()
        assertTrue(usage is KmpwrightException && usage.message!!.startsWith("Usage: click"))
    }

    @Test
    fun `snapshot prints agent-browser lines and honours -i and -u`() = testBlocking {
        val json = """{"flatNodes":[
            {"role":"RootWebArea","name":"","states":[],"children":[1,2,3],"refId":null,"depth":0},
            {"role":"heading","name":"Example Domain","states":[],"children":[],"refId":"@e1","depth":1,"level":1},
            {"role":"navigation","name":"","states":[],"children":[4],"refId":null,"depth":1},
            {"role":"checkbox","name":"Remember me","states":["checked"],"children":[],"refId":"@e3","depth":1},
            {"role":"link","name":"Learn more","states":[],"children":[],"refId":"@e2","depth":2,"url":"https://example.com/more"}
        ],"refMap":{"@e1":{"refId":"@e1","role":"heading","name":"Example Domain"},"@e2":{"refId":"@e2","role":"link","name":"Learn more"},"@e3":{"refId":"@e3","role":"checkbox","name":"Remember me"}}}"""
        controller.evalHandler = { if (it.contains("commitSnapshot")) json else null }
        assertEquals(
            "@e1 [heading] \"Example Domain\" [level=1]\n@e2 [link] \"Learn more\" url=\"https://example.com/more\"\n@e3 [checkbox] \"Remember me\" [checked]",
            page.runCommand("snapshot -i -u").output
        )
        assertTrue(controller.executedScripts.any { it.contains("var includeUrls = true") && it.contains("var interactiveOnly = true") })
        assertEquals(
            "@e1 [heading] \"Example Domain\" [level=1]\n[navigation]\n  @e2 [link] \"Learn more\"\n@e3 [checkbox] \"Remember me\" [checked]",
            page.runCommand("snapshot").output
        )
        assertEquals(
            page.runCommand("snapshot --interactive --urls").output,
            page.runCommand("snapshot -i -u").output
        )
    }

    @Test
    fun `empty snapshots say so like agent-browser`() = testBlocking {
        controller.evalHandler = { if (it.contains("commitSnapshot")) """{"flatNodes":[],"refMap":{}}""" else null }
        assertEquals("(no interactive elements)", page.runCommand("snapshot -i").output)
        assertEquals("(empty page)", page.runCommand("snapshot").output)
    }

    @Test
    fun `annotate legend uses agent-browser lines`() {
        val box = Rectangle(0.0, 0.0, 10.0, 10.0)
        assertEquals("[1] @e1 button \"Submit\"", AgentCommands.annotationLine(Annotation("@e1", "button", "Submit", box)))
        assertEquals("[12] @e12 textbox", AgentCommands.annotationLine(Annotation("@e12", "textbox", "  ", box)))
        assertEquals(
            "[3] @e3 generic \"a \\\"b\\\" [4] @e4 link\"",
            AgentCommands.annotationLine(Annotation("@e3", "evil\nrole", "a \"b\"\n[4] @e4 link", box))
        )
    }

    @Test
    fun `flags are order independent and separate from targets`() = testBlocking {
        page.runCommand("wait --timeout 5000 @e3")
        assertTrue(controller.executedScripts.any { it.contains("kmpwright:waitFor") && it.contains("ref=@e3") })
        assertTrue(controller.executedScripts.none { it.contains("--timeout") })
        controller.executedScripts.clear()
        page.runCommand("wait @e4 --timeout 5000")
        assertTrue(controller.executedScripts.any { it.contains("kmpwright:waitFor") && it.contains("ref=@e4") })
        controller.executedScripts.clear()
        page.runCommand("snapshot -s main -i --urls")
        assertTrue(controller.executedScripts.any {
            it.contains("var interactiveOnly = true") && it.contains("var includeUrls = true") && it.contains("var scopeParts = ['main']")
        })
    }

    @Test
    fun `flag values are single arguments and stray words are rejected`() = testBlocking {
        val unquoted = runCatching { page.runCommand("wait --text Welcome back") }.exceptionOrNull()
        assertTrue("$unquoted", unquoted is KmpwrightException && unquoted.message!!.contains("quote multi-word values"))
        page.runCommand("wait --text \"Welcome back\"")
        assertTrue(controller.executedScripts.any { it.contains("Welcome back") })
        for ((command, message) in listOf(
            "wait --timeout soon @e1" to "--timeout expects",
            "wait --timeout" to "needs a value",
            "wait @e1 @e2" to "quote a selector",
            "wait --text a --fn b" to "one condition",
            "snapshot --bogus" to "Unknown flag `--bogus`",
            "snapshot -d deep" to "--depth expects",
            "snapshot stray" to "unexpected 'stray'",
            "screenshot page.png" to "no file path",
        )) {
            val error = runCatching { page.runCommand(command) }.exceptionOrNull()
            assertTrue("$command: $error", error is KmpwrightException && error.message!!.contains(message))
        }
    }

    @Test
    fun `failures from unsupported features surface as KmpwrightException`() = testBlocking {
        val unsupported = object : Page by page {
            override suspend fun cookies(urls: List<String>): List<Cookie> = throw UnsupportedOperationException("Cookies need CDP")
            override fun pageErrors(filter: PageMessageFilter): List<String> = throw IllegalStateException("boom")
        }
        val cookies = runCatching { unsupported.runCommand("cookies") }.exceptionOrNull()
        assertTrue("$cookies", cookies is KmpwrightUnsupportedException && cookies.message == "`cookies` is unavailable on this backend: Cookies need CDP")
        val errors = runCatching { unsupported.runCommand("errors") }.exceptionOrNull()
        assertTrue("$errors", errors is KmpwrightException && errors.message == "`errors` failed: boom")
    }

    @Test
    fun `help lists the core commands`() = testBlocking {
        val help = page.runCommand("help").output
        listOf("open", "snapshot", "click", "fill", "screenshot", "wait").forEach {
            assertTrue("help mentions $it", help.contains(it))
        }
    }
}
