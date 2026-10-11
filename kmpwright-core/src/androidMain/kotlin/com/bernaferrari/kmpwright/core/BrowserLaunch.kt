package com.bernaferrari.kmpwright.core

actual suspend fun launchBrowser(options: BrowserLaunchOptions, config: KmpwrightConfig): LaunchedBrowser =
    throw UnsupportedOperationException("Desktop browser process launch requires the JVM backend")
