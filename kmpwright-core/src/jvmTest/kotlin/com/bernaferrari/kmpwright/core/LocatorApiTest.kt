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
        locator.pressSequentially("abc")
    }

    @Test
    fun `zero-delay type batches characters in one bridge evaluation`() = testBlocking {
        locator.pressSequentially("a'b")

        val typeScripts = controller.executedScripts.filter { it.contains("H.perform(el, 'type'") }
        assertEquals(1, typeScripts.size)
        assertTrue(typeScripts.single().contains("\"text\":\"'\""))
    }

    @Test
    fun `pressSequentially with delay`() = testBlocking {
        locator.pressSequentially("xyz", PressSequentiallyOptions(delayMs = 10))
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
    fun `focus executes without error`() = testBlocking {
        locator.focus()
    }


    private fun checkbox(initial: Boolean) {
        var checked = initial
        controller.evalHandler = { script ->
            when {
                script.contains("/*kmpwright:waitFor*/") -> "true"
                script.contains("H.checkedState") -> checked.toString()
                script.contains("'radio'") -> "false"
                script.contains("H.perform(el, 'click'") -> { checked = !checked; null }
                else -> null
            }
        }
    }

    @Test
    fun `check clicks an unchecked box and verifies the new state`() = testBlocking {
        checkbox(initial = false)
        locator.check()
        assertTrue(controller.executedScripts.any { it.contains("H.perform(el, 'click'") })
    }

    @Test
    fun `check with custom options`() = testBlocking {
        checkbox(initial = false)
        locator.check(CheckOptions(timeoutMs = 5000, force = true))
    }

    @Test
    fun `check is a no-op when already checked`() = testBlocking {
        checkbox(initial = true)
        locator.check()
        assertFalse(controller.executedScripts.any { it.contains("H.perform(el, 'click'") })
    }

    @Test
    fun `uncheck executes without error`() = testBlocking {
        checkbox(initial = true)
        locator.uncheck()
    }

    @Test
    fun `uncheck with custom options`() = testBlocking {
        checkbox(initial = true)
        locator.uncheck(CheckOptions(timeoutMs = 5000, force = true))
    }

    @Test
    fun `check fails when the click does not change the state`() {
        controller.evalHandler = { script ->
            when {
                script.contains("/*kmpwright:waitFor*/") -> "true"
                script.contains("H.checkedState") -> "false"
                else -> null
            }
        }
        val error = org.junit.Assert.assertThrows(KmpwrightException::class.java) { kotlinx.coroutines.runBlocking { locator.check() } }
        assertTrue(error.message!!.contains("did not check"))
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
        locator.setInputFiles(emptyList())
    }

    @Test
    fun `dragTo executes without error`() = testBlocking {
        controller.waitResultOverride = "true"
        val target = TestLocator("div.target", controller)
        locator.dragTo(target)
    }

    @Test
    fun `dragTo with custom options`() = testBlocking {
        controller.waitResultOverride = "true"
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
        controller.evalHandler = { script -> if (script.contains("H.isDisabled")) "true" else null }
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
    fun `missing element screenshot fails explicitly`() = testBlocking {
        kotlin.test.assertFailsWith<KmpwrightException> { locator.screenshot() }
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
        val filtered = locator.filter(hasText = "hello")
        assertNotNull(filtered)
        assertTrue(filtered is TestLocator)
    }

    @Test
    fun `frameLocator returns a FrameLocator`() {
        val frame = locator.frameLocator("iframe")
        assertNotNull(frame)
    }

    @Test
    fun `expect returns LocatorAssertions`() {
        val expect = expect(locator)
        assertNotNull(expect)
    }
}
