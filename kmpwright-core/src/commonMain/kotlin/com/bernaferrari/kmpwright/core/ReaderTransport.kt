package com.bernaferrari.kmpwright.core

import io.ktor.client.HttpClient
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.*
import io.ktor.utils.io.charsets.Charsets
import io.ktor.utils.io.charsets.forName
import io.ktor.utils.io.charsets.decode
import kotlinx.io.Buffer
import io.ktor.utils.io.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class ReaderFetch(val finalUrl: String, val status: Int, val contentType: String,
    val headers: Map<String, String>, val body: String, val truncated: Boolean) {
    val successful: Boolean get() = status in 200..299
    val mediaType: String get() = contentType.substringBefore(';').trim().lowercase()
}

/** Redirects are followed here, after policy checks, rather than inside an engine. */
internal class ReaderTransport(private val client: HttpClient, private val requestHeaders: Map<String, String>,
    private val maxBytes: Int, private val checkUrl: suspend (String) -> Unit) {
    suspend fun fetch(rawUrl: String): ReaderFetch {
        val original = normalizeReaderUrl(rawUrl)
        val origin = canonicalHttpOrigin(original)
        var target = original
        val visited = mutableSetOf<String>()
        repeat(11) {
            checkUrl(target)
            if (!visited.add(target)) throw KmpwrightException("HTTP read redirect loop")
            val result = client.prepareGet(target) {
                if (requestHeaders.keys.none { it.equals("User-Agent", true) }) this.headers.append("User-Agent", "Kmpwright read")
                if (requestHeaders.keys.none { it.equals("Accept", true) }) {
                    this.headers.append("Accept", "text/markdown, text/plain;q=0.9, text/html;q=0.7, */*;q=0.1")
                }
                // Custom headers, including nonstandard API keys, belong to the
                // original origin. Negotiation headers can follow redirects.
                requestHeaders.forEach { (name, value) ->
                    if (canonicalHttpOrigin(target) == origin || name.equals("Accept", true)) this.headers.append(name, value)
                }
            }.execute { response ->
                val status = response.status.value
                val responseHeaders = response.headers.entries().associate { it.key.lowercase() to it.value.joinToString(", ") }
                if (status in setOf(301, 302, 303, 307, 308)) {
                    response.bodyAsChannel().cancel()
                    ReaderFetch(target, status, responseHeaders["content-type"].orEmpty(), responseHeaders, "", false)
                } else {
                    val channel = response.bodyAsChannel()
                    try {
                        val chunks = mutableListOf<ByteArray>()
                        val buffer = ByteArray(8192)
                        var total = 0
                        while (total <= maxBytes) {
                            currentCoroutineContext().ensureActive()
                            val read = channel.readAvailable(buffer, 0, minOf(buffer.size, maxBytes + 1 - total))
                            if (read < 0) break
                            if (read == 0) continue
                            chunks += buffer.copyOf(read)
                            total += read
                        }
                        val bounded = ByteArray(minOf(total, maxBytes))
                        var offset = 0
                        for (chunk in chunks) {
                            val size = minOf(chunk.size, bounded.size - offset)
                            if (size <= 0) break
                            chunk.copyInto(bounded, offset, 0, size)
                            offset += size
                        }
                        ReaderFetch(target, status, responseHeaders["content-type"].orEmpty(), responseHeaders,
                            decode(bounded, responseHeaders["content-type"].orEmpty(), total > maxBytes), total > maxBytes)
                    } finally { channel.cancel() }
                }
            }
            if (result.status !in setOf(301, 302, 303, 307, 308)) return result
            val location = result.headers["location"] ?: throw KmpwrightException("HTTP redirect has no Location header")
            target = resolveReaderUrl(target, location)
        }
        throw KmpwrightException("HTTP read exceeds 10 redirects")
    }

    private fun decode(bytes: ByteArray, contentType: String, truncated: Boolean): String {
        val charsetName = contentType.split(';').map(String::trim)
            .firstOrNull { it.startsWith("charset=", true) }?.substringAfter('=')?.trim('"', '\'') ?: "UTF-8"
        if (charsetName.equals("UTF-8", true) || charsetName.equals("utf8", true)) {
            val value = bytes.decodeToString()
            return if (truncated) boundedUtf8(value, bytes.size) else value
        }
        return try { Charsets.forName(charsetName).newDecoder().decode(Buffer().apply { write(bytes) }) }
        catch (error: Exception) { throw KmpwrightException("Could not decode HTTP charset $charsetName: ${error.message}") }
    }
}

internal fun normalizeReaderUrl(raw: String): String {
    val candidate = raw.trim().let { if ("://" in it) it else "https://$it" }
    if (candidate.any { it.code < 32 } || candidate.length > 8192) throw KmpwrightException("Invalid HTTP read URL")
    val url = try { io.ktor.http.Url(candidate) } catch (error: Exception) { throw KmpwrightException("Invalid HTTP read URL: ${error.message}") }
    if (url.protocol.name !in setOf("http", "https") || canonicalHttpOrigin(candidate) == null) {
        throw KmpwrightException("HTTP read requires an http/https URL without embedded credentials")
    }
    return URLBuilder(url).apply {
        fragment = ""
        // Ktor preserves dot segments when resolving relative references.
        // Normalize them before policy checks and before sending the request.
        val segments = mutableListOf<String>()
        val path = encodedPath.ifEmpty { "/" }
        val parts = path.split('/').drop(1)
        for ((index, segment) in parts.withIndex()) {
            when (segment.replace(Regex("%2e", RegexOption.IGNORE_CASE), ".")) {
                "." -> if (index == parts.lastIndex) segments.add("")
                ".." -> {
                    if (segments.isNotEmpty()) segments.removeAt(segments.lastIndex)
                    if (index == parts.lastIndex) segments.add("")
                }
                else -> segments.add(segment)
            }
        }
        encodedPath = "/" + segments.joinToString("/")
    }.buildString()
}

internal fun resolveReaderUrl(base: String, reference: String): String =
    normalizeReaderUrl(URLBuilder(base).apply {
        if (reference.isNotEmpty() && !reference.startsWith('#')) parameters.clear()
        takeFrom(reference)
    }.buildString())
