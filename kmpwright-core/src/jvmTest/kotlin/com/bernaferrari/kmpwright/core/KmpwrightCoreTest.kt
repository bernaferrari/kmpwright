package com.bernaferrari.kmpwright.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class KmpwrightConfigTest {

    @Test
    fun `default config has sane defaults`() {
        val config = KmpwrightConfig()
        assertEquals(InstallMode.DocumentStartPreferred, config.installMode)
        assertEquals(InputMode.Hybrid, config.inputMode)
        assertNotNull(config.timeouts)
        assertNotNull(config.debug)
    }

    @Test
    fun `cdp config hides scrollbars in headless screenshots by default`() {
        val config = CdpConfig()
        assertTrue(config.headless)
        assertTrue(config.hideScrollbars)
    }

    @Test
    fun `config allows custom origin rules`() {
        val config = KmpwrightConfig(
            allowedOriginRules = setOf("https://example.com", "https://test.com")
        )
        assertEquals(2, config.allowedOriginRules.size)
        assertTrue(config.allowedOriginRules.contains("https://example.com"))
    }

    @Test
    fun `debug config defaults to disabled`() {
        val config = KmpwrightConfig()
        assertEquals(false, config.debug.enabled)
        assertEquals(false, config.debug.showHighlight)
        assertEquals(false, config.debug.logJsCommands)
    }

    @Test
    fun `timeout config has reasonable defaults`() {
        val timeouts = TimeoutConfig()
        assertEquals(30_000L, timeouts.default)
        assertEquals(30_000L, timeouts.navigation)
        assertEquals(30_000L, timeouts.action)
        assertEquals(30_000L, timeouts.waitForSelector)
    }

    @Test
    fun `install mode enum has all values`() {
        assertEquals(3, InstallMode.entries.size)
        assertTrue(InstallMode.entries.contains(InstallMode.DocumentStartPreferred))
        assertTrue(InstallMode.entries.contains(InstallMode.AlwaysEvaluate))
        assertTrue(InstallMode.entries.contains(InstallMode.Disabled))
    }

    @Test
    fun `input mode enum has all values`() {
        assertEquals(3, InputMode.entries.size)
        assertTrue(InputMode.entries.contains(InputMode.JsOnly))
        assertTrue(InputMode.entries.contains(InputMode.TouchOnly))
        assertTrue(InputMode.entries.contains(InputMode.Hybrid))
    }

    @Test
    fun `web settings config has all properties`() {
        val settings = DefaultWebSettings
        assertTrue(settings.javaScriptEnabled)
        assertTrue(settings.domStorageEnabled)
        assertFalse(settings.allowFileAccess)
        assertFalse(settings.allowContentAccess)
        assertEquals(0, settings.mixedContentMode)
    }
}

class LocatorOptionsTest {

    @Test
    fun `click options have default values`() {
        val options = ClickOptions()
        assertNull(options.timeoutMs)
        assertEquals(MouseButton.LEFT, options.button)
        assertEquals(1, options.clickCount)
    }

    @Test
    fun `tap options have default values`() {
        val options = TapOptions()
        assertNull(options.timeoutMs)
    }

    @Test
    fun `fill options have default values`() {
        val options = FillOptions()
        assertNull(options.timeoutMs)
        assertEquals(false, options.force)
    }

    @Test
    fun `type options have default values`() {
        val options = TypeOptions()
        assertNull(options.timeoutMs)
    }

    @Test
    fun `press options have default values`() {
        val options = PressOptions()
        assertNull(options.timeoutMs)
        assertEquals(0L, options.delayMs)
    }

    @Test
    fun `scroll into view options have default values`() {
        val options = ScrollIntoViewOptions()
        assertNull(options.timeoutMs)
        assertEquals(ScrollPosition.Center, options.block)
        assertEquals(ScrollPosition.Nearest, options.inline)
    }

    @Test
    fun `filter options have default values`() {
        val options = FilterOptions()
        assertNull(options.hasText)
        assertNull(options.has)
        assertNull(options.hasNotText)
        assertNull(options.hasNot)
    }

    @Test
    fun `action options have default values`() {
        val options = ActionOptions()
        assertNull(options.timeoutMs)
        assertEquals(false, options.force)
    }

