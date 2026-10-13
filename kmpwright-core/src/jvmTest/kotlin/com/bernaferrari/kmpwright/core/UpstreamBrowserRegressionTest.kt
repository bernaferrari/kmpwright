package com.bernaferrari.kmpwright.core

import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/** Real Chromium checks; run with KMPWRIGHT_TEST_CHROME=/path/to/chrome. */
class UpstreamBrowserRegressionTest {
    private suspend fun verifyTargets(browser: Browser) {
        val owned = browser.pages().filterIsInstance<CdpPage>().mapNotNull { it.targetId }.toSet()
        val foreign = browser.targets().first { it.id !in owned }
        val attached = browser.attachTarget(foreign.id)
        assertSame(attached, browser.attachTarget(foreign.id))
        attached.setContent("<title>Attached tab</title><div id=state>Keep me</div>")
        browser.activateTarget(foreign.id)
        attached.close()
        assertTrue(browser.targets().any { it.id == foreign.id })
        val reattached = browser.attachTarget(foreign.id)
        assertEquals("Keep me", reattached.locator("#state").textContent())
        assertTrue(browser.closeTarget(foreign.id))
        assertFalse(browser.targets().any { it.id == foreign.id })
        val window = browser.newWindow()
        window.setContent("<div id=window>New window</div>")
        assertEquals("New window", window.locator("#window").textContent())
        window.close()
        assertEquals(owned, browser.pages().filterIsInstance<CdpPage>().mapNotNull { it.targetId }.toSet())
    }

    private suspend fun verifyFileChooser(page: Page, directory: java.nio.file.Path) = coroutineScope {
        val file = directory.resolve("chooser.txt")
        Files.writeString(file, "chooser fixture")
        page.setContent("<input type=file id=chooser>")
        page.setFileChooserInterception()
        val event = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { page.waitForFileChooser(3_000) }
        page.locator("#chooser").click()
        val handle = event.await()
        assertFalse(handle.isMultiple)
        handle.setFiles(listOf(file.toString()))
        assertEquals("chooser.txt", page.evaluate("document.querySelector('#chooser').files[0].name").value)
        try { handle.setFiles(emptyList()); fail("Chooser handle must be single-use") }
        catch (error: KmpwrightException) { assertTrue(error.message!!.contains("stale")) }
        page.setFileChooserInterception(false)
    }

    private suspend fun verifyAuthLogin(page: Page, port: Int) {
        val profile = AuthProfile("fixture", "http://127.0.0.1:$port/login", "user-fixture@example.test", "password-fixture")
        page.goto(profile.url)
        page.evaluate("document.body.innerHTML='<form><input type=text name=username style=display:none><input type=email name=email><input type=password disabled><input type=password name=password><button type=submit>Sign in</button></form>'; document.querySelector('form').onsubmit=function(event){event.preventDefault();window.submitted=true}")
        val result = page.login(profile, navigate = false)
        assertTrue(result.submitted)
        assertEquals("user-fixture@example.test", page.locator("input[type=email]").inputValue())
        assertEquals("password-fixture", page.locator("input[name=password]").inputValue())
        assertEquals("true", page.evaluate("window.submitted").value)
        page.evaluate("document.querySelector('input[type=email]').value='';document.querySelector('input[name=password]').value='';document.querySelector('input[type=email]').oninput=function(){document.querySelector('input[name=password]').name='changed'}")
        try { page.login(profile, navigate = false); fail("Changed credential target accepted") }
        catch (error: KmpwrightException) { assertTrue(error.message!!.contains("changed")) }
        assertEquals("", page.locator("input[name=changed]").inputValue())
        page.evaluate("document.querySelector('input[type=email]').oninput=null;document.querySelector('form').action='https://foreign.invalid/login'")
        try { page.login(profile, navigate = false); fail("Foreign form destination accepted") }
        catch (error: KmpwrightException) { assertTrue(error.message!!.contains("foreign")) }
    }

    private suspend fun verifyFrameNetworkHistory(page: Page) {
        val request = kotlinx.coroutines.withTimeout(5_000) {
            while (true) {
                val match = page.network.requests("/frame-history").firstOrNull {
                    it.request.url.contains("://localhost:") && it.state == NetworkRequestState.Finished
                }
                if (match != null) return@withTimeout match
                delay(20)
            }
            error("Unreachable")
        }
        assertTrue(request.request.id.startsWith("frame:"))
        assertEquals("child network body", page.network.responseBody(request.id).content)
        assertTrue(page.url.value.orEmpty().endsWith("/audit"))
    }

