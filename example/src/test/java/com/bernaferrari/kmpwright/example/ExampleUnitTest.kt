package com.bernaferrari.kmpwright.example

import org.junit.Test
import org.junit.Assert.*

class ExampleUnitTest {

    @Test
    fun addition_isCorrect() {
        assertEquals(4, 2 + 2)
    }

    @Test
    fun urlInput_defaultValue() {
        val urlInput = "https://example.com"
        assertTrue(urlInput.startsWith("https://"))
    }
}
