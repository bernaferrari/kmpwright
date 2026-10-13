package com.bernaferrari.kmpwright.core

import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Real-Chromium behaviour of the shared (WebView) locator implementation. Pages use
 * [InputMode.JsOnly] so the CDP locator delegates to [BaseLocator] — exactly the code Android and
 * iOS run. Requires KMPWRIGHT_TEST_CHROME=/path/to/chrome; skipped otherwise.
 */
class ActionabilityBrowserTest {
    companion object {
        private var launched: LaunchedBrowser? = null
        private val jsOnly: Browser? get() = launched?.browser

        @BeforeClass @JvmStatic
        fun startChrome() {
            val executable = System.getenv("KMPWRIGHT_TEST_CHROME")
            if (executable.isNullOrBlank()) return
            runBlocking {
                launched = launchBrowser(executable, config = KmpwrightConfig(
                    inputMode = InputMode.JsOnly,
                    timeouts = TimeoutConfig(action = 5_000)
                ))
            }
        }

        @AfterClass @JvmStatic
        fun stopChrome() {
            runBlocking { runCatching { launched?.close() } }
        }
    }

    private fun page(): Page {
        assumeTrue("Set KMPWRIGHT_TEST_CHROME for real browser contracts", jsOnly != null)
        return runBlocking { jsOnly!!.newPage() }
    }

    private fun browserTest(block: suspend (Page) -> Unit) {
        val page = page()
        runBlocking {
            try { block(page) } finally { runCatching { page.close() } }
        }
    }

    private suspend fun Page.js(expression: String) = evaluate(expression).value

    @Test
    fun `click dispatches a realistic pointer and mouse sequence`() = browserTest { page ->
        page.setContent("""<button id=b>Save</button><script>
            window.log=[];['pointerover','pointerenter','mouseover','mouseenter','pointermove','mousemove','pointerdown','mousedown','focus','pointerup','mouseup','click']
              .forEach(function(t){ b.addEventListener(t, function(e){ log.push(t + (e.isTrusted ? '' : '')) }); });
        </script>""")
        page.locator("#b").click()
        assertEquals("pointerover,pointerenter,mouseover,mouseenter,pointermove,mousemove,pointerdown,mousedown,focus,pointerup,mouseup,click",
            page.js("log.join(',')"))
        assertEquals("b", page.js("document.activeElement.id"))
    }

    @Test
    fun `double click fires two clicks and a dblclick with increasing detail`() = browserTest { page ->
        page.setContent("""<button id=b>Twice</button><script>window.log=[];
            ['click','dblclick'].forEach(function(t){ b.addEventListener(t, function(e){ log.push(t+':'+e.detail) }) });</script>""")
        page.locator("#b").dblclick()
        assertEquals("click:1,click:2,dblclick:2", page.js("log.join(',')"))
    }

    @Test
    fun `actions wait for the element to appear and become enabled`() = browserTest { page ->
        page.setContent("""<div id=host></div><script>
            setTimeout(function(){ host.innerHTML = '<button id=later disabled onclick="window.clicked=true">Later</button>'; }, 200);
            setTimeout(function(){ document.getElementById('later').disabled = false; }, 500);
        </script>""")
        page.locator("#later").click()
        assertEquals("true", page.js("window.clicked"))
    }

    @Test
    fun `disabled element times out with an actionable message`() = browserTest { page ->
        page.setContent("<button disabled>Save</button>")
        val error = runCatching { page.locator("text=Save").click(timeoutMs = 400) }.exceptionOrNull()
        assertTrue("expected a timeout, got $error", error is KmpwrightTimeoutException)
        assertTrue(error!!.message, error.message!!.contains("is not enabled after 400ms"))
        assertTrue(error.message, error.message!!.contains("<button disabled>Save</button>"))
    }

    @Test
    fun `snapshot refs appear in errors`() = browserTest { page ->
        page.setContent("<button disabled>Pay</button>")
        val ref = page.snapshot(interactive = true).refs.keys.single()
        val error = runCatching { page.locator(ref).click(timeoutMs = 300) }.exceptionOrNull()
        assertTrue(error?.message.orEmpty(), error!!.message!!.startsWith("Element $ref is not enabled after 300ms"))
    }

