package com.bernaferrari.kmpwright.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit

/**
 * Parses a CDP WebSocket URL from a Chrome DevTools discovery endpoint.
 * JVM-only since it reads the filesystem and makes HTTP connections.
 */
internal object ChromeDiscovery {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Attempts to auto-discover a running Chrome instance.
     * Checks DevToolsActivePort files on macOS and Linux,
     * and probes common ports as fallback.
     *
     * Returns the WebSocket debugger URL, or null if no Chrome found.
     */
    @JvmStatic
    fun discoverChromeWsUrl(): String? {
        val userHome = System.getProperty("user.home") ?: return null
        val paths = listOf(
            "$userHome/Library/Application Support/Google/Chrome/DevToolsActivePort",
            "$userHome/Library/Application Support/Google/Chrome Canary/DevToolsActivePort",
            "$userHome/Library/Application Support/Chromium/DevToolsActivePort",
            "$userHome/Library/Application Support/BraveSoftware/Brave-Browser/DevToolsActivePort",
            "$userHome/.config/google-chrome/DevToolsActivePort",
            "$userHome/.config/chromium/DevToolsActivePort"
        )

        for (path in paths) {
            val file = java.io.File(path)
            if (file.exists()) {
                val content = file.readText().trim()
                parseActivePortFile(content)?.let { return it }
            }
        }

        // Fallback: probe common ports
        val probePorts = intArrayOf(9222, 9229, 9333)
        for (port in probePorts) {
            discoverChromeWsUrl("localhost", port)?.let { return it }
        }

        return null
    }

    /**
     * Discovers a WebSocket URL from a host/port pair by probing Chrome's
     * DevTools HTTP endpoints. `/json/version` is preferred; `/json/list` is
     * used for runtimes that omit browser-level version discovery.
     */
    @JvmStatic
    fun discoverChromeWsUrl(host: String, port: Int, query: String? = null): String? {
        val versionUrl = "http://${bracketIpv6(host)}:$port/json/version"
        fetchText(versionUrl)?.let { body ->
            parseVersionWebSocketUrl(body)?.let {
                return appendQuery(rewriteWsHost(it, host, port), query)
            }
        }

        val listUrl = "http://${bracketIpv6(host)}:$port/json/list"
        fetchText(listUrl)?.let { body ->
            parseListWebSocketUrl(body)?.let {
                return appendQuery(rewriteWsHost(it, host, port), query)
            }
        }

        val directUrl = appendQuery("ws://${bracketIpv6(host)}:$port/devtools/browser", query)
        if (verifyDirectWebSocket(directUrl)) return directUrl

        return null
    }

    /**
     * Resolves an HTTP DevTools endpoint or returns a WebSocket endpoint as-is.
     * This lets callers pass `http://host:port?token=...` and still connect to
     * the discovered browser WebSocket with query parameters preserved.
     */
    @JvmStatic
    fun resolveCdpEndpoint(endpoint: String): String? {
        if (endpoint.startsWith("ws://") || endpoint.startsWith("wss://")) return endpoint
        val uri = runCatching { URI(endpoint) }.getOrNull() ?: return null
        if (uri.scheme != "http" && uri.scheme != "https") return null
        val host = uri.host ?: return null
        val basePath = uri.rawPath.orEmpty().trimEnd('/').removeSuffix("/json/version").removeSuffix("/json/list")
        val authority = uri.rawAuthority ?: return null
        val query = uri.rawQuery?.let { "?$it" }.orEmpty()
        for ((suffix, parser) in listOf("/json/version" to ::parseVersionWebSocketUrl, "/json/list" to ::parseListWebSocketUrl)) {
            val discovery = "${uri.scheme}://$authority$basePath$suffix$query"
            fetchText(discovery)?.let { body -> parser(body)?.let { advertised ->
                val ws = runCatching { URI(advertised) }.getOrNull() ?: return@let
                val scheme = if (uri.scheme == "https") "wss" else ws.scheme
                val path = ws.rawPath.orEmpty().let { if (basePath.isNotEmpty() && !it.startsWith("$basePath/")) basePath + it else it }
                val advertisedQuery = ws.rawQuery?.let { "?$it" }.orEmpty()
                return appendQuery("$scheme://$authority$path$advertisedQuery", uri.rawQuery)
            } }
        }
        val direct = appendQuery("${if (uri.scheme == "https") "wss" else "ws"}://$authority$basePath/devtools/browser", uri.rawQuery)
        return direct.takeIf(::verifyDirectWebSocket)
    }

