package com.bernaferrari.kmpwright.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonNull
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Adapters for cloud browser providers.
 * Each provider makes an HTTP POST to create a browser session,
 * returning a [ProviderSession] with a CDP WebSocket URL.
 */
internal object CloudProviders {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .build()

    /**
     * Resolves a CDP WebSocket URL based on the config.
     * Priority: direct URL > cloud provider > auto-connect.
     */
    suspend fun resolveWsUrl(config: CdpConfig): ProviderSession {
        // Direct URL takes precedence
        config.websocketUrl?.let {
            // Discovery performs blocking HTTP/WebSocket probes; keep them off the caller's dispatcher.
            val wsUrl = withContext(Dispatchers.IO) { ChromeDiscovery.resolveCdpEndpoint(it) }
                ?: throw KmpwrightException("Unable to resolve CDP endpoint: $it")
            return ProviderSession(
                provider = "direct",
                sessionId = "",
                websocketUrl = wsUrl,
                stopUrl = null
            )
        }

        // Cloud provider
        config.provider?.let { provider ->
            return when (provider) {
                CloudProvider.Browserbase -> browserbase()
                CloudProvider.Browserless -> browserless()
                CloudProvider.BrowserUse -> browserUse()
                CloudProvider.Kernel -> kernel()
                CloudProvider.AutoConnect -> autoConnect()
            }
        }

        throw KmpwrightException(
            "No CDP connection configured. Set cdp.websocketUrl or cdp.provider in KmpwrightConfig."
        )
    }

    // --- Browserbase ---

    private suspend fun browserbase(): ProviderSession {
        val apiKey = System.getenv("BROWSERBASE_API_KEY")
            ?: throw KmpwrightException("BROWSERBASE_API_KEY environment variable not set")
        val projectId = System.getenv("BROWSERBASE_PROJECT_ID")
        val request = browserbaseCreateRequest(apiKey, projectId)

        val response = httpPost(
            "https://api.browserbase.com/v1/sessions",
            request.body,
            headers = request.headers
        )
        return parseBrowserbaseSession(response)
    }

    // --- Browserless ---

    private suspend fun browserless(): ProviderSession {
        val apiKey = System.getenv("BROWSERLESS_API_KEY")
            ?: throw KmpwrightException("BROWSERLESS_API_KEY environment variable not set")

        val baseUrl = System.getenv("BROWSERLESS_API_URL") ?: "https://production-sfo.browserless.io"
        val browser = System.getenv("BROWSERLESS_BROWSER_TYPE") ?: "chromium"

        val stealthArg = if (System.getenv("BROWSERLESS_STEALTH")?.toBoolean() == true) {
            ""","context":{"launchArgs":["--disable-blink-features=AutomationControlled"]}"""
        } else ""

        val response = httpPost(
            "$baseUrl/session",
            """{"browser":{"browser":"$browser","headless":true}$stealthArg}""",
            headers = mapOf("Authorization" to "Bearer $apiKey")
        )

        val body = json.parseToJsonElement(response).jsonObject
        val wsUrl = body["connect"]?.jsonPrimitive?.content
            ?: throw KmpwrightException("Browserless response missing connect URL")
        val stopUrl = body["stop"]?.jsonPrimitive?.content

        return ProviderSession(
            provider = "browserless",
            sessionId = body["id"]?.jsonPrimitive?.content ?: "",
            websocketUrl = wsUrl,
            stopUrl = stopUrl
        )
    }

    // --- Browser Use ---

    private suspend fun browserUse(): ProviderSession {
        val apiKey = System.getenv("BROWSER_USE_API_KEY")
            ?: throw KmpwrightException("BROWSER_USE_API_KEY environment variable not set")

        val request = browserUseCreateRequest(apiKey, System::getenv)
        val response = httpPost(
            "$BROWSER_USE_API_BASE/browsers",
            request.body,
            headers = request.headers,
            timeoutSeconds = 10
        )
        return parseBrowserUseSession(response)
    }

    // --- Kernel ---

    private suspend fun kernel(): ProviderSession {
        val apiKey = System.getenv("KERNEL_API_KEY")
            ?: throw KmpwrightException("KERNEL_API_KEY environment variable not set")

        val baseUrl = System.getenv("KERNEL_ENDPOINT") ?: "https://api.onkernel.com"
        val request = kernelCreateRequest(apiKey, System::getenv)
        val response = httpPost(
            "${baseUrl.trimEnd('/')}/browsers", request.body, headers = request.headers
        )
        return parseKernelSession(response, baseUrl)
    }