    @Test
    fun `covered element reports the interceptor and force bypasses checks`() = browserTest { page ->
        page.setContent("""<button id=b onclick="window.clicked=(window.clicked||0)+1">Under</button>
            <div class=overlay style="position:fixed;inset:0;background:rgba(0,0,0,.2)"></div>""")
        val error = runCatching { page.locator("#b").click(timeoutMs = 300) }.exceptionOrNull()
        assertTrue(error?.message.orEmpty(), error!!.message!!.contains("covered by <div class=\"overlay\""))
        assertEquals("undefined", page.js("String(window.clicked)"))
        page.locator("#b").click(force = true)
        assertEquals("1", page.js("window.clicked"))
    }

    @Test
    fun `click waits for a moving element to settle`() = browserTest { page ->
        page.setContent("""<style>@keyframes slide{from{transform:translateX(0)}to{transform:translateX(200px)}}
            #m{animation:slide .4s linear forwards}</style><button id=m onclick="window.done=true">Move</button>""")
        page.locator("#m").click()
        assertEquals("true", page.js("window.done"))
    }

    @Test
    fun `fill updates React-like controlled inputs through the native value setter`() = browserTest { page ->
        // Mirrors React's inputValueTracking: an instance-level value property shadows the prototype
        // setter, and onChange fires only when the DOM value differs from the tracked value.
        page.setContent("""<input id=name><textarea id=bio></textarea><script>
            window.changes = [];
            ['name','bio'].forEach(function(id){
              var el = document.getElementById(id), proto = Object.getPrototypeOf(el);
              var d = Object.getOwnPropertyDescriptor(proto, 'value'), tracked = '';
              Object.defineProperty(el, 'value', {configurable:true, get:function(){return d.get.call(this)}, set:function(v){tracked=String(v); d.set.call(this, v)}});
              el.addEventListener('input', function(){ var now = d.get.call(el); if (now !== tracked) { tracked = now; changes.push(id + '=' + now); } });
            });
        </script>""")
        page.locator("#name").fill("Ada")
        page.locator("#bio").fill("line1\nline2")
        assertEquals("name=Ada,bio=line1\nline2", page.js("changes.join(',')"))
    }

    @Test
    fun `fill drives a real React 19 controlled input`() = browserTest { page ->
        val bundle = reactBundle() ?: run { assumeTrue("React fixture not installed (scripts/build-react-fixture.sh)", false); return@browserTest }
        page.setContent("""<div id=root></div><script>${Files.readString(bundle)}</script>""")
        page.locator("#email").fill("ada@example.com")
        expect(page.locator("#echo")).toHaveText("ada@example.com", timeoutMs = 3_000)
        page.locator("#email").fill("")
        expect(page.locator("#echo")).toHaveText("", timeoutMs = 3_000)
        page.locator("#email").pressSequentially("x@y")
        expect(page.locator("#echo")).toHaveText("x@y", timeoutMs = 3_000)
    }

    @Test
    fun `fill rejects elements that can never be edited`() = browserTest { page ->
        page.setContent("<input id=c type=checkbox><div id=d>text</div><input id=r readonly value=x>")
        val checkbox = runCatching { page.locator("#c").fill("x") }.exceptionOrNull()
        assertTrue(checkbox?.message.orEmpty(), checkbox!!.message!!.contains("Input of type \"checkbox\" cannot be filled"))
        assertFalse(checkbox is KmpwrightTimeoutException)
        val div = runCatching { page.locator("#d").fill("x") }.exceptionOrNull()
        assertTrue(div?.message.orEmpty(), div!!.message!!.contains("not an <input>"))
        val readonly = runCatching { page.locator("#r").fill("y", timeoutMs = 300) }.exceptionOrNull()
        assertTrue(readonly?.message.orEmpty(), readonly is KmpwrightTimeoutException && readonly.message!!.contains("read-only"))
    }

