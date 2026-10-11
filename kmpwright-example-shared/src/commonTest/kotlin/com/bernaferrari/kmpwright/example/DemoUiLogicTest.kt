package com.bernaferrari.kmpwright.example.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DemoUiLogicTest {
    @Test
    fun coordinateMovesUseTenPixelStepsAndStayInsideThePad() {
        assertEquals(
            140f to 200f,
            moveCoordinate(150f, 200f, 360f, 720f, CoordinateMove.Left)
        )
        assertEquals(
            360f to 720f,
            moveCoordinate(358f, 719f, 360f, 720f, CoordinateMove.Down).let { (x, y) ->
                moveCoordinate(x, y, 360f, 720f, CoordinateMove.Right)
            }
        )
        assertEquals(
            0f to 0f,
            moveCoordinate(2f, 3f, 360f, 720f, CoordinateMove.Up).let { (x, y) ->
                moveCoordinate(x, y, 360f, 720f, CoordinateMove.Left)
            }
        )
    }

    @Test
    fun compactDockKeepsTheCurrentSectionSelectedAndTogglesItsSheet() {
        val openCurrent = selectCompactDockSection(2, 2, previewOpen = false)
        assertEquals(2, openCurrent.section)
        assertTrue(openCurrent.previewOpen)

        val closeCurrent = selectCompactDockSection(2, 2, previewOpen = true)
        assertFalse(closeCurrent.previewOpen)

        val switchSection = selectCompactDockSection(2, 3, previewOpen = true)
        assertEquals(3, switchSection.section)
        assertTrue(switchSection.previewOpen)
    }

    @Test
    fun navigationFailureExplainsWhatToTryAndPreservesTechnicalDetail() {
        val message = explainNavigationFailure(
            action = "open https://example.invalid",
            suggestion = "Check the address and network connection, then try again.",
            error = IllegalStateException("Host was not found")
        )

        assertTrue(message.startsWith("Couldn’t open https://example.invalid."))
        assertTrue(message.contains("Check the address and network connection"))
        assertTrue(message.endsWith("Details: Host was not found"))
    }
}
