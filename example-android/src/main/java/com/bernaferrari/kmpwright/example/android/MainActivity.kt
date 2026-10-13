package com.bernaferrari.kmpwright.example.android

import android.os.Bundle
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.bernaferrari.kmpwright.example.KmpwrightExampleApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Lets chrome://inspect attach to the sample's WebView for debugging.
        WebView.setWebContentsDebuggingEnabled(true)
        enableEdgeToEdge()
        setContent { KmpwrightExampleApp() }
    }
}
