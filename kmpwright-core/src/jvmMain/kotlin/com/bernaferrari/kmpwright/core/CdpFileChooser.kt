package com.bernaferrari.kmpwright.core

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path

/** Opaque, single-use chooser handles; root and explicitly resolved frame sessions share ownership. */
internal class CdpFileChooser(private val transport: CdpEventTransport, private val rootSession: String?,
    private val report: (FileChooserEvent) -> Unit) {
    private data class Handle(val session: String?, val node: Long, val frame: String, val multiple: Boolean)
    private val lock = Any()
    private val mutex = Mutex()
    private val sessions = mutableMapOf<String?, List<CdpEventSubscription>>()
    private val handles = linkedMapOf<String, Handle>()
    private val prefix = java.util.UUID.randomUUID().toString()
    private var counter = 0L
    @Volatile private var enabled = false
    private var closed = false

    suspend fun setEnabled(value: Boolean) = mutex.withLock {
        check(!closed) { "File chooser runtime is closed" }
        if (value) {
            install(rootSession)
            enabled = true
        } else {
            enabled = false
            releaseSessions()
        }
    }

    suspend fun frameReady(session: String?) = mutex.withLock {
        if (enabled && !closed) install(session)
    }

    suspend fun frameGone(session: String) = mutex.withLock {
        sessions.remove(session)?.forEach { it.cancel() }
        synchronized(lock) { handles.entries.removeAll { it.value.session == session } }
    }

    private suspend fun install(session: String?) {
        if (sessions.containsKey(session)) return
        if (sessions.size >= 256) throw KmpwrightException("File chooser exceeds 256 frame sessions")
        val subscriptions = listOf(
            transport.onEvent("Page.fileChooserOpened", session) { params ->
                if (!enabled || closed) return@onEvent
                val node = params["backendNodeId"]?.jsonPrimitive?.longOrNull ?: return@onEvent
                val frame = params["frameId"]?.jsonPrimitive?.content ?: return@onEvent
                val multiple = params["mode"]?.jsonPrimitive?.content == "selectMultiple"
                val token = synchronized(lock) {
                    if (handles.size >= 64) handles.remove(handles.keys.first())
                    "chooser-$prefix-${++counter}".also { handles[it] = Handle(session, node, frame, multiple) }
                }
                report(FileChooserEvent(token, multiple))
            },
            transport.onEvent("Page.frameNavigated", session) { params ->
                val frame = params["frame"]?.jsonObject ?: return@onEvent
                synchronized(lock) {
                    if (session == rootSession && frame["parentId"] == null) handles.clear()
                    else handles.entries.removeAll { it.value.frame == frame["id"]?.jsonPrimitive?.content }
                }
            },
            transport.onEvent("Page.frameDetached", session) { params ->
                if (params["reason"]?.jsonPrimitive?.content != "swap") synchronized(lock) {
                    handles.entries.removeAll { it.value.frame == params["frameId"]?.jsonPrimitive?.content }
                }
            }
        )
        try { send(session, "Page.setInterceptFileChooserDialog", mapOf("enabled" to true)) }
        catch (error: Throwable) { subscriptions.forEach { it.cancel() }; throw error }
        sessions[session] = subscriptions
    }

    suspend fun setFiles(event: FileChooserEvent, files: List<String>): Unit = mutex.withLock {
        check(!closed) { "File chooser runtime is closed" }
        val handle = synchronized(lock) { handles[event.element] }
            ?: throw KmpwrightException("File chooser handle is stale or belongs to another page")
        require(handle.multiple || files.size <= 1) { "This file chooser accepts one file" }
        val paths = files.map { path -> Path.of(path).toAbsolutePath().normalize().also {
            require(Files.isRegularFile(it)) { "Upload file does not exist: $path" }
        }.toString() }
        send(handle.session, "DOM.setFileInputFiles", mapOf("files" to paths, "backendNodeId" to handle.node))
        synchronized(lock) { handles.remove(event.element) }
    }

    suspend fun close() = withContext(NonCancellable) { mutex.withLock {
        closed = true; enabled = false; releaseSessions()
    } }

    private suspend fun releaseSessions() {
        val owned = sessions.toMap()
        sessions.clear()
        owned.values.flatten().forEach { it.cancel() }
        synchronized(lock) { handles.clear() }
        withContext(NonCancellable) { withTimeoutOrNull(3_000) {
            owned.keys.toList().chunked(16).forEach { batch -> coroutineScope {
                batch.map { session -> launch { withTimeoutOrNull(1_000) { runCatching {
                    send(session, "Page.setInterceptFileChooserDialog", mapOf("enabled" to false))
                } } } }.joinAll()
            } }
        } }
    }

    private suspend fun send(session: String?, method: String, params: Map<String, Any?>): JsonObject {
        val response = if (session == null) transport.send(method, params) else transport.sendSession(session, method, params)
        response.error?.let { throw KmpwrightException("$method: ${it.message}") }
        return response.result ?: JsonObject(emptyMap())
    }
}
