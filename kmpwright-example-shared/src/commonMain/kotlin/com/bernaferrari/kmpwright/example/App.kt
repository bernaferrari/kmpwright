package com.bernaferrari.kmpwright.example.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SecondaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bernaferrari.kmpwright.core.PlatformContext
import com.bernaferrari.kmpwright.example.shared.screens.CookiesDemoScreen
import com.bernaferrari.kmpwright.example.shared.screens.HomeScreen
import com.bernaferrari.kmpwright.example.shared.screens.KeyboardDemoScreen
import com.bernaferrari.kmpwright.example.shared.screens.MouseDemoScreen
import com.bernaferrari.kmpwright.example.shared.screens.NavigationDemoScreen
import com.bernaferrari.kmpwright.example.shared.screens.ScreenshotDemoScreen
import com.bernaferrari.kmpwright.example.shared.screens.SelectorDemoScreen
import com.bernaferrari.kmpwright.example.shared.screens.TouchDemoScreen
import kotlinx.coroutines.launch

private val ExpressiveLightColors = lightColorScheme(
    primary = Color(0xFF5753C6),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE4E0FF),
    onPrimaryContainer = Color(0xFF17105B),
    secondary = Color(0xFF76536F),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFD7F3),
    onSecondaryContainer = Color(0xFF2D1229),
    tertiary = Color(0xFF006B5F),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFF9EF2E0),
    onTertiaryContainer = Color(0xFF00201B),
    background = Color(0xFFF9F7FF),
    surface = Color(0xFFF9F7FF),
    surfaceVariant = Color(0xFFE6E1EC),
    outlineVariant = Color(0xFFCAC4D0)
)

private val ExpressiveDarkColors = darkColorScheme(
    primary = Color(0xFFC5C0FF),
    onPrimary = Color(0xFF282175),
    primaryContainer = Color(0xFF403A9A),
    onPrimaryContainer = Color(0xFFE4E0FF),
    secondary = Color(0xFFE5BAD9),
    secondaryContainer = Color(0xFF5C3C56),
    tertiary = Color(0xFF82D5C4),
    tertiaryContainer = Color(0xFF005047),
    background = Color(0xFF131218),
    surface = Color(0xFF131218),
    surfaceVariant = Color(0xFF49454F),
    outlineVariant = Color(0xFF49454F)
)

private sealed class DemoPage(
    val title: String,
    val icon: ImageVector,
    val content: @Composable (Modifier) -> Unit
) {
    data object Home : DemoPage("Start", Icons.Default.Home, { HomeScreen(it) })
    data object Selectors : DemoPage("Selectors", Icons.Default.Search, { SelectorDemoScreen(it) })
    data object Touch : DemoPage("Touch", Icons.Default.TouchApp, { TouchDemoScreen(it) })
    data object Mouse : DemoPage("Mouse", Icons.Default.Mouse, { MouseDemoScreen(it) })
    data object Keyboard : DemoPage("Keyboard", Icons.Default.Keyboard, { KeyboardDemoScreen(it) })
    data object Cookies : DemoPage("Cookies", Icons.Default.Code, { CookiesDemoScreen(it) })
    data object Screenshot : DemoPage("Capture", Icons.Default.Visibility, { ScreenshotDemoScreen(it) })
}

private data class AppSection(val label: String, val icon: ImageVector, val pages: List<DemoPage>)

private val sections = listOf(
    AppSection("Start", Icons.Default.Home, listOf(DemoPage.Home)),
    AppSection("Find", Icons.Default.Search, listOf(DemoPage.Selectors)),
    AppSection("Input", Icons.Default.TouchApp, listOf(DemoPage.Touch, DemoPage.Mouse, DemoPage.Keyboard)),
    AppSection("Tools", Icons.Default.Build, listOf(DemoPage.Cookies, DemoPage.Screenshot))
)

internal data class CompactDockSelection(
    val section: Int,
    val previewOpen: Boolean
)

