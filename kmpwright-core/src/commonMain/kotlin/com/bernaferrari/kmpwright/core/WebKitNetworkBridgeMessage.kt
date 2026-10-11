package com.bernaferrari.kmpwright.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Bounds applied before a page message can reach the native network router. */
internal data class WebKitNetworkBridgeLimits(
    val maxPayloadChars: Int = 128 * 1024,
    val maxStringChars: Int = 16 * 1024,
    val maxBodyChars: Int = 64 * 1024,
    val maxHeaders: Int = 64,
    val maxHeaderNameChars: Int = 256,
    val maxHeaderValueChars: Int = 8 * 1024,
    val maxInFlightRequests: Int = 256
)

internal const val WEBKIT_NETWORK_BRIDGE_MAX_MESSAGES_PER_SECOND: Int = 512

internal data class WebKitNetworkBridgeFrameMetadata(
    val isMainFrame: Boolean,
    val isExpectedWebView: Boolean,
    val frameOrigin: String?,
    val expectedOrigin: String?
)

internal fun isTrustedWebKitNetworkBridgeFrame(metadata: WebKitNetworkBridgeFrameMetadata): Boolean =
    metadata.isMainFrame && metadata.isExpectedWebView &&
        metadata.frameOrigin != null && metadata.frameOrigin == metadata.expectedOrigin

internal class WebKitNetworkBridgeRateLimiter(
    private val maxMessagesPerWindow: Int = WEBKIT_NETWORK_BRIDGE_MAX_MESSAGES_PER_SECOND,
    private val windowMillis: Long = 1000L
) {
    private var windowStartMillis = Long.MIN_VALUE
    private var acceptedInWindow = 0

    fun allow(nowMillis: Long): Boolean {
        if (windowStartMillis == Long.MIN_VALUE || nowMillis - windowStartMillis >= windowMillis) {
            windowStartMillis = nowMillis
            acceptedInWindow = 0
        }
        if (acceptedInWindow >= maxMessagesPerWindow) return false
        acceptedInWindow += 1
        return true
    }
}

internal sealed interface WebKitNetworkBridgeMessage {
    data class RequestStarted(
        val kind: String,
        val id: String,
        val url: String,
        val method: String,
        val headers: Map<String, String>,
        val body: String?
    ) : WebKitNetworkBridgeMessage

    data class ResponseReceived(
        val id: String,
        val url: String,
        val status: Int,
        val statusText: String?,
        val headers: Map<String, String>
    ) : WebKitNetworkBridgeMessage

    data class RequestFailed(
        val id: String,
        val url: String,
        val error: String
    ) : WebKitNetworkBridgeMessage
}

internal fun parseWebKitNetworkBridgeMessage(
    json: String,
    expectedNonce: String? = null,
    limits: WebKitNetworkBridgeLimits = WebKitNetworkBridgeLimits()
): WebKitNetworkBridgeMessage? {
    if (json.length > limits.maxPayloadChars) return null
    val element = try {
        Json.parseToJsonElement(json).jsonObject
    } catch (_: Exception) {
        return null
    }
    if (expectedNonce != null && element.string("nonce", limits.maxStringChars) != expectedNonce) return null
    val type = element.string("type", limits.maxStringChars) ?: return null
    return when (type) {
        "fetch", "xhr" -> {
            val id = element.string("id", limits.maxStringChars) ?: return null
            val url = element.string("url", limits.maxStringChars) ?: return null
            val method = element.string("method", limits.maxStringChars)
                ?: if (element.containsKey("method")) return null else "GET"
            if (id.isEmpty() || url.isEmpty() || method.isEmpty() || !isHttpMethod(method)) return null
            val headers = element.stringMap("headers", limits) ?: return null
            val body = element.string("body", limits.maxBodyChars)
            if (element.containsKey("body") && element["body"] !is JsonNull && body == null) return null
            WebKitNetworkBridgeMessage.RequestStarted(type, id, url, method, headers, body)
        }

        "response" -> {
            val id = element.string("id", limits.maxStringChars) ?: return null
            val status = element.number("status", limits.maxStringChars)?.toIntOrNull() ?: return null
            if (id.isEmpty() || status !in 100..599) return null
            val url = element.string("url", limits.maxStringChars)
                ?: if (element.containsKey("url")) return null else ""
            val statusText = element.string("statusText", limits.maxStringChars)
            if (element.containsKey("statusText") && statusText == null) return null
            val headers = element.stringMap("headers", limits) ?: return null
            WebKitNetworkBridgeMessage.ResponseReceived(id, url, status, statusText, headers)
        }

        "requestFailed" -> {
            val id = element.string("id", limits.maxStringChars) ?: return null
            val error = element.string("error", limits.maxStringChars) ?: return null
            if (id.isEmpty() || error.isEmpty()) return null
            val url = element.string("url", limits.maxStringChars)
                ?: if (element.containsKey("url")) return null else ""
            WebKitNetworkBridgeMessage.RequestFailed(
                id,
                url,
                error
            )
        }

        else -> null
    }
}

private fun JsonObject.string(name: String, maxChars: Int): String? {
    val value = this[name] ?: return null
    val primitive = value as? JsonPrimitive ?: return null
    if (!primitive.isString) return null
    return primitive.content.takeIf { it.length <= maxChars }
}

private fun JsonObject.number(name: String, maxChars: Int): String? {
    val value = this[name] as? JsonPrimitive ?: return null
    if (value.isString || value.content.length > maxChars) return null
    return value.content
}

private fun JsonObject.stringMap(name: String, limits: WebKitNetworkBridgeLimits): Map<String, String>? {
    val value = this[name] ?: return emptyMap()
    val jsonObject = value as? JsonObject ?: return null
    if (jsonObject.size > limits.maxHeaders) return null
    val result = LinkedHashMap<String, String>(jsonObject.size)
    for ((key, rawValue) in jsonObject) {
        if (key.length > limits.maxHeaderNameChars) return null
        val primitive = rawValue as? JsonPrimitive ?: return null
        val content = primitive.content
        if (!primitive.isString || content.length > limits.maxHeaderValueChars) return null
        result[key] = content
    }
    return result
}

private fun isHttpMethod(method: String): Boolean =
    method.length <= 32 && method.all { it in 'A'..'Z' || it in 'a'..'z' || it == '-' }
