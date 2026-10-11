package com.bernaferrari.kmpwright.core

internal data class BackingStoreSelection<T>(
    val store: T,
    val owned: Boolean
)

internal fun <T> selectBackingStore(
    embeddedStore: T?,
    providedStore: T?,
    createStore: () -> T
): BackingStoreSelection<T> {
    return when {
        embeddedStore != null -> BackingStoreSelection(embeddedStore, owned = false)
        providedStore != null -> BackingStoreSelection(providedStore, owned = false)
        else -> BackingStoreSelection(createStore(), owned = true)
    }
}
