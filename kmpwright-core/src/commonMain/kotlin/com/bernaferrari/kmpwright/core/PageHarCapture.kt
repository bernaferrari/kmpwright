package com.bernaferrari.kmpwright.core

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.*

/** Capture the controller's unified page/frame events; native SDKs provide metadata only. */
internal class PageHarCapture(private val controller: WebViewNetworkController, private val scope: CoroutineScope) {
    private class Capture(val recorder: HarRecorder) {
        val ids = linkedMapOf<String, String>()
        val responses = mutableMapOf<String, Response>()
        val jobs = mutableSetOf<Job>()
        var sequence = 0L
    }
    private val lock = SynchronizedObject()
    private var current: Capture? = null
    fun start() = synchronized(lock) { if (current == null) current = Capture(HarRecorder().also { it.start() }) }
    fun started(request: Request) = synchronized(lock) {
        val capture = current ?: return@synchronized
        val id = (++capture.sequence).toString()
        capture.ids[request.id] = id
        if (capture.ids.size > 500) {
            val oldest = capture.ids.keys.first(); capture.ids.remove(oldest); capture.responses.remove(oldest)
        }
        capture.recorder.recordRequest(id, HarRequest(request.method, request.url,
            headers = request.headers.map { HarHeader(it.key, it.value) },
            postData = request.postData?.let { HarPostData(text = it) }))
    }
    fun received(response: Response) = synchronized(lock) {
        val capture = current ?: return@synchronized
        val id = capture.ids[response.id] ?: return@synchronized
        capture.responses[response.id] = response
        val headers = response.headers.map { HarHeader(it.key, it.value) }
        capture.recorder.recordResponse(id, HarResponse(response.status, response.statusText.orEmpty(), headers = headers,
            content = HarContent(-1, headers.firstOrNull { it.name.equals("content-type", true) }?.value?.substringBefore(';') ?: "application/octet-stream"),
            redirectURL = headers.firstOrNull { it.name.equals("location", true) }?.value.orEmpty()))
    }
    fun finished(request: Request) = synchronized(lock) {
        val capture = current ?: return@synchronized
        val id = capture.ids[request.id] ?: return@synchronized
        capture.recorder.recordLoadingFinished(id)
        val response = capture.responses.remove(request.id) ?: return@synchronized
        if (response.status == 0 || response.status in 300..399 || capture.jobs.size >= 32) return@synchronized
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val body = withTimeout(1_500) { controller.networkResponseBody(request.id, 1024 * 1024) }
                capture.recorder.recordResponseBody(id, body.content, body.base64Encoded, body.truncated)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Preserve metadata when the SDK, cache or browser cannot supply a body. */ }
        }
        capture.jobs += job
        job.invokeOnCompletion { synchronized(lock) { capture.jobs -= job } }
        job.start()
    }
    fun failed(request: Request, error: String) {
        received(Response(request.url, 0, boundedUtf8(error, 1024), id = request.id))
        finished(request)
    }
    suspend fun stop(): String {
        val capture = synchronized(lock) { current.also { current = null } } ?: return HarRecorder().toJson()
        val jobs = synchronized(lock) { capture.jobs.toList() }
        try {
            withTimeoutOrNull(2_000) { jobs.joinAll() }
            return capture.recorder.toJson()
        } finally { jobs.forEach { it.cancel() }; capture.recorder.clear() }
    }
    fun dispose() = synchronized(lock) {
        current?.let { capture -> capture.jobs.toList().forEach { it.cancel() }; capture.recorder.clear() }
        current = null
    }
}
