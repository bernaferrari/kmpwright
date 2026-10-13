@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)

package com.bernaferrari.kmpwright.core

import kotlinx.cinterop.useContents
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import platform.Foundation.NSURL
import platform.WebKit.WKWebView
import kotlin.coroutines.resume
import kotlin.native.ref.WeakReference
import kotlin.native.runtime.GC
import kotlin.native.runtime.NativeRuntimeApi
import kotlin.test.*

/**
 * Behavioural contracts that execute real documents in WKWebView.
 *
 * WebKit only starts a WebContent process for app bundles, so these tests
 * cannot run in the bare executable that `./gradlew :kmpwright-core:iosSimTest`
 * launches; that task excludes this class (see kmpwright-core/build.gradle.kts)
 * rather than reporting passes it never ran. Run them, together with every
 * other iOS test, through an app host with `scripts/run-ios-webkit-tests.sh`.
 * Outside an app host they fail loudly instead of silently passing.
 */
class IOSWebKitDocumentTest {
    private val baseUrl = "https://fixture.invalid/"

    @Test
    fun evaluation_awaits_promises_serializes_objects_and_keeps_completion_values() = runWebKitTest {
        requireWebKitDocuments()
        val controller = IOSWebViewController()
        try {
            controller.loadHtmlAsync("<title>Eval</title><p>eval</p>", baseUrl, 10_000)
            assertEquals("42", controller.evaluateJavaScriptAsync("Promise.resolve(41 + 1)"))
            assertEquals("""{"a":1,"b":[1,2],"c":"x"}""", controller.evaluateJavaScriptAsync("({a: 1, b: [1, 2], c: 'x'})"))
            assertEquals("text", controller.evaluateJavaScriptAsync("'text'"))
            assertEquals("true", controller.evaluateJavaScriptAsync("1 === 1"))
            assertEquals("1.5", controller.evaluateJavaScriptAsync("3 / 2"))
            assertNull(controller.evaluateJavaScriptAsync("undefined"))
            assertEquals("Eval", controller.evaluateJavaScriptAsync("document.title"))
            // A trailing line comment must not swallow the wrapper's closing paren.
            assertEquals("2", controller.evaluateJavaScriptAsync("1 + 1 // trailing comment"))
            assertEquals("5", controller.evaluateJavaScriptAsync("return 5"))
            // Programs keep their completion value, including booleans and objects.
            assertEquals("2", controller.evaluateJavaScriptAsync("var q = 1; q + 1"))
            assertEquals("true", controller.evaluateJavaScriptAsync("var flag = true; flag"))
            assertEquals("""{"n":3,"s":"v"}""", controller.evaluateJavaScriptAsync("var o = {n: 3, s: 'v'}; o"))
            assertEquals("7", controller.evaluateJavaScriptAsync("var pending = Promise.resolve(7); pending"))
            assertEquals("1", controller.evaluateJavaScriptAsync("var once = (window.runs = (window.runs || 0) + 1); once"))
            assertEquals("1", controller.evaluateJavaScriptAsync("window.runs"))
            // Top-level return/await fall back to an async function body.
            assertEquals("6", controller.evaluateJavaScriptAsync("const z = await Promise.resolve(3);\nreturn z * 2"))
            // Callback API uses the same awaited, JSON-serialized path.
            val viaCallback = suspendCancellableCoroutine<String?> { continuation ->
                controller.evaluateJavaScript("Promise.resolve({x: 1})") { continuation.resume(it) }
            }
            assertEquals("""{"x":1}""", viaCallback)
        } finally { controller.dispose() }
    }

    @Test
    fun program_evaluation_keeps_completion_values_when_csp_blocks_eval() = runWebKitTest {
        requireWebKitDocuments()
        val controller = IOSWebViewController()
        try {
            controller.loadHtmlAsync("<meta http-equiv='Content-Security-Policy' content=\"script-src 'self'\"><p>csp</p>", baseUrl, 10_000)
            assertEquals("blocked", controller.evaluateJavaScriptAsync("(function() { try { eval('1'); return 'allowed'; } catch (e) { return 'blocked'; } })()"))
            assertEquals("2", controller.evaluateJavaScriptAsync("var r = 1; r + 1"))
            assertEquals("1", controller.evaluateJavaScriptAsync("var count = (window.cspRuns = (window.cspRuns || 0) + 1); count"))
            // A Promise completion value cannot cross WebKit's native bridge; the script still runs once.
            assertNull(controller.evaluateJavaScriptAsync("window.cspRuns += 1; var p = Promise.resolve(1); p"))
            assertEquals("2", controller.evaluateJavaScriptAsync("window.cspRuns"))
            assertEquals("3", controller.evaluateJavaScriptAsync("Promise.resolve(3)"))
        } finally { controller.dispose() }
    }

