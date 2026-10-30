package com.bernaferrari.kmpwright.example

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ManageSearch
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bernaferrari.kmpwright.core.Browser
import com.bernaferrari.kmpwright.core.PlatformContext
import kotlinx.coroutines.launch

enum class Destination(val label: String, val icon: ImageVector) {
    Agent("Agent", Icons.Default.AutoAwesome),
    Recipes("Recipes", Icons.AutoMirrored.Filled.MenuBook),
    Inspect("Inspect", Icons.AutoMirrored.Filled.ManageSearch),
}

/**
 * Set when a native shell owns navigation (the iOS 26 Liquid Glass tab bar).
 * Screens then skip their own switcher and hand navigation events to it.
 */
class NativeNavigation(
    val selectDestination: (Destination) -> Unit,
)

internal val LocalNativeNavigation = staticCompositionLocalOf<NativeNavigation?> { null }

/** False while this screen is off-screen and another one is showing the shared WebView. */
internal val LocalWebViewActive = compositionLocalOf { true }

/** Lets panels ask the phone layout's sheet to open (e.g. when the composer gets focus). */
internal val LocalExpandPanel = staticCompositionLocalOf<() -> Unit> { {} }

/** Set where a panel runs edge to edge (iOS, under the tab bar): its top gap and the bars' bottom inset. */
internal val LocalPanelInsets = staticCompositionLocalOf<PaddingValues?> { null }

/** A panel's scroll padding. Edge to edge, the top gap scrolls with the content and the bottom clears the bars. */
@Composable
internal fun panelPadding(bottom: Dp): PaddingValues {
    val insets = LocalPanelInsets.current
    return PaddingValues(
        start = 16.dp,
        end = 16.dp,
        top = insets?.calculateTopPadding() ?: 0.dp,
        bottom = insets?.calculateBottomPadding() ?: bottom,
    )
}

/** The shared KMPwright sample: a browser you can drive like an agent. */
@Composable
fun KmpwrightExampleApp() {
    KmpwrightTheme {
        // WebView construction is expensive on a cold start. Commit one frame
        // first so the window is focused and responsive before it happens.
        var ready by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { withFrameNanos { }; ready = true }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainer) {
            if (ready) Studio() else Splash()
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Splash() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        LoadingIndicator(Modifier.size(72.dp))
    }
}

@Composable
private fun Studio() {
    val platformContext = rememberPlatformContext()
    val scope = rememberCoroutineScope()
    val state = remember(platformContext) {
        StudioState(Browser(platformContext), scope) { releaseWebViewKeyboard(platformContext) }
    }
    DisposableEffect(state) {
        onDispose {
            state.dispose()
            disposePlatformContext(platformContext)
        }
    }
    LaunchedEffect(state) { state.loadDemo() }

    var destination by rememberSaveable { mutableStateOf(Destination.Agent) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth >= 840.dp) {
            WideStudio(state, platformContext, destination, { destination = it })
        } else {
            PhoneStudio(state, platformContext, destination, { destination = it })
        }
    }
}

/** The panel for a destination, shared by the phone sheet, the tablet side panel and iOS native tabs. */
@Composable
internal fun DestinationPanel(
    destination: Destination,
    state: StudioState,
    modifier: Modifier = Modifier,
    onRunStarted: () -> Unit = {},
) {
    when (destination) {
        Destination.Agent -> AgentPanel(state, modifier)
        Destination.Recipes -> RecipesPanel(state, onRunStarted, modifier)
        Destination.Inspect -> InspectPanel(state, modifier)
    }
}

@Composable
private fun SwitchingPanel(destination: Destination, state: StudioState, onRunStarted: () -> Unit, modifier: Modifier = Modifier) {
    val enter = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val exit = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    AnimatedContent(
        targetState = destination,
        modifier = modifier,
        transitionSpec = { fadeIn(enter) togetherWith fadeOut(exit) },
        label = "panel",
    ) { current -> DestinationPanel(current, state, Modifier.fillMaxSize(), onRunStarted) }
}

