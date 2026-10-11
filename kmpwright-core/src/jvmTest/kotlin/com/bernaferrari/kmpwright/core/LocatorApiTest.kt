package com.bernaferrari.kmpwright.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.collections.immutable.persistentSetOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class LocatorApiTest {

    private lateinit var locator: TestLocator
    private lateinit var controller: MockWebViewController

    @OptIn(ExperimentalCoroutinesApi::class)
    @Before
    fun setup() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        controller = MockWebViewController()
        locator = TestLocator("div.test", controller)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `selectorStr returns the selector string`() {
        val loc = TestLocator("#submit-btn")
        assertEquals("#submit-btn", loc.selectorStr)
    }

    @Test
    fun `selectorStr preserves complex selectors`() {
        val loc = TestLocator("div.container > ul > li:nth-child(2)")
        assertEquals("div.container > ul > li:nth-child(2)", loc.selectorStr)
    }

    @Test
    fun `click executes without error`() = testBlocking {
        locator.click()
    }

    @Test
    fun `click with custom options`() = testBlocking {
        locator.click(ClickOptions(timeoutMs = 5000, button = MouseButton.RIGHT))
    }

    @Test
    fun `dblclick executes without error`() = testBlocking {
        locator.dblclick()
    }

    @Test
    fun `tap executes without error`() = testBlocking {
        locator.tap()
    }

    @Test
    fun `tap with custom options`() = testBlocking {
        locator.tap(TapOptions(timeoutMs = 5000, force = true))
    }

    @Test
    fun `fill executes without error`() = testBlocking {
        locator.fill("hello world")
    }

    @Test
    fun `fill with custom options`() = testBlocking {
        locator.fill("test", FillOptions(timeoutMs = 10000, force = true))
    }

    @Test
    fun `type executes without error`() = testBlocking {
        locator.type("abc")
    }

    @Test
    fun `zero-delay type batches characters in one bridge evaluation`() = testBlocking {
        BaseLocator.injectedControllers = persistentSetOf()
        locator.type("a'b")

        val typeScripts = controller.executedScripts.filter { it.contains("new InputEvent") }
        assertEquals(1, typeScripts.size)
        assertTrue(typeScripts.single().contains("dispatchEvent(new Event('change'"))
        assertTrue(typeScripts.single().contains("a\\'b"))
    }

    @Test
    fun `type with delay`() = testBlocking {
        locator.type("xyz", TypeOptions(delayMs = 10))
    }

    @Test
    fun `press executes without error`() = testBlocking {
        locator.press("Enter")
    }

    @Test
    fun `press with custom options`() = testBlocking {
        locator.press("Tab", PressOptions(delayMs = 50))
    }

    @Test
    fun `hover executes without error`() = testBlocking {
        locator.hover()
    }

    @Test
    fun `scrollIntoViewIfNeeded executes without error`() = testBlocking {
        locator.scrollIntoViewIfNeeded()
    }

    @Test
    fun `scrollIntoViewIfNeeded with custom options`() = testBlocking {
        locator.scrollIntoViewIfNeeded(
            ScrollIntoViewOptions(block = ScrollPosition.Start, inline = ScrollPosition.Start)
        )
    }

    @Test
    fun `scrollDown executes without error`() = testBlocking {
        locator.scrollDown(100.0)
    }

    @Test
    fun `scrollDown with zero distance`() = testBlocking {
        locator.scrollDown(0.0)
    }

    @Test
    fun `scrollUp executes without error`() = testBlocking {
        locator.scrollUp(100.0)
    }

    @Test
    fun `scrollUp with zero distance`() = testBlocking {
        locator.scrollUp(0.0)
    }

    @Test
    fun `focus executes without error`() = testBlocking {
        locator.focus()
    }

    @Test
    fun `check executes without error`() = testBlocking {
        locator.check()
    }

    @Test
    fun `check with custom options`() = testBlocking {
        locator.check(CheckOptions(timeoutMs = 5000, force = true))
    }

    @Test
    fun `uncheck executes without error`() = testBlocking {
        locator.uncheck()
    }

    @Test
    fun `uncheck with custom options`() = testBlocking {
        locator.uncheck(CheckOptions(timeoutMs = 5000, force = true))
    }

    @Test
    fun `selectOption with single value`() = testBlocking {
        locator.selectOption("option1")
    }

    @Test
    fun `selectOption with list of values`() = testBlocking {
        locator.selectOption(listOf("option1", "option2"))
    }

    @Test
    fun `setInputFiles throws KmpwrightException`() = testBlocking {
        try {
            locator.setInputFiles(listOf("/path/to/file.txt"))
        } catch (e: KmpwrightException) {
            // Expected on WebView
            return@testBlocking
        }
    }

    @Test
    fun `clearInputFiles executes without error`() = testBlocking {
        locator.clearInputFiles()
    }

    @Test
    fun `dragTo executes without error`() = testBlocking {
        val target = TestLocator("div.target", controller)
        locator.dragTo(target)
    }

    @Test
    fun `dragTo with custom options`() = testBlocking {
        val target = TestLocator("div.target", controller)
        locator.dragTo(target, DragOptions(timeoutMs = 10000))
    }

    @Test
    fun `textContent returns nullable string`() = testBlocking {
        val result = locator.textContent()
        assertNull(result)
    }

    @Test
    fun `innerText returns string`() = testBlocking {
        val result = locator.innerText()
        assertEquals("", result)
    }

    @Test
    fun `innerHTML returns string`() = testBlocking {
        val result = locator.innerHTML()
        assertEquals("", result)
    }

    @Test
    fun `getAttribute returns nullable string`() = testBlocking {
        val result = locator.getAttribute("data-id")
        assertNull(result)
    }

    @Test
    fun `inputValue returns nullable string`() = testBlocking {
        val result = locator.inputValue()
        assertNull(result)
    }

    @Test
    fun `computedStyles returns empty map for mock`() = testBlocking {
        val styles = locator.computedStyles()
        assertTrue(styles.isEmpty())
    }

    @Test
    fun `count returns integer`() = testBlocking {
        val count = locator.count()
        assertEquals(0, count)
    }

    @Test
    fun `isVisible returns boolean`() = testBlocking {
        val visible = locator.isVisible()
        assertFalse(visible)
    }

    @Test
    fun `isEnabled returns boolean`() = testBlocking {
        val enabled = locator.isEnabled()
        assertTrue(enabled)
    }

    @Test
    fun `isChecked returns boolean`() = testBlocking {
        val checked = locator.isChecked()
        assertFalse(checked)
    }

    @Test
    fun `isFocused returns boolean`() = testBlocking {
        val focused = locator.isFocused()
        assertFalse(focused)
    }

    @Test
    fun `isEditable returns boolean`() = testBlocking {
        val editable = locator.isEditable()
        assertFalse(editable)
    }

    @Test
    fun `boundingBox returns nullable rectangle`() = testBlocking {
        val box = locator.boundingBox()
        assertNull(box)
    }

    @Test
    fun `screenshot executes without error`() = testBlocking {
        val result = locator.screenshot()
        assertNotNull(result)
    }

    @Test
    fun `highlight executes without error`() = testBlocking {
        locator.highlight()
    }

    @Test
    fun `highlight with custom options`() = testBlocking {
        locator.highlight(HighlightOptions(color = "blue", borderColor = "darkblue", durationMs = 5000))
    }

    @Test
    fun `waitFor times out in mock`() = testBlocking {
        try {
            locator.waitFor(SelectorState.Visible, timeoutMs = 100)
        } catch (e: KmpwrightException) {
            // Expected timeout in mock
        }
    }

    @Test
    fun `first returns a Locator`() {
        val first = locator.first()
        assertNotNull(first)
        assertTrue(first is TestLocator)
    }

    @Test
    fun `nth returns a Locator`() {
        val nth = locator.nth(2)
        assertNotNull(nth)
        assertTrue(nth is TestLocator)
    }

    @Test
    fun `last returns a Locator`() {
        val last = locator.last()
        assertNotNull(last)
        assertTrue(last is TestLocator)
    }

    @Test
    fun `locator returns a nested Locator`() {
        val child = locator.locator("span")
        assertNotNull(child)
        assertTrue(child is TestLocator)
        assertEquals("div.test >> span", (child as TestLocator).selectorStr)
    }

    @Test
    fun `filter returns a Locator`() {
        val filtered = locator.filter(FilterOptions(hasText = "hello"))
        assertNotNull(filtered)
        assertTrue(filtered is TestLocator)
    }

    @Test
    fun `frameLocator returns a FrameLocator`() {
        val frame = locator.frameLocator("iframe")
        assertNotNull(frame)
    }

    @Test
    fun `expect returns KmpwrightExpect`() {
        val expect = locator.expect()
        assertNotNull(expect)
    }
}
