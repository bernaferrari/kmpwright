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
import platform.UIKit.UIViewController
import platform.WebKit.WKWebView

/** iOS < 26: the whole sample, navigation included, is drawn by Compose. */
fun createViewController(): UIViewController = ComposeUIViewController { KmpwrightExampleApp() }

/**
 * iOS 26+: SwiftUI owns a Liquid Glass `TabView` and `NavigationStack`s, and
 * each screen below is a Compose controller sharing one browser session.
 */
@OptIn(ExperimentalForeignApi::class)
class NativeStudio(
    private val onSelectTab: (String) -> Unit,
    private val onOpenRecipe: (id: String, title: String) -> Unit,
) {
    private val platformContext = IOSPlatformContext(WKWebView())
    private val state = StudioState(Browser(platformContext), MainScope()) { releaseWebViewKeyboard(platformContext) }
        .also { it.loadDemo() }

    /** The screen currently on top; only it shows the shared web view. */
    private var activeScreen by mutableStateOf("Agent")

    private val navigation = NativeNavigation(
        selectDestination = { onSelectTab(it.name) },
        openRecipe = { onOpenRecipe(it.id, it.title) },
    )

    /** Call when a tab or pushed screen appears, using the same key passed to the factory. */
    fun screenDidAppear(key: String) {
        activeScreen = key
    }

    /** Root controller for a tab: "Agent", "Recipes" or "Inspect". */
    fun tabViewController(tab: String): UIViewController {
        val destination = Destination.valueOf(tab)
        return screen(tab) { DestinationPanel(destination, state, it) }
    }

    /** Pushed controller for one recipe. */
    fun recipeViewController(id: String): UIViewController {
        val recipe = recipes.first { it.id == id }
        return screen("recipe:$id") { RecipeDetailPanel(state, recipe, it) }
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
