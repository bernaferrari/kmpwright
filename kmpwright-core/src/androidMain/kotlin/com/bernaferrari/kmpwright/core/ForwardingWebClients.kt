package com.bernaferrari.kmpwright.core

import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Message
import android.view.KeyEvent
import android.view.View
import android.webkit.ClientCertRequest
import android.webkit.ConsoleMessage
import android.webkit.GeolocationPermissions
import android.webkit.HttpAuthHandler
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SafeBrowsingResponse
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * A [WebViewClient] that forwards every non-deprecated callback to [delegate]
 * (typically the client the host app installed before Kmpwright attached).
 * Deprecated overloads are reached through the platform defaults, which call
 * them from the modern forms.
 */
internal open class ForwardingWebViewClient(internal val delegate: WebViewClient?) : WebViewClient() {
    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean =
        delegate?.shouldOverrideUrlLoading(view, request) ?: super.shouldOverrideUrlLoading(view, request)

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        delegate?.onPageStarted(view, url, favicon) ?: super.onPageStarted(view, url, favicon)
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        delegate?.onPageFinished(view, url) ?: super.onPageFinished(view, url)
    }

    override fun onLoadResource(view: WebView?, url: String?) {
        delegate?.onLoadResource(view, url) ?: super.onLoadResource(view, url)
    }

    override fun onPageCommitVisible(view: WebView?, url: String?) {
        delegate?.onPageCommitVisible(view, url) ?: super.onPageCommitVisible(view, url)
    }

    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? =
        if (delegate != null) delegate.shouldInterceptRequest(view, request) else super.shouldInterceptRequest(view, request)

    override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
        delegate?.onReceivedError(view, request, error) ?: super.onReceivedError(view, request, error)
    }

    override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
        delegate?.onReceivedHttpError(view, request, errorResponse) ?: super.onReceivedHttpError(view, request, errorResponse)
    }

    override fun onFormResubmission(view: WebView?, dontResend: Message?, resend: Message?) {
        delegate?.onFormResubmission(view, dontResend, resend) ?: super.onFormResubmission(view, dontResend, resend)
    }

    override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
        delegate?.doUpdateVisitedHistory(view, url, isReload) ?: super.doUpdateVisitedHistory(view, url, isReload)
    }

    override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
        delegate?.onReceivedSslError(view, handler, error) ?: super.onReceivedSslError(view, handler, error)
    }

    override fun onReceivedClientCertRequest(view: WebView?, request: ClientCertRequest?) {
        delegate?.onReceivedClientCertRequest(view, request) ?: super.onReceivedClientCertRequest(view, request)
    }

    override fun onReceivedHttpAuthRequest(view: WebView?, handler: HttpAuthHandler?, host: String?, realm: String?) {
        delegate?.onReceivedHttpAuthRequest(view, handler, host, realm) ?: super.onReceivedHttpAuthRequest(view, handler, host, realm)
    }

    override fun shouldOverrideKeyEvent(view: WebView?, event: KeyEvent?): Boolean =
        delegate?.shouldOverrideKeyEvent(view, event) ?: super.shouldOverrideKeyEvent(view, event)

    override fun onUnhandledKeyEvent(view: WebView?, event: KeyEvent?) {
        delegate?.onUnhandledKeyEvent(view, event) ?: super.onUnhandledKeyEvent(view, event)
    }

    override fun onScaleChanged(view: WebView?, oldScale: Float, newScale: Float) {
        delegate?.onScaleChanged(view, oldScale, newScale) ?: super.onScaleChanged(view, oldScale, newScale)
    }

    override fun onReceivedLoginRequest(view: WebView?, realm: String?, account: String?, args: String?) {
        delegate?.onReceivedLoginRequest(view, realm, account, args) ?: super.onReceivedLoginRequest(view, realm, account, args)
    }

    override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean =
        delegate?.onRenderProcessGone(view, detail) ?: super.onRenderProcessGone(view, detail)

    override fun onSafeBrowsingHit(view: WebView?, request: WebResourceRequest?, threatType: Int, callback: SafeBrowsingResponse?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1 && delegate != null) {
            delegate.onSafeBrowsingHit(view, request, threatType, callback)
        } else {
            super.onSafeBrowsingHit(view, request, threatType, callback)
        }
    }
}

