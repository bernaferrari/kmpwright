package com.bernaferrari.kmpwright.core

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetSocketAddress
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/**
 * Manages a CDP (Chrome DevTools Protocol) connection over WebSocket.
 *
 * Provides:
 * - `send(method, params)` — send CDP command, await response
 * - `events` — flow of CDP events
 * - Session management for target-based connections
 */
internal class CdpConnection(
    private val wsUrl: String,
    private val connectionTimeoutMs: Long = 30_000,
    private val proxy: ProxyConfig? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) : CdpEventTransport {
    internal val downloadConfigurationMutex = kotlinx.coroutines.sync.Mutex()
    internal val configuredDownloadContexts = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    internal val tracingOwner = java.util.concurrent.atomic.AtomicReference<Any?>(null)
    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected
    override val connectionState: StateFlow<Boolean> get() = isConnected

    private val _connectionError = MutableStateFlow<String?>(null)
    val connectionError: StateFlow<String?> = _connectionError

    /** Channel for receiving CDP responses and events. */
    private val incoming = Channel<String>(32)
    // Backpressure stops WebSocket demand until buffered protocol data has room.
    private val incomingBudget = kotlinx.coroutines.sync.Semaphore(64)
    private fun messageUnits(message: String): Int = maxOf(1, (message.length + 1_048_575) / 1_048_576)

    /** Pending response callbacks keyed by request ID. */
    private val pendingResponses = ConcurrentHashMap<Int, (CdpResponse) -> Unit>()

    /** Event listeners keyed by method name. */
    private data class EventListener(
        val sessionId: String?,
        val callback: (JsonObject) -> Unit
    )

    private val eventListeners = ConcurrentHashMap<String, ConcurrentHashMap<Long, EventListener>>()
    private val nextListenerId = java.util.concurrent.atomic.AtomicLong()

    private var webSocket: WebSocket? = null
    private val sendMutex = Mutex()
    private var listenerJob: Job? = null
    private var keepAliveJob: Job? = null
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Connects to the CDP WebSocket endpoint.
     */
    suspend fun connect() {
        var client = HttpClient.newHttpClient()
        if (proxy != null) {
            val proxySelector = ProxySelector.of(InetSocketAddress(proxy.host, proxy.port))
            client = HttpClient.newBuilder()
                .proxy(proxySelector)
                .connectTimeout(java.time.Duration.ofMillis(connectionTimeoutMs))
                .build()
        }
        val builder = client.newWebSocketBuilder()
            .connectTimeout(java.time.Duration.ofMillis(connectionTimeoutMs))

        val listener = object : WebSocket.Listener {
                private var textBuffer = StringBuilder()

                override fun onOpen(webSocket: WebSocket) {
                    webSocket.request(1)
                }

                override fun onError(webSocket: WebSocket, error: Throwable) {
                    _connectionError.value = error.message
                    _isConnected.value = false
                    failPending("CDP connection error: ${error.message.orEmpty()}")
                }

                override fun onClose(webSocket: WebSocket, code: Int, reason: String?): CompletionStage<*>? {
                    _isConnected.value = false
                    failPending("CDP connection closed ($code): ${reason.orEmpty()}")
                    return null
                }

                override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
                    if (textBuffer.length.toLong() + data.length > 32L * 1024 * 1024) {
                        textBuffer = StringBuilder()
                        _connectionError.value = "CDP message exceeds its 64 MiB text memory budget"
                        _isConnected.value = false
                        failPending("CDP message exceeds its text memory budget")
                        webSocket.abort()
                        return null
                    }
                    textBuffer.append(data)
                    if (!last) { webSocket.request(1); return null }
                    val message = textBuffer.toString()
                    if (textBuffer.capacity() > 1_048_576) textBuffer = StringBuilder() else textBuffer.clear()
                    val consumed = java.util.concurrent.CompletableFuture<Void>()
                    val job = scope.launch {
                        var acquired = 0
                        try {
                            repeat(messageUnits(message)) { incomingBudget.acquire(); acquired++ }
                            incoming.send(message)
                            acquired = 0 // The event pump owns these budget units now.
                            webSocket.request(1)
                            consumed.complete(null)
                        } finally { repeat(acquired) { incomingBudget.release() } }
                    }
                    job.invokeOnCompletion { error -> if (error != null) consumed.completeExceptionally(error) }
                    return consumed
                }

                override fun onBinary(webSocket: WebSocket, data: java.nio.ByteBuffer, last: Boolean): CompletionStage<*>? {
                    // Binary frames not used in CDP
                    webSocket.request(1)
                    return null
                }
            }
        val ws = withTimeout(connectionTimeoutMs) {
            suspendCancellableCoroutine<WebSocket> { cont ->
                val future = builder.buildAsync(URI(wsUrl), listener)
                cont.invokeOnCancellation { future.cancel(true) }
                future.whenComplete { socket, error ->
                    if (!cont.isActive) return@whenComplete
                    if (error != null) {
                        cont.resumeWith(Result.failure(KmpwrightException("Failed to connect to CDP: ${error.message}")))
                    } else {
                        cont.resume(socket)
                    }
                }
            }
        }

        webSocket = ws
        _isConnected.value = true
        _connectionError.value = null
        startListener()
        startKeepAlive()
    }

    /**
     * Sends a CDP command and returns the response.
     */
    override suspend fun send(method: String, params: Map<String, Any?>): CdpResponse {
        val id = CdpIdGenerator.next()
        val request = CdpRequest(id = id, method = method, params = mapToJsonElement(params))
        val requestJson = CdpSerializer.serializeRequest(request)

        return awaitResponse(id, method, requestJson)
    }

    private suspend fun awaitResponse(id: Int, method: String, requestJson: String): CdpResponse {
        val socket = webSocket
            ?: return CdpResponse(id = id, error = CdpError(-1, "CDP connection is not open"))
        return try {
            withTimeout(connectionTimeoutMs) {
                val response = CompletableDeferred<CdpResponse>()
                pendingResponses[id] = { response.complete(it) }
                try {
                    // Java WebSocket permits only one pending text send. Hold the
                    // mutex until the send completes, then let replies arrive in
                    // any order through their independent request IDs.
                    sendMutex.withLock {
                        suspendCancellableCoroutine<Unit> { continuation ->
                            socket.sendText(requestJson, true).whenComplete { _, error ->
                                if (continuation.isActive) {
                                    if (error == null) continuation.resume(Unit)
                                    else continuation.resumeWith(Result.failure(error))
                                }
                            }
                        }
                    }
                    response.await()
                } finally {
                    pendingResponses.remove(id)
                }
            }
        } catch (_: TimeoutCancellationException) {
            // Only translate our transport deadline. Preserve caller cancellation.
            currentCoroutineContext().ensureActive()
            CdpResponse(id = id, error = CdpError(-1, "Timed out waiting for CDP command $method"))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            CdpResponse(id = id, error = CdpError(-1, "Failed to send CDP command $method: ${e.message}"))
        }
    }

    /**
     * Sends a CDP command in a browser session (for multi-tab support).
     */
    override suspend fun sendSession(
        sessionId: String,
        method: String,
        params: Map<String, Any?>
    ): CdpResponse {
        val id = CdpIdGenerator.next()
        val request = CdpRequest(id = id, method = method, params = mapToJsonElement(params), sessionId = sessionId)
        val requestJson = CdpSerializer.serializeRequest(request)

        return awaitResponse(id, method, requestJson)
    }

    /**
     * Registers a listener for a specific CDP event method.
     */
    override fun onEvent(
        method: String,
        sessionId: String?,
        listener: (JsonObject) -> Unit
    ): CdpEventSubscription {
        val listenerId = nextListenerId.incrementAndGet()
        val listeners = eventListeners.computeIfAbsent(method) { ConcurrentHashMap() }
        listeners[listenerId] = EventListener(sessionId, listener)
        return CdpEventSubscription {
            eventListeners[method]?.let { entries ->
                entries.remove(listenerId)
                if (entries.isEmpty()) eventListeners.remove(method, entries)
            }
        }
    }

    /**
     * Closes the WebSocket connection.
     */
    fun close() {
        try {
            keepAliveJob?.cancel()
            failPending("CDP connection closed")
            webSocket?.sendClose(1000, "Closing")
            listenerJob?.cancel()
            scope.cancel()
        } catch (e: Exception) {
            // Log but don't throw — close is best-effort
        }
        _isConnected.value = false
    }

    private fun startKeepAlive() {
        keepAliveJob?.cancel()
        keepAliveJob = scope.launch {
            while (isActive && _isConnected.value) {
                delay(20_000)
                val ws = webSocket ?: continue
                try {
                    ws.sendPing(ByteBuffer.wrap(ByteArray(0))).whenComplete { _, error ->
                        if (error != null) {
                            _connectionError.value = error.message
                            _isConnected.value = false
                        }
                    }
                } catch (e: Exception) {
                    _connectionError.value = e.message
                    _isConnected.value = false
                }
            }
        }
    }

    private fun startListener() {
        listenerJob = scope.launch {
            while (isActive) {
                try {
                    val message = incoming.receive()
                    repeat(messageUnits(message)) { incomingBudget.release() }
                    dispatchIncoming(message)
                } catch (e: CancellationException) {
                    break
                } catch (e: Throwable) {
                    // An unexpected pump failure must release commands waiting for a response.
                    _isConnected.value = false
                    _connectionError.value = e.message
                    failPending("CDP event pump terminated: ${e.message.orEmpty()}")
                    break
                }
            }
        }
    }

    internal fun dispatchIncoming(message: String) {
        try {
            val obj = json.parseToJsonElement(message).jsonObject
            val id = obj["id"]?.jsonPrimitive?.intOrNull

            if (id != null && pendingResponses.containsKey(id)) {
                // This is a response
                val response = CdpSerializer.deserializeResponse(message)
                pendingResponses.remove(id)?.let { callback ->
                    try {
                        callback(response)
                    } catch (_: Throwable) {
                        // A response callback must not terminate the event pump.
                    }
                }
            } else {
                // This is an event
                val method = obj["method"]?.jsonPrimitive?.content ?: return
                val params = obj["params"]?.jsonObject ?: return
                val eventSessionId = obj["sessionId"]?.jsonPrimitive?.content
                eventListeners[method]?.values?.forEach { listener ->
                    if (listener.sessionId == eventSessionId) {
                        try {
                            listener.callback(params)
                        } catch (_: Throwable) {
                            // Isolate user listeners: one bad callback cannot starve others.
                        }
                    }
                }
            }
        } catch (_: kotlinx.serialization.SerializationException) {
            // Malformed message — skip but don't kill the connection.
        } catch (_: IllegalArgumentException) {
            // Valid JSON with the wrong object/primitive shape is also malformed.
        }
    }

    private fun failPending(message: String) {
        pendingResponses.entries.toList().forEach { (id, callback) ->
            if (pendingResponses.remove(id, callback)) {
                try {
                    callback(CdpResponse(id = id, error = CdpError(-1, message)))
                } catch (_: Throwable) {
                    // Keep failing the remaining commands if one callback misbehaves.
                }
            }
        }
    }
}

/**
 * Factory for creating CDP connections.
 */
internal object CdpConnectionFactory {
    /**
     * Creates a CDP connection to a WebSocket URL.
     */
    fun connect(
        wsUrl: String,
        timeoutMs: Long = 30_000,
        proxy: ProxyConfig? = null
    ): CdpConnection {
        val conn = CdpConnection(wsUrl, timeoutMs, proxy)
        // Note: caller must call connect() as a suspend function
        return conn
    }

    /**
     * Auto-discovers a local Chrome and returns a connection, or null.
     */
    suspend fun connectToLocalChrome(timeoutMs: Long = 10_000): CdpConnection? {
        val wsUrl = ChromeDiscovery.discoverChromeWsUrl() ?: return null
        val conn = CdpConnection(wsUrl, timeoutMs)
        conn.connect()
        return conn
    }
}
