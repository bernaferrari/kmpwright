package com.bernaferrari.kmpwright.core

import kotlin.coroutines.cancellation.CancellationException

@Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
internal actual suspend fun launchPlatformBrowser(options: BrowserLaunchOptions, config: KmpwrightConfig): LaunchedBrowser =
    throw KmpwrightUnsupportedException("Desktop browser process launch requires the JVM backend")