@Composable
private fun DestinationSwitcher(destination: Destination, onSelect: (Destination) -> Unit, modifier: Modifier = Modifier) {
    ConnectedChoice(Destination.entries, destination, onSelect, { it.label }, modifier, icon = { it.icon })
}

/** Shows what a running recipe is doing, so it stays visible with the sheet collapsed. */
@Composable
private fun ActivityBanner(state: StudioState, modifier: Modifier = Modifier) {
    val activity = state.activity
    AnimatedVisibility(activity != null, modifier, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
        val shown = activity ?: return@AnimatedVisibility
        val failed = shown.done == false
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = if (failed) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = if (failed) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
        ) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                    when (shown.done) {
                        null -> LoadingIndicator(Modifier.size(32.dp), color = LocalContentColor.current)
                        true -> Icon(Icons.Default.CheckCircle, contentDescription = "Finished")
                        false -> Icon(Icons.Default.Error, contentDescription = "Failed")
                    }
                }
                Text(
                    shown.text,
                    modifier = Modifier.padding(start = 10.dp),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun refLongPress(state: StudioState, onAgent: () -> Unit): (RefBadge) -> Unit = { badge ->
    state.draft = if (badge.role in setOf("textbox", "searchbox", "combobox", "input")) "fill ${badge.ref} \"\"" else "click ${badge.ref}"
    onAgent()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PhoneStudio(
    state: StudioState,
    platformContext: PlatformContext,
    destination: Destination,
    onDestination: (Destination) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.PartiallyExpanded,
        enabledValues = setOf(SheetValue.PartiallyExpanded, SheetValue.Expanded),
    )
    val scaffoldState = rememberBottomSheetScaffoldState(bottomSheetState = sheetState)
    val navBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val peek = PEEK_HEIGHT + navBar
    val expand: () -> Unit = { scope.launch { sheetState.expand() } }
    val collapse: () -> Unit = { scope.launch { sheetState.partialExpand() } }

    CompositionLocalProvider(LocalExpandPanel provides expand) {
      Box(Modifier.fillMaxSize()) {
        BottomSheetScaffold(
            scaffoldState = scaffoldState,
            sheetPeekHeight = peek,
            sheetContainerColor = MaterialTheme.colorScheme.surface,
            sheetShadowElevation = 12.dp,
            sheetDragHandle = { BottomSheetDefaults.DragHandle(height = 4.dp) },
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            sheetContent = {
                Column(Modifier.fillMaxHeight(0.92f).windowInsetsPadding(WindowInsets.navigationBars)) {
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        ActivityBanner(state)
                        DestinationSwitcher(
                            destination,
                            onSelect = {
                                onDestination(it)
                                if (it != Destination.Agent) expand()
                            },
                            modifier = Modifier.padding(bottom = 12.dp),
                        )
                    }
                    SwitchingPanel(destination, state, onRunStarted = collapse, modifier = Modifier.weight(1f))
                }
            },
        ) {
            BrowserPane(
                state,
                platformContext,
                refLongPress(state) { onDestination(Destination.Agent); expand() },
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                    // Keep the whole page visible above the collapsed sheet.
                    .padding(bottom = peek - 8.dp),
            )
        }
        // Sheet content scrolls beneath the transparent gesture bar; keep that strip solid.
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(navBar)
                .background(MaterialTheme.colorScheme.surface)
        )
      }
    }
}

@Composable
private fun WideStudio(
    state: StudioState,
    platformContext: PlatformContext,
    destination: Destination,
    onDestination: (Destination) -> Unit,
) {
    BoxWithConstraints(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(vertical = 8.dp)
    ) {
        val panelWidth = if (maxWidth >= 1200.dp) 460.dp else 400.dp
        Row(Modifier.fillMaxSize()) {
            Surface(
                modifier = Modifier.width(panelWidth).fillMaxHeight().padding(start = 8.dp, end = 4.dp),
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surface,
            ) {
                Column(Modifier.padding(top = 16.dp)) {
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        ActivityBanner(state)
                        DestinationSwitcher(destination, onDestination, Modifier.padding(bottom = 12.dp))
                    }
                    SwitchingPanel(destination, state, onRunStarted = {}, modifier = Modifier.weight(1f))
                }
            }
            BrowserPane(
                state,
                platformContext,
                refLongPress(state) { onDestination(Destination.Agent) },
                Modifier.weight(1f).fillMaxHeight(),
            )
        }
    }
}

