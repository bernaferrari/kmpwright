package com.bernaferrari.kmpwright.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * CDP (Chrome DevTools Protocol) JSON-RPC 2.0 message types.
 */
@Serializable
data class CdpRequest(
    val id: Int,
    val method: String,
    val params: JsonObject = JsonObject(emptyMap()),
    val sessionId: String? = null
)

@Serializable
data class CdpResponse(
    val id: Int,
    val result: JsonObject? = null,
    val error: CdpError? = null
)

@Serializable
data class CdpError(
    val code: Int,
    val message: String,
    val data: String? = null
)

data class CdpEvent(
    val method: String,
    val params: JsonObject,
    val sessionId: String? = null
)

/** Result of connecting to a cloud browser provider. */
data class ProviderSession(
    val provider: String,
    val sessionId: String,
    val websocketUrl: String,
    val stopUrl: String?
)

/**
 * CDP domain methods commonly used by Kmpwright.
 * Grouped by domain for discoverability.
 */
object CdpMethods {
    // --- Page ---
    const val PAGE_ENABLE = "Page.enable"
    const val PAGE_DISABLE = "Page.disable"
    const val PAGE_NAVIGATE = "Page.navigate"
    const val PAGE_RELOAD = "Page.reload"
    const val PAGE_STOP_LOADING = "Page.stopLoading"
    const val PAGE_CAPTURE_SCREENSHOT = "Page.captureScreenshot"
    const val PAGE_PRINT_TO_PDF = "Page.printToPDF"
    const val PAGE_CLOSE = "Page.close"
    const val PAGE_GET_LAYOUT_METRICS = "Page.getLayoutMetrics"
    const val PAGE_BRING_TO_FRONT = "Page.bringToFront"

    // --- Runtime ---
    const val RUNTIME_ENABLE = "Runtime.enable"
    const val RUNTIME_DISABLE = "Runtime.disable"
    const val RUNTIME_EVALUATE = "Runtime.evaluate"
    const val RUNTIME_CALL_FUNCTION_ON = "Runtime.callFunctionOn"

    // --- DOM ---
    const val DOM_ENABLE = "DOM.enable"
    const val DOM_DISABLE = "DOM.disable"
    const val DOM_DOCUMENT = "DOM.getDocument"
    const val DOM_GET_BOX_MODEL = "DOM.getBoxModel"

    // --- Network ---
    const val NETWORK_ENABLE = "Network.enable"
    const val NETWORK_DISABLE = "Network.disable"
    const val NETWORK_SET_EXTRA_HTTP_HEADERS = "Network.setExtraHTTPHeaders"
    const val NETWORK_ENABLE_FETCH = "Network.enableFetch"
    const val NETWORK_DISABLE_FETCH = "Network.disableFetch"
    const val NETWORK_SET_CACHE_DISABLED = "Network.setCacheDisabled"
    const val NETWORK_CLEAR_BROWSER_CACHE = "Network.clearBrowserCache"
    const val NETWORK_SET_USER_AGENT_OVERRIDE = "Network.setUserAgentOverride"
    const val NETWORK_EMULATE_NETWORK_CONDITIONS = "Network.emulateNetworkConditions"
    const val NETWORK_SET_COOKIE = "Network.setCookie"
    const val NETWORK_DELETE_COOKIE = "Network.deleteCookie"
    const val NETWORK_GET_COOKIES = "Network.getAllCookies"
    const val NETWORK_CLEAR_COOKIES = "Network.clearBrowserCookies"

    // --- Input ---
    const val INPUT_DISPATCH_MOUSE_EVENT = "Input.dispatchMouseEvent"
    const val INPUT_DISPATCH_KEY_EVENT = "Input.dispatchKeyEvent"
    const val INPUT_DISPATCH_TOUCH_EVENT = "Input.dispatchTouchEvent"
    const val INPUT_INSERT_TEXT = "Input.insertText"
    const val INPUT_SET_INTERCEPT_DRAG_FROM_EVENTS_ENABLED = "Input.setInterceptDragsFromEvents"

    // --- Target ---
    const val TARGET_DISCOVER_TARGETS = "Target.discoverTargets"
    const val TARGET_GET_TARGETS = "Target.getTargets"
    const val TARGET_ATTACH_TO_TARGET = "Target.attachToTarget"
    const val TARGET_CLOSE_TARGET = "Target.closeTarget"
    const val TARGET_CREATE_TARGET = "Target.createTarget"
    const val TARGET_ACTIVATE_TARGET = "Target.activateTarget"
    const val TARGET_DETACH_FROM_TARGET = "Target.detachFromTarget"