    @Test
    fun evaluation_errors_surface_and_runtime_syntax_errors_do_not_rerun_side_effects() = runWebKitTest {
        requireWebKitDocuments()
        val controller = IOSWebViewController()
        try {
            controller.loadHtmlAsync("<p>errors</p>", baseUrl, 10_000)
            val rejected = assertFailsWith<KmpwrightException> {
                controller.evaluateJavaScriptAsync("Promise.reject(new Error('boom'))")
            }
            assertTrue(rejected.message.orEmpty().contains("boom"), rejected.message)
            assertFailsWith<KmpwrightException> {
                controller.evaluateJavaScriptAsync("window.sideEffects = (window.sideEffects || 0) + 1; JSON.parse('{')")
            }
            assertEquals("1", controller.evaluateJavaScriptAsync("window.sideEffects"))
            assertFailsWith<KmpwrightException> { controller.evaluateJavaScriptAsync("this is not javascript") }
            val callbackResult = suspendCancellableCoroutine<String?> { continuation ->
                controller.evaluateJavaScript("Promise.reject(new Error('x'))") { continuation.resume(it) }
            }
            assertNull(callbackResult)
        } finally { controller.dispose() }
    }

    @Test
    fun screenshots_report_png_pixel_dimensions_and_render_full_page_content() = runWebKitTest {
        requireWebKitDocuments()
        val controller = IOSWebViewController(KmpwrightConfig(viewport = Viewport(320, 480)))
        try {
            controller.loadHtmlAsync(
                "<meta name=viewport content='width=device-width'><body style='margin:0'>" +
                    "<div style='height:1000px;background:#ff0000'></div>" +
                    "<div style='height:1000px;background:#00ff00'></div>" +
                    "<div style='height:1000px;background:#0000ff'></div></body>",
                baseUrl, 10_000
            )
            val webView = controller.getWebView()
            val screenScale = webView.snapshotScreenScale()
            val screenshots = IOSScreenshotController(webView)

            val viewport = screenshots.captureViewport(1.0)
            val viewportImage = assertNotNull(viewport.toRgbaImage())
            assertEquals(viewportImage.width, viewport.width)
            assertEquals(viewportImage.height, viewport.height)
            assertEquals((320 * screenScale).toInt(), viewport.width)

            val fullPage = screenshots.captureFullPage(1.0)
            val fullImage = assertNotNull(fullPage.toRgbaImage())
            assertEquals(fullImage.width, fullPage.width)
            assertEquals(fullImage.height, fullPage.height)
            assertEquals((3000 * screenScale).toInt(), fullPage.height)
            assertEquals(Triple(255, 0, 0), fullImage.rgbAt(0.1))
            assertEquals(Triple(0, 255, 0), fullImage.rgbAt(0.5))
            // Content far outside the visible viewport must be rendered, not blank.
            assertEquals(Triple(0, 0, 255), fullImage.rgbAt(0.95))

            val half = screenshots.captureViewport(0.5)
            assertEquals(assertNotNull(half.toRgbaImage()).width, half.width)

            val viaController = suspendCancellableCoroutine<Triple<ByteArray?, Int, Int>> { continuation ->
                controller.takeScreenshot(ScreenshotOptions(fullPage = true)) { bytes, width, height ->
                    continuation.resume(Triple(bytes, width, height))
                }
            }
            val decoded = assertNotNull(ScreenshotResult(assertNotNull(viaController.first), 0, 0).toRgbaImage())
            assertEquals(decoded.width to decoded.height, viaController.second to viaController.third)
        } finally { controller.dispose() }
    }

    @Test
    fun keyboard_press_respects_prevented_keydown_and_keypress() = runWebKitTest {
        requireWebKitDocuments()
        val controller = IOSWebViewController()
        try {
            controller.loadHtmlAsync(
                "<input id=field><script>var field = document.getElementById('field');" +
                    "field.addEventListener('keydown', function(e) { if (e.key === 'a') e.preventDefault(); });" +
                    "field.addEventListener('keypress', function(e) { if (e.key === 'c') e.preventDefault(); });" +
                    "field.focus();</script>",
                baseUrl, 10_000
            )
            val keyboard = IOSKeyboard(controller)
            keyboard.press("a")
            keyboard.press("b")
            keyboard.press("c")
            assertEquals("b", controller.evaluateJavaScriptAsync("document.getElementById('field').value"))
        } finally { controller.dispose() }
    }