internal fun selectCompactDockSection(
    currentSection: Int,
    selectedSection: Int,
    previewOpen: Boolean
): CompactDockSelection = CompactDockSelection(
    section = selectedSection,
    previewOpen = selectedSection != currentSection || !previewOpen
)

internal fun explainNavigationFailure(
    action: String,
    suggestion: String,
    error: Throwable
): String {
    val detail = error.message?.trim()?.takeIf(String::isNotEmpty)
    return buildString {
        append("Couldn’t ")
        append(action)
        append(". ")
        append(suggestion)
        if (detail != null) {
            append(" Details: ")
            append(detail)
        }
    }
}

@Composable
fun KmpwrightExampleApp(darkTheme: Boolean = false) {
    var browserReady by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        // WebView startup can be expensive on a cold device. Commit a focused,
        // responsive app window before constructing the platform browser so an
        // early key/touch event cannot trigger an unfocused-window ANR.
        withFrameNanos { }
        browserReady = true
    }

    if (!browserReady) {
        MaterialTheme(colorScheme = if (darkTheme) ExpressiveDarkColors else ExpressiveLightColors) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Preparing browser…",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }
        }
        return
    }

    KmpwrightWorkbench(darkTheme)
}

@Composable
private fun KmpwrightWorkbench(darkTheme: Boolean) {
    var selectedSection by remember { mutableIntStateOf(0) }
    var selectedInputPage by remember { mutableIntStateOf(0) }
    var selectedBrowserPage by remember { mutableIntStateOf(0) }
    var compactPreviewOpen by remember { mutableStateOf(false) }
    val platformContext = rememberPlatformContext()
    val browser = rememberBrowser(platformContext)
    val pageManager = remember(browser) { PageLifecycleManager(browser) }
    DisposableEffect(pageManager, platformContext) {
        onDispose {
            pageManager.close()
            disposePlatformContext(platformContext)
        }
    }
    val currentUrl by pageManager.page.url.collectAsState(null)
    val currentTitle by pageManager.page.title.collectAsState(null)

    val section = sections[selectedSection]
    val selectedPageIndex = when (selectedSection) {
        2 -> selectedInputPage.coerceIn(section.pages.indices)
        3 -> selectedBrowserPage.coerceIn(section.pages.indices)
        else -> 0
    }
    val selectedPage = section.pages[selectedPageIndex]
    val onSectionSelected: (Int) -> Unit = { index ->
        selectedSection = index
    }
    val onPageSelected: (Int) -> Unit = { index ->
        if (selectedSection == 2) selectedInputPage = index
        if (selectedSection == 3) selectedBrowserPage = index
    }

    CompositionLocalProvider(LocalBrowser provides browser, LocalPageManager provides pageManager) {
        MaterialTheme(colorScheme = if (darkTheme) ExpressiveDarkColors else ExpressiveLightColors) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                if (maxWidth >= 840.dp) {
                    WideWorkbench(
                        selectedSection = selectedSection,
                        section = section,
                        selectedPageIndex = selectedPageIndex,
                        selectedPage = selectedPage,
                        currentUrl = currentUrl,
                        currentTitle = currentTitle,
                        onSectionSelected = onSectionSelected,
                        onPageSelected = onPageSelected,
                        platformContext = platformContext
                    )
                } else {
                    CompactWorkbench(
                        selectedSection = selectedSection,
                        section = section,
                        selectedPageIndex = selectedPageIndex,
                        selectedPage = selectedPage,
                        previewOpen = compactPreviewOpen,
                        currentUrl = currentUrl,
                        currentTitle = currentTitle,
                        onPageSelected = onPageSelected,
                        onDockSelected = { index ->
                            val selection = selectCompactDockSection(
                                currentSection = selectedSection,
                                selectedSection = index,
                                previewOpen = compactPreviewOpen
                            )
                            selectedSection = selection.section
                            compactPreviewOpen = selection.previewOpen
                        },
                        onPreviewDismiss = { compactPreviewOpen = false },
                        platformContext = platformContext
                    )
                }
            }
        }
    }
}

