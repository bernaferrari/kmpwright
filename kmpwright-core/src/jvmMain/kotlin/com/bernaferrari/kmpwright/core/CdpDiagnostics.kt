package com.bernaferrari.kmpwright.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** `Runtime.consoleAPICalled` to a [ConsoleMessage]; CDP type names are Playwright's console types. */
internal fun cdpConsoleMessage(params: JsonObject): ConsoleMessage? {
    val type = params.text("type") ?: return null
    val args = (params["args"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
    val text = args.joinToString(" ") { renderCdpPreview(it) }.take(MAX_DIAGNOSTIC_TEXT_CHARS)
    val frame = (params["stackTrace"] as? JsonObject)?.get("callFrames")?.let { it as? JsonArray }?.firstOrNull() as? JsonObject
    val location = frame?.let { SourceLocation(it.text("url").orEmpty(), it.int("lineNumber") ?: 0, it.int("columnNumber") ?: 0) }
    return ConsoleMessage(if (type in PLAYWRIGHT_CONSOLE_TYPES) type else "log", text, location)
}

/** `Runtime.exceptionThrown` to a [PageError], following Playwright's `exceptionToError`. */
internal fun cdpPageError(params: JsonObject): PageError? {
    val details = params["exceptionDetails"] as? JsonObject ?: return null
    val exception = details["exception"] as? JsonObject
    val messageWithStack = if (exception != null) {
        exception.text("description") ?: exception["value"]?.let(::primitiveText) ?: ""
    } else buildString {
        append(details.text("text").orEmpty())
        val frames = ((details["stackTrace"] as? JsonObject)?.get("callFrames") as? JsonArray).orEmpty()
        frames.mapNotNull { it as? JsonObject }.forEach { frame ->
            append("\n    at ").append(frame.text("functionName")?.ifEmpty { null } ?: "<anonymous>")
                .append(" (").append(frame.text("url").orEmpty()).append(':').append(frame.int("lineNumber") ?: 0)
                .append(':').append(frame.int("columnNumber") ?: 0).append(')')
        }
    }
    val lines = messageWithStack.split('\n')
    val firstStackLine = lines.indexOfFirst { it.startsWith("    at") }
    val messageWithName = if (firstStackLine == -1) messageWithStack else lines.take(firstStackLine).joinToString("\n")
    val (name, message) = splitErrorMessage(messageWithName)
    val url = details.text("url")
    val location = if (url == null && details["lineNumber"] == null) null
        else SourceLocation(url.orEmpty(), details.int("lineNumber") ?: 0, details.int("columnNumber") ?: 0)
    return PageError(
        name = name,
        message = message.take(MAX_DIAGNOSTIC_TEXT_CHARS),
        stack = if (firstStackLine == -1) null else messageWithStack.take(MAX_DIAGNOSTIC_TEXT_CHARS),
        location = location
    )
}

/** Playwright's Chromium `renderPreview` for a `Runtime.RemoteObject`. */
internal fun renderCdpPreview(remote: JsonObject): String {
    if (remote.text("type") == "undefined") return "undefined"
    remote["value"]?.let { return primitiveText(it) }
    remote.text("unserializableValue")?.let { return it }
    val preview = remote["preview"] as? JsonObject
    val properties = (preview?.get("properties") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
    if (remote.text("description") == "Object" && preview != null) {
        return properties.joinToString(", ", "{", "}") { "${it.text("name")}: ${it.text("value") ?: it.text("type")}" }
    }
    if (remote.text("subtype") == "array" && preview != null) {
        return properties.joinToString(", ", "[", "]") { it.text("value") ?: it.text("type").orEmpty() }
    }
    return remote.text("description") ?: remote.text("type").orEmpty()
}

private fun primitiveText(element: JsonElement): String = when (element) {
    JsonNull -> "null"
    is JsonPrimitive -> element.contentOrNull ?: "null"
    else -> element.toString()
}

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