    @Test
    fun touch_and_mouse_input_run_in_order_through_the_awaited_path() = runWebKitTest {
        requireWebKitDocuments()
        val controller = IOSWebViewController(KmpwrightConfig(viewport = Viewport(320, 480)))
        try {
            controller.loadHtmlAsync(
                "<body style='margin:0'><button id=b style='width:200px;height:100px'>b</button><script>" +
                    "window.events = []; var b = document.getElementById('b');" +
                    "['touchstart','touchend','mousedown','mouseup','click'].forEach(function(t) {" +
                    "b.addEventListener(t, function() { window.events.push(t); }); });</script>",
                baseUrl, 10_000
            )
            IOSMouse(controller).click(50.0, 50.0, MouseClickOptions())
            assertEquals(
                """["touchstart","touchend","mousedown","mouseup","click"]""",
                controller.evaluateJavaScriptAsync("window.events")
            )
        } finally { controller.dispose() }
    }

    @Test
    fun denied_iframes_do_not_fail_navigation_and_main_frame_denials_report_once() = runWebKitTest {
        requireWebKitDocuments()
        val controller = IOSWebViewController()
        try {
            controller.setNavigationGuard { url -> !url.contains("blocked.invalid") }
            controller.loadHtmlAsync("<p>host</p><iframe src='https://blocked.invalid/frame'></iframe>", baseUrl, 10_000)

            val results = mutableListOf<NavigationResult>()
            controller.setNavigationCallback { results += it }
            controller.evaluateJavaScriptAsync("setTimeout(function() { location.href = 'https://blocked.invalid/page'; }, 0); true")
            delay(1_000)
            assertEquals(1, results.size, "results: $results")
            assertFalse(results.single().ok)

            // A stale policy failure must not complete the next navigation.
            controller.loadHtmlAsync("<p>after</p>", baseUrl, 10_000)
            assertEquals("after", controller.evaluateJavaScriptAsync("document.body.innerText"))
        } finally { controller.dispose() }
    }

    @Test
    fun dialog_listener_is_bounded_by_the_handler_timeout() = runWebKitTest {
        requireWebKitDocuments()
        val controller = IOSWebViewController(KmpwrightConfig(timeouts = TimeoutConfig(handler = 300)))
        try {
            controller.loadHtmlAsync("<p>dialog</p>", baseUrl, 10_000)
            controller.addDialogListener(object : DialogListener {
                override suspend fun onDialogAsync(type: DialogType, message: String, defaultPrompt: String?): DialogResponse =
                    awaitCancellation()
            })
            val answer = withTimeout(5_000) { controller.evaluateJavaScriptAsync("confirm('continue?')") }
            assertEquals("false", answer)
        } finally { controller.dispose() }
    }

    @Test
    fun custom_scheme_routes_fulfill_documents_and_dispose_fails_in_flight_tasks() = runWebKitTest {
        requireWebKitDocuments()
        val controller = IOSWebViewController(KmpwrightConfig(iosSchemeInterception = IosSchemeInterceptionConfig(scheme = "kmpw")))
        val slowEntered = CompletableDeferred<Unit>()
        controller.addRouteHandler(".*/index") {
            it.fulfill(status = 200, body = "<title>Scheme</title>ok", contentType = "text/html")
        }
        controller.addRouteHandler(".*/slow") {
            slowEntered.complete(Unit)
            awaitCancellation()
        }
        try {
            val result = suspendCancellableCoroutine<NavigationResult> { continuation ->
                controller.setNavigationCallback { if (continuation.isActive) continuation.resume(it) }
                controller.loadUrl("kmpw://app/index")
            }
            assertTrue(result.ok, result.error)
            assertEquals("Scheme", controller.evaluateJavaScriptAsync("document.title"))

            controller.setNavigationCallback(null)
            controller.loadUrl("kmpw://app/slow")
            withTimeout(5_000) { slowEntered.await() }
        } finally { controller.dispose() }
        // WebKit raises an Objective-C exception if a stopped task is messaged
        // or an in-flight one is completed twice; give it time to surface.
        delay(300)
    }

    private fun RgbaImage.rgbAt(verticalFraction: Double): Triple<Int, Int, Int> {
        val row = (height * verticalFraction).toInt().coerceIn(0, height - 1)
        val offset = (row * width + width / 2) * 4
        return Triple(pixels[offset].toInt() and 255, pixels[offset + 1].toInt() and 255, pixels[offset + 2].toInt() and 255)
    }
}

/** WebKit object-graph contracts that need no document, so they also run under Gradle. */
class IOSWebKitLifecycleTest {
    @Test
    fun webkit_owned_handlers_do_not_retain_the_controller() = runWebKitTest {
        val webView = createAndForgetController(KmpwrightConfig(iosSchemeInterception = IosSchemeInterceptionConfig(scheme = "kmpw")))
        val reference = webView.second
        repeat(3) {
            @OptIn(NativeRuntimeApi::class)
            GC.collect()
            pumpMainRunLoop(0.05)
        }
        assertNull(reference.value, "IOSWebViewController leaked through a WebKit-retained handler")
        assertNotNull(webView.first.configuration.urlSchemeHandlerForURLScheme("kmpw"))
    }