/**
 * Layout for screens hosted by a native shell (iOS 26 tabs): the browser on
 * top and one destination's panel below, without Compose navigation.
 */
@Composable
internal fun NativeHostedStudio(
    state: StudioState,
    platformContext: PlatformContext,
    onRefLongPress: () -> Unit,
    panel: @Composable (Modifier) -> Unit,
) {
    // A Surface rather than a background, so icons outside other surfaces get the theme's content colour.
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainer) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val longPress = refLongPress(state, onRefLongPress)
            if (maxWidth >= 840.dp) {
                Row(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(vertical = 8.dp)) {
                    Surface(
                        modifier = Modifier.width(420.dp).fillMaxHeight().padding(horizontal = 8.dp),
                        shape = MaterialTheme.shapes.extraLarge,
                        color = MaterialTheme.colorScheme.surface,
                    ) { Column(Modifier.padding(top = 16.dp)) { ActivityBanner(state, Modifier.padding(horizontal = 16.dp)); panel(Modifier.weight(1f)) } }
                    BrowserPane(state, platformContext, longPress, Modifier.weight(1f).fillMaxHeight())
                }
            } else {
                // Edge to edge: the panel runs under the tab bar and scrolls its content clear of it.
                val bottomInset = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom).asPaddingValues().calculateBottomPadding()
                var height by remember { mutableIntStateOf(1) }
                Column(
                    Modifier.fillMaxSize()
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                        .onSizeChanged { height = it.height.coerceAtLeast(1) }
                ) {
                    BrowserPane(state, platformContext, longPress, Modifier.fillMaxWidth().weight(state.pageSplit))
                    SplitGrabber(onDrag = { delta -> state.pageSplit = (state.pageSplit + delta / height).coerceIn(0.2f, 0.8f) })
                    Surface(
                        modifier = Modifier.fillMaxWidth().weight(1f - state.pageSplit),
                        shape = MaterialTheme.shapes.extraLarge.copy(bottomStart = CornerSize(0.dp), bottomEnd = CornerSize(0.dp)),
                        color = MaterialTheme.colorScheme.surface,
                    ) {
                        CompositionLocalProvider(LocalPanelInsets provides PaddingValues(top = 16.dp, bottom = bottomInset)) {
                            Column(verticalArrangement = Arrangement.Top) {
                                ActivityBanner(state, Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp))
                                panel(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A split-screen divider: drag the pill to give the page or the panel more room. It perks up while held. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SplitGrabber(onDrag: (Float) -> Unit) {
    val haptics = LocalHapticFeedback.current
    var held by remember { mutableStateOf(false) }
    val spatial = MaterialTheme.motionScheme.fastSpatialSpec<Dp>()
    val width by animateDpAsState(if (held) 56.dp else 36.dp, spatial)
    val thickness by animateDpAsState(if (held) 6.dp else 4.dp, spatial)
    val color by animateColorAsState(
        if (held) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        MaterialTheme.motionScheme.fastEffectsSpec(),
    )
    Box(
        Modifier.fillMaxWidth().height(20.dp)
            .draggable(
                rememberDraggableState(onDrag),
                Orientation.Vertical,
                startDragImmediately = true,
                onDragStarted = { held = true; haptics.performHapticFeedback(HapticFeedbackType.SegmentTick) },
                onDragStopped = { held = false },
            )
            .semantics { contentDescription = "Resize page and panel" },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(width = width, height = thickness).clip(CircleShape).background(color))
    }
}

/** Switcher (≈48dp) + composer and suggestions (≈118dp) + handle and spacing. */
private val PEEK_HEIGHT = 220.dp