    @Test
    fun `drag options have default values`() {
        val options = DragOptions()
        assertNull(options.timeoutMs)
        assertNull(options.sourcePosition)
        assertNull(options.targetPosition)
    }
}

class LoadStateTest {

    @Test
    fun `load state enum has expected values`() {
        assertEquals(3, LoadState.entries.size)
        assertTrue(LoadState.entries.contains(LoadState.DOMContentLoaded))
        assertTrue(LoadState.entries.contains(LoadState.Load))
        assertTrue(LoadState.entries.contains(LoadState.NetworkIdle))
    }
}

class SelectorStateTest {

    @Test
    fun `selector state enum has all values`() {
        assertEquals(4, SelectorState.entries.size)
        assertTrue(SelectorState.entries.contains(SelectorState.Attached))
        assertTrue(SelectorState.entries.contains(SelectorState.Detached))
        assertTrue(SelectorState.entries.contains(SelectorState.Visible))
        assertTrue(SelectorState.entries.contains(SelectorState.Hidden))
    }
}

class ScreenshotOptionsTest {

    @Test
    fun `default screenshot options`() {
        val options = ScreenshotOptions()
        assertFalse(options.fullPage)
        assertNull(options.clip)
        assertFalse(options.omitBackground)
        assertNull(options.timeoutMs)
        assertEquals(90, options.quality)
        assertEquals(ScreenshotFormat.PNG, options.format)
        assertEquals(1.0, options.scale, 0.001)
    }

    @Test
    fun `screenshot with full page`() {
        val options = ScreenshotOptions(fullPage = true)
        assertTrue(options.fullPage)
    }

    @Test
    fun `screenshot with clip`() {
        val clip = Rectangle(0.0, 0.0, 100.0, 50.0)
        val options = ScreenshotOptions(clip = clip)
        assertNotNull(options.clip)
        assertEquals(0.0, options.clip!!.x, 0.001)
        assertEquals(0.0, options.clip!!.y, 0.001)
        assertEquals(100.0, options.clip!!.width, 0.001)
        assertEquals(50.0, options.clip!!.height, 0.001)
    }

    @Test
    fun `screenshot with scale`() {
        val options = ScreenshotOptions(scale = 0.5)
        assertEquals(0.5, options.scale, 0.001)
    }

    @Test
    fun `screenshot with jpeg format`() {
        val options = ScreenshotOptions(format = ScreenshotFormat.JPEG)
        assertEquals(ScreenshotFormat.JPEG, options.format)
    }

    @Test
    fun `screenshot copy preserves values`() {
        val original = ScreenshotOptions(fullPage = true, scale = 2.0)
        val copy = original.copy(fullPage = false, clip = Rectangle(0.0, 0.0, 100.0, 100.0))
        assertFalse(copy.fullPage)
        assertEquals(2.0, copy.scale, 0.001)
        assertNotNull(copy.clip)
        // Playwright rejects fullPage together with clip.
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { original.copy(clip = Rectangle(0.0, 0.0, 1.0, 1.0)) }
    }
}

class ViewportTest {

    @Test
    fun `default viewport has correct dimensions`() {
        val viewport = Viewport.DEFAULT
        assertEquals(1280, viewport.width)
        assertEquals(800, viewport.height)
        assertEquals(1.0, viewport.deviceScaleFactor, 0.001)
        assertFalse(viewport.isMobile)
        assertFalse(viewport.hasTouch)
        assertTrue(viewport.isLandscape)
    }

    @Test
    fun `mobile viewport has correct values`() {
        val viewport = Viewport.MOBILE
        assertEquals(390, viewport.width)
        assertEquals(844, viewport.height)
        assertTrue(viewport.isMobile)
        assertTrue(viewport.hasTouch)
    }

    @Test
    fun `tablet viewport has correct values`() {
        val viewport = Viewport.TABLET
        assertEquals(768, viewport.width)
        assertEquals(1024, viewport.height)
        assertTrue(viewport.isMobile)
        assertTrue(viewport.hasTouch)
    }

