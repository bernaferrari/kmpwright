package com.bernaferrari.kmpwright.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp

internal actual fun readerHttpClient(timeoutMs: Long, caCertificatePath: String?, useSystemCa: Boolean): HttpClient = HttpClient(OkHttp) {
    configureReaderTransport(timeoutMs)
    if (caCertificatePath != null || !useSystemCa) engine {
        val trust = readerTrustManager(caCertificatePath, useSystemCa)
        val ssl = javax.net.ssl.SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
        config { sslSocketFactory(ssl.socketFactory, trust) }
    }
}

internal actual fun isReaderTransportTimeout(error: Exception): Boolean = error is io.ktor.client.plugins.HttpRequestTimeoutException || error is java.net.SocketTimeoutException
