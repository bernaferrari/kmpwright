package com.bernaferrari.kmpwright.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin

internal actual fun readerHttpClient(timeoutMs: Long, caCertificatePath: String?, useSystemCa: Boolean): HttpClient {
    if (caCertificatePath != null || !useSystemCa) throw UnsupportedOperationException("Custom HTTP certificate trust is unavailable on the Darwin reader; configure platform trust")
    return HttpClient(Darwin) {
    configureReaderTransport(timeoutMs)
}

}

internal actual fun isReaderTransportTimeout(error: Exception): Boolean = error is io.ktor.client.plugins.HttpRequestTimeoutException