    @Test
    fun `custom viewport preserves all values`() {
        val viewport = Viewport(
            width = 1920,
            height = 1080,
            deviceScaleFactor = 2.0,
            isMobile = false,
            hasTouch = false,
            isLandscape = true
        )
        assertEquals(1920, viewport.width)
        assertEquals(1080, viewport.height)
        assertEquals(2.0, viewport.deviceScaleFactor, 0.001)
        assertFalse(viewport.isMobile)
        assertFalse(viewport.hasTouch)
        assertTrue(viewport.isLandscape)
    }
}

class NavigationResultTest {

    @Test
    fun `navigation result creation`() {
        val result = NavigationResult(
            url = "https://example.com",
            status = 200,
            ok = true
        )
        assertEquals("https://example.com", result.url)
        assertEquals(200, result.status)
        assertTrue(result.ok)
        assertNull(result.error)
    }

    @Test
    fun `navigation result with error`() {
        val result = NavigationResult(
            url = "https://example.com",
            status = 404,
            ok = false,
            error = "Not Found"
        )
        assertEquals(404, result.status)
        assertFalse(result.ok)
        assertEquals("Not Found", result.error)
    }
}

class KmpwrightExceptionTest {

    @Test
    fun `exception with message`() {
        val exception = KmpwrightException("Test error message")
        assertEquals("Test error message", exception.message)
    }

    @Test
    fun `exception is throwable`() {
        val exception = KmpwrightException("Test")
        assertTrue(exception is Exception)
    }
}

class RectangleTest {

    @Test
    fun `rectangle creation`() {
        val rect = Rectangle(10.0, 20.0, 100.0, 50.0)
        assertEquals(10.0, rect.x, 0.001)
        assertEquals(20.0, rect.y, 0.001)
        assertEquals(100.0, rect.width, 0.001)
        assertEquals(50.0, rect.height, 0.001)
    }

    @Test
    fun `rectangle equality`() {
        val rect1 = Rectangle(10.0, 20.0, 100.0, 50.0)
        val rect2 = Rectangle(10.0, 20.0, 100.0, 50.0)
        assertEquals(rect1, rect2)
    }
}

class PositionTest {

    @Test
    fun `position creation`() {
        val position = Position(100.0, 200.0)
        assertEquals(100.0, position.x, 0.001)
        assertEquals(200.0, position.y, 0.001)
    }
}

class ScreenshotResultTest {

    @Test
    fun `screenshot result with bytes`() {
        val bytes = byteArrayOf(1, 2, 3)
        val result = ScreenshotResult(bytes, 1920, 1080)
        assertEquals(3, result.bytes.size)
        assertEquals(1920, result.width)
        assertEquals(1080, result.height)
    }

    @Test
    fun `screenshot result equality`() {
        val bytes = byteArrayOf(1, 2, 3)
        val result1 = ScreenshotResult(bytes, 1920, 1080)
        val result2 = ScreenshotResult(bytes, 1920, 1080)
        assertEquals(result1, result2)
    }
}

class ScreenshotFormatTest {

    @Test
    fun `screenshot format enum has all values`() {
        assertEquals(3, ScreenshotFormat.entries.size)
        assertTrue(ScreenshotFormat.entries.contains(ScreenshotFormat.PNG))
        assertTrue(ScreenshotFormat.entries.contains(ScreenshotFormat.JPEG))
        assertTrue(ScreenshotFormat.entries.contains(ScreenshotFormat.WEBP))
    }
}

class GotoOptionsTest {

    @Test
    fun `default goto options defer to the configured navigation timeout`() {
        val options = GotoOptions()
        assertNull(options.timeoutMs)
        assertEquals(LoadState.Load, options.waitUntil)
    }
}

class SetContentOptionsTest {

    @Test
    fun `default set content options`() {
        val options = SetContentOptions()
        assertNull(options.timeoutMs)
        assertEquals(LoadState.Load, options.waitUntil)
    }
}

class ScrollPositionTest {

    @Test
    fun `scroll position enum has all values`() {
        assertEquals(4, ScrollPosition.entries.size)
        assertTrue(ScrollPosition.entries.contains(ScrollPosition.Start))
        assertTrue(ScrollPosition.entries.contains(ScrollPosition.Center))
        assertTrue(ScrollPosition.entries.contains(ScrollPosition.End))
        assertTrue(ScrollPosition.entries.contains(ScrollPosition.Nearest))
    }
}

class NavigationTypeTest {