    // --- Browser ---
    const val BROWSER_GET_VERSION = "Browser.getVersion"
    const val BROWSER_CLOSE = "Browser.close"
    const val BROWSER_GRANT_PERMISSIONS = "Browser.grantPermissions"
    const val BROWSER_SET_DOWNLOAD_BEHAVIOR = "Browser.setDownloadBehavior"

    // --- Emulation ---
    const val EMULATION_SET_DEVICE_METRICS_OVERRIDE = "Emulation.setDeviceMetricsOverride"
    const val EMULATION_SET_CPU_THROTTLING_RATE = "Emulation.setCPUThrottlingRate"
    const val EMULATION_SET_TOUCH_EMULATION_ENABLED = "Emulation.setTouchEmulationEnabled"
    const val EMULATION_SET_GEOLATION_OVERRIDE = "Emulation.setGeolocationOverride"

    // --- Fetch (request interception) ---
    const val FETCH_ENABLE = "Fetch.enable"
    const val FETCH_DISABLE = "Fetch.disable"
    const val FETCH_FULFILL_REQUEST = "Fetch.fulfillRequest"
    const val FETCH_CONTINUE_REQUEST = "Fetch.continueRequest"
    const val FETCH_FAIL_REQUEST = "Fetch.failRequest"

    // --- Storage ---
    const val STORAGE_GET_Cookies = "Storage.getCookies"
    const val STORAGE_SET_COOKIES = "Storage.setCookies"
    const val STORAGE_CLEAR_COOKIES = "Storage.clearCookies"
    const val STORAGE_CLEAR_DATA_FOR_ORIGIN = "Storage.clearDataForOrigin"

    // --- Tracing ---
    const val TRACING_START = "Tracing.start"
    const val TRACING_END = "Tracing.end"

    // --- Profiler ---
    const val PROFILER_ENABLE = "Profiler.enable"
    const val PROFILER_DISABLE = "Profiler.disable"
    const val PROFILER_START = "Profiler.start"
    const val PROFILER_STOP = "Profiler.stop"

    // --- IO ---
    const val IO_READ_FILE = "IO.read"
    const val IO_CLOSE = "IO.close"
}

/**
 * Converts a Map<String, Any?> to a JsonObject by encoding primitive values.
 */
internal fun mapToJsonElement(map: Map<String, Any?>): JsonObject {
    val entries = map.entries.associate { (key, value) ->
        key to when (value) {
            null -> JsonNull
            is String -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value)
            is JsonObject -> value
            is JsonElement -> value
            is List<*> -> kotlinx.serialization.json.JsonArray(value.map { item ->
                when (item) {
                    null -> JsonNull
                    is String -> JsonPrimitive(item)
                    is Number -> JsonPrimitive(item)
                    is Boolean -> JsonPrimitive(item)
                    is Map<*, *> -> mapToJsonElement(item.entries.associate { (k, v) -> k.toString() to v })
                    else -> JsonPrimitive(item.toString())
                }
            })
            is Map<*, *> -> mapToJsonElement(value.entries.associate { (k, v) -> k.toString() to v })
            else -> JsonPrimitive(value.toString())
        }
    }
    return JsonObject(entries)
}

/**
 * Utility for serializing CDP messages to/from JSON.
 */
internal object CdpSerializer {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        isLenient = true
    }

    fun serializeRequest(request: CdpRequest): String = json.encodeToString(CdpRequest.serializer(), request)

    fun deserializeResponse(jsonStr: String): CdpResponse = json.decodeFromString(CdpResponse.serializer(), jsonStr)

    fun deserializeEvent(jsonStr: String): CdpEvent {
        val obj = json.parseToJsonElement(jsonStr).jsonObject
        return CdpEvent(
            method = obj["method"]?.jsonPrimitive?.content ?: "",
            params = obj["params"]?.jsonObject ?: buildJsonObject {},
            sessionId = obj["sessionId"]?.jsonPrimitive?.content
        )
    }
}

/**
 * Simple monotonic ID generator for CDP requests.
 */
internal object CdpIdGenerator {
    private val nextId = kotlinx.atomicfu.atomic(1)

    fun next(): Int = nextId.getAndIncrement()
}
