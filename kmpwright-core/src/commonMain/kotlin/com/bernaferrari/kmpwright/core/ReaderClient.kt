package com.bernaferrari.kmpwright.core

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.compression.ContentEncoding

internal expect fun readerHttpClient(timeoutMs: Long, caCertificatePath: String? = null, useSystemCa: Boolean = true): HttpClient

internal fun HttpClientConfig<*>.configureReaderTransport(timeoutMs: Long) {
    followRedirects = false
    expectSuccess = false
    install(HttpTimeout) {
        requestTimeoutMillis = timeoutMs
        connectTimeoutMillis = timeoutMs
        socketTimeoutMillis = timeoutMs
    }
    install(ContentEncoding) { gzip(); deflate() }
}

internal expect fun isReaderTransportTimeout(error: Exception): Boolean
