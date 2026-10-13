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
    scope: CoroutineScope? = null,
    /** Largest single protocol message buffered in memory (UTF-16 chars, so 2x in bytes). */
    internal val maxMessageChars: Int = DEFAULT_MAX_MESSAGE_CHARS,
    /** Upper bound on concurrently awaited commands; protects against unbounded growth. */
    private val maxPendingRequests: Int = 10_000
) : CdpEventTransport {
    private val scope: CoroutineScope = scope ?: CoroutineScope(SupervisorJob() + Dispatchers.IO +
        CoroutineExceptionHandler { _, error ->
            // Background protocol work must never vanish silently.
            _connectionError.value = error.message ?: error.toString()
            kmpwrightLogWarning("CDP connection background task failed", error)
        })
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

    @Volatile private var webSocket: WebSocket? = null
    @Volatile private var httpClient: HttpClient? = null
    private val sendMutex = Mutex()
    private var listenerJob: Job? = null
    private var keepAliveJob: Job? = null
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Connects to the CDP WebSocket endpoint.
     */
    suspend fun connect() {
        // A proxy needs a dedicated client; otherwise share one per connection
        // and release it in close() (HttpClient is AutoCloseable on JDK 21+).
        val client = if (proxy != null) {
            HttpClient.newBuilder()
                .proxy(ProxySelector.of(InetSocketAddress(proxy.host, proxy.port)))
                .connectTimeout(java.time.Duration.ofMillis(connectionTimeoutMs))
                .build()
        } else HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofMillis(connectionTimeoutMs))
            .build()
        httpClient = client
        val builder = client.newWebSocketBuilder()
            .connectTimeout(java.time.Duration.ofMillis(connectionTimeoutMs))

        val listener = object : WebSocket.Listener {
                private val assembler = CdpMessageAssembler(maxMessageChars)

                override fun onOpen(webSocket: WebSocket) {
                    webSocket.request(1)
                }

                override fun onError(webSocket: WebSocket, error: Throwable) {
                    markDisconnected("CDP connection error: ${error.message.orEmpty()}", error.message)
                }

                override fun onClose(webSocket: WebSocket, code: Int, reason: String?): CompletionStage<*>? {
                    markDisconnected("CDP connection closed ($code): ${reason.orEmpty()}", null)
                    return null
                }

                override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
                    val message = when (val assembled = assembler.accept(data, last)) {
                        null -> { webSocket.request(1); return null }
                        is CdpMessageAssembler.Result.Oversized -> {
                            // Fail only the command whose reply exceeded the
                            // budget; the socket and every other command survive.
                            failOversized(assembled)
                            webSocket.request(1)
                            return null
                        }
                        is CdpMessageAssembler.Result.Complete -> assembled.message
                    }
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
        if (socket == null || !_isConnected.value) {
            return CdpResponse(id = id, error = CdpError(-1,
                "CDP connection is not open" + (_connectionError.value?.let { ": $it" } ?: "")))
        }
        if (pendingResponses.size >= maxPendingRequests) {
            return CdpResponse(id = id, error = CdpError(-1,
                "Too many CDP commands in flight ($maxPendingRequests); refusing $method"))
        }
        return try {
            withTimeout(connectionTimeoutMs) {
                val response = CompletableDeferred<CdpResponse>()
                pendingResponses[id] = { response.complete(it) }
                // failPending() may have run between the connectivity check and
                // registration; never wait the full deadline on a dead socket.
                if (!_isConnected.value && pendingResponses.remove(id) != null) {
                    return@withTimeout CdpResponse(id = id, error = CdpError(-1, "CDP connection is not open"))
                }
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
        _isConnected.value = false
        try {
            keepAliveJob?.cancel()
            failPending("CDP connection closed")
            webSocket?.let { socket ->
                // sendClose completes asynchronously; abort afterwards so the
                // input side never lingers when the peer does not answer.
                socket.sendClose(1000, "Closing").whenComplete { _, _ -> socket.abort() }
            }
            listenerJob?.cancel()
            scope.cancel()
        } catch (e: Exception) {
            // close is best-effort
        }
        webSocket = null
        (httpClient as? AutoCloseable)?.let { runCatching { it.close() } }
        httpClient = null
    }

    private fun markDisconnected(pendingMessage: String, error: String?) {
        if (error != null) _connectionError.value = error
        _isConnected.value = false
        failPending(pendingMessage)
    }

    private fun failOversized(result: CdpMessageAssembler.Result.Oversized) {
        val description = "CDP message of ${result.length} chars exceeds the ${maxMessageChars}-char budget"
        val id = result.id
        if (id == null) {
            // An oversized event cannot be delivered; record it without
            // disturbing commands that are still in flight.
            _connectionError.value = description
            kmpwrightLogWarning("$description (event dropped)", null)
            return
        }
        pendingResponses.remove(id)?.let { callback ->
            try { callback(CdpResponse(id = id, error = CdpError(-1, description))) } catch (_: Throwable) {}
        }
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
                            markDisconnected("CDP keep-alive failed: ${error.message.orEmpty()}", error.message)
                            ws.abort()
                        }
                    }
                } catch (e: Exception) {
                    markDisconnected("CDP keep-alive failed: ${e.message.orEmpty()}", e.message)
                    ws.abort()
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

internal const val DEFAULT_MAX_MESSAGE_CHARS = 32 * 1024 * 1024

/**
 * Reassembles fragmented WebSocket text frames under a memory budget.
 *
 * Messages above [maxChars] are not buffered: only a short prefix is kept so
 * the command id can be recovered (Chromium always serializes `id` first in
 * replies) and the single affected command failed, instead of aborting the
 * whole socket.
 */
internal class CdpMessageAssembler(private val maxChars: Int) {
    sealed interface Result {
        data class Complete(val message: String) : Result
        data class Oversized(val id: Int?, val length: Long) : Result
    }

    private var buffer = StringBuilder()
    private var oversizedHead: StringBuilder? = null
    private var oversizedLength = 0L

    fun accept(data: CharSequence, last: Boolean): Result? {
        val head = oversizedHead
        if (head != null) {
            oversizedLength += data.length
            if (head.length < HEAD_CHARS) head.append(data, 0, minOf(data.length, HEAD_CHARS - head.length))
        } else if (buffer.length.toLong() + data.length > maxChars) {
            val prefix = StringBuilder(HEAD_CHARS)
            prefix.append(buffer, 0, minOf(buffer.length, HEAD_CHARS))
            if (prefix.length < HEAD_CHARS) prefix.append(data, 0, minOf(data.length, HEAD_CHARS - prefix.length))
            oversizedLength = buffer.length.toLong() + data.length
            oversizedHead = prefix
            buffer = StringBuilder()
        } else {
            buffer.append(data)
        }
        if (!last) return null
        oversizedHead?.let { prefix ->
            oversizedHead = null
            val id = LEADING_ID.find(prefix)?.groupValues?.get(1)?.toIntOrNull()
            return Result.Oversized(id, oversizedLength).also { oversizedLength = 0 }
        }
        val message = buffer.toString()
        if (buffer.capacity() > 1_048_576) buffer = StringBuilder() else buffer.clear()
        return Result.Complete(message)
    }

    private companion object {
        const val HEAD_CHARS = 256
        val LEADING_ID = Regex("""^\s*\{\s*"id"\s*:\s*(\d+)""")
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
        val wsUrl = withContext(Dispatchers.IO) { ChromeDiscovery.discoverChromeWsUrl() } ?: return null
        val conn = CdpConnection(wsUrl, timeoutMs)
        conn.connect()
        return conn
    }
}