    @Test
    fun `navigation type enum has all values`() {
        assertEquals(2, NavigationType.entries.size)
        assertTrue(NavigationType.entries.contains(NavigationType.SameDocument))
        assertTrue(NavigationType.entries.contains(NavigationType.NewDocument))
    }
}

class DialogTypeTest {

    @Test
    fun `dialog type enum has all values`() {
        assertEquals(4, DialogType.entries.size)
        assertTrue(DialogType.entries.contains(DialogType.Alert))
        assertTrue(DialogType.entries.contains(DialogType.Confirm))
        assertTrue(DialogType.entries.contains(DialogType.Prompt))
        assertTrue(DialogType.entries.contains(DialogType.BeforeUnload))
    }
}

class ScriptTagOptionsTest {

    @Test
    fun `script tag options with url`() {
        val options = ScriptTagOptions(
            url = "https://example.com/script.js",
            type = "text/javascript",
            id = "my-script",
            async = true,
            defer = false
        )
        assertEquals("https://example.com/script.js", options.url)
        assertEquals("text/javascript", options.type)
        assertEquals("my-script", options.id)
        assertTrue(options.async)
        assertFalse(options.defer)
        assertNull(options.content)
    }

    @Test
    fun `script tag options with content`() {
        val content = "console.log('hello')"
        val options = ScriptTagOptions(content = content)
        assertEquals(content, options.content)
        assertNull(options.url)
        assertEquals("text/javascript", options.type)
    }

    @Test
    fun `script tag options defaults`() {
        val options = ScriptTagOptions()
        assertNull(options.url)
        assertNull(options.content)
        assertEquals("text/javascript", options.type)
        assertEquals("", options.id)
        assertFalse(options.async)
        assertFalse(options.defer)
    }
}

class StyleTagOptionsTest {

    @Test
    fun `style tag options with url`() {
        val options = StyleTagOptions(
            url = "https://example.com/style.css",
            id = "my-style"
        )
        assertEquals("https://example.com/style.css", options.url)
        assertEquals("my-style", options.id)
        assertNull(options.content)
    }

    @Test
    fun `style tag options with content`() {
        val content = "body { color: red; }"
        val options = StyleTagOptions(content = content)
        assertEquals(content, options.content)
        assertNull(options.url)
    }

    @Test
    fun `style tag options defaults`() {
        val options = StyleTagOptions()
        assertNull(options.url)
        assertNull(options.content)
        assertEquals("", options.id)
    }
}

class PdfOptionsTest {

    @Test
    fun `pdf options defaults`() {
        val options = PdfOptions()
        assertNull(options.format)
        assertEquals(8.5 to 11.0, options.paperSizeInches())
        assertNull(options.timeoutMs)
        assertFalse(options.landscape)
        assertFalse(options.displayHeaderFooter)
        assertEquals("", options.headerTemplate)
        assertEquals("", options.footerTemplate)
        assertTrue(options.printBackground)
        assertEquals(1.0, options.scale, 0.001)
        assertNull(options.width)
        assertNull(options.height)
        assertEquals("", options.pageRanges)
    }

    @Test
    fun `pdf options with custom values`() {
        val margin = PdfMargin(top = "2cm", bottom = "2cm", left = "1cm", right = "1cm")
        val options = PdfOptions(
            format = PdfFormat.A4,
            landscape = true,
            scale = 2.0,
            margin = margin
        )
        assertEquals(PdfFormat.A4, options.format)
        assertTrue(options.landscape)
        assertEquals(2.0, options.scale, 0.001)
        assertEquals("2cm", options.margin.top)
    }

    @Test
    fun `pdf format enum has all values`() {
        assertEquals(12, PdfFormat.entries.size)
        assertTrue(PdfFormat.entries.contains(PdfFormat.Letter))
        assertTrue(PdfFormat.entries.contains(PdfFormat.A4))
        assertTrue(PdfFormat.entries.contains(PdfFormat.A3))
    }
}

class PdfMarginTest {

    @Test
    fun `pdf margin defaults`() {
        val margin = PdfMargin()
        assertEquals("0", margin.top)
        assertEquals("0", margin.bottom)
        assertEquals("0", margin.left)
        assertEquals("0", margin.right)
        assertEquals(0.0, pdfLengthInches(margin.top), 0.0)
    }

