package com.bernaferrari.kmpwright.core

/**
 * Delegate for HTTP headers and credentials operations.
 * Extracted from BasePage to reduce class size and improve testability.
 */
internal class PageHttpDelegate(
    private val controller: WebViewNetworkController
) {
    suspend fun setExtraHTTPHeaders(headers: Map<String, String>) {
        controller.setExtraHttpHeadersAsync(headers)
    }

    suspend fun setHTTPCredentials(username: String, password: String) {
        controller.setHttpCredentialsAsync(username, password)
    }

    suspend fun clearHTTPCredentials() {
        controller.setHttpCredentialsAsync(null, null)
    }
}
