package com.bernaferrari.kmpwright.example

import com.bernaferrari.kmpwright.core.*
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import platform.WebKit.WKWebView

/** Executable app-host contracts, enabled only by the example's test launch mode. */
@OptIn(ExperimentalForeignApi::class)
class IOSReliabilityProbe {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun start(view: WKWebView, baseUrl: String, progress: (String) -> Unit, completion: (String) -> Unit) {
        scope.launch {
            val browser = Browser(IOSPlatformContext(view))
            var step = "startup"
            try {
                withTimeout(60_000) {
                    val page = browser.page()
                    step = "1/5 locator identity"; progress(step)
                    check(page.goto("$baseUrl/first").ok) { "Initial navigation failed" }
                    val rows = page.locator(".row")
                    val filteredCount = rows.filter(hasText = "Second").count()
                    check(filteredCount == 1) { "hasText count: expected 1, actual $filteredCount; JS number=${page.evaluate("1").value}" }
                    check(rows.filter(hasNotText = "First").count() == 1) { "hasNotText count" }
                    check(rows.filter(has = page.locator("#second")).count() == 1) { "has descendant count" }
                    check(rows.filter(hasNot = page.locator("#second")).count() == 1) { "hasNot descendant count" }
                    check(rows.nth(1).locator("button").getAttribute("id") == "second") { "Indexed descendant" }
                    check(rows.nth(1).boundingBox()!!.y > rows.first().boundingBox()!!.y) { "Indexed bounding box" }
                    check(runCatching { rows.nth(99).waitFor(SelectorState.Attached, 150) }.isFailure) { "Absent indexed wait passed" }
                    check(runCatching { expect(rows.nth(1)).toHaveText(rows.first().innerText(), 150) }.isFailure) { "Wrong-element assertion passed" }
                    check(page.locator("text=Unique text").getAttribute("id") == "nested") { "Nested text identity" }

                    step = "2/5 link navigation"; progress(step)
                    val navigation = async(start = CoroutineStart.UNDISPATCHED) { page.waitForNavigation(WaitForNavigationOptions(timeoutMs = 3_000)) }
                    page.locator("a").click()
                    check(navigation.await()?.url == "$baseUrl/next")
                    check(page.url.value == "$baseUrl/next")
                    check(page.locator("h1").innerText() == "Arrived")

                    step = "3/5 native fetch and XHR"; progress(step)
                    page.evaluate("fetch(new URL('$baseUrl/echo')).then(r=>r.text()).then(t=>window.urlResult=t).catch(e=>window.urlResult='error:'+e)")
                    page.waitForFunction("window.urlResult !== undefined", 3_000)
                    check(page.evaluate("window.urlResult").value == "echo") { "urlResult: " + page.evaluate("window.urlResult").value }
                    page.evaluate("var form=new FormData();form.append('name','Kotlin');fetch('$baseUrl/echo',{method:'POST',body:form}).then(r=>r.text()).then(t=>window.formResult=t).catch(e=>window.formResult='error:'+e)")
                    page.waitForFunction("window.formResult !== undefined", 3_000)
                    check(page.evaluate("window.formResult.includes('Kotlin')").value == "true") { "FormData result: " + page.evaluate("window.formResult").value }
                    page.evaluate("fetch('$baseUrl/echo',{method:'POST',body:new Uint8Array([0,128,255])}).then(r=>r.arrayBuffer()).then(b=>window.binaryResult=Array.from(new Uint8Array(b)).join(',')).catch(e=>window.binaryResult='error:'+e)")
                    page.waitForFunction("window.binaryResult !== undefined", 3_000)
                    check(page.evaluate("window.binaryResult").value == "0,128,255") { "binaryResult: " + page.evaluate("window.binaryResult").value }
                    page.route("$baseUrl/no-content") { it.fulfill(status = 204) }
                    page.evaluate("fetch('$baseUrl/no-content').then(async r=>window.emptyResult=r.status+':'+await r.text()).catch(e=>window.emptyResult='error:'+e)")
                    page.waitForFunction("window.emptyResult !== undefined", 3_000)
                    check(page.evaluate("window.emptyResult").value == "204:") { "emptyResult: " + page.evaluate("window.emptyResult").value }
                    page.evaluate("var xhr=new XMLHttpRequest();xhr.open('GET','$baseUrl/echo');xhr.onload=()=>window.xhrResult=xhr.responseText;xhr.onerror=()=>window.xhrResult='error';xhr.send()")
                    page.waitForFunction("window.xhrResult !== undefined", 3_000)
                    check(page.evaluate("window.xhrResult").value == "echo") { "xhrResult: " + page.evaluate("window.xhrResult").value }

                    step = "4/5 native cookie and storage round trips"; progress(step)
                    val expires = currentTimeMillis() / 1000.0 + 3600
                    page.addCookies(listOf(Cookie("persistent", "saved", "127.0.0.1", expires = expires, httpOnly = true)))
                    page.addCookies(listOf(Cookie("session", "saved", "127.0.0.1")))
                    page.localStorage.set("key", "value")
                    page.sessionStorage.set("key", "value")
                    check(page.localStorage.get("key") == "value")
                    check(page.sessionStorage.get("key") == "value")
                    val roundtrip = page.storageState()
                    page.clearCookies()
                    page.localStorage.clear()
                    page.setStorageState(roundtrip)
                    val restored = page.cookies().associateBy { it.name }
                    check(restored.getValue("persistent").httpOnly)
                    check(kotlin.math.abs(restored.getValue("persistent").expires!! - expires) < 2)
                    check(restored.getValue("session").expires == null)
                    check(page.localStorage.get("key") == "value")
                    page.localStorage.clear(); page.sessionStorage.clear(); val empty = page.storageState()
                    page.localStorage.set("later", "data"); page.sessionStorage.set("later", "data")
                    page.setStorageState(empty)
                    check(page.localStorage.entries().isEmpty() && page.sessionStorage.entries().isEmpty())

                    step = "5/5 native PDF and context clearing"; progress(step)
                    val pdf = page.pdf(PdfOptions(format = PdfFormat.Content, margin = PdfMargin("0", "0", "0", "0")))
                    check(pdf.bytes.take(4).toByteArray().decodeToString() == "%PDF")
                    val context = browser.newContext()
                    try {
                        val isolated = context.newPage()
                        check(isolated.goto("$baseUrl/next").ok)
                        check(isolated.cookies().none { it.name == "persistent" })
                        isolated.localStorage.set("clear", "data")
                        context.clearData()
                        check(isolated.localStorage.get("clear") == null)
                    } finally { context.close() }
                }
                completion(Json.encodeToString(mapOf("status" to "passed", "step" to step)))
            } catch (failure: Throwable) {
                completion(Json.encodeToString(mapOf("status" to "failed", "step" to step, "error" to failure.stackTraceToString())))
            } finally {
                browser.close()
                scope.cancel()
            }
        }
    }
}
