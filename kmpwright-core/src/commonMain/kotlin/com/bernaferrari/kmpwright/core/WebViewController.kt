package com.bernaferrari.kmpwright.core

/**
 * Compatibility facade for backend controllers.
 *
 * Keep this public aggregate stable while implementation code can accept a
 * capability-scoped supertype. Existing backend implementations and mocks
 * remain source-compatible because all ports retain the former signatures and
 * defaults.
 */
interface WebViewController :
    WebViewNavigationController,
    WebViewNetworkController,
    WebViewCookieStateController,
    WebViewLocatorController,
    WebViewLifecycleController

sealed class RouteAction {
    data class Fulfill(
        val status: Int,
        val headers: Map<String, String>,
        val body: String,
        val contentType: String,
        val bodyBytes: ByteArray? = null
    ) : RouteAction()
    data class Abort(val errorCode: String) : RouteAction()
    data class Continue(val url: String?, val method: String?, val headers: Map<String, String>?, val postData: String?) : RouteAction()
    data object Fallthrough : RouteAction()
}

data class Cookie(
    val name: String,
    val value: String,
    val domain: String,
    val path: String = "/",
    val expires: String? = null,
    val secure: Boolean = false,
    val httpOnly: Boolean = false,
    val sameSite: String? = null
)

interface RequestListener {
    fun onRequestStarted(request: Request)
    fun onResponseReceived(response: Response)
    fun onRequestFinished(request: Request)
    fun onRequestFailed(request: Request, error: String)
}

interface DialogListener {
    fun onDialog(type: DialogType, message: String, defaultPrompt: String?): DialogResponse
    suspend fun onDialogAsync(type: DialogType, message: String, defaultPrompt: String?): DialogResponse =
        onDialog(type, message, defaultPrompt)
}