    private fun createAndForgetController(config: KmpwrightConfig): Pair<WKWebView, WeakReference<IOSWebViewController>> {
        val controller = IOSWebViewController(config)
        return controller.getWebView() to WeakReference(controller)
    }
}

class IOSRouteResolutionTest {
    private fun request(url: String) = Request(url = url, id = "req-${url.hashCode()}")

    @Test
    fun last_registered_matching_route_wins_and_fallthrough_falls_back() = runWebKitTest {
        val controller = IOSWebViewController()
        val calls = mutableListOf<String>()
        try {
            controller.addRouteHandler(".*/api/.*") { calls += "old"; it.fulfill(status = 201) }
            controller.addRouteHandler(".*/api/items") { calls += "new"; it.fulfill(status = 202) }
            val first = controller.resolveRouteAction(request("https://fixture.invalid/api/items"))
            assertEquals(202, (first as RouteAction.Fulfill).status)
            assertEquals(listOf("new"), calls)

            calls.clear()
            controller.addRouteHandler(".*/api/items") { calls += "fallback"; it.fallback() }
            val second = controller.resolveRouteAction(request("https://fixture.invalid/api/items"))
            assertEquals(201, (second as RouteAction.Fulfill).status)
            assertEquals(listOf("fallback", "old"), calls)

            // An explicit decision ends the chain.
            controller.addRouteHandler(".*/explicit") { route -> route.abort("blockedbyclient") }
            assertEquals(RouteAction.Abort("blockedbyclient"), controller.resolveRouteAction(request("https://fixture.invalid/explicit")))

            controller.removeRouteHandler(".*/api/.*")
            controller.removeRouteHandler(".*/api/items")
            assertEquals(RouteAction.Fallback, controller.resolveRouteAction(request("https://fixture.invalid/api/items")))
        } finally { controller.dispose() }
    }

    @Test
    fun failing_route_handlers_abort_instead_of_letting_the_request_through() = runWebKitTest {
        val controller = IOSWebViewController()
        try {
            controller.addRouteHandler(".*/throws") { throw IllegalStateException("handler bug") }
            assertEquals(RouteAction.Abort("failed"), controller.resolveRouteAction(request("https://fixture.invalid/throws")))
        } finally { controller.dispose() }
    }

    @Test
    fun hung_route_handlers_time_out_and_abort() = runWebKitTest(timeoutMs = 15_000) {
        // The bound is TimeoutConfig.handler, identical on every backend.
        val controller = IOSWebViewController(KmpwrightConfig(timeouts = TimeoutConfig(handler = 500)))
        try {
            controller.addRouteHandler(".*/hangs") { awaitCancellation() }
            assertEquals(RouteAction.Abort("timedout"), controller.resolveRouteAction(request("https://fixture.invalid/hangs")))
            controller.addRouteHandler(".*/later") { it.fallback() }
            assertEquals(RouteAction.Fallback, controller.resolveRouteAction(Request(url = "https://fixture.invalid/later", id = "req-stale")))
        } finally { controller.dispose() }
    }
}

class IOSPlatformSupportTest {
    @Test
    fun iso8601_round_trips_in_utc_and_bad_input_falls_back_to_now() {
        assertEquals(1_704_164_645_678L, parseIso8601ToMillis("2024-01-02T03:04:05.678Z"))
        assertEquals("2024-01-02T03:04:05.678Z", formatIso8601(1_704_164_645_678L))
        val before = currentTimeMillis()
        val fallback = parseIso8601ToMillis("not a date")
        assertTrue(fallback >= before && fallback <= currentTimeMillis())
    }

    @Test
    fun native_completion_values_use_the_json_envelope_shape() {
        assertNull(nativeScriptResult(null))
        assertEquals("text", nativeScriptResult("text"))
        assertEquals("2", nativeScriptResult(2.0))
        assertEquals("2.5", nativeScriptResult(2.5))
        assertEquals("true", nativeScriptResult(true))
        assertEquals("""{"a":1,"b":[true,"x",null]}""", nativeScriptResult(mapOf("a" to 1.0, "b" to listOf(true, "x", null))))
    }

    @Test
    fun state_directory_is_application_support() {
        assertTrue(platformHomeDir().endsWith("/Library/Application Support"), platformHomeDir())
        assertTrue(authProfileDir().startsWith(platformHomeDir()))
        val dir = "${platformHomeDir()}/.kmpwright/test-${currentTimeMillis()}"
        fileWrite("$dir/state.json", "{}")
        try {
            assertEquals("{}", fileRead("$dir/state.json"))
        } finally {
            fileDelete("$dir/state.json")
            fileDelete(dir)
        }
    }
}
