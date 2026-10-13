package com.bernaferrari.kmpwright.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DocumentScreenshotTest {
    @Test fun wideCssLayoutsConvertToNativePoints() {
        val rect = cssRectangleToNative(Rectangle(700.0, 100.0, 160.0, 120.0), 1200.0, 390.0)
        assertEquals(227.5, rect.x, 0.0001)
        assertEquals(52.0, rect.width, 0.0001)
        assertEquals(39.0, rect.height, 0.0001)
    }
    @Test fun selectedRegionBelowTheFullPageBudgetIsNotClamped() {
        val clip = Rectangle(0.0, 6000.0, 100.0, 1800.0)
        val plan = planDocumentCapture(DocumentScreenshotOptions(clip, 2.0, 7_000_000, 10_000), 400.0, 7800.0)
        assertEquals(clip, plan.rect)
        assertEquals(200, plan.width)
        assertEquals(3600, plan.height)
        assertFalse(plan.truncated)
    }
    @Test fun selectionIsNeverSilentlyTruncated() {
        assertFailsWith<KmpwrightException> {
            planDocumentCapture(DocumentScreenshotOptions(Rectangle(0.0, 0.0, 100.0, 9000.0), 2.0, 7_000_000, 10_000, true), 400.0, 9000.0)
        }
        assertFailsWith<IllegalArgumentException> {
            planDocumentCapture(DocumentScreenshotOptions(Rectangle(700.0, 0.0, 160.0, 120.0)), 390.0, 1000.0)
        }
    }
    @Test fun fractionalBoundsUseTheBudgetEvenWhenScrollHeightIsRounded() {
        val clip = Rectangle(0.0, 0.285, 411.428, 9000.0)
        assertFailsWith<KmpwrightException> {
            planDocumentCapture(DocumentScreenshotOptions(clip, 2.0, 7_000_000, 10_000), 412.0, 9000.0)
        }
        val plan = planDocumentCapture(DocumentScreenshotOptions(clip, 1.0), 412.0, 9000.0)
        assertEquals(clip, plan.rect)
        assertFalse(plan.truncated)
        assertFailsWith<IllegalArgumentException> {
            planDocumentCapture(DocumentScreenshotOptions(clip.copy(y = 2.0)), 412.0, 9000.0)
        }
    }
    @Test fun fullPageTruncationMustBeRequestedAndReported() {
        val options = DocumentScreenshotOptions(pixelsPerCssPixel = 2.0, maxPixels = 7_000_000, maxHeight = 10_000)
        assertFailsWith<KmpwrightException> { planDocumentCapture(options, 400.0, 18_000.0) }
        val plan = planDocumentCapture(options.copy(truncateFullPage = true), 400.0, 18_000.0)
        assertTrue(plan.truncated)
        assertEquals(800, plan.width)
        assertEquals(8750, plan.height)
        assertTrue(plan.width.toLong() * plan.height <= options.maxPixels)
    }
    @Test fun invalidAndSubpixelRegionsFailBeforeAllocating() {
        for (clip in listOf(Rectangle(-1.0, 0.0, 10.0, 10.0), Rectangle(0.0, 0.0, Double.NaN, 10.0), Rectangle(0.0, 0.0, 0.1, 10.0))) {
            assertFailsWith<IllegalArgumentException> { planDocumentCapture(DocumentScreenshotOptions(clip), 400.0, 1000.0) }
        }
    }
}
