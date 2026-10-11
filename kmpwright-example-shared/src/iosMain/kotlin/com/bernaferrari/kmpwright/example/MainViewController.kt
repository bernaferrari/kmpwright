package com.bernaferrari.kmpwright.example.shared

import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

/** Swift entry point for the shared Compose example application. */
fun createViewController(): UIViewController = ComposeUIViewController {
    KmpwrightExampleApp()
}
