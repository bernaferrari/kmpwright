package com.bernaferrari.kmpwright.example.shared

import kotlin.test.Test
import kotlin.test.assertEquals

class CloseOnceTest {
    @Test
    fun closesOwnedResourcesOnlyOnce() {
        var closeCount = 0
        val owner = CloseOnce { closeCount++ }

        owner.close()
        owner.close()

        assertEquals(1, closeCount)
    }
}