@Composable
private fun WideWorkbench(
    selectedSection: Int,
    section: AppSection,
    selectedPageIndex: Int,
    selectedPage: DemoPage,
    currentUrl: String?,
    currentTitle: String?,
    onSectionSelected: (Int) -> Unit,
    onPageSelected: (Int) -> Unit,
    platformContext: PlatformContext
) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        NavigationRail(
            modifier = Modifier.fillMaxHeight(),
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Surface(
                modifier = Modifier.padding(vertical = 18.dp).size(54.dp),
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = RoundedCornerShape(18.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Language, contentDescription = "Kmpwright")
                }
            }
            Spacer(Modifier.height(10.dp))
            sections.forEachIndexed { index, item ->
                NavigationRailItem(
                    selected = selectedSection == index,
                    onClick = { onSectionSelected(index) },
                    icon = { Icon(item.icon, contentDescription = null) },
                    label = { Text(item.label) }
                )
            }
        }

        Surface(
            modifier = Modifier.width(390.dp).fillMaxHeight(),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f),
            shape = RoundedCornerShape(topEnd = 32.dp, bottomEnd = 32.dp)
        ) {
            Column {
                PageTabs(section, selectedPageIndex, onPageSelected)
                Box(Modifier.weight(1f)) {
                    key(LocalPageManager.current.page) { selectedPage.content(Modifier.fillMaxSize()) }
                }
            }
        }

        BrowserCanvas(
            modifier = Modifier.weight(1f).fillMaxHeight().padding(start = 12.dp, end = 12.dp),
            expanded = true,
            edgeToEdge = false,
            currentUrl = currentUrl,
            currentTitle = currentTitle,
            platformContext = platformContext
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CompactWorkbench(
    selectedSection: Int,
    section: AppSection,
    selectedPageIndex: Int,
    selectedPage: DemoPage,
    previewOpen: Boolean,
    currentUrl: String?,
    currentTitle: String?,
    onPageSelected: (Int) -> Unit,
    onDockSelected: (Int) -> Unit,
    onPreviewDismiss: () -> Unit,
    platformContext: PlatformContext
) {
    Box(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        BrowserCanvas(
            modifier = Modifier.fillMaxSize(),
            expanded = true,
            edgeToEdge = true,
            currentUrl = currentUrl,
            currentTitle = currentTitle,
            platformContext = platformContext
        )

        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 8.dp, vertical = 12.dp)
                .widthIn(max = 344.dp)
                .fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(30.dp),
            tonalElevation = 6.dp,
            shadowElevation = 5.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                sections.forEachIndexed { index, item ->
                    CompactDockItem(
                        selected = selectedSection == index,
                        label = item.label,
                        icon = item.icon,
                        onClick = {
                            onDockSelected(index)
                        }
                    )
                }
            }
        }

        if (previewOpen) {
            ModalBottomSheet(
                onDismissRequest = onPreviewDismiss,
                containerColor = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp)
            ) {
                Column(Modifier.fillMaxHeight(0.82f)) {
                    PageTabs(section, selectedPageIndex, onPageSelected)
                    Box(Modifier.weight(1f)) {
                        key(LocalPageManager.current.page) { selectedPage.content(Modifier.fillMaxSize()) }
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactDockItem(
    selected: Boolean,
    label: String,
    icon: ImageVector,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .width(72.dp)
            .heightIn(min = 58.dp)
            .semantics { this.selected = selected }
            .clickable(role = Role.Tab, onClick = onClick),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        contentColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        shape = RoundedCornerShape(22.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun PageTabs(section: AppSection, selectedIndex: Int, onSelected: (Int) -> Unit) {
    if (section.pages.size <= 1) return
    SecondaryScrollableTabRow(
        selectedTabIndex = selectedIndex,
        modifier = Modifier.fillMaxWidth(),
        edgePadding = 12.dp
    ) {
        section.pages.forEachIndexed { index, page ->
            Tab(
                selected = selectedIndex == index,
                onClick = { onSelected(index) },
                modifier = Modifier.heightIn(min = 48.dp),
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(page.icon, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(page.title, fontWeight = FontWeight.Medium)
                    }
                }
            )
        }
    }
}

@Composable
private fun BrowserCanvas(
    modifier: Modifier,
    expanded: Boolean,
    edgeToEdge: Boolean,
    currentUrl: String?,
    currentTitle: String?,
    platformContext: PlatformContext
) {
    if (!expanded) {
        KmpwrightWebViewHost(modifier = modifier, platformContext = platformContext)
        return
    }

    val page = LocalPageManager.current.page
    val scope = rememberCoroutineScope()
    var address by remember { mutableStateOf(currentUrl?.takeIf { it.startsWith("http") } ?: "https://example.com") }
    var navigating by remember { mutableStateOf(false) }
    var navigationError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(currentUrl) {
        currentUrl?.takeIf { it.startsWith("http") }?.let { address = it }
    }
    fun runNavigation(
        action: String,
        suggestion: String,
        navigate: suspend () -> Unit
    ) {
        if (navigating) return
        navigating = true
        navigationError = null
        scope.launch {
            try { navigate() }
            catch (error: Exception) {
                navigationError = explainNavigationFailure(
                    action = action,
                    suggestion = suggestion,
                    error = error
                )
            }
            finally { navigating = false }
        }
    }
    fun navigate() {
        if (address.isBlank()) return
        runNavigation(
            action = "open $address",
            suggestion = "Check the address and network connection, then try again."
        ) { page.goto(address) }
    }

    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface,
        shape = if (edgeToEdge) RectangleShape else RoundedCornerShape(30.dp),
        tonalElevation = if (edgeToEdge) 0.dp else 2.dp,
        shadowElevation = if (edgeToEdge) 0.dp else 1.dp
    ) {
        BoxWithConstraints {
        val wideChrome = !edgeToEdge
        Column(Modifier.padding(if (edgeToEdge) 0.dp else 10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                IconButton(
                    onClick = {
                        runNavigation(
                            action = "go back",
                            suggestion = "There may not be an earlier page in this tab’s history."
                        ) { page.back() }
                    },
                    enabled = !navigating
                ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                if (wideChrome) {
                    IconButton(
                        onClick = {
                            runNavigation(
                                action = "reload the page",
                                suggestion = "Check the network connection, then try again."
                            ) { page.reload() }
                        },
                        enabled = !navigating
                    ) { Icon(Icons.Default.Refresh, "Reload") }
                    IconButton(
                        onClick = {
                            runNavigation(
                                action = "go forward",
                                suggestion = "There may not be a later page in this tab’s history."
                            ) { page.forward() }
                        },
                        enabled = !navigating
                    ) { Icon(Icons.AutoMirrored.Filled.ArrowForward, "Forward") }
                }
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it; navigationError = null },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Enter a URL") },
                    leadingIcon = { Box(Modifier.size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.tertiary)) },
                    isError = navigationError != null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { navigate() }),
                    singleLine = true,
                    shape = RoundedCornerShape(18.dp)
                )
                FilledIconButton(onClick = { navigate() }, enabled = !navigating && address.isNotBlank()) {
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, "Open URL")
                }
            }
            navigationError?.let {
                DemoStatus(
                    message = it,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                    kind = DemoStatusKind.Error
                )
            }
            Box(
                Modifier.fillMaxSize().clip(if (edgeToEdge) RectangleShape else RoundedCornerShape(22.dp))
                    .background(Color.White)
            ) {
                KmpwrightWebViewHost(Modifier.fillMaxSize(), platformContext)
                if (currentUrl.isNullOrBlank() && currentTitle.isNullOrBlank()) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Surface(
                                modifier = Modifier.size(72.dp),
                                color = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                shape = RoundedCornerShape(24.dp)
                            ) { Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.Language, null, Modifier.size(34.dp)) } }
                            Text("Browser canvas", Modifier.padding(top = 14.dp), style = MaterialTheme.typography.titleMedium)
                            Text(
                                if (wideChrome) "Run a control from the side panel" else "Open a tool from the dock below",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
        }
    }
}
