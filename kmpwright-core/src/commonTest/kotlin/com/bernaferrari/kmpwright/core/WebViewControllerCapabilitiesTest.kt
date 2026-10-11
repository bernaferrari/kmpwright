package com.bernaferrari.kmpwright.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

/** Compile contracts for the capability-scoped controller seam. */
class WebViewControllerCapabilitiesTest {
    @Test
    fun scriptConsumersCanUseOnlyTheScriptPort() {
        val script: WebViewScriptController = ScriptOnlyController()

        assertNotNull(script)
        assertFalse(script is WebViewController)
    }

    @Test
    fun navigationConsumersCanUseOnlyTheNavigationPort() {
        val navigation: WebViewNavigationController = NavigationOnlyController()

        assertNotNull(navigation)
        assertFalse(navigation is WebViewController)
    }

    @Test
    fun evaluateJavaScriptAsyncBridgesTheCallbackResult() {
        val controller = CallbackScriptController()
        var completed: Result<String?>? = null

        suspend { controller.evaluateJavaScriptAsync("1 + 1") }.startCoroutine(
            object : Continuation<String?> {
                override val context = EmptyCoroutineContext

                override fun resumeWith(result: Result<String?>) {
                    completed = result
                }
            }
        )

        assertEquals(null, completed)
        controller.callback!!.invoke("2")
        assertEquals("2", completed!!.getOrThrow())
    }

    @Test
    fun defaultNetworkMutationsFailClosed() {
        val network = NetworkOnlyController()

        assertFailsWith<KmpwrightException> {
            runSuspend { network.setExtraHttpHeadersAsync(mapOf("X-Test" to "value")) }
        }
        assertFailsWith<KmpwrightException> {
            runSuspend { network.setHttpCredentialsAsync("user", "password") }
        }
        assertFailsWith<KmpwrightException> {
            runSuspend { network.setDomainFilterAsync(setOf("example.com")) }
        }
    }

    private class ScriptOnlyController : WebViewScriptController {
        override fun evaluateJavaScript(script: String, callback: (String?) -> Unit) {
            callback(null)
        }

        override fun injectJavaScript(script: String, callback: ((String?) -> Unit)?) {
            callback?.invoke(null)
        }
    }

    private class CallbackScriptController : WebViewScriptController {
        var callback: ((String?) -> Unit)? = null

        override fun evaluateJavaScript(script: String, callback: (String?) -> Unit) {
            this.callback = callback
        }

        override fun injectJavaScript(script: String, callback: ((String?) -> Unit)?) = Unit
    }

    private class NetworkOnlyController : WebViewNetworkController {
        override fun addRequestListener(listener: RequestListener) = Unit
        override fun removeRequestListener(listener: RequestListener) = Unit
        override fun getPendingRequestCount(): Int = 0
        override fun resetRequestCount() = Unit
        override fun setRequestIdleTimeout(timeoutMs: Long) = Unit
        override fun addDialogListener(listener: DialogListener) = Unit
        override fun removeDialogListener(listener: DialogListener) = Unit
        override fun addRouteHandler(urlPattern: String, handler: suspend (Route) -> RouteHandler) = Unit
        override fun removeRouteHandler(urlPattern: String) = Unit
        override fun removeAllRouteHandlers() = Unit
        override fun fulfillRoute(requestId: String, options: FulfillOptions) = Unit
        override fun abortRoute(requestId: String, errorCode: String) = Unit
        override fun continueRoute(requestId: String, options: ContinueOptions) = Unit
        override fun interceptRequest(
            url: String,
            method: String,
            headers: Map<String, String>,
            onResult: (RouteAction?) -> Unit
        ) = Unit
    }

    private class NavigationOnlyController : WebViewNavigationController {
        override fun loadUrl(url: String) = Unit
        override fun loadHtml(content: String, baseUrl: String?) = Unit
        override fun goBack() = Unit
        override fun goForward() = Unit
        override fun reload() = Unit
        override fun getUrl(): String? = null
        override fun getTitle(): String? = null
        override fun setNavigationCallback(callback: ((NavigationResult) -> Unit)?) = Unit
    }

    private fun <T> runSuspend(block: suspend () -> T) {
        var completed: Result<T>? = null
        block.startCoroutine(
            object : Continuation<T> {
                override val context = EmptyCoroutineContext

                override fun resumeWith(result: Result<T>) {
                    completed = result
                }
            }
        )
        completed!!.getOrThrow()
    }
}
