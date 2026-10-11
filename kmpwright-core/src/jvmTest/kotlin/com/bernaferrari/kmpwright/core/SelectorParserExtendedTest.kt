package com.bernaferrari.kmpwright.core

import org.junit.Assert.*
import org.junit.Test

class SelectorParserExtendedTest {

    @Test
    fun `parses alt selector`() {
        val result = SelectorParser.parse("alt=logo")
        assertEquals(SelectorType.CSS, result.type)
        assertEquals("img[alt=\"logo\"],[role=\"img\"][aria-label=\"logo\"]", result.css)
    }

    @Test
    fun `parses testid selector`() {
        val result = SelectorParser.parse("testid=submit-button")
        assertEquals(SelectorType.CSS, result.type)
        assertEquals("[data-testid=\"submit-button\"]", result.css)
    }

    @Test
    fun `parses title selector`() {
        val result = SelectorParser.parse("title=Close")
        assertEquals(SelectorType.CSS, result.type)
        assertEquals("[title=\"Close\"]", result.css)
    }

    @Test
    fun `parses role selector for list`() {
        val result = SelectorParser.parse("role=list")
        assertEquals(SelectorType.ROLE, result.type)
        assertEquals("ul,ol,dl,[role=\"list\"]", result.css)
    }

    @Test
    fun `parses role selector for listitem`() {
        val result = SelectorParser.parse("role=listitem")
        assertEquals(SelectorType.ROLE, result.type)
        assertEquals("li,[role=\"listitem\"]", result.css)
    }

    @Test
    fun `parses role selector for input`() {
        val result = SelectorParser.parse("role=input")
        assertEquals(SelectorType.ROLE, result.type)
        assertEquals("input,textarea,select,[role=\"textbox\"],[role=\"combobox\"]", result.css)
    }
}

class ParseRefExtendedTest {

    @Test
    fun `parses at-e format`() {
        assertEquals("@e1", parseRef("@e1"))
        assertEquals("@e42", parseRef("@e42"))
        assertEquals("@e0", parseRef("@e0"))
    }

    @Test
    fun `parses e-number format`() {
        assertEquals("@e1", parseRef("e1"))
        assertEquals("@e99", parseRef("e99"))
    }

    @Test
    fun `parses ref-equals format`() {
        assertEquals("@e1", parseRef("ref=e1"))
        assertEquals("@e5", parseRef("ref=e5"))
    }

    @Test
    fun `parses ref-at format`() {
        assertEquals("@e5", parseRef("ref=@e5"))
    }

    @Test
    fun `returns null for invalid inputs`() {
        assertNull(parseRef(""))
        assertNull(parseRef("abc"))
        assertNull(parseRef("e"))
        assertNull(parseRef("@e"))
        assertNull(parseRef("@eabc"))
        assertNull(parseRef("ref=abc"))
        assertNull(parseRef("ref=@abc"))
    }

    @Test
    fun `trims whitespace`() {
        assertEquals("@e1", parseRef(" @e1 "))
        assertEquals("@e5", parseRef(" ref=e5 "))
    }
}
