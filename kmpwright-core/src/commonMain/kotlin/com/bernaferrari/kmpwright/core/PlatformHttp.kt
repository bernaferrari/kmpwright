package com.bernaferrari.kmpwright.core

internal expect suspend fun platformHttpGetText(
    url: String,
    headers: Map<String, String> = emptyMap()
): String