    @Test
    fun `pdf margin custom values`() {
        val margin = PdfMargin(
            top = "0.5in",
            bottom = "10mm",
            left = "20px",
            right = "1in"
        )
        assertEquals("0.5in", margin.top)
        assertEquals("10mm", margin.bottom)
        assertEquals("20px", margin.left)
        assertEquals("1in", margin.right)
    }
}

class PdfResultTest {

    @Test
    fun `pdf result with bytes`() {
        val bytes = byteArrayOf(1, 2, 3, 4, 5)
        val result = PdfResult(bytes)
        assertEquals(5, result.bytes.size)
        assertEquals(1.toByte(), result.bytes[0])
        assertEquals(5.toByte(), result.bytes[4])
    }

    @Test
    fun `pdf result equality`() {
        val bytes = byteArrayOf(1, 2, 3)
        val result1 = PdfResult(bytes)
        val result2 = PdfResult(bytes)
        assertEquals(result1, result2)
    }

    @Test
    fun `pdf result inequality with different bytes`() {
        val result1 = PdfResult(byteArrayOf(1, 2, 3))
        val result2 = PdfResult(byteArrayOf(1, 2, 4))
        assertFalse(result1 == result2)
    }
}

class A11ySnapshotOptionsTest {

    @Test
    fun `default options`() {
        val options = SnapshotOptions()
        assertFalse(options.includeHidden)
        assertNull(options.maxDepth)
        assertFalse(options.interactive)
        assertFalse(options.compact)
    }

    @Test
    fun `custom options`() {
        val options = SnapshotOptions(includeHidden = true, maxDepth = 10, interactive = true, compact = true)
        assertTrue(options.includeHidden)
        assertEquals(10, options.maxDepth)
        assertTrue(options.interactive)
        assertTrue(options.compact)
    }
}

class DiffOptionsTest {

    @Test
    fun `default diff options`() {
        val options = DiffOptions()
        assertEquals(0.1, options.threshold, 0.001)
        assertTrue(options.generateDiffImage)
    }
}

class ActionTypeTest {

    @Test
    fun `ActionType has all expected values`() {
        val types = ActionType.entries
        assertEquals(22, types.size)
        assertTrue(types.contains(ActionType.Click))
        assertTrue(types.contains(ActionType.Navigate))
        assertTrue(types.contains(ActionType.AddScriptTag))
    }
}

class PolicyResultTest {

    @Test
    fun `PolicyResult has all values`() {
        assertEquals(3, PolicyResult.entries.size)
        assertTrue(PolicyResult.entries.contains(PolicyResult.Allow))
        assertTrue(PolicyResult.entries.contains(PolicyResult.Deny))
        assertTrue(PolicyResult.entries.contains(PolicyResult.Confirm))
    }
}

class RefEntryTest {

    @Test
    fun `RefEntry defaults`() {
        val entry = RefEntry(ref = "@e1", role = "button", name = "Click")
        assertEquals("@e1", entry.ref)
        assertEquals("button", entry.role)
        assertEquals("Click", entry.name)
        assertNull(entry.cssSelector)
    }

    @Test
    fun `RefEntry with all fields`() {
        val entry = RefEntry(ref = "@e2", role = "link", name = "Home", cssSelector = "nav > a")
        assertEquals("@e2", entry.ref)
        assertEquals("nav > a", entry.cssSelector)
    }
}

class DimensionMismatchTest {

    @Test
    fun `dimension mismatch creation`() {
        val dm = DimensionMismatch(100, 200, 300, 400)
        assertEquals(100, dm.expectedWidth)
        assertEquals(200, dm.expectedHeight)
        assertEquals(300, dm.actualWidth)
        assertEquals(400, dm.actualHeight)
    }
}

class ActionPolicyDefaultsTest {

    @Test
    fun `default policy allows everything`() {
        val policy = ActionPolicy()
        assertEquals(PolicyResult.Allow, policy.defaultAction)
        assertTrue(policy.allowedActions.isEmpty())
        assertTrue(policy.deniedActions.isEmpty())
        assertTrue(policy.confirmActions.isEmpty())
        assertTrue(policy.allowedDomains.isEmpty())
        assertTrue(policy.deniedDomains.isEmpty())
    }
}
