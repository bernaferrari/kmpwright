package com.bernaferrari.kmpwright.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilitySnapshotTest {

    @Test
    fun `parseSnapshotJson with empty null returns empty snapshot`() {
        val snapshot = AccessibilitySnapshotEngine.parseSnapshotJson("null")
        assertTrue(snapshot.nodes.isEmpty())
        assertTrue(snapshot.refMap.isEmpty())
        assertEquals("", snapshot.textTree)
    }

    @Test
    fun `parseSnapshotJson with empty string returns empty snapshot`() {
        val snapshot = AccessibilitySnapshotEngine.parseSnapshotJson("")
        assertTrue(snapshot.nodes.isEmpty())
        assertTrue(snapshot.refMap.isEmpty())
    }

    @Test
    fun `parseSnapshotJson with valid simple JSON`() {
        val json = """{"flatNodes":[{"role":"heading","name":"Title","value":null,"states":[],"children":[],"refId":null,"depth":0,"level":1}],"refMap":{}}"""
        val snapshot = AccessibilitySnapshotEngine.parseSnapshotJson(json)
        assertEquals(1, snapshot.nodes.size)
        assertEquals("heading", snapshot.nodes[0].role)
        assertEquals("Title", snapshot.nodes[0].name)
        assertEquals(1, snapshot.nodes[0].level)
        assertTrue(snapshot.refMap.isEmpty())
    }

    @Test
    fun `parseSnapshotJson with ref entries`() {
        val json = """{"flatNodes":[{"role":"button","name":"Click me","value":null,"states":[],"children":[],"refId":"@e1","depth":0,"level":null}],"refMap":{"@e1":{"refId":"@e1","role":"button","name":"Click me","cssSelector":"#my-btn"}}}"""
        val snapshot = AccessibilitySnapshotEngine.parseSnapshotJson(json)
        assertEquals(1, snapshot.nodes.size)
        assertEquals("@e1", snapshot.nodes[0].refId)
        assertEquals(1, snapshot.refMap.size)
        assertTrue(snapshot.refMap.containsKey("@e1"))
        assertEquals("#my-btn", snapshot.refMap["@e1"]!!.cssSelector)
    }

    @Test
    fun `parseSnapshotJson with nested children`() {
        val json = """{"flatNodes":[
            {"role":"list","name":"","value":null,"states":[],"children":[1,2],"refId":null,"depth":0,"level":null},
            {"role":"listitem","name":"Item 1","value":null,"states":[],"children":[],"refId":null,"depth":1,"level":null},
            {"role":"listitem","name":"Item 2","value":null,"states":[],"children":[],"refId":null,"depth":1,"level":null}
        ],"refMap":{}}"""
        val snapshot = AccessibilitySnapshotEngine.parseSnapshotJson(json)
        assertEquals(1, snapshot.nodes.size)
        assertEquals("list", snapshot.nodes[0].role)
        assertEquals(2, snapshot.nodes[0].children.size)
        assertEquals("listitem", snapshot.nodes[0].children[0].role)
        assertEquals("Item 1", snapshot.nodes[0].children[0].name)
        assertEquals("Item 2", snapshot.nodes[0].children[1].name)
    }

    @Test
    fun `parseSnapshotJson with states`() {
        val json = """{"flatNodes":[{"role":"checkbox","name":"Accept","value":null,"states":["checked","required"],"children":[],"refId":"@e1","depth":0,"level":null}],"refMap":{"@e1":{"refId":"@e1","role":"checkbox","name":"Accept","cssSelector":"#cb1"}}}"""
        val snapshot = AccessibilitySnapshotEngine.parseSnapshotJson(json)
        assertEquals(2, snapshot.nodes[0].states.size)
        assertTrue(snapshot.nodes[0].states.contains("checked"))
        assertTrue(snapshot.nodes[0].states.contains("required"))
    }

    @Test
    fun `parseSnapshotJson with input value`() {
        val json = """{"flatNodes":[{"role":"textbox","name":"Email","value":"test@example.com","states":[],"children":[],"refId":"@e1","depth":0,"level":null}],"refMap":{}}"""
        val snapshot = AccessibilitySnapshotEngine.parseSnapshotJson(json)
        assertEquals("test@example.com", snapshot.nodes[0].value)
    }

    @Test
    fun `textTree contains node info`() {
        val json = """{"flatNodes":[{"role":"button","name":"Submit","value":null,"states":["disabled"],"children":[],"refId":"@e1","depth":0,"level":null}],"refMap":{"@e1":{"refId":"@e1","role":"button","name":"Submit"}}}"""
        val snapshot = AccessibilitySnapshotEngine.parseSnapshotJson(json)
        assertTrue(snapshot.textTree.contains("button"))
        assertTrue(snapshot.textTree.contains("Submit"))
        assertTrue(snapshot.textTree.contains("disabled"))
        assertTrue(snapshot.textTree.contains("@e1"))
    }

    @Test
    fun `textTree shows heading level`() {
        val json = """{"flatNodes":[{"role":"heading","name":"Hello","value":null,"states":[],"children":[],"refId":null,"depth":0,"level":2}],"refMap":{}}"""
        val snapshot = AccessibilitySnapshotEngine.parseSnapshotJson(json)
        assertTrue(snapshot.textTree.contains("heading"))
        assertTrue(snapshot.textTree.contains("[h2]"))
    }

    @Test
    fun `parseSnapshotJson with malformed JSON returns empty snapshot`() {
        val snapshot = AccessibilitySnapshotEngine.parseSnapshotJson("not valid json {")
        assertTrue(snapshot.nodes.isEmpty())
    }

    @Test
    fun `snapshotJs generates valid JS function`() {
        val options = A11ySnapshotOptions()
        val js = AccessibilitySnapshotEngine.snapshotJs(options)
        assertNotNull(js)
        assertTrue(js.contains("function()"))
        assertTrue(js.contains("walk(root"))
        assertTrue(js.contains("JSON.stringify"))
    }

    @Test
    fun `snapshotJs respects includeHidden option`() {
        val jsHidden = AccessibilitySnapshotEngine.snapshotJs(A11ySnapshotOptions(includeHidden = true))
        assertTrue(jsHidden.contains("var includeHidden = true"))

        val jsNotHidden = AccessibilitySnapshotEngine.snapshotJs(A11ySnapshotOptions(includeHidden = false))
        assertTrue(jsNotHidden.contains("var includeHidden = false"))
    }

    @Test
    fun `snapshotJs respects maxDepth option`() {
        val jsDepth = AccessibilitySnapshotEngine.snapshotJs(A11ySnapshotOptions(maxDepth = 5))
        assertTrue(jsDepth.contains("var maxDepth = 5"))

        val jsNoDepth = AccessibilitySnapshotEngine.snapshotJs(A11ySnapshotOptions(maxDepth = null))
        assertTrue(jsNoDepth.contains("var maxDepth = null"))
    }

    @Test
    fun `snapshotJs respects interactiveOnly option`() {
        val js = AccessibilitySnapshotEngine.snapshotJs(A11ySnapshotOptions(interactiveOnly = true))
        assertTrue(js.contains("var interactiveOnly = true"))
    }

    @Test
    fun `snapshotJs falls back to visible text for semantic element names`() {
        val js = AccessibilitySnapshotEngine.snapshotJs(A11ySnapshotOptions())

        assertTrue(js.contains("el.innerText || el.textContent"))
        assertTrue(js.contains("INTERACTIVE_TAGS[tag] || INTERACTIVE_ROLES[explicitRole]"))
    }

    @Test
    fun `A11ySnapshotOptions defaults`() {
        val options = A11ySnapshotOptions()
        assertFalse(options.includeHidden)
        assertEquals(null, options.maxDepth)
        assertFalse(options.interactiveOnly)
        assertFalse(options.compact)
    }

    @Test
    fun `A11yNode data class holds all fields`() {
        val node = A11yNode(
            role = "button",
            name = "Click",
            value = null,
            states = setOf("disabled"),
            children = emptyList(),
            refId = "@e1",
            depth = 0,
            level = null
        )
        assertEquals("button", node.role)
        assertEquals("Click", node.name)
        assertEquals("@e1", node.refId)
        assertTrue(node.states.contains("disabled"))
    }

    @Test
    fun `RefEntry data class holds all fields`() {
        val entry = RefEntry(
            refId = "@e1",
            role = "link",
            name = "Home",
            cssSelector = "nav > a:nth-of-type(1)",
            nth = 0
        )
        assertEquals("@e1", entry.refId)
        assertEquals("link", entry.role)
        assertEquals("nav > a:nth-of-type(1)", entry.cssSelector)
    }

    @Test
    fun `snapshotJs produces balanced JS structure`() {
        val js = AccessibilitySnapshotEngine.snapshotJs(A11ySnapshotOptions())
        var braceCount = 0
        var parenCount = 0
        for (c in js) {
            when (c) {
                '{' -> braceCount++
                '}' -> braceCount--
                '(' -> parenCount++
                ')' -> parenCount--
            }
        }
        assertEquals("Braces should be balanced", 0, braceCount)
        assertEquals("Parentheses should be balanced", 0, parenCount)
    }

    @Test
    fun `extracted JS fragments contain expected functions`() {
        val js = AccessibilitySnapshotEngine.snapshotJs(A11ySnapshotOptions())
        assertTrue(js.contains("function getRole(el)"))
        assertTrue(js.contains("function getName(el)"))
        assertTrue(js.contains("function getStates(el)"))
        assertTrue(js.contains("function getValue(el)"))
        assertTrue(js.contains("function getLevel(el)"))
        assertTrue(js.contains("function isInteractive(el)"))
        assertTrue(js.contains("function isVisible(el)"))
        assertTrue(js.contains("function generateSelector(el)"))
        assertTrue(js.contains("function walk(el, depth)"))
    }
}
