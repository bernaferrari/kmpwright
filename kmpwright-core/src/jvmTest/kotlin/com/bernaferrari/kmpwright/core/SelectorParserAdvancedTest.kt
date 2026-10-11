package com.bernaferrari.kmpwright.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectorParserAdvancedTest {

    @Test
    fun `parse css with complex selector`() {
        val result = SelectorParser.parse("div.container > ul li.active > a[href]")
        assertEquals("div.container > ul li.active > a[href]", result.css)
        assertEquals(SelectorType.CSS, result.type)
    }

    @Test
    fun `parse css with pseudo selectors`() {
        val result = SelectorParser.parse(":first-child")
        assertEquals(":first-child", result.css)
        assertEquals(SelectorType.CSS, result.type)
    }

    @Test
    fun `parse css with attribute selector`() {
        val result = SelectorParser.parse("[data-testid='submit-btn']")
        assertEquals("[data-testid='submit-btn']", result.css)
        assertEquals(SelectorType.CSS, result.type)
    }

    @Test
    fun `parse role with underscore`() {
        val result = SelectorParser.parse("role=text_box")
        // underscore is converted to hyphen, so text_box -> text-box (not in map, falls back)
        assertTrue(result.css.contains("text-box"))
        assertEquals(SelectorType.ROLE, result.type)
    }

    @Test
    fun `parse role with mixed case`() {
        val result = SelectorParser.parse("role=Button")
        assertTrue(result.css.contains("button"))
    }

    @Test
    fun `parse unknown role falls back to attribute selector`() {
        val result = SelectorParser.parse("role=custom-widget")
        assertEquals("[role=\"custom-widget\"]", result.css)
        assertEquals(SelectorType.ROLE, result.type)
    }

    @Test
    fun `parse text with colon separator`() {
        val result = SelectorParser.parse("text:Submit")
        assertEquals("Submit", result.css)
        assertEquals(SelectorType.TEXT, result.type)
        assertEquals("Submit", result.original)
    }

    @Test
    fun `parse xpath selector`() {
        val result = SelectorParser.parse("xpath=//div[@class='item']")
        assertEquals("//div[@class='item']", result.css)
        assertEquals(SelectorType.XPATH, result.type)
    }

    @Test
    fun `parse chain with three parts`() {
        val result = SelectorParser.parse("div.container >> ul >> li.active")
        assertEquals(SelectorType.CHAIN, result.type)
        assertTrue(result.css.contains(">>"))
    }

    @Test
    fun `parse chain with two parts`() {
        val result = SelectorParser.parse("div.container >> span.text")
        assertEquals(SelectorType.CHAIN, result.type)
    }

    @Test
    fun `parse label with special characters`() {
        val result = SelectorParser.parse("label=Email Address")
        assertEquals("Email Address", result.original)
        assertEquals(SelectorType.LABEL, result.type)
    }

    @Test
    fun `parse text with empty label`() {
        val result = SelectorParser.parse("text=")
        assertEquals("", result.css)
        assertEquals(SelectorType.TEXT, result.type)
    }

    @Test
    fun `SelectorType enum values`() {
        assertEquals(6, SelectorType.entries.size)
        assertTrue(SelectorType.entries.contains(SelectorType.CSS))
        assertTrue(SelectorType.entries.contains(SelectorType.ROLE))
        assertTrue(SelectorType.entries.contains(SelectorType.LABEL))
        assertTrue(SelectorType.entries.contains(SelectorType.TEXT))
        assertTrue(SelectorType.entries.contains(SelectorType.CHAIN))
        assertTrue(SelectorType.entries.contains(SelectorType.XPATH))
    }

    @Test
    fun `Selectors constants`() {
        assertEquals("aria/", Selectors.ARIA)
        assertEquals("[data-testid=\"", Selectors.DATA_TEST_ID)
        assertEquals("\"]", Selectors.DATA_TEST_ID_END)
    }
}
