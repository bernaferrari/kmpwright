package com.bernaferrari.kmpwright.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KeyboardSemanticsTest {

    @Test
    fun `plain printable keys insert their own text`() {
        val descriptor = describeSyntheticKeyPress("a", emptySet())

        assertEquals("a", descriptor.key)
        assertEquals("KeyA", descriptor.code)
        assertEquals(65, descriptor.keyCode)
        assertEquals("a", descriptor.text)
    }

    @Test
    fun `shift modifies printable key display and inserted text`() {
        val letter = describeSyntheticKeyPress("a", setOf(KeyboardModifiers.SHIFT))
        val digit = describeSyntheticKeyPress("1", setOf(KeyboardModifiers.SHIFT))

        assertEquals("A", letter.key)
        assertEquals("A", letter.text)
        assertEquals("KeyA", letter.code)
        assertEquals(65, letter.keyCode)

        assertEquals("!", digit.key)
        assertEquals("!", digit.text)
        assertEquals("Digit1", digit.code)
        assertEquals(49, digit.keyCode)
    }

    @Test
    fun `control and meta shortcuts do not synthesize text insertion`() {
        val control = describeSyntheticKeyPress("a", setOf(KeyboardModifiers.CONTROL))
        val metaShift = describeSyntheticKeyPress("a", setOf(KeyboardModifiers.META, KeyboardModifiers.SHIFT))

        assertEquals("a", control.key)
        assertNull(control.text)

        assertEquals("A", metaShift.key)
        assertNull(metaShift.text)
        assertEquals("KeyA", metaShift.code)
    }

    @Test
    fun `named keys preserve dom key metadata`() {
        val enter = describeSyntheticKeyPress("Enter", emptySet())
        val shift = describeSyntheticKeyPress("shift", emptySet())

        assertEquals("Enter", enter.key)
        assertEquals("Enter", enter.code)
        assertEquals(13, enter.keyCode)
        assertNull(enter.text)

        assertEquals(KeyboardModifiers.SHIFT, shift.key)
        assertEquals("ShiftLeft", shift.code)
        assertEquals(16, shift.keyCode)
    }

    @Test
    fun `unicode text units preserve surrogate pairs`() {
        assertEquals(listOf("A", "🙂", "B"), "A🙂B".toUnicodeTextUnits())
    }
}
