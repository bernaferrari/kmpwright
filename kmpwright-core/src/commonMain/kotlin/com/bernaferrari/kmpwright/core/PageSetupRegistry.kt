package com.bernaferrari.kmpwright.core

import kotlinx.atomicfu.atomic

/** Successful setup operations, ordered as applied, for tabs derived from one page. */
internal class PageSetupRegistry {
    private val actions = atomic<List<Pair<String, suspend (Page) -> Unit>>>(emptyList())

    fun remember(key: String, apply: suspend (Page) -> Unit) = update { before ->
        before.filterNot { it.first == key } + (key to apply)
    }

    fun forget(key: String) = update { before -> before.filterNot { it.first == key } }
    fun forgetPrefix(prefix: String) = update { before -> before.filterNot { it.first.startsWith(prefix) } }
    fun clear() { actions.value = emptyList() }

    suspend fun applyTo(page: Page) {
        for ((_, apply) in actions.value) apply(page)
    }

    private inline fun update(change: (List<Pair<String, suspend (Page) -> Unit>>) -> List<Pair<String, suspend (Page) -> Unit>>) {
        while (true) {
            val before = actions.value
            if (actions.compareAndSet(before, change(before))) return
        }
    }
}
