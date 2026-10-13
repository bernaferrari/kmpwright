package com.bernaferrari.kmpwright.example

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.bernaferrari.kmpwright.core.PlatformContext

/** Creates the platform WebView the sample automates; lives as long as the composition. */
@Composable
internal expect fun rememberPlatformContext(): PlatformContext

internal expect fun disposePlatformContext(context: PlatformContext)

/** Shows the WebView owned by [platformContext]. */
@Composable
internal expect fun WebViewHost(platformContext: PlatformContext, modifier: Modifier = Modifier)

/** The WebView's native scroll offset and width, in the same (native) units. */
internal class NativeScroll(val x: Float, val y: Float, val viewWidth: Float)

/** Reads the WebView's scroll without touching JavaScript; cheap enough to call every frame. */
internal expect fun readNativeScroll(platformContext: PlatformContext): NativeScroll?

/**
 * Hides the soft keyboard if the *page* has it (automation focused an input), without touching
 * other text fields such as the agent composer.
 */
internal expect fun releaseWebViewKeyboard(platformContext: PlatformContext)