    @Test
    fun `fill supports contenteditable and select`() = browserTest { page ->
        page.setContent("""<div id=rich contenteditable>old</div><select id=s><option value=a>Alpha</option><option value=b>Beta</option></select>""")
        page.locator("#rich").fill("new text")
        assertEquals("new text", page.locator("#rich").innerText())
        page.locator("#s").fill("Beta")
        assertEquals("b", page.locator("#s").inputValue())
    }

    @Test
    fun `press Enter submits the enclosing form unless prevented`() = browserTest { page ->
        page.setContent("""<form id=f><input id=q name=q><button>Go</button></form><script>
            window.submits=0; f.addEventListener('submit', function(e){ e.preventDefault(); submits++; });
        </script>""")
        page.locator("#q").fill("kotlin")
        page.locator("#q").press("Enter")
        assertEquals("1", page.js("submits"))
        page.evaluate("q.addEventListener('keydown', function(e){ if (e.key === 'Enter') e.preventDefault(); })")
        page.locator("#q").press("Enter")
        assertEquals("1", page.js("submits"))
    }

    @Test
    fun `press dispatches keydown keypress input keyup and edits text`() = browserTest { page ->
        page.setContent("""<input id=i value=ab><input id=next><script>window.log=[];
            ['keydown','keypress','beforeinput','input','keyup'].forEach(function(t){ i.addEventListener(t, function(e){ log.push(t + ':' + (e.key || e.data)) }) });
        </script>""")
        page.evaluate("i.setSelectionRange(2,2)")
        page.locator("#i").press("c")
        assertEquals("keydown:c,keypress:c,beforeinput:c,input:c,keyup:c", page.js("log.join(',')"))
        assertEquals("abc", page.locator("#i").inputValue())
        page.locator("#i").press("Backspace")
        assertEquals("ab", page.locator("#i").inputValue())
        page.locator("#i").press("Tab")
        assertEquals("next", page.js("document.activeElement.id"))
        page.locator("#next").pressSequentially("hi")
        page.locator("#next").press("Control+a")
        page.locator("#next").press("Delete")
        assertEquals("", page.locator("#next").inputValue())
    }

    @Test
    fun `hover and tap dispatch their sequences`() = browserTest { page ->
        page.setContent("""<button id=b>Hover</button><script>window.log=[];
            ['mouseover','mouseenter','pointerdown','touchstart','touchend','click'].forEach(function(t){ b.addEventListener(t, function(e){ log.push(t) }) });</script>""")
        page.locator("#b").hover()
        assertEquals("mouseover,mouseenter", page.js("log.join(',')"))
        page.evaluate("log.length = 0")
        page.locator("#b").tap()
        assertEquals("pointerdown,touchstart,touchend,mouseover,click", page.js("log.join(',')"))
    }

    @Test
    fun `dragTo runs an HTML5 drag and drop with a DataTransfer`() = browserTest { page ->
        page.setContent("""<div id=card draggable=true style="width:80px;height:40px;background:#ccc">Card</div>
            <div id=lane style="margin-top:80px;width:200px;height:100px;background:#eee">Lane</div><script>
            window.log=[];
            card.addEventListener('dragstart', function(e){ e.dataTransfer.setData('text/plain', 'card-1'); log.push('dragstart'); });
            lane.addEventListener('dragenter', function(e){ log.push('dragenter'); });
            lane.addEventListener('dragover', function(e){ e.preventDefault(); });
            lane.addEventListener('drop', function(e){ e.preventDefault(); log.push('drop:' + e.dataTransfer.getData('text/plain')); });
            card.addEventListener('dragend', function(){ log.push('dragend'); });
        </script>""")
        page.locator("#card").dragTo(page.locator("#lane"))
        assertEquals("dragstart,dragenter,drop:card-1,dragend", page.js("log.join(',')"))
    }

