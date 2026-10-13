package com.bernaferrari.kmpwright.core

/**
 * Aggregate port implemented by every backend controller; implementation code can accept a
 * capability-scoped supertype instead.
 */
internal interface WebViewController :
    WebViewNavigationController,
    WebViewNetworkController,
    WebViewCookieStateController,
    WebViewLocatorController,
    WebViewEmulationController,
    WebViewDiagnosticsController,
    WebViewLifecycleController

/** A browser cookie, like Playwright's `Cookie`. [expires] is Unix epoch seconds; null is a session cookie. */
public data class Cookie(
    val name: String,
    val value: String,
    val domain: String,
    val path: String = "/",
    /** Unix epoch seconds; null denotes a session cookie. */
    val expires: Double? = null,
    val secure: Boolean = false,
    val httpOnly: Boolean = false,
    val sameSite: String? = null,
    val hostOnly: Boolean = !domain.startsWith(".")
)

internal interface RequestListener {
    fun onRequestStarted(request: Request)
    fun onResponseReceived(response: Response)
    fun onRequestFinished(request: Request)
    fun onRequestFailed(request: Request, error: String)
}

/** Receives JavaScript dialogs from a backend; the backend applies the returned response. */
internal interface DialogListener {
    suspend fun onDialogAsync(type: DialogType, message: String, defaultPrompt: String?): DialogResponse

    /**
     * Whether automation currently answers dialogs. When false, a backend may show its native dialog
     * UI instead of calling [onDialogAsync] (which would dismiss).
     */
    val hasDialogHandlers: Boolean get() = true

    /**
     * A dialog the backend leaves to the host or native UI (no automation handler); reported so
     * [PageEvents.dialog] still observes it.
     */
    fun onDialogObserved(type: DialogType, message: String, defaultPrompt: String?) {}
}
