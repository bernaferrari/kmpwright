package com.bernaferrari.kmpwright.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URI

internal actual suspend fun platformHttpGetText(
    url: String,
    headers: Map<String, String>
): String = withContext(Dispatchers.IO) {
    val connection = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        instanceFollowRedirects = true
        connectTimeout = 15_000
        readTimeout = 15_000
        headers.forEach { (name, value) -> setRequestProperty(name, value) }
    }

    try {
        val status = connection.responseCode
        if (status !in 200..299) {
            // Drain the error body so the connection can be reused, but never
            // return it as if it were the requested resource.
            runCatching { connection.errorStream?.use { it.readNBytes(64 * 1024) } }
            throw KmpwrightException("HTTP GET $url failed with status $status ${defaultHttpStatusText(status)}")
        }
        connection.inputStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
    } finally {
        connection.disconnect()
    }
}
