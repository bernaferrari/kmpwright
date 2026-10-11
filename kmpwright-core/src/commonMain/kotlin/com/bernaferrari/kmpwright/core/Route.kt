package com.bernaferrari.kmpwright.core

interface Route {
    val request: Request
    val requestId: String

    fun continueRequest(options: ContinueOptions = ContinueOptions())
    fun fulfill(options: FulfillOptions)
    fun abort(errorCode: String = "aborted")
}

data class ContinueOptions(
    val url: String? = null,
    val method: String? = null,
    val headers: Map<String, String>? = null,
    val postData: String? = null
)

data class FulfillOptions(
    val status: Int = 200,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
    val bodyBytes: ByteArray? = null,
    val contentType: String? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FulfillOptions) return false
        if (status != other.status) return false
        if (headers != other.headers) return false
        if (body != other.body) return false
        if (bodyBytes != null) {
            if (other.bodyBytes == null) return false
            if (!bodyBytes.contentEquals(other.bodyBytes)) return false
        } else if (other.bodyBytes != null) return false
        if (contentType != other.contentType) return false
        return true
    }

    override fun hashCode(): Int {
        var result = status
        result = 31 * result + headers.hashCode()
        result = 31 * result + (body?.hashCode() ?: 0)
        result = 31 * result + (bodyBytes?.contentHashCode() ?: 0)
        result = 31 * result + (contentType?.hashCode() ?: 0)
        return result
    }
}

sealed class RouteHandler {
    data class Continue(val options: ContinueOptions = ContinueOptions()) : RouteHandler()
    data class Fulfill(val options: FulfillOptions) : RouteHandler()
    data class Abort(val errorCode: String = "aborted") : RouteHandler()
    data object Fallthrough : RouteHandler()
}

interface RouteRegistry {
    fun addHandler(urlPattern: String, handler: suspend (Route) -> RouteHandler)
    fun removeHandler(urlPattern: String)
    fun clear()
    fun findHandler(url: String): Pair<String, suspend (Route) -> RouteHandler>?
}
