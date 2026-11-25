package com.bernaferrari.kmpwright.core

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.ref.WeakReference
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSLocalizedDescriptionKey
import platform.Foundation.NSURLErrorBadServerResponse
import platform.Foundation.NSURLErrorBadURL
import platform.Foundation.NSURLErrorCancelled
import platform.Foundation.NSURLErrorCannotConnectToHost
import platform.Foundation.NSURLErrorCannotFindHost
import platform.Foundation.NSURLErrorDomain
import platform.Foundation.NSURLErrorNetworkConnectionLost
import platform.Foundation.NSURLErrorNoPermissionsToReadFile
import platform.Foundation.NSURLErrorNotConnectedToInternet
import platform.Foundation.NSURLErrorTimedOut
import platform.Foundation.NSURLErrorUnknown
import platform.Foundation.NSURLResponse
import platform.Foundation.NSURLSessionDataTask
import platform.darwin.NSObject
import platform.WebKit.WKURLSchemeHandlerProtocol
import platform.WebKit.WKURLSchemeTaskProtocol
import platform.WebKit.WKWebView

internal fun schemeTaskError(code: Long, description: String): NSError =
    NSError(domain = NSURLErrorDomain, code = code, userInfo = mapOf<Any?, Any?>(NSLocalizedDescriptionKey to description))

/** Maps Playwright abort error codes onto the closest NSURLError. */
internal fun routeAbortError(errorCode: String): NSError {
    val code = when (errorCode.lowercase()) {
        "aborted" -> NSURLErrorCancelled
        "timedout" -> NSURLErrorTimedOut
        "accessdenied" -> NSURLErrorNoPermissionsToReadFile
        "addressunreachable", "connectionrefused" -> NSURLErrorCannotConnectToHost
        "connectionaborted", "connectionreset", "connectionclosed" -> NSURLErrorNetworkConnectionLost
        "internetdisconnected" -> NSURLErrorNotConnectedToInternet
        "namenotresolved" -> NSURLErrorCannotFindHost
        "blockedbyclient", "blockedbyresponse" -> NSURLErrorCancelled
        else -> NSURLErrorUnknown
    }
    return schemeTaskError(code, "Request aborted by route: $errorCode")
}

/**
 * One WebKit URL-scheme task. WebKit raises an exception if a task is
 * completed twice or after `stopURLSchemeTask`, so every completion goes
 * through [finish]/[fail], which are no-ops once the task is inactive.
 * Main thread only.
 */
@OptIn(ExperimentalForeignApi::class)
internal class IOSSchemeTask(val task: WKURLSchemeTaskProtocol) {
    var active: Boolean = true
        private set
    var dataTask: NSURLSessionDataTask? = null

    fun finish(response: NSURLResponse, data: NSData?) {
        if (!active) return
        active = false
        dataTask = null
        task.didReceiveResponse(response)
        if (data != null && data.length > 0u) task.didReceiveData(data)
        task.didFinish()
    }

    fun fail(error: NSError) {
        if (!active) return
        active = false
        dataTask?.cancel()
        dataTask = null
        task.didFailWithError(error)
    }

    /** WebKit stopped the task; it must not be messaged again. */
    fun stop() {
        active = false
        dataTask?.cancel()
        dataTask = null
    }
}

/**
 * Retained by the WKWebViewConfiguration, so it references the controller
 * weakly to avoid a configuration -> handler -> controller -> web view cycle.
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
internal class IOSSchemeHandler(
    private val config: IosSchemeInterceptionConfig,
    controller: IOSWebViewController
) : NSObject(), WKURLSchemeHandlerProtocol {
    private val controller = WeakReference(controller)
    private val tasks = mutableListOf<IOSSchemeTask>()

    /** Fails every in-flight task; the owning page is closing. */
    fun dispose() {
        val inFlight = tasks.toList()
        tasks.clear()
        inFlight.forEach {
            it.fail(NSError(domain = NSURLErrorDomain, code = NSURLErrorCancelled,
                userInfo = mapOf<Any?, Any?>(NSLocalizedDescriptionKey to "The page was closed")))
        }
    }

    @ObjCSignatureOverride
    override fun webView(webView: WKWebView, startURLSchemeTask: WKURLSchemeTaskProtocol) {
        val record = IOSSchemeTask(startURLSchemeTask)
        val owner = controller.value
        if (owner == null || owner.isDisposed) {
            record.fail(NSError(domain = NSURLErrorDomain, code = NSURLErrorCancelled,
                userInfo = mapOf<Any?, Any?>(NSLocalizedDescriptionKey to "The page was closed")))
            return
        }
        tasks += record
        owner.handleSchemeTask(record, config) { tasks.remove(record) }
    }

    @ObjCSignatureOverride
    override fun webView(webView: WKWebView, stopURLSchemeTask: WKURLSchemeTaskProtocol) {
        val record = tasks.firstOrNull { it.task.isEqual(stopURLSchemeTask) } ?: return
        tasks.remove(record)
        record.stop()
    }
}
