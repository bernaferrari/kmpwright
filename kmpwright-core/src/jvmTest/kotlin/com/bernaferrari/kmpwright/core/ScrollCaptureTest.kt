package com.bernaferrari.kmpwright.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScrollCaptureTest {
    @Test fun scrollDuringFrameAcquisitionCannotReturnAnImageWithStaleCropCoordinates() = runTest {
        var scroll = 0 to 100
        var discarded = false
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val result = async {
            runCatching {
                captureAtScroll(0 to 100, { scroll }, {
                    started.complete(Unit)
                    release.await()
                    "frame"
                }, { discarded = true })
            }
        }
        started.await()
        scroll = 0 to 200
        release.complete(Unit)
        assertIs<KmpwrightException>(result.await().exceptionOrNull())
        assertTrue(discarded)
    }
    @Test fun aStableFrameIsReturnedWithoutBeingDiscarded() = runTest {
        var discarded = false
        assertEquals("frame", captureAtScroll(0 to 100, { 0 to 100 }, { "frame" }, { discarded = true }))
        assertFalse(discarded)
    }
}