    internal fun parseActivePortFile(content: String): String? {
        val lines = content.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.size < 2) return null

        val port = lines[0].toIntOrNull() ?: return null
        val path = lines[1]

        return when {
            path.startsWith("ws://") || path.startsWith("wss://") -> path
            path.startsWith("/") -> "ws://127.0.0.1:$port$path"
            else -> "ws://127.0.0.1:$port/$path"
        }
    }

    internal fun parseVersionWebSocketUrl(body: String): String? =
        runCatching {
            json.parseToJsonElement(body).jsonObject["webSocketDebuggerUrl"]?.jsonPrimitive?.content
        }.getOrNull()

    internal fun parseListWebSocketUrl(body: String): String? =
        runCatching {
            val targets = json.parseToJsonElement(body).jsonArray
            val browserTarget = targets.firstOrNull {
                it.jsonObject["type"]?.jsonPrimitive?.content == "browser"
            }
            val target = browserTarget ?: targets.firstOrNull()
            target?.jsonObject?.get("webSocketDebuggerUrl")?.jsonPrimitive?.content
        }.getOrNull()

    internal fun rewriteWsHost(wsUrl: String, host: String, port: Int): String {
        val uri = runCatching { URI(wsUrl) }.getOrNull() ?: return wsUrl
        val rawPath = uri.rawPath ?: ""
        val rawQuery = uri.rawQuery?.let { "?$it" } ?: ""
        val rawFragment = uri.rawFragment?.let { "#$it" } ?: ""
        return "${uri.scheme}://${bracketIpv6(host)}:$port$rawPath$rawQuery$rawFragment"
    }

    internal fun appendQuery(url: String, query: String?): String {
        if (query.isNullOrBlank()) return url
        return if ("?" in url) "$url&$query" else "$url?$query"
    }

    /** DevTools discovery documents are tiny; refuse to buffer anything unexpectedly large. */
    private const val MAX_DISCOVERY_BYTES = 1024 * 1024

    /** Blocking probe. Callers on coroutines must run discovery on [kotlinx.coroutines.Dispatchers.IO]. */
    private fun fetchText(url: String): String? {
        val conn = try { URI(url).toURL().openConnection() as HttpURLConnection } catch (_: Exception) { return null }
        return try {
            conn.connectTimeout = 500
            conn.readTimeout = 1000
            conn.connect()
            if (conn.responseCode != 200) return null
            conn.inputStream.use { stream ->
                val bytes = stream.readNBytes(MAX_DISCOVERY_BYTES + 1)
                if (bytes.size > MAX_DISCOVERY_BYTES) null else bytes.decodeToString()
            }
        } catch (_: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    private fun verifyDirectWebSocket(wsUrl: String): Boolean = try {
        val verified = CompletableFuture<Boolean>()
        val client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(1000))
            .build()
        val listener = object : WebSocket.Listener {
            private val buffer = StringBuilder()

            override fun onOpen(webSocket: WebSocket) {
                webSocket.request(1)
                webSocket.sendText("""{"id":1,"method":"Browser.getVersion"}""", true)
            }

            override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*> {
                buffer.append(data)
                if (last && buffer.contains(""""id":1""")) {
                    verified.complete(buffer.contains(""""result""""))
                    webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "verified")
                } else {
                    webSocket.request(1)
                }
                return CompletableFuture.completedFuture(null)
            }

            override fun onError(webSocket: WebSocket, error: Throwable) {
                verified.complete(false)
            }
        }
        val webSocket = client.newWebSocketBuilder()
            .connectTimeout(Duration.ofMillis(1000))
            .buildAsync(URI(wsUrl), listener)
            .get(2, TimeUnit.SECONDS)
        val result = verified.get(2, TimeUnit.SECONDS)
        if (!webSocket.isInputClosed || !webSocket.isOutputClosed) {
            webSocket.abort()
        }
        result
    } catch (_: Exception) {
        false
    }

    private fun bracketIpv6(host: String): String =
        if (host.contains(":") && !host.startsWith("[")) "[$host]" else host
}
