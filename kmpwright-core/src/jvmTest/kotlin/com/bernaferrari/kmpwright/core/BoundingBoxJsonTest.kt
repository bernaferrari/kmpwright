package com.bernaferrari.kmpwright.core

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class BoundingBoxJsonTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `parse valid JSON bounding box`() {
        val input = """{"x": 10.5, "y": 20.3, "width": 100.0, "height": 50.0}"""
        val result = json.decodeFromString<BaseLocator.BoundingBoxJson>(input)
        assertEquals(10.5, result.x, 0.001)
        assertEquals(20.3, result.y, 0.001)
        assertEquals(100.0, result.width, 0.001)
        assertEquals(50.0, result.height, 0.001)
    }

    @Test
    fun `parse valid JSON with integer values`() {
        val input = """{"x": 0, "y": 0, "width": 375, "height": 667}"""
        val result = json.decodeFromString<BaseLocator.BoundingBoxJson>(input)
        assertEquals(0.0, result.x, 0.001)
        assertEquals(0.0, result.y, 0.001)
        assertEquals(375.0, result.width, 0.001)
        assertEquals(667.0, result.height, 0.001)
    }

    @Test(expected = Exception::class)
    fun `malformed JSON throws exception`() {
        val input = """{x: 10, y: 20}"""
        json.decodeFromString<BaseLocator.BoundingBoxJson>(input)
    }

    @Test(expected = Exception::class)
    fun `invalid JSON types throws exception`() {
        val input = """{"x": "not_a_number", "y": 20, "width": 100, "height": 50}"""
        json.decodeFromString<BaseLocator.BoundingBoxJson>(input)
    }

    @Test
    fun `parse with negative coordinates`() {
        val input = """{"x": -5.0, "y": -10.0, "width": 200.0, "height": 150.0}"""
        val result = json.decodeFromString<BaseLocator.BoundingBoxJson>(input)
        assertEquals(-5.0, result.x, 0.001)
        assertEquals(-10.0, result.y, 0.001)
        assertEquals(200.0, result.width, 0.001)
        assertEquals(150.0, result.height, 0.001)
    }

    @Test
    fun `parse with extra whitespace`() {
        val input = """{  "x" :  1.0 ,  "y"  :  2.0 ,  "width"  :  3.0 ,  "height"  :  4.0  }"""
        val result = json.decodeFromString<BaseLocator.BoundingBoxJson>(input)
        assertEquals(1.0, result.x, 0.001)
        assertEquals(2.0, result.y, 0.001)
        assertEquals(3.0, result.width, 0.001)
        assertEquals(4.0, result.height, 0.001)
    }

    @Test
    fun `parse with zero dimensions`() {
        val input = """{"x": 0.0, "y": 0.0, "width": 0.0, "height": 0.0}"""
        val result = json.decodeFromString<BaseLocator.BoundingBoxJson>(input)
        assertEquals(0.0, result.x, 0.001)
        assertEquals(0.0, result.width, 0.001)
    }

    @Test
    fun `parse with scientific notation`() {
        val input = """{"x": 1e2, "y": 2.5e-1, "width": 1.0E3, "height": 5.0e+2}"""
        val result = json.decodeFromString<BaseLocator.BoundingBoxJson>(input)
        assertEquals(100.0, result.x, 0.001)
        assertEquals(0.25, result.y, 0.001)
        assertEquals(1000.0, result.width, 0.001)
        assertEquals(500.0, result.height, 0.001)
    }

    @Test
    fun `parse with extra fields ignores unknown keys`() {
        val input = """{"x": 1.0, "y": 2.0, "width": 3.0, "height": 4.0, "extra": "field"}"""
        val result = json.decodeFromString<BaseLocator.BoundingBoxJson>(input)
        assertEquals(1.0, result.x, 0.001)
        assertEquals(2.0, result.y, 0.001)
    }
}