    // --- Auto-connect ---

    private suspend fun autoConnect(): ProviderSession {
        val wsUrl = withContext(Dispatchers.IO) { ChromeDiscovery.discoverChromeWsUrl() }
            ?: throw KmpwrightException(
                "No running Chrome instance found. Start Chrome with --remote-debugging-port=9222"
            )
        return ProviderSession(
            provider = "auto-connect",
            sessionId = "",
            websocketUrl = wsUrl,
            stopUrl = null
        )
    }

    // --- Session cleanup ---

    /**
     * Releases a cloud browser session.
     */
    suspend fun releaseSession(session: ProviderSession) {
        val stopUrl = session.stopUrl ?: return
        try {
            if (session.provider == "browserbase") {
                // Browserbase uses PATCH with status "REQUEST_RELEASE"
                val apiKey = System.getenv("BROWSERBASE_API_KEY") ?: return
                httpPatch(stopUrl, """{"status":"REQUEST_RELEASE"}""", mapOf("X-BB-API-Key" to apiKey))
            } else if (session.provider == "browser-use") {
                val apiKey = System.getenv("BROWSER_USE_API_KEY") ?: return
                httpPatch(stopUrl, """{"action":"stop"}""", mapOf("X-Browser-Use-API-Key" to apiKey), timeoutSeconds = 4)
            } else {
                // Other providers use DELETE with their own credentials.
                httpDelete(stopUrl, releaseHeaders(session.provider, System::getenv) ?: return)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Best-effort cleanup
        }
    }

    // --- HTTP helpers ---

    private suspend fun httpPost(url: String, body: String, headers: Map<String, String> = emptyMap(), timeoutSeconds: Long = 30): String = withContext(Dispatchers.IO) {
        val requestBuilder = HttpRequest.newBuilder()
            .uri(URI(url))
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .POST(HttpRequest.BodyPublishers.ofString(body))

        headers.forEach { (key, value) -> requestBuilder.header(key, value) }

        val response = httpClient.send(requestBuilder.build(), java.net.http.HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            throw KmpwrightException("Provider API error ${response.statusCode()}")
        }
        response.body()
    }

    private suspend fun httpDelete(url: String, headers: Map<String, String> = emptyMap()): String = withContext(Dispatchers.IO) {
        val builder = HttpRequest.newBuilder()
            .uri(URI(url))
            .timeout(Duration.ofSeconds(10))
            .DELETE()
        headers.forEach { (key, value) -> builder.header(key, value) }
        val response = httpClient.send(builder.build(), java.net.http.HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) throw KmpwrightException("Provider API error ${response.statusCode()}")
        response.body()
    }

    private suspend fun httpPatch(url: String, body: String, headers: Map<String, String> = emptyMap(), timeoutSeconds: Long = 10): String = withContext(Dispatchers.IO) {
        val builder = HttpRequest.newBuilder()
            .uri(URI(url))
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .method("PATCH", HttpRequest.BodyPublishers.ofString(body))
        headers.forEach { (key, value) -> builder.header(key, value) }
        val response = httpClient.send(builder.build(), java.net.http.HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) throw KmpwrightException("Provider API error ${response.statusCode()}")
        response.body()
    }

}

/**
 * Headers used to release a provider session via DELETE, or null when the
 * provider's credential is unavailable. Browserless authenticates its session
 * endpoints with the same Bearer API key used to create the session; sending
 * an unauthenticated DELETE would be rejected and leak the cloud session.
 */
internal fun releaseHeaders(provider: String, lookup: (String) -> String?): Map<String, String>? = when (provider) {
    "kernel" -> lookup("KERNEL_API_KEY")?.let { mapOf("Authorization" to "Bearer $it") }
    "browserless" -> lookup("BROWSERLESS_API_KEY")?.let { mapOf("Authorization" to "Bearer $it") }
    else -> emptyMap()
}

internal data class ProviderHttpRequest(val body: String, val headers: Map<String, String>)

internal fun browserbaseCreateRequest(apiKey: String, projectId: String?): ProviderHttpRequest {
    val body = projectId?.let {
        buildJsonObject { put("projectId", JsonPrimitive(it)) }.toString()
    } ?: "{}"
    return ProviderHttpRequest(body, mapOf("X-BB-API-Key" to apiKey))
}

internal const val BROWSER_USE_API_BASE = "https://api.browser-use.com/api/v4"

internal fun browserUseCreateRequest(
    apiKey: String,
    lookup: (String) -> String? = { null }
): ProviderHttpRequest {
    val body = buildJsonObject {
        lookup("BROWSER_USE_PROFILE_ID")?.takeIf { it.isNotBlank() }?.let { put("profileId", JsonPrimitive(it)) }
        lookup("BROWSER_USE_PROXY_COUNTRY")?.takeIf { it.isNotBlank() }?.let {
            val country = it.lowercase()
            put("proxyCountryCode", if (country in setOf("none", "direct")) JsonNull else JsonPrimitive(country))
        }
        lookup("BROWSER_USE_ENABLE_RECORDING")?.let {
            put("enableRecording", JsonPrimitive(it.lowercase() !in setOf("0", "false", "no", "")))
        }
    }
    return ProviderHttpRequest(body.toString(), mapOf("X-Browser-Use-API-Key" to apiKey))
}

internal fun kernelCreateRequest(apiKey: String, lookup: (String) -> String?): ProviderHttpRequest {
    fun flag(name: String, default: Boolean = false): Boolean = lookup(name)?.let {
        it == "1" || it.equals("true", ignoreCase = true)
    } ?: default
    val body = buildJsonObject {
        put("headless", JsonPrimitive(flag("KERNEL_HEADLESS", true)))
        put("stealth", JsonPrimitive(flag("KERNEL_STEALTH")))
        put("timeout_seconds", JsonPrimitive(lookup("KERNEL_TIMEOUT_SECONDS")?.toLongOrNull()?.takeIf { it > 0 } ?: 300))
        lookup("KERNEL_PROFILE_NAME")?.takeIf { it.isNotBlank() }?.let { name ->
            put("profile", buildJsonObject {
                put("name", JsonPrimitive(name))
                put("save_changes", JsonPrimitive(flag("KERNEL_PROFILE_SAVE_CHANGES")))
            })
        }
    }
    return ProviderHttpRequest(body.toString(), mapOf("Authorization" to "Bearer $apiKey"))
}

internal fun parseKernelSession(response: String, baseUrl: String): ProviderSession {
    val body = Json.parseToJsonElement(response).jsonObject
    val id = (body["session_id"] ?: body["id"])?.jsonPrimitive?.content
        ?.takeIf { it.matches(Regex("[A-Za-z0-9_-]+")) }
        ?: throw KmpwrightException("Kernel response missing valid session id")
    val websocketUrl = listOf("cdp_ws_url", "connectUrl", "connect_url", "cdpUrl", "cdp_url", "wsUrl")
        .firstNotNullOfOrNull { body[it]?.jsonPrimitive?.content?.takeIf { value -> value.startsWith("ws://") || value.startsWith("wss://") } }
        ?: throw KmpwrightException("Kernel response missing CDP WebSocket URL")
    return ProviderSession("kernel", id, websocketUrl, "${baseUrl.trimEnd('/')}/browsers/$id")
}

internal fun parseBrowserbaseSession(response: String): ProviderSession {
    val body = Json.parseToJsonElement(response).jsonObject
    val id = body["id"]?.jsonPrimitive?.content?.takeIf { it.matches(Regex("[A-Za-z0-9_-]+")) }
        ?: throw KmpwrightException("Provider response missing valid session id")
    return ProviderSession(
        provider = "browserbase",
        sessionId = id,
        websocketUrl = body["connectUrl"]?.jsonPrimitive?.content
            ?: throw KmpwrightException("Browserbase response missing connectUrl"),
        stopUrl = "https://api.browserbase.com/v1/sessions/$id"
    )
}

internal fun parseBrowserUseSession(response: String): ProviderSession {
    val body = Json.parseToJsonElement(response).jsonObject
    val id = body["id"]?.jsonPrimitive?.content?.takeIf { it.matches(Regex("[A-Za-z0-9_-]+")) }
        ?: throw KmpwrightException("Provider response missing valid session id")
    return ProviderSession(
        provider = "browser-use",
        sessionId = id,
        websocketUrl = body["cdpUrl"]?.jsonPrimitive?.content
            ?: throw KmpwrightException("Browser Use response missing cdpUrl"),
        stopUrl = "$BROWSER_USE_API_BASE/browsers/$id"
    )
}
