package com.bernaferrari.kmpwright.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class StateAutosaveTest {
    @Test fun `autosave writes immediately periodically and stops without a final write`() = runTest {
        var saves = 0
        val autosave = StorageStateAutosave(this, 1_000) { saves++ }.start()
        assertEquals(1, saves)
        runCurrent()
        advanceTimeBy(1_000); runCurrent()
        assertEquals(2, saves)
        assertNotNull(autosave.lastSavedAt.value)
        autosave.stop()
        advanceTimeBy(10_000); runCurrent()
        assertEquals(2, saves)
        assertFalse(autosave.isActive)
    }
    @Test fun `a periodic write error is observable and stops later writes`() = runTest {
        var saves = 0
        val autosave = StorageStateAutosave(this, 1_000) { if (++saves > 1) error("disk unavailable") }.start()
        runCurrent(); advanceTimeBy(1_000); runCurrent()
        assertEquals("disk unavailable", autosave.error.value)
        assertFalse(autosave.isActive)
        advanceTimeBy(10_000); runCurrent()
        assertEquals(2, saves)
    }
    @Test fun `initial write failure does not leave a lazy job behind`() = runTest {
        assertFailsWith<IllegalStateException> { StorageStateAutosave(this, 1_000) { error("first write") }.start() }
    }
}
