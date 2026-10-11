package com.bernaferrari.kmpwright.test

import android.app.Activity
import android.os.Bundle
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.view.WindowInsets

/** Visible, measured WebView used by instrumentation rather than an unattached view. */
class WebViewHarnessActivity : Activity() {
    lateinit var webView: WebView
        private set

    private lateinit var container: FrameLayout
    private lateinit var progress: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        progress = TextView(this).apply {
            text = "Preparing tests…"
            textSize = 14f
            val padding = (12 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }
        container = FrameLayout(this)
        layout.addView(progress, LinearLayout.LayoutParams(-1, -2))
        layout.addView(container, LinearLayout.LayoutParams(-1, 0, 1f))
        layout.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(layout)
    }

    fun showProgress(current: Int, total: Int, name: String) {
        progress.text = "$current / $total · $name"
    }

    /** Reset test-owned browser state while retaining the visible activity. */
    fun resetWebView(): WebView {
        container.removeAllViews()
        if (this::webView.isInitialized) webView.destroy()
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
        }
        container.addView(webView, FrameLayout.LayoutParams(-1, -1))
        return webView
    }

    override fun onDestroy() {
        if (this::webView.isInitialized) webView.destroy()
        super.onDestroy()
    }
}
