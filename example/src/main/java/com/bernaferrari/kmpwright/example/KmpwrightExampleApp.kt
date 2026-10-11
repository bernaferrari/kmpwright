package com.bernaferrari.kmpwright.example

import android.app.Application

class KmpwrightExampleApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        android.webkit.WebView.setWebContentsDebuggingEnabled(true)
    }
}
