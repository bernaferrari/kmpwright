package com.bernaferrari.kmpwright.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AndroidDocumentScreenshotTest {
    @Test fun documentTileAcceptsSinglePixelRoundingAtTheTrailingEdge() {
        assertEquals(199 until 200, documentTileSlice(599, 400, 200, 1)?.source)
        assertEquals(199 until 200, documentTileSlice(600, 400, 200, 1)?.source)
        assertEquals(null, documentTileSlice(601, 400, 200, 1))
        assertEquals(null, documentTileSlice(600, 400, 200, 2))
    }
    @Test fun fullPageAndVisibleClipCannotBothBeSelected() {
        assertFailsWith<IllegalArgumentException> {
            ScreenshotOptions(fullPage = true, clip = Rectangle(0.0, 0.0, 100.0, 100.0))
        }
    }

    @Test fun shiftedScrollUsesTheActualViewportOrigin() {
        val final = documentTileSlice(500, 399, 200, 100)
        assertEquals(101 until 200, final?.source)
        assertEquals(99, final?.count)
        assertEquals(null, documentTileSlice(400, 402, 200, 100))
    }

    @Test fun fractionalCssClipAtHighDensityStaysWithinDocumentScrollExtent() {
        val clip = Rectangle(0.8, 0.8, 400.0, 1000.0)
        val plan = planDocumentCapture(DocumentScreenshotOptions(clip), 400.0, 1000.0)
        val bounds = documentNativeBounds(plan.rect, 400.0, 1000.0, viewportWidth = 1200, viewportHeight = 600, nativePerCss = 3.0)
        assertEquals(2, bounds.originY)
        assertEquals(2998, bounds.height)
        assertEquals(2400, bounds.lastTop)
        // Even if Chromium clamps scrollY down by 1px at the end, the final tile slices cleanly.
        val slice = documentTileSlice(bounds.originY + bounds.height - 1, bounds.lastTop - 1, viewportSize = 600, remaining = 1)
        assertEquals(599 until 600, slice?.source)
    }
}
