package com.bernaferrari.kmpwright.example

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.uikit.OnFocusBehavior
import androidx.compose.ui.window.ComposeUIViewController
import com.bernaferrari.kmpwright.core.Browser
import com.bernaferrari.kmpwright.core.IOSPlatformContext
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import platform.Foundation.NSThread
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIViewController
import platform.WebKit.WKWebView

/** iOS < 26: the whole sample, navigation included, is drawn by Compose. */
fun createViewController(): UIViewController = ComposeUIViewController { KmpwrightExampleApp() }

/**
 * iOS 26+: SwiftUI owns a Liquid Glass `TabView`, and each tab is a Compose controller
 * sharing one browser session. Recipes open inside their panel, so the page never moves.
 */
@OptIn(ExperimentalForeignApi::class)
class NativeStudio(
    private val onSelectTab: (String) -> Unit,
) {
    private val platformContext = IOSPlatformContext(WKWebView())
    private val scope = MainScope()
    private var closed = false
    private val state = StudioState(Browser(platformContext), scope) { releaseWebViewKeyboard(platformContext) }
        .also { it.loadDemo() }

    fun close() {
        if (!NSThread.isMainThread) {
            NSOperationQueue.mainQueue.addOperationWithBlock { close() }
            return
        }
        if (closed) return
        closed = true
        scope.cancel()
        state.dispose()
        disposePlatformContext(platformContext)
    }

    /** The screen currently on top; only it shows the shared web view. */
    private var activeScreen by mutableStateOf("Agent")

    private val navigation = NativeNavigation(selectDestination = { onSelectTab(it.name) })

    /** Call when a tab appears, using the same key passed to the factory. */
    fun screenDidAppear(key: String) {
        activeScreen = key
    }

    /** Root controller for a tab: "Agent", "Recipes" or "Inspect". */
    fun tabViewController(tab: String): UIViewController {
        val destination = Destination.valueOf(tab)
        return screen(tab) { DestinationPanel(destination, state, it) }
    }

    private fun screen(key: String, panel: @androidx.compose.runtime.Composable (Modifier) -> Unit): UIViewController =
        ComposeUIViewController(configure = { onFocusBehavior = OnFocusBehavior.DoNothing }) {
            KmpwrightTheme {
                CompositionLocalProvider(
                    LocalNativeNavigation provides navigation,
                    LocalWebViewActive provides (activeScreen == key),
                ) {
                    NativeHostedStudio(
                        state = state,
                        platformContext = platformContext,
                        onRefLongPress = { onSelectTab(Destination.Agent.name) },
                        panel = panel,
                    )
                }
            }
        }
}
