package com.bernaferrari.kmpwright.core

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

/** Each collector attaches its own session, allowing streaming alongside recording. */
internal class CdpScreencast(private val transport: CdpEventTransport, private val pageSession: CdpPageSession) {
    private val lock = Any()
    private var closed = false
    private val collectors = mutableSetOf<Job>()

    private data class RawFrame(val data: String, val timestamp: Double, val metadata: JsonObject)

    fun frames(options: ScreencastOptions): Flow<ScreencastFrame> = callbackFlow<RawFrame> {
        val owner = requireNotNull(currentCoroutineContext()[Job])
        synchronized(lock) { check(!closed) { "Page is closed" }; collectors += owner }
        var capture: String? = null
        var subscription: CdpEventSubscription? = null
        var connectionWatcher: Job? = null
        val lifecycleSubscriptions = mutableListOf<CdpEventSubscription>()
        var lastTimestamp = Double.NEGATIVE_INFINITY
        val pendingAcks = AtomicInteger()
        try {
            connectionWatcher = transport.connectionState?.let { state -> launch {
                state.first { !it }
                close(KmpwrightException("CDP connection closed during screencast"))
            } }
            val target = pageSession.targetId ?: send(pageSession.sessionId, "Target.getTargetInfo")
                .getValue("targetInfo").jsonObject.getValue("targetId").jsonPrimitive.content
            capture = send(null, "Target.attachToTarget", mapOf("targetId" to target, "flatten" to true))
                .getValue("sessionId").jsonPrimitive.content
            val session = capture
            lifecycleSubscriptions += transport.onEvent("Inspector.targetCrashed", session) { close(KmpwrightException("Recording target crashed")) }
            lifecycleSubscriptions += transport.onEvent("Inspector.detached", session) { close(KmpwrightException("Recording session detached")) }
            lifecycleSubscriptions += transport.onEvent("Target.detachedFromTarget", null) { params ->
                if (params["sessionId"]?.jsonPrimitive?.content == session) close(KmpwrightException("Recording session detached"))
            }
            send(session, "Page.enable")
            subscription = transport.onEvent("Page.screencastFrame", session) { params ->
                val frameId = params["sessionId"]?.jsonPrimitive?.longOrNull ?: return@onEvent
                if (pendingAcks.incrementAndGet() > 32) {
                    pendingAcks.decrementAndGet()
                    close(KmpwrightException("Screencast acknowledgments exceeded their queue budget"))
                    return@onEvent
                }
                launch(Dispatchers.IO) {
                    try { withTimeout(1_000) { send(session, "Page.screencastFrameAck", mapOf("sessionId" to frameId)) } }
                    catch (error: Exception) { close(error) }
                    finally { pendingAcks.decrementAndGet() }
                }
                val metadata = params["metadata"]?.jsonObject ?: return@onEvent
                val timestamp = metadata["timestamp"]?.jsonPrimitive?.doubleOrNull?.times(1000)
                    ?: currentTimeMillis().toDouble()
                if (timestamp - lastTimestamp < 1000.0 / options.maxFps) return@onEvent
                lastTimestamp = timestamp
                val data = params["data"]?.jsonPrimitive?.content ?: return@onEvent
                if (data.length > 16 * 1024 * 1024) { close(KmpwrightException("Screencast frame exceeds 16 MiB")); return@onEvent }
                trySend(RawFrame(data, timestamp, metadata))
            }
            send(session, "Page.startScreencast", mapOf("format" to "jpeg", "quality" to options.quality,
                "maxWidth" to options.maxWidth, "maxHeight" to options.maxHeight, "everyNthFrame" to 1))
            awaitClose { }
        } finally {
            connectionWatcher?.cancel()
            lifecycleSubscriptions.forEach { it.cancel() }
            runCatching { subscription?.cancel() }
            withContext(NonCancellable) {
                capture?.let { session ->
                    withTimeoutOrNull(1_000) { runCatching { send(session, "Page.stopScreencast") } }
                    withTimeoutOrNull(1_000) { runCatching { send(null, "Target.detachFromTarget", mapOf("sessionId" to session)) } }
                }
            }
            synchronized(lock) { collectors -= owner }
        }
    }.buffer(Channel.CONFLATED).map { raw ->
        fun number(key: String, default: Double = 0.0) = raw.metadata[key]?.jsonPrimitive?.doubleOrNull ?: default
        val bytes = try { Base64.getDecoder().decode(raw.data) }
            catch (error: Exception) { throw KmpwrightException("Invalid screencast frame: ${error.message}") }
        ScreencastFrame(bytes, raw.timestamp, number("deviceWidth"), number("deviceHeight"),
            number("pageScaleFactor", 1.0), number("scrollOffsetX"), number("scrollOffsetY"))
    }.flowOn(Dispatchers.IO).buffer(Channel.CONFLATED)

    suspend fun close() {
        val owned = synchronized(lock) { closed = true; collectors.toList() }
        owned.forEach { it.cancel() }
        withContext(NonCancellable) { withTimeoutOrNull(2_500) { owned.forEach { it.join() } } }
    }

    private suspend fun send(session: String?, method: String, params: Map<String, Any?> = emptyMap()): JsonObject {
        val response = if (session == null) transport.send(method, params) else transport.sendSession(session, method, params)
        response.error?.let { throw KmpwrightException("$method: ${it.message}") }
        return response.result ?: buildJsonObject { }
    }
}
