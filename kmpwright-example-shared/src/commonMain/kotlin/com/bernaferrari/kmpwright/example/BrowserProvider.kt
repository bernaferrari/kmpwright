package com.bernaferrari.kmpwright.example.shared

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.remember
import com.bernaferrari.kmpwright.core.Browser
import com.bernaferrari.kmpwright.core.PlatformContext
import com.bernaferrari.kmpwright.core.KmpwrightConfig

@Composable
fun rememberBrowser(
    platformContext: PlatformContext? = null,
    config: KmpwrightConfig = KmpwrightConfig()
): Browser {
    val ctx = platformContext ?: rememberPlatformContext()
    return remember(ctx) {
        Browser(ctx, config)
    }
}

@Composable
expect fun rememberPlatformContext(): PlatformContext

expect fun disposePlatformContext(context: PlatformContext)

val LocalBrowser = staticCompositionLocalOf<Browser> {
    error("LocalBrowser not provided")
}
