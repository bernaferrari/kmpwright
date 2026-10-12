package com.bernaferrari.kmpwright.core

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select

/** Caller-owned work terminates when its page closes, even if a native callback never arrives. */
internal suspend fun <T> awaitPageOperation(closed: Deferred<Unit>?, block: suspend () -> T): T {
    if (closed == null) return block()
    if (closed.isCompleted) throw KmpwrightException("Page is closed")
    return coroutineScope {
        val operation = async(start = CoroutineStart.UNDISPATCHED) { block() }
        try {
            select {
                closed.onAwait { throw KmpwrightException("Page closed during operation") }
                operation.onAwait { it }
            }
        } finally { operation.cancel() }
    }
}
