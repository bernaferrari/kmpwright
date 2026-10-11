package com.bernaferrari.kmpwright.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotDiffTest {

    @Test
    fun `identical strings return no diff`() {
        val text = "button \"Submit\" @e1\nheading \"Title\" [h1]\nlink \"Home\" @e2"
        val result = AccessibilitySnapshotEngine.diffSnapshots(text, text)
        assertFalse(result.changed)
        assertEquals("", result.unifiedDiff)
        assertEquals(0, result.additions)
        assertEquals(0, result.removals)
        assertEquals(3, result.unchanged)
    }

    @Test
    fun `empty strings return no diff`() {
        val result = AccessibilitySnapshotEngine.diffSnapshots("", "")
        assertFalse(result.changed)
        assertEquals("", result.unifiedDiff)
    }

    @Test
    fun `addition detected`() {
        val before = "button \"Submit\" @e1"
        val after = "button \"Submit\" @e1\nlink \"Home\" @e2"
        val result = AccessibilitySnapshotEngine.diffSnapshots(before, after)
        assertTrue(result.changed)
        assertEquals(1, result.additions)
        assertEquals(0, result.removals)
        assertTrue(result.unifiedDiff.contains("+link \"Home\" @e2"))
    }

    @Test
    fun `removal detected`() {
        val before = "button \"Submit\" @e1\nlink \"Home\" @e2"
        val after = "button \"Submit\" @e1"
        val result = AccessibilitySnapshotEngine.diffSnapshots(before, after)
        assertTrue(result.changed)
        assertEquals(0, result.additions)
        assertEquals(1, result.removals)
        assertTrue(result.unifiedDiff.contains("-link \"Home\" @e2"))
    }

    @Test
    fun `modification detected as removal and addition`() {
        val before = "button \"Submit\" @e1"
        val after = "button \"Cancel\" @e1"
        val result = AccessibilitySnapshotEngine.diffSnapshots(before, after)
        assertTrue(result.changed)
        assertEquals(1, result.additions)
        assertEquals(1, result.removals)
        assertTrue(result.unifiedDiff.contains("-button \"Submit\" @e1"))
        assertTrue(result.unifiedDiff.contains("+button \"Cancel\" @e1"))
    }

    @Test
    fun `multi-line diff with interleaved changes`() {
        val before = """
            heading "Main" [h1]
            textbox "Name" @e1
            button "Submit" @e2
            link "Cancel"
        """.trimIndent()
        val after = """
            heading "Main" [h1]
            textbox "Email" @e1
            button "Submit" @e2
            link "Help"
            button "Reset" @e3
        """.trimIndent()
        val result = AccessibilitySnapshotEngine.diffSnapshots(before, after)
        assertTrue(result.changed)
        // heading and button lines are unchanged
        assertTrue(result.unchanged >= 1)
        assertTrue(result.additions >= 1) // At least Help or Reset
        assertTrue(result.removals >= 1) // At least Cancel
    }

    @Test
    fun `completely different strings`() {
        val before = "aaa\nbbb\nccc"
        val after = "xxx\nyyy\nzzz"
        val result = AccessibilitySnapshotEngine.diffSnapshots(before, after)
        assertTrue(result.changed)
        assertEquals(0, result.unchanged)
        assertEquals(3, result.additions)
        assertEquals(3, result.removals)
    }

    @Test
    fun `diff preserves unchanged lines`() {
        val before = "A\nB\nC\nD"
        val after = "A\nX\nC\nD"
        val result = AccessibilitySnapshotEngine.diffSnapshots(before, after)
        assertTrue(result.changed)
        assertTrue(result.unchanged >= 2) // A, C, D could all be unchanged
    }

    @Test
    fun `large repeated reordered lines keep ordered counts`() {
        val beforeLines = (0 until 600).map { if (it % 2 == 0) "row A" else "row B" }
        val afterLines = (0 until 600).map { if (it % 2 == 0) "row B" else "row A" }

        val result = AccessibilitySnapshotEngine.diffSnapshots(
            beforeLines.joinToString("\n"),
            afterLines.joinToString("\n")
        )

        assertTrue(result.changed)
        assertEquals(1, result.additions)
        assertEquals(1, result.removals)
        assertEquals(599, result.unchanged)
        assertTrue(result.unifiedDiff.startsWith("+row B\n row A"))
        assertTrue(result.unifiedDiff.endsWith(" row A\n-row B"))
    }

    @Test
    fun `large repeated insertion keeps one-for-one counts`() {
        val beforeLines = List(5_000) { "row" }
        val afterLines = buildList {
            repeat(2_500) { add("row") }
            add("inserted")
            repeat(2_499) { add("row") }
        }

        val result = AccessibilitySnapshotEngine.diffSnapshots(
            beforeLines.joinToString("\n"),
            afterLines.joinToString("\n")
        )

        assertTrue(result.changed)
        assertEquals(1, result.additions)
        assertEquals(1, result.removals)
        assertEquals(4_999, result.unchanged)
        assertTrue(result.unifiedDiff.contains("+inserted"))
    }
}
