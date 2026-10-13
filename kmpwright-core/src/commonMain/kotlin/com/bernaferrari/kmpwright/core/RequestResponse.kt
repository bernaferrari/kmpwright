package com.bernaferrari.kmpwright.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.coroutines.cancellation.CancellationException

/**
 * A network request issued by the page, like Playwright's `Request`.
 *
 * [resourceType] is best effort and backend dependent: CDP reports Chromium's type in lower case
 * (`document`, `script`, `fetch`, `xhr`, `image`...); Android WebView reports `document` for the
 * main-frame document and `other` otherwise; iOS reports `fetch`/`xhr` for requests seen by the
 * page's fetch/XHR shim, `document` for intercepted main documents, and `other` otherwise.
 */
@Serializable
public data class Request(
    val url: String,
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap(),
    val postData: String? = null,
    val resourceType: String = "other",
    val timestamp: Long = currentTimeMillis(),
    val id: String = generateRequestId()
)

/**
 * The request body parsed as JSON (Playwright `request.postDataJSON()`): null without a body, a
 * JSON object of string values for `application/x-www-form-urlencoded` bodies, otherwise the parsed
 * JSON. @throws KmpwrightException when the body is not valid JSON.
 */
@Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class)
public fun Request.postDataJSON(): JsonElement? {
    val data = postData ?: return null
    val contentType = headers.entries.firstOrNull { it.key.equals("content-type", ignoreCase = true) }?.value.orEmpty()
    if (contentType.substringBefore(';').trim().equals("application/x-www-form-urlencoded", ignoreCase = true)) {
        return JsonObject(data.split('&').filter { it.isNotEmpty() }.associate { pair ->
            decodeFormComponent(pair.substringBefore('=')) to JsonPrimitive(decodeFormComponent(pair.substringAfter('=', "")))
        })
    }
    return try {
        Json.parseToJsonElement(data)
    } catch (error: Exception) {
        throw KmpwrightException("POST data is not a valid JSON object: $data", error)
    }
}

private fun decodeFormComponent(value: String): String {
    val bytes = ArrayList<Byte>(value.length)
    var index = 0
    while (index < value.length) {
        val char = value[index]
        when {
            char == '+' -> { bytes += ' '.code.toByte(); index++ }
            char == '%' && index + 2 < value.length -> {
                val hex = value.substring(index + 1, index + 3).toIntOrNull(16)
                if (hex == null) { bytes += '%'.code.toByte(); index++ } else { bytes += hex.toByte(); index += 3 }
            }
            else -> { char.toString().encodeToByteArray().forEach { bytes += it }; index++ }
        }
    }
    return bytes.toByteArray().decodeToString()
}

/**
 * A network response, like Playwright's `Response`; [ok] is true for 2xx statuses. [id] is the id
 * of the [Request] it answers. Read the body with [body]/[text]/[json] (or
 * [NetworkHistory.responseBody]).
 */
@Serializable
public data class Response(
    val url: String,
    val status: Int,
    val statusText: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val fromCache: Boolean = false,
    val timestamp: Long = currentTimeMillis(),
    val id: String = generateRequestId()
) {
    val ok: Boolean get() = status in 200..299

    /** Reads this response's body through the page that observed it; null for detached copies. */
    @Transient
    internal var bodyReader: (suspend (maxBytes: Int) -> NetworkResponseBody)? = null
}

/**
 * The response body bytes (Playwright `response.body()`), read through the page's
 * [NetworkHistory.responseBody]. Only the CDP backend can read bodies of page-loaded responses.
 * @throws KmpwrightException when the body is unavailable (detached/deserialized [Response], no
 * longer retained, or a redirect hop).
 * @throws KmpwrightUnsupportedException on backends that cannot read response bodies.
 */
@Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
public suspend fun Response.body(maxBytes: Int = 4 * 1024 * 1024): ByteArray {
    val reader = bodyReader ?: throw KmpwrightException(
        "Response body is unavailable: this Response was not delivered by a page (or was copied or deserialized)"
    )
    val body = reader(maxBytes)
    return if (body.base64Encoded) kotlin.io.encoding.Base64.decode(body.content) else body.content.encodeToByteArray()
}

/** The response body decoded as UTF-8 (Playwright `response.text()`). */
@Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
public suspend fun Response.text(maxBytes: Int = 4 * 1024 * 1024): String = body(maxBytes).decodeToString()

/** The response body parsed as JSON (Playwright `response.json()`). */
@Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
public suspend fun Response.json(maxBytes: Int = 4 * 1024 * 1024): JsonElement = Json.parseToJsonElement(text(maxBytes))

private fun generateRequestId(): String {
    return "${currentTimeMillis()}-${kotlin.random.Random.nextInt(0, Int.MAX_VALUE)}"
}