    @Test
    fun `dragTo moves the pointer for mouse-driven drag handles`() = browserTest { page ->
        page.setContent("""<div id=handle style="width:40px;height:40px;background:red"></div>
            <div id=target style="margin-top:100px;width:100px;height:60px;background:blue"></div><script>
            window.log=[]; var dragging=false;
            handle.addEventListener('mousedown', function(){ dragging=true; log.push('down'); });
            document.addEventListener('mousemove', function(e){ if (dragging && e.buttons === 1) log.push('move'); });
            target.addEventListener('mouseup', function(){ if (dragging) log.push('up@target'); dragging=false; });
        </script>""")
        page.locator("#handle").dragTo(page.locator("#target"))
        val log = page.js("log.join(',')")
        assertTrue(log, log.startsWith("down,move") && log.endsWith("up@target"))
    }

    @Test
    fun `check and uncheck verify the resulting state`() = browserTest { page ->
        page.setContent("""<label><input id=c type=checkbox> Accept</label><input id=r type=radio name=g>
            <input id=stuck type=checkbox onclick="return false">""")
        page.locator("#c").check()
        assertTrue(page.locator("#c").isChecked())
        page.locator("#c").uncheck()
        assertFalse(page.locator("#c").isChecked())
        page.locator("#r").check()
        val radio = runCatching { page.locator("#r").uncheck() }.exceptionOrNull()
        assertTrue(radio?.message.orEmpty(), radio!!.message!!.contains("Cannot uncheck radio"))
        val stuck = runCatching { page.locator("#stuck").check() }.exceptionOrNull()
        assertTrue(stuck?.message.orEmpty(), stuck!!.message!!.contains("did not check"))
    }

    @Test
    fun `selectOption waits for options and reports the available ones`() = browserTest { page ->
        page.setContent("""<select id=s><option value=a>Alpha</option></select>
            <script>setTimeout(function(){ s.insertAdjacentHTML('beforeend', '<option value=b>Beta</option>'); }, 200)</script>""")
        page.locator("#s").selectOption("Beta")
        assertEquals("b", page.locator("#s").inputValue())
        val error = runCatching { page.locator("#s").selectOption("missing", timeoutMs = 300) }.exceptionOrNull()
        assertTrue(error?.message.orEmpty(), error is KmpwrightTimeoutException && error.message!!.contains("\"missing\"") && error.message!!.contains("Alpha"))
    }

    @Test
    fun `query states do not wait and missing elements are not visible`() = browserTest { page ->
        page.setContent("<button id=b>Here</button>")
        assertFalse(page.isVisible("#missing"))
        assertEquals(0, page.count("#missing"))
        assertTrue(page.isVisible("#b"))
        // Refs never resolve to "missing": an unknown ref is an immediate error.
        val unknownRef = runCatching { page.isVisible("@e999") }.exceptionOrNull()
        assertEquals("Unknown ref @e999 \u2014 run snapshot again", unknownRef?.message)
    }

    private fun reactBundle(): Path? {
        var dir: Path? = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        while (dir != null && !Files.isDirectory(dir.resolve(".upstream/react-fixture/node_modules/react-dom"))) dir = dir.parent
        val fixture = dir?.resolve(".upstream/react-fixture") ?: return null
        val esbuild = fixture.resolve("node_modules/.bin/esbuild")
        if (!Files.isExecutable(esbuild)) return null
        val app = fixture.resolve("controlled-input.js")
        Files.writeString(app, """
            import React, {useState} from 'react';
            import {createRoot} from 'react-dom/client';
            function App() {
              const [email, setEmail] = useState('');
              return React.createElement('main', null,
                React.createElement('input', {id: 'email', value: email, onChange: e => setEmail(e.target.value)}),
                React.createElement('p', {id: 'echo'}, email));
            }
            createRoot(document.getElementById('root')).render(React.createElement(App));
        """.trimIndent())
        val out = fixture.resolve("controlled-input.bundle.js")
        val process = ProcessBuilder(esbuild.toString(), app.toString(), "--bundle", "--format=iife", "--platform=browser",
            "--define:process.env.NODE_ENV=\"production\"", "--outfile=$out").redirectErrorStream(true).start()
        if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0) return null
        return out
    }
}
