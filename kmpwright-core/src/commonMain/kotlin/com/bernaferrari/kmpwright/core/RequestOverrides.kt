package com.bernaferrari.kmpwright.core

internal data class RequestOverride(
    val url: String,
    val method: String,
    val headers: Map<String, String>,
    val postData: String?
)

internal fun Request.applyContinueOptions(continueAction: RouteAction.Continue): RequestOverride {
    return RequestOverride(
        url = continueAction.url ?: url,
        method = continueAction.method ?: method,
        headers = continueAction.headers ?: headers,
        postData = continueAction.postData ?: postData
    )
}
