package com.bernaferrari.kmpwright.test

import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bernaferrari.kmpwright.core.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class DocumentScreenshotInstrumentedTest {
    @get:Rule val host = ActivityScenarioRule(WebViewHarnessActivity::class.java)
    private lateinit var browser: Browser
    private lateinit var page: Page
    private lateinit var view: android.webkit.WebView
    @Before fun setUp() {
        host.scenario.onActivity { activity ->
            view = activity.resetWebView()
            browser = Browser(AndroidPlatformContext(activity, view))
            page = browser.page()
        }
    }
    @After fun tearDown() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            if (this::browser.isInitialized) browser.dispose()
        }
    }
    private val tall = """<meta name=viewport content="width=device-width,initial-scale=1"><style>body{margin:0}</style><div style="height:6000px"></div><div id="target" style="width:100px"><div style="height:1600px;background:blue"></div><div style="height:200px;background:lime"></div></div>"""
    private fun assertGreenFooter(shot: ScreenshotResult) {
        val bitmap = BitmapFactory.decodeByteArray(shot.bytes, 0, shot.bytes.size)
        try {
            val footer = bitmap.getPixel(bitmap.width / 2, bitmap.height - 10)
            assertTrue("The selected footer must be present: ${Integer.toHexString(footer)}", Color.green(footer) > 200 && Color.blue(footer) < 40)
        } finally { bitmap.recycle() }
    }
    @Test fun tallElementScreenshotIncludesTheEntireSelectedPart() = runBlocking(Dispatchers.Main) {
        page.setContent(tall)
        val dpr = page.evaluate("window.devicePixelRatio").value.toDouble()
        val shot = page.locator("#target").screenshot()
        assertEquals(100 * dpr, shot.width.toDouble(), 3.0)
        assertEquals(1800 * dpr, shot.height.toDouble(), 3.0)
        assertGreenFooter(shot)
    }
    @Test fun elementCaptureRetainsAndroidsScaleLimits() = runBlocking(Dispatchers.Main) {
        page.setContent("<meta name=viewport content='width=device-width'><body style='margin:0'><div id='target' style='width:100px;height:120px;background:lime'></div></body>")
        val dpr = page.evaluate("window.devicePixelRatio").value.toDouble()
        val shot = page.locator("#target").screenshot(scale = 0.01)
        assertEquals((100 * dpr * 0.1).roundToInt(), shot.width)
        assertEquals((120 * dpr * 0.1).roundToInt(), shot.height)
        assertGreenFooter(shot)
    }
    @Test fun selectedRegionBelowFullPageBudgetCapturesItsLastTileAndRestoresState() = runBlocking(Dispatchers.Main) {
        page.setContent(tall)
        page.evaluate("window.scrollTo(0, 100); document.documentElement.style.transform='scale(1)'; 0")
        val shot = captureWebViewDocument(view, Rectangle(0.0, 6000.0, 100.0, 1800.0), 2.0, 7_000_000, 10_000)
        assertEquals(200, shot.width)
        assertEquals(3600, shot.height)
        assertFalse(shot.truncated)
        assertGreenFooter(shot)
        assertEquals("scale(1)", page.evaluate("document.documentElement.style.transform").value)
        assertEquals(100.0, page.evaluate("window.scrollY").value.toDouble(), 1.0)
    }
    @Test fun wideLayoutSelectionUsesTheActualOverviewScale() = runBlocking(Dispatchers.Main) {
        page.setContent("""<meta name=viewport content="width=1200"><style>body{margin:0;width:1200px;background:red}</style><div style="height:6000px"></div><div style="margin-left:700px;width:160px;height:120px;background:lime"></div>""")
        val shot = captureWebViewDocument(view, Rectangle(700.0, 6000.0, 160.0, 120.0), 2.0)
        assertEquals(320, shot.width)
        assertEquals(240, shot.height)
        assertGreenFooter(shot)
    }
    @Test fun oversizedSelectedRegionFailsWithoutReturningPartialContent() = runBlocking(Dispatchers.Main) {
        page.setContent("<meta name=viewport content='width=device-width'><body style='margin:0'><div style='height:9000px'>Too large</div></body>")
        try {
            captureWebViewDocument(view, Rectangle(0.0, 0.0, 100.0, 9000.0), 2.0, 7_000_000, 10_000, true)
            fail("Expected a capture-budget failure")
        } catch (error: KmpwrightException) { assertTrue(error.message.orEmpty().contains("too large")) }
        assertTrue(page.screenshot().bytes.isNotEmpty())
    }
    @Test fun cancellationRestoresThePageBeforeAnotherCapture() = runBlocking(Dispatchers.Main) {
        page.setContent(tall)
        page.evaluate("window.scrollTo(0, 100); document.documentElement.style.setProperty('transform','scale(1)','important'); 0")
        val firstRow = CompletableDeferred<Unit>()
        val clip = Rectangle(0.0, 6000.0, 100.0, 1800.0)
        val capture = launch {
            captureWebViewDocument(view, clip = clip, pixelsPerCssPixel = 2.0) {
                firstRow.complete(Unit)
                awaitCancellation()
            }
        }
        withTimeout(30_000) { firstRow.await() }
        capture.cancelAndJoin()
        assertEquals("scale(1)", page.evaluate("document.documentElement.style.transform").value)
        assertEquals("important", page.evaluate("document.documentElement.style.getPropertyPriority('transform')").value)
        assertEquals(100.0, page.evaluate("window.scrollY").value.toDouble(), 1.0)
        assertGreenFooter(captureWebViewDocument(view, clip = clip, pixelsPerCssPixel = 2.0))
    }
    @Test fun softwareViewportCaptureUsesTheScrolledContent() = runBlocking(Dispatchers.Main) {
        page.setContent(tall.replace("width:100px", "width:100%"))
        page.evaluate("window.scrollTo(0, Math.max(document.body.scrollHeight, document.documentElement.scrollHeight)); 0")
        delay(200)
        assertGreenFooter(page.screenshot())
        (view.parent as android.view.ViewGroup).removeView(view)
        // PixelCopy cannot read a detached view; this exercises the software fallback.
        assertGreenFooter(page.screenshot())
    }
    @Test fun boundedDocumentCaptureReportsItsTruncation() = runBlocking(Dispatchers.Main) {
        page.setContent("<meta name=viewport content='width=device-width,initial-scale=1'><body style='margin:0'><div style='height:18000px;background:blue'></div></body>")
        val shot = captureWebViewDocument(view, pixelsPerCssPixel = 2.0, maxPixels = 7_000_000, maxHeight = 10_000, truncateFullPage = true, viewportWidthOnly = true)
        assertTrue(shot.truncated)
        @Suppress("DEPRECATION")
        val visibleCssWidth = view.width / view.scale.toDouble()
        assertEquals((visibleCssWidth * 2).roundToInt(), shot.width)
        assertTrue(shot.height <= 10_000)
        assertTrue(shot.width.toLong() * shot.height <= 7_000_000)
    }
}
