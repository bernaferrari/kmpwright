package com.bernaferrari.kmpwright.core

import kotlin.coroutines.cancellation.CancellationException

@Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
public actual suspend fun launchBrowser(options: BrowserLaunchOptions, config: KmpwrightConfig): LaunchedBrowser =
    throw KmpwrightUnsupportedException("Desktop browser process launch requires the JVM backend")
