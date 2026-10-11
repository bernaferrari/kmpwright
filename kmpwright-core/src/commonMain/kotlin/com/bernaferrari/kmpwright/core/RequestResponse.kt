package com.bernaferrari.kmpwright.core

import kotlinx.serialization.Serializable

@Serializable
data class Request(
    val url: String,
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap(),
    val postData: String? = null,
    val postDataJSON: String? = null,
    val resourceType: String = "document",
    val timestamp: Long = currentTimeMillis(),
    val id: String = generateRequestId()
)

@Serializable
data class Response(
    val url: String,
    val status: Int,
    val statusText: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
    val fromCache: Boolean = false,
    val timestamp: Long = currentTimeMillis(),
    val id: String = generateRequestId()
) {
    val ok: Boolean get() = status in 200..299
}

private fun generateRequestId(): String {
    return "${currentTimeMillis()}-${kotlin.random.Random.nextInt(0, Int.MAX_VALUE)}"
}
