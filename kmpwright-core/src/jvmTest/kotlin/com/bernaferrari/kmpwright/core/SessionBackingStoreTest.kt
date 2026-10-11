package com.bernaferrari.kmpwright.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionBackingStoreTest {

    @Test
    fun `embedded store wins and is not owned`() {
        val selection = selectBackingStore(
            embeddedStore = "webview-store",
            providedStore = "context-store",
            createStore = { "owned-store" }
        )

        assertEquals("webview-store", selection.store)
        assertFalse(selection.owned)
    }

    @Test
    fun `provided store is reused and not owned`() {
        val selection = selectBackingStore(
            embeddedStore = null,
            providedStore = "context-store",
            createStore = { "owned-store" }
        )

        assertEquals("context-store", selection.store)
        assertFalse(selection.owned)
    }

    @Test
    fun `new store is created and owned when none provided`() {
        val selection = selectBackingStore(
            embeddedStore = null,
            providedStore = null,
            createStore = { "owned-store" }
        )

        assertEquals("owned-store", selection.store)
        assertTrue(selection.owned)
    }
}
