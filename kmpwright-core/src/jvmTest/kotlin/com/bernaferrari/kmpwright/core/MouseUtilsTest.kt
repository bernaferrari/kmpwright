package com.bernaferrari.kmpwright.core

import org.junit.Assert.assertEquals
import org.junit.Test

class MouseUtilsTest {

    @Test
    fun `mouseButtonCode maps buttons to dom button numbers`() {
        assertEquals(0, mouseButtonCode(MouseButton.LEFT))
        assertEquals(1, mouseButtonCode(MouseButton.MIDDLE))
        assertEquals(2, mouseButtonCode(MouseButton.RIGHT))
    }

    @Test
    fun `mouseButtonsBitfield matches dom buttons mask`() {
        assertEquals(0, mouseButtonsBitfield(emptySet()))
        assertEquals(1, mouseButtonsBitfield(setOf(MouseButton.LEFT)))
        assertEquals(2, mouseButtonsBitfield(setOf(MouseButton.RIGHT)))
        assertEquals(4, mouseButtonsBitfield(setOf(MouseButton.MIDDLE)))
        assertEquals(3, mouseButtonsBitfield(setOf(MouseButton.LEFT, MouseButton.RIGHT)))
        assertEquals(7, mouseButtonsBitfield(setOf(MouseButton.LEFT, MouseButton.MIDDLE, MouseButton.RIGHT)))
    }
}