/** A [WebChromeClient] that forwards every non-deprecated callback to [delegate]. */
internal open class ForwardingWebChromeClient(internal val delegate: WebChromeClient?) : WebChromeClient() {
    override fun onProgressChanged(view: WebView?, newProgress: Int) {
        delegate?.onProgressChanged(view, newProgress) ?: super.onProgressChanged(view, newProgress)
    }

    override fun onReceivedTitle(view: WebView?, title: String?) {
        delegate?.onReceivedTitle(view, title) ?: super.onReceivedTitle(view, title)
    }

    override fun onReceivedIcon(view: WebView?, icon: Bitmap?) {
        delegate?.onReceivedIcon(view, icon) ?: super.onReceivedIcon(view, icon)
    }

    override fun onReceivedTouchIconUrl(view: WebView?, url: String?, precomposed: Boolean) {
        delegate?.onReceivedTouchIconUrl(view, url, precomposed) ?: super.onReceivedTouchIconUrl(view, url, precomposed)
    }

    override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
        delegate?.onShowCustomView(view, callback) ?: super.onShowCustomView(view, callback)
    }

    override fun onHideCustomView() {
        delegate?.onHideCustomView() ?: super.onHideCustomView()
    }

    override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?): Boolean =
        delegate?.onCreateWindow(view, isDialog, isUserGesture, resultMsg) ?: super.onCreateWindow(view, isDialog, isUserGesture, resultMsg)

    override fun onRequestFocus(view: WebView?) {
        delegate?.onRequestFocus(view) ?: super.onRequestFocus(view)
    }

    override fun onCloseWindow(window: WebView?) {
        delegate?.onCloseWindow(window) ?: super.onCloseWindow(window)
    }

    override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean =
        delegate?.onJsAlert(view, url, message, result) ?: super.onJsAlert(view, url, message, result)

    override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean =
        delegate?.onJsConfirm(view, url, message, result) ?: super.onJsConfirm(view, url, message, result)

    override fun onJsPrompt(view: WebView?, url: String?, message: String?, defaultValue: String?, result: JsPromptResult?): Boolean =
        delegate?.onJsPrompt(view, url, message, defaultValue, result) ?: super.onJsPrompt(view, url, message, defaultValue, result)

    override fun onJsBeforeUnload(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean =
        delegate?.onJsBeforeUnload(view, url, message, result) ?: super.onJsBeforeUnload(view, url, message, result)

    override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback?) {
        delegate?.onGeolocationPermissionsShowPrompt(origin, callback) ?: super.onGeolocationPermissionsShowPrompt(origin, callback)
    }

    override fun onGeolocationPermissionsHidePrompt() {
        delegate?.onGeolocationPermissionsHidePrompt() ?: super.onGeolocationPermissionsHidePrompt()
    }

    override fun onPermissionRequest(request: PermissionRequest?) {
        delegate?.onPermissionRequest(request) ?: super.onPermissionRequest(request)
    }

    override fun onPermissionRequestCanceled(request: PermissionRequest?) {
        delegate?.onPermissionRequestCanceled(request) ?: super.onPermissionRequestCanceled(request)
    }

    override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean =
        delegate?.onConsoleMessage(consoleMessage) ?: super.onConsoleMessage(consoleMessage)

    override fun getDefaultVideoPoster(): Bitmap? =
        if (delegate != null) delegate.defaultVideoPoster else super.getDefaultVideoPoster()

    override fun getVideoLoadingProgressView(): View? =
        if (delegate != null) delegate.videoLoadingProgressView else super.getVideoLoadingProgressView()

    override fun getVisitedHistory(callback: ValueCallback<Array<String>>?) {
        delegate?.getVisitedHistory(callback) ?: super.getVisitedHistory(callback)
    }

    override fun onShowFileChooser(
        webView: WebView?,
        filePathCallback: ValueCallback<Array<Uri>>?,
        fileChooserParams: FileChooserParams?
    ): Boolean = delegate?.onShowFileChooser(webView, filePathCallback, fileChooserParams)
        ?: super.onShowFileChooser(webView, filePathCallback, fileChooserParams)
}
