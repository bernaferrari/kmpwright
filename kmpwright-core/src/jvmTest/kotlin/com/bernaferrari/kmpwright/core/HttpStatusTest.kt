package com.bernaferrari.kmpwright.core

import org.junit.Assert.assertEquals
import org.junit.Test

class HttpStatusTest {

    @Test
    fun `known status codes use standard reason phrases`() {
        assertEquals("OK", defaultHttpStatusText(200))
        assertEquals("Not Found", defaultHttpStatusText(404))
        assertEquals("Internal Server Error", defaultHttpStatusText(500))
    }

    @Test
    fun `unknown status codes fall back to numeric label`() {
        assertEquals("HTTP 799", defaultHttpStatusText(799))
    }
}
