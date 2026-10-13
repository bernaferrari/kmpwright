package com.bernaferrari.kmpwright.core

import org.junit.Assert.assertEquals
import org.junit.Test

class MouseUtilsTest {

    @Test
    fun `mouseButtonCode maps buttons to dom button numbers`() {
        assertEquals(0, mouseButtonCode(MouseButton.Left))
        assertEquals(1, mouseButtonCode(MouseButton.Middle))
        assertEquals(2, mouseButtonCode(MouseButton.Right))
    }

    @Test
    fun `mouseButtonsBitfield matches dom buttons mask`() {
        assertEquals(0, mouseButtonsBitfield(emptySet()))
        assertEquals(1, mouseButtonsBitfield(setOf(MouseButton.Left)))
        assertEquals(2, mouseButtonsBitfield(setOf(MouseButton.Right)))
        assertEquals(4, mouseButtonsBitfield(setOf(MouseButton.Middle)))
        assertEquals(3, mouseButtonsBitfield(setOf(MouseButton.Left, MouseButton.Right)))
        assertEquals(7, mouseButtonsBitfield(setOf(MouseButton.Left, MouseButton.Middle, MouseButton.Right)))
    }
}