    private suspend fun verifyReaderAndNetworkHistory(page: Page, port: Int) {
                page.network.clear()
                page.goto("http://127.0.0.1:$port/history")
                val deadline = System.nanoTime() + 2_000_000_000L
                while (page.network.requests("/history").none { it.state == NetworkRequestState.Finished }) {
                    check(System.nanoTime() < deadline) { "Network history did not finish" }
                    kotlinx.coroutines.delay(20)
                }
                val historical = page.network.requests("/history").first()
                assertEquals(200, historical.response?.status)
                assertEquals("HAR fixture 雪", page.network.responseBody(historical.id).content)
                assertTrue(page.read().content.contains("HAR fixture 雪"))
                assertEquals("HAR fixture 雪", page.read("http://127.0.0.1:$port/read").content)
                assertEquals(404, page.goto("http://127.0.0.1:$port/not-found").status)
    }

    private suspend fun verifyScreencast(page: Page) {
        val frame = kotlinx.coroutines.withTimeout(15_000) {
            page.screencast(maxWidth = 320, maxHeight = 240).first()
        }
        val image = javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(frame.bytes))
        assertTrue(image.width in 1..320 && image.height in 1..240)
        assertTrue(frame.timestampMs > 0)
        assertEquals("image/jpeg", frame.mimeType)
    }

    private suspend fun verifyRecording(page: Page) {
        val directory = Files.createTempDirectory("kmpwright-recording-test-")
        val ffmpeg = System.getenv("KMPWRIGHT_TEST_FFMPEG") ?: "ffmpeg"
        try {
            page.setContent("<style>html,body{margin:0;height:100%;background:blue}</style>")
            page.evaluate("requestAnimationFrame(()=>requestAnimationFrame(()=>window.recordingPaintReady=true))")
            page.waitForFunction("window.recordingPaintReady === true")
            page.video.start(directory.resolve("capture.mp4").toString(), fps = 30,
                maxWidth = 320, maxHeight = 240, ffmpegExecutable = ffmpeg,
                contactSheetPath = directory.resolve("contact.png").toString())
            page.mouse.move(100.0, 80.0)
            delay(250)
            page.evaluate("document.body.style.background='orange'")
            delay(350)
            // Another capture has its own session and must not stop the recording.
            verifyScreencast(page)
            delay(200)
            val result = page.video.stop()
            assertEquals(30, result.fps)
            assertTrue(result.frameCount >= 15)
            assertEquals(result.frameCount * 1000 / 30, result.durationMs)
            assertTrue(result.width in 2..320 && result.height in 2..240)
            assertTrue("Contact frames: ${result.contactFrames}", result.contactFrames.size >= 3)
            assertEquals(0L, result.contactFrames.first().index)
            assertEquals(result.frameCount - 1, result.contactFrames.last().index)
            assertTrue(Files.size(directory.resolve("capture.mp4")) > 100)
            assertNotNull(javax.imageio.ImageIO.read(directory.resolve("contact.png").toFile()))
            val decoded = directory.resolve("decoded.png")
            val decode = ProcessBuilder(ffmpeg, "-hide_banner", "-loglevel", "error", "-ss", "0.15", "-i",
                result.path, "-frames:v", "1", decoded.toString()).redirectErrorStream(true)
                .redirectOutput(directory.resolve("decode.log").toFile()).start()
            assertTrue(decode.waitFor(10, TimeUnit.SECONDS))
            assertEquals(Files.readString(directory.resolve("decode.log")), 0, decode.exitValue())
            val image = javax.imageio.ImageIO.read(decoded.toFile())
            assertEquals(result.width, image.width)
            assertEquals(result.height, image.height)
            assertTrue("Output must include decoded page pixels", (image.getRGB(5, 5) and 255) > 150)
            val cursorFrame = directory.resolve("cursor.png")
            val cursorDecode = ProcessBuilder(ffmpeg, "-hide_banner", "-loglevel", "error", "-ss", (result.durationMs * 0.6 / 1000).toString(), "-i",
                result.path, "-frames:v", "1", cursorFrame.toString()).redirectErrorStream(true)
                .redirectOutput(directory.resolve("cursor-decode.log").toFile()).start()
            assertTrue(cursorDecode.waitFor(10, TimeUnit.SECONDS)); assertEquals(0, cursorDecode.exitValue())
            val cursorImage = javax.imageio.ImageIO.read(cursorFrame.toFile())
            assertTrue("Encoded video must contain a visible white cursor over the blue/orange fixture", (0 until cursorImage.height).any { y ->
                (0 until cursorImage.width).any { x -> val rgb = cursorImage.getRGB(x, y); ((rgb shr 16) and 255) > 160 && ((rgb shr 8) and 255) > 160 && (rgb and 255) > 160 }
            })
            assertEquals("0", page.evaluate("Object.keys(window).filter(k=>k.startsWith('__kmpwrightPointer_')).length").value)
            assertTrue(Files.list(directory).use { paths -> paths.noneMatch { it.fileName.toString().startsWith(".kmpwright-") } })
        } finally { directory.toFile().deleteRecursively() }
    }

    private suspend fun verifyFrameRoles(frame: FrameLocator) {
        assertEquals("field", frame.locator("role=textbox").getAttribute("id"))
        assertEquals("trusted", frame.locator("role=button").first().getAttribute("id"))
    }

    private suspend fun verifyAxHierarchy(page: Page) {
        page.setContent("<div id=owner role=group aria-label='Owned group' aria-owns=owned></div><button id=owned>Owned button</button><button id=other>Other button</button>")
        fun flatten(nodes: List<SnapshotNode>): List<SnapshotNode> = nodes.flatMap { listOf(it) + flatten(it.children) }
        val snapshot = page.snapshot()
        val group = flatten(snapshot.nodes).single { it.role == "group" && it.name == "Owned group" }
        assertTrue(flatten(group.children).any { it.role == "button" && it.name == "Owned button" })
        assertEquals(1, snapshot.refs.values.count { it.name == "Owned button" })
        val scoped = page.snapshot(selector = "#owner")
        assertTrue(scoped.refs.values.any { it.name == "Owned button" })
        assertFalse(scoped.refs.values.any { it.name == "Other button" })
        val interactive = page.snapshot(interactive = true)
        assertEquals(setOf("Owned button", "Other button"), flatten(interactive.nodes).filter { it.role == "button" }.map { it.name }.toSet())
        val button = page.snapshot(selector = "#owned")
        assertEquals(1, button.refs.size)
    }

    private suspend fun verifyNativeRoles(page: Page) {
        page.setContent("""<label>Volume<input type=range id=volume></label><label>Amount<input type=number id=amount></label>
            <input type=search id=search aria-label="Search"><a id=ignored>Ignored</a>
            <div id=shadow></div><script>document.querySelector('#shadow').attachShadow({mode:'open'}).innerHTML='<input type=range id=shadowSlider aria-label=Brightness>'</script>""")
        assertEquals("volume", page.getByRole("slider").first().getAttribute("id"))
        assertEquals("amount", page.getByRole("spinbutton").getAttribute("id"))
        assertEquals("search", page.getByRole("searchbox").getAttribute("id"))
        assertEquals("shadowSlider", page.getByRole("slider").last().getAttribute("id"))
        assertEquals(0, page.locator("role=link").count())
        page.evaluate("setTimeout(()=>document.body.insertAdjacentHTML('beforeend','<progress id=laterRole value=50 max=100></progress>'),100)")
        page.getByRole("progressbar").waitFor(timeoutMs = 3_000)
        assertEquals("laterRole", page.getByRole("progressbar").getAttribute("id"))
    }

    private suspend fun verifyInitialContracts(page: Page, directory: java.nio.file.Path): java.nio.file.Path {
        page.setContent("""<label id="first">Full</label><label id="last">Name</label>
            <input id="file" type="file"><input id="name" aria-labelledby="first last"><button id="one">One</button>
            <select id="choice"><option value="a">Alpha</option><option value="b">  Beta&#160; Choice  </option></select>
            <select id="multi" multiple><option value="a">Alpha</option><option value="b">Beta</option></select>""")
        page.locator("#choice").selectOption("Beta Choice")
        assertEquals("b", page.locator("#choice").inputValue())
        try {
            page.locator("#choice").selectOption("missing")
            fail("Unknown option must fail")
        } catch (e: KmpwrightException) { assertTrue(e.message!!.contains("missing")) }
        assertEquals("b", page.locator("#choice").inputValue())
        page.locator("#multi").selectOption(listOf("Alpha", "b"))
        assertEquals("2", page.evaluate("document.querySelector('#multi').selectedOptions.length").value)
        assertEquals(1, page.locator("label=Full Name").count())
        coroutineScope {
            val results = (1..25).map { value -> async { page.evaluate("$value").value } }.awaitAll()
            assertEquals((1..25).map(Int::toString), results)
        }
        val before = page.snapshot()
        val oneRef = before.refs.values.single { it.name == "One" }.ref
        page.evaluate("document.body.insertAdjacentHTML('afterbegin', '<button id=zero>Zero</button>')")
        val after = page.snapshot()
        assertEquals(oneRef, after.refs.values.single { it.name == "One" }.ref)
        page.locator(oneRef).click()
        // Native input changes focus; compare two captures after the interaction.
        page.snapshot()
        val diff = page.diffSnapshot("__baseline__")
        assertFalse(diff.changed)
        page.evaluate("document.querySelector('#one').textContent='Changed'")
        assertTrue(page.diffSnapshot("__baseline__").changed)
        try { page.snapshot(selector = "#absent"); fail("Scope must exist") }
        catch (e: KmpwrightException) { assertTrue(e.message!!.contains("#absent")) }
        val annotated = page.screenshot(annotate = true)
        assertTrue(annotated.annotations.isNotEmpty())
        assertTrue(annotated.bytes.isNotEmpty())
        page.evaluate("document.querySelector('#one').remove()")
        try { page.locator(oneRef).click(); fail("Removed ref must not click a replacement") }
        catch (e: KmpwrightException) { assertTrue(e.message, e.message!!.contains("is no longer attached")) }
        page.evaluate(EmulationScripts.script(EmulationState(allowedDomains = setOf("*.example.com"))))
        assertEquals("SecurityError", page.evaluate("""(function(){ try { new RTCPeerConnection(); return 'allowed'; } catch(e){ return e.name; } })()""").value)
        assertEquals("SecurityError", page.evaluate("""(function(){ try { new WebSocket('wss://evil123.com'); return 'allowed'; } catch(e){ return e.name; } })()""").value)
        page.evaluate(EmulationScripts.script(EmulationState()))
        page.evaluate("""(function(){ var frame = document.createElement('iframe'); frame.id='embedded'; document.body.appendChild(frame); frame.contentDocument.body.innerHTML='<input id=inside><button>Inside</button>'; })()""")
        val frame = page.frameLocator("#embedded")
        frame.locator("#inside").fill("Frame value")
        assertEquals("Frame value", frame.locator("#inside").inputValue())
        assertEquals(1, frame.locator("button").count())
        assertEquals(0, page.locator("#missing >> button").count())
        val upload = directory.resolve("upload.txt")
        Files.writeString(upload, "hello")
        page.locator("#file").setInputFiles(listOf(upload.toString()))
        assertEquals("upload.txt", page.evaluate("document.querySelector('#file').files[0].name").value)
        page.locator("#file").setInputFiles(emptyList())
        assertEquals("0", page.evaluate("document.querySelector('#file').files.length").value)
        try { page.locator("#never").waitFor(timeoutMs = 100); fail("Missing selector must time out") }
        catch (e: KmpwrightException) { assertTrue(e.message, e.message!!.contains("Timeout")) }
        page.evaluate("setTimeout(function(){document.body.insertAdjacentHTML('beforeend', '<button id=later>Later</button>')}, 100)")
        page.locator("#later").waitFor(timeoutMs = 2_000)
        return upload
    }

    @Test
    fun `upstream behavior works in a real document`() = runBlocking {
        val executable = System.getenv("KMPWRIGHT_TEST_CHROME")
        assumeTrue("Set KMPWRIGHT_TEST_CHROME for real browser contracts", !executable.isNullOrBlank())
        val directory = Files.createTempDirectory("kmpwright-chrome-")
        val process = ProcessBuilder(executable!!, "--headless=new", "--enable-experimental-web-platform-features", "--enable-blink-features=WebMCP", "--remote-debugging-port=0",
            "--user-data-dir=$directory", "--no-first-run", "--no-default-browser-check", "about:blank")
            .redirectErrorStream(true).redirectOutput(directory.resolve("chrome.log").toFile()).start()
        var browser: Browser? = null
        try {
            val endpoint = directory.resolve("DevToolsActivePort")
            repeat(400) { if (!Files.exists(endpoint) && process.isAlive) delay(50) }
            check(Files.exists(endpoint)) { "Chrome did not start: " + Files.readString(directory.resolve("chrome.log")) }
            val lines = Files.readAllLines(endpoint)
            browser = Browser(JvmPlatformContext(), KmpwrightConfig(cdp = CdpConfig(
                remoteBrowserSharesFileSystem = true, websocketUrl = "ws://127.0.0.1:${lines[0]}${lines[1]}")))
            val page = browser.newPage()
            verifyTargets(browser)
            val upload = verifyInitialContracts(page, directory)
            verifyFileChooser(page, directory)
            val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/") { exchange ->
                val body = "HAR fixture 雪".encodeToByteArray()
                exchange.responseHeaders.add("Content-Type", "text/plain; charset=utf-8")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            server.createContext("/not-found") { exchange ->
                val body = "Missing page".toByteArray()
                exchange.sendResponseHeaders(404, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            server.start()
            try {
                verifyAuthLogin(page, server.address.port)
                verifyReaderAndNetworkHistory(page, server.address.port)
                page.network.startHar()
                page.goto("http://127.0.0.1:${server.address.port}/")
                page.evaluate("fetch('/api').then(function(r){return r.text()})")
                val har = page.network.stopHar()
                assertTrue(har.contains("HAR fixture 雪"))
                assertTrue(kotlinx.serialization.json.Json.parseToJsonElement(har).jsonObject.containsKey("log"))
            } finally { server.stop(0) }
            verifyAxHierarchy(page)
            verifyNativeRoles(page)
            verifyScreencast(page)
            verifyRecording(page)
            page.setContent("""<html><body>
                <button id="trusted">Native</button><input id="edit" value="old"><div id="rich" contenteditable>old</div>
                <script>
                  window.clicks = []; window.inputs = [];
                  document.querySelector('#trusted').addEventListener('click', function(e) { clicks.push(e.isTrusted); });
                  document.querySelector('#edit').addEventListener('input', function(e) { inputs.push(e.isTrusted); });
                </script></body></html>""")
            page.locator("#trusted").click()
            assertEquals("[true]", page.evaluate("JSON.stringify(clicks)").value)
            page.locator("#edit").fill("new")
            assertEquals("new", page.locator("#edit").inputValue())
            assertEquals("[true]", page.evaluate("JSON.stringify(inputs)").value)
            page.locator("#edit").press("Backspace")
            assertEquals("ne", page.locator("#edit").inputValue())
            page.locator("#edit").pressSequentially("雪😀")
            assertEquals("ne雪😀", page.locator("#edit").inputValue())
            page.locator("#edit").fill("")
            assertEquals("", page.locator("#edit").inputValue())
            page.locator("#rich").fill("editable")
            assertEquals("editable", page.locator("#rich").innerText())
            page.evaluate("var overlay=document.createElement('div'); overlay.id='overlay'; overlay.style='position:fixed;inset:0;z-index:999;background:white'; document.body.appendChild(overlay)")
            // Native (CDP) clicks retry the hit-target check like the WebView path and report a readable error.
            try { page.locator("#trusted").click(timeoutMs = 500); fail("Overlay must intercept") }
            catch (e: KmpwrightTimeoutException) { assertTrue(e.message, e.message!!.contains("is covered by <div id=\"overlay\">")) }
            assertEquals("[true]", page.evaluate("JSON.stringify(clicks)").value)
            val init = page.addInitScript("window.early = 'ready';")
            page.setContent("""<script>window.seenEarly = window.early</script><iframe srcdoc="&lt;script>window.seenEarly=window.early&lt;/script>"></iframe>""")
            assertEquals("ready", page.evaluate("window.seenEarly").value)
            page.waitForFunction("document.querySelector('iframe').contentWindow.seenEarly === 'ready'")
            page.setExtraHTTPHeaders(mapOf("X-Kmpwright" to "inherited"))
            page.setViewportSize(640, 480)
            val tabResult = page.openNewTab("data:text/html,<script>window.seenEarly=window.early</script>")
            assertTrue(tabResult.ok)
            val inheritedPage = browser.pages().last()
            assertEquals("ready", inheritedPage.evaluate("window.seenEarly").value)
            assertEquals("640", inheritedPage.evaluate("innerWidth").value)
            inheritedPage.close()
            page.removeInitScript(init)
            page.setContent("<script>window.seenEarly = typeof window.early</script>")
            assertEquals("undefined", page.evaluate("window.seenEarly").value)
            page.setContent("""<span id="ax-label" aria-label="Browser name">Fallback name</span>
                <input id="slider" type="range" aria-labelledby="ax-label">
                <input id="quantity" type="number" aria-label="Quantity"><div id="shadow"></div>
                <script>document.querySelector('#shadow').attachShadow({mode:'open'}).innerHTML='<button>Shadow action</button>'</script>""")
            val nativeSnapshot = page.snapshot()
            assertTrue(nativeSnapshot.refs.values.any { it.role == "slider" && it.name == "Browser name" })
            assertTrue(nativeSnapshot.refs.values.any { it.role == "spinbutton" && it.name == "Quantity" })
            val shadowRef = nativeSnapshot.refs.values.single { it.name == "Shadow action" }.ref
            page.locator(shadowRef).click()
            page.setContent("<button id=prompt onclick=\"window.promptAnswer=prompt('Name','default')\">Prompt</button>")
            val dialogHandler = page.onDialog { event ->
                assertEquals(DialogType.Prompt, event.type)
                delay(200)
                DialogResponse.Text("Kotlin")
            }
            page.locator("#prompt").click()
            page.waitForFunction("window.promptAnswer === 'Kotlin'", 3_000)
            dialogHandler.close()
            page.locator("#prompt").click()
            page.waitForFunction("window.promptAnswer === null", 3_000)
            val toolsServer = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
            toolsServer.createContext("/") { exchange ->
                val body = """<h1>WebMCP fixture</h1><script>
                    console.timeStamp('Hydrated', 1, 5, '', 'Scheduler ⚛', 'primary');
                    console.timeStamp('Fixture', 2, 4, 'Components ⚛', '', 'tertiary-light');
                    const modelContext = document.modelContext || navigator.modelContext;
                    if (modelContext) modelContext.registerTool({
                      name:'greet', description:'Return a greeting', inputSchema:{type:'object',properties:{name:{type:'string'}}},
                      execute:async function(args){return {content:[{type:'text',text:'Hello '+args.name}]}}
                    });
                </script>""".encodeToByteArray()
                exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
                exchange.sendResponseHeaders(200, body.size.toLong()); exchange.responseBody.use { it.write(body) }
            }
            toolsServer.createContext("/audit") { exchange ->
                val body = ("<html lang=en><title>Audit</title><main id=scope><img src=/missing></main>" +
                    "<iframe id=same title=same src='/child'></iframe>" +
                    "<iframe id=cross title=cross allow=tools src='http://localhost:" + toolsServer.address.port + "/nested'></iframe></html>").toByteArray()
                exchange.responseHeaders.add("Content-Type", "text/html")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            toolsServer.createContext("/child") { exchange ->
                val body = "<html lang=en><title>Child</title><main><img src=/missing><input id=field><button id=trusted onclick=\"this.dataset.trusted=event.isTrusted\">Frame action</button><input type=file id=upload onchange=\"this.dataset.file=this.files[0].name\"><div id=shadow></div></main><script>document.querySelector('#shadow').attachShadow({mode:'open'}).innerHTML='<button></button>';fetch('/frame-history');const model=document.modelContext||navigator.modelContext;document.body.dataset.tool=String(!!model);if(model)model.registerTool({name:'child',description:'Frame tool',inputSchema:{type:'object'},execute:async function(){return {content:[{type:'text',text:location.hostname}]}}})</script></html>".toByteArray()
                exchange.responseHeaders.add("Content-Type", "text/html")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            toolsServer.createContext("/frame-history") { exchange ->
                val body = "child network body".toByteArray()
                exchange.responseHeaders.add("Content-Type", "text/plain")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            toolsServer.createContext("/nested") { exchange ->
                val body = "<html lang=en><title>Nested frame</title><iframe id=nestedChild title=nestedChild src='/child' allow=tools></iframe></html>".toByteArray()
                exchange.responseHeaders.add("Content-Type", "text/html")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            val reactBundle = System.getenv("KMPWRIGHT_TEST_REACT_BUNDLE")
            if (reactBundle != null) {
                toolsServer.createContext("/react") { exchange ->
                    val body = "<html lang=en><title>React fixture</title><div id=root></div><script src='/react.js'></script></html>".toByteArray()
                    exchange.responseHeaders.add("Content-Type", "text/html")
                    exchange.sendResponseHeaders(200, body.size.toLong())
                    exchange.responseBody.use { it.write(body) }
                }
                toolsServer.createContext("/react.js") { exchange ->
                    val body = Files.readAllBytes(java.nio.file.Path.of(reactBundle))
                    exchange.responseHeaders.add("Content-Type", "text/javascript")
                    exchange.sendResponseHeaders(200, body.size.toLong())
                    exchange.responseBody.use { it.write(body) }
                }
            }
            toolsServer.start()
            try {
                page.goto("http://127.0.0.1:${toolsServer.address.port}/")
                assertEquals("object", page.evaluate("typeof (document.modelContext || navigator.modelContext)").value)
                val observerProbe = page.addInitScript("""
                    window.liveObservers = new Set();
                    const NativeObserver = window.PerformanceObserver;
                    window.PerformanceObserver = class extends NativeObserver {
                      constructor(callback){super(callback);liveObservers.add(this)}
                      disconnect(){liveObservers.delete(this);super.disconnect()}
                    };
                    window.originalStamp = console.timeStamp;
                    window.webVitals = {pageOwned:true};
                """.trimIndent())
                val vitals = try { page.webVitals(settleMs = 300) }
                    finally { page.removeInitScript(observerProbe) }
                assertEquals("0", page.evaluate("window.liveObservers.size").value)
                assertEquals("true", page.evaluate("console.timeStamp === window.originalStamp").value)
                assertEquals("true", page.evaluate("window.webVitals.pageOwned").value)
                assertNotNull(vitals.ttfb)
                assertNotNull(vitals.fcp)
                assertTrue(vitals.supportedEntryTypes.contains("paint"))
                assertEquals(4.0, vitals.hydration!!.duration, 0.001)
                assertEquals("Fixture", vitals.hydratedComponents.single().label)
                assertEquals("0", page.evaluate("Object.keys(window).filter(function(k){return k.startsWith('__kmpwrightVitals_')}).length").value)
                page.evaluate("window.next={router:{push:async function(url){await new Promise(function(resolve){setTimeout(resolve,50)});history.pushState(null,'',url);window.routerUsed=true;return true;}}}")
                val routed = page.pushState("/route?q=1")
                assertTrue(routed.ok)
                assertEquals("true", page.evaluate("window.routerUsed").value)
                assertTrue(page.url.value!!.endsWith("/route?q=1"))
                page.evaluate("delete window.next;window.popCount=0;addEventListener('popstate',function(){popCount++})")
                page.pushState("/plain")
                assertEquals("1", page.evaluate("window.popCount").value)
                assertEquals("0", page.evaluate("Object.keys(window).filter(function(k){return k.startsWith('__kmpwrightTask_')}).length").value)
                val tools = page.webMcp.discover()
                assertEquals(listOf("greet"), tools.map { it.name })
                val invocation = page.webMcp.invoke("greet", kotlinx.serialization.json.buildJsonObject { put("name", kotlinx.serialization.json.JsonPrimitive("Kotlin")) })
                assertEquals(WebMcpInvocationStatus.Completed, invocation.status)
                assertTrue(invocation.output.toString().contains("Hello Kotlin"))
                page.network.startHar()
                page.goto("http://127.0.0.1:${toolsServer.address.port}/audit")
                page.waitForLoadState(LoadState.Load)
                page.evaluate("Object.defineProperty(window,'axe',{value:{pageOwned:true},configurable:false});window.define=function(){throw new Error('Page AMD loader must not be called')};window.define.amd={}")
                val audit = page.accessibilityAudit(tags = setOf("wcag2a"))
                assertEquals("4.14.0", audit.axeVersion)
                assertEquals(audit.coverage.toString(), 4, audit.coverage.auditedFrames)
                assertTrue(audit.coverage.skippedFrames.isEmpty())
                assertTrue(audit.violations.any { it.id == "image-alt" && it.nodeCount >= 3 })
                assertTrue(audit.violations.flatMap { it.nodes }.any { node -> node.target.any { it is kotlinx.serialization.json.JsonArray } })
                assertEquals("true", page.evaluate("window.axe.pageOwned").value)
                val crossFrame = page.frameLocator("#cross").frameLocator("#nestedChild")
                assertTrue(crossFrame.url()!!.contains("localhost"))
                assertEquals("nestedChild", crossFrame.name())
                verifyFrameNetworkHistory(page)
                val frameHar = kotlinx.serialization.json.Json.parseToJsonElement(page.network.stopHar()).jsonObject["log"]!!.jsonObject["entries"]!!.jsonArray
                val childHar = frameHar.single { it.jsonObject["request"]!!.jsonObject["url"]!!.jsonPrimitive.content.contains("://localhost:") && it.jsonObject["request"]!!.jsonObject["url"]!!.jsonPrimitive.content.endsWith("/frame-history") }.jsonObject
                assertEquals("child network body", childHar["response"]!!.jsonObject["content"]!!.jsonObject["text"]!!.jsonPrimitive.content)
                verifyFrameRoles(crossFrame)
                crossFrame.locator("#field").fill("Cross process 雪😀")
                assertEquals("Cross process 雪😀", crossFrame.locator("#field").inputValue())
                page.evaluate("var ancestorOverlay=document.createElement('div');ancestorOverlay.id='ancestorOverlay';ancestorOverlay.style='position:fixed;inset:0;z-index:99999;background:white';document.body.appendChild(ancestorOverlay)")
                try { crossFrame.locator("#trusted").click(timeoutMs = 1_000); fail("Ancestor overlay must intercept frame input") }
                catch (error: KmpwrightException) { assertTrue(error.message, error.message.orEmpty().contains("intercepted")) }
                page.evaluate("document.querySelector('#ancestorOverlay').remove()")
                crossFrame.locator("#trusted").click()
                assertEquals("true", crossFrame.locator("#trusted").getAttribute("data-trusted"))
                page.setFileChooserInterception()
                coroutineScope {
                    val chooser = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { page.waitForFileChooser(3_000) }
                    crossFrame.locator("#upload").click()
                    chooser.await().setFiles(listOf(upload.toString()))
                }
                page.setFileChooserInterception(false)
                crossFrame.locator("#upload").setInputFiles(listOf(upload.toString()))
                assertEquals("upload.txt", crossFrame.locator("#upload").getAttribute("data-file"))
                assertTrue(crossFrame.locator("#trusted").screenshot().bytes.isNotEmpty())
                val frameTools = page.webMcp.discover()
                assertEquals(frameTools.toString() + " child: " + crossFrame.locator("body").getAttribute("data-tool"), 2, frameTools.count { it.name == "child" })
                try { page.webMcp.invoke("child"); fail("Duplicate frame tool names require a frame id") }
                catch (error: KmpwrightException) { assertTrue(error.message.orEmpty().contains("ambiguous")) }
                val selectedTool = frameTools.single { it.origin.contains("localhost") }
                val frameToolResult = page.webMcp.invoke("child", frameId = selectedTool.frameId)
                assertEquals(WebMcpInvocationStatus.Completed, frameToolResult.status)
                assertTrue(frameToolResult.output.toString().contains("localhost"))
                val scopedAudit = page.accessibilityAudit(tags = setOf("wcag2a"), selector = "#scope")
                assertEquals(1, scopedAudit.coverage.auditedFrames)
                assertEquals(1, scopedAudit.violations.single { it.id == "image-alt" }.nodeCount)
                for (selector in listOf("[", "#does-not-exist")) {
                    try { page.accessibilityAudit(selector = selector); fail("Invalid audit scope must fail") }
                    catch (error: KmpwrightException) { assertTrue(error.message.orEmpty().contains(selector)) }
                }

                if (reactBundle != null) {
                    page.react.enable()
                    val flightRegistration = page.addInitScript("""
                        var hook=window.__REACT_DEVTOOLS_GLOBAL_HOOK__;
                        hook.inject({rendererPackageName:'react-server-dom-webpack',version:'19.3.0',bundleType:1,
                          getCurrentStack:function(){return null}});
                    """.trimIndent())
                    try {
                        page.goto("http://127.0.0.1:${toolsServer.address.port}/react")
                        page.locator("#counter").waitFor()
                        assertTrue(page.evaluate("JSON.stringify(Array.from(window.__REACT_DEVTOOLS_GLOBAL_HOOK__.renderers.values()).map(function(r){return r.rendererPackageName}))").value!!.startsWith("[\"react-server-dom-webpack\""))
                        val tree = page.react.tree()
                        val counter = tree.single { it.name == "Counter" }
                        assertTrue(page.react.inspect(counter.id).text.contains("Counter"))
                        val suspense = page.react.suspense()
                        assertTrue(suspense.any { it.isSuspended })
                        page.react.startRenderProfile()
                        page.locator("#counter").click()
                        page.waitForFunction("document.querySelector('#counter').textContent === 'Count 1'")
                        page.locator("#counter").click()
                        page.waitForFunction("document.querySelector('#counter').textContent === 'Count 2'")
                        val profile = page.react.stopRenderProfile()
                        assertTrue(profile.totalRenders >= 2)
                        assertTrue(profile.components.any { it.name == "Counter" && it.changes.any { change -> change.type == "state" } })
                        page.evaluate("window.releaseSuspense()")
                        page.waitForFunction("!document.querySelector('#waiting')", timeoutMs = 5_000)
                        assertFalse(page.react.suspense().any { it.isSuspended })
                        page.react.disable()
                        assertEquals("0", page.evaluate("Object.keys(window).filter(function(k){return k.startsWith('__kmpwrightReact_')}).length").value)
                    } finally { page.removeInitScript(flightRegistration) }
                }

            } finally { toolsServer.stop(0) }
            page.close()
        } finally {
            browser?.close()
            process.destroy()
            if (!process.waitFor(5, TimeUnit.SECONDS)) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS) }
            directory.toFile().deleteRecursively()
        }
    }
}
