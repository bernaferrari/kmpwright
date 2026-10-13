package com.bernaferrari.kmpwright.example

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Numbers
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bernaferrari.kmpwright.core.PlatformContext
import kotlin.math.roundToInt

/** Address bar plus the live page, with snapshot refs drawn on top of it. */
@Composable
internal fun BrowserPane(
    state: StudioState,
    platformContext: PlatformContext,
    onRefLongPress: (RefBadge) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        AddressBar(state, Modifier.padding(start = 4.dp, end = 8.dp, top = 4.dp, bottom = 8.dp))
        Box(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp)
                .clip(MaterialTheme.shapes.extraLarge)
                .background(MaterialTheme.colorScheme.surfaceContainerLowest)
        ) {
            WebViewHost(platformContext, Modifier.fillMaxSize())
            RefOverlay(state, platformContext, onRefLongPress, Modifier.fillMaxSize())
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AddressBar(state: StudioState, modifier: Modifier = Modifier) {
    val url by state.page.url.collectAsState()
    val title by state.page.title.collectAsState()
    val address = rememberTextFieldState()
    var focused by remember { mutableStateOf(false) }
    // Follow the page unless the user is typing.
    LaunchedEffect(url, title, focused) {
        if (!focused) address.setTextAndPlaceCursorAtEnd(displayUrl(url, title))
    }
    val focusManager = LocalFocusManager.current

    fun go() {
        val target = address.text.toString().trim()
        if (target.isNotEmpty() && target != displayUrl(url, title)) state.run("open $target")
        focusManager.clearFocus()
    }

    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { state.run("back") }, shapes = IconButtonDefaults.shapes()) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
        }
        Surface(
            modifier = Modifier.weight(1f).height(52.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
        ) {
            Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                val secure = url?.startsWith("https://") == true
                Icon(
                    if (secure) Icons.Default.Lock else Icons.Default.Public,
                    contentDescription = if (secure) "Secure connection" else null,
                    modifier = Modifier.size(18.dp),
                    tint = if (secure) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                BasicTextField(
                    state = address,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp)
                        .onFocusChanged { focused = it.isFocused }
                        .semantics { contentDescription = "Address" },
                    lineLimits = TextFieldLineLimits.SingleLine,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    onKeyboardAction = { go() },
                    decorator = { field ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (address.text.isEmpty()) {
                                Text(
                                    "Search or type a URL",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            field()
                        }
                    },
                )
                AnimatedContent(
                    targetState = state.busy,
                    transitionSpec = { (fadeIn() + scaleIn(initialScale = 0.6f)) togetherWith fadeOut() },
                    label = "loading",
                ) { busy ->
                    if (busy) {
                        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                            LoadingIndicator(Modifier.size(32.dp))
                        }
                    } else {
                        IconButton(onClick = { state.run("reload") }, shapes = IconButtonDefaults.shapes()) {
                            Icon(Icons.Default.Refresh, contentDescription = "Reload")
                        }
                    }
                }
            }
        }
        FilledTonalIconToggleButton(
            checked = state.showRefs,
            onCheckedChange = { show ->
                state.showRefs = show
                if (show) state.refreshRefs() else state.clearRefs()
            },
            shapes = IconButtonDefaults.toggleableShapes(),
            modifier = Modifier.padding(start = 6.dp),
        ) {
            Icon(Icons.Default.Numbers, contentDescription = if (state.showRefs) "Hide element refs" else "Show element refs")
        }
    }
}

/** Hosts read better than full URLs; local demo pages show their title instead. */
private fun displayUrl(url: String?, title: String?): String = when {
    url == null || url == "about:blank" || url.startsWith("data:") -> title.orEmpty()
    else -> url.removePrefix("https://").removePrefix("www.").removeSuffix("/")
}

/**
 * Outlines every snapshot ref on top of the live page with a tappable `e3`
 * label. Positions come from document coordinates plus the WebView's native
 * scroll, read every frame, so labels stay glued to elements while scrolling.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RefOverlay(
    state: StudioState,
    platformContext: PlatformContext,
    onLongPress: (RefBadge) -> Unit,
    modifier: Modifier,
) {
    val refs = state.refs
    val visible = state.showRefs && refs.isNotEmpty() && state.viewportWidth > 0

    LaunchedEffect(visible, platformContext) {
        if (!visible) return@LaunchedEffect
        while (true) {
            withFrameNanos { }
            val native = readNativeScroll(platformContext) ?: continue
            val cssPerNative = (state.viewportWidth / native.viewWidth).toFloat()
            state.scroll = Offset(native.x * cssPerNative, native.y * cssPerNative)
        }
    }

    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()),
        exit = fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            // dp per CSS pixel: the page's layout viewport spans the whole view.
            val scale = maxWidth.value / state.viewportWidth.toFloat()
            val outline = MaterialTheme.colorScheme.tertiary
            Canvas(Modifier.fillMaxSize()) {
                val px = scale * density
                val scroll = state.scroll
                refs.forEach { badge ->
                    drawRoundRect(
                        color = outline.copy(alpha = 0.85f),
                        topLeft = Offset((badge.box.x.toFloat() - scroll.x) * px, (badge.box.y.toFloat() - scroll.y) * px),
                        size = Size(badge.box.width.toFloat() * px, badge.box.height.toFloat() * px),
                        cornerRadius = CornerRadius(8.dp.toPx()),
                        style = Stroke(width = 1.5.dp.toPx()),
                    )
                }
            }
            refs.forEach { badge ->
                Surface(
                    modifier = Modifier
                        .layout { measurable, constraints ->
                            // Pin to the element's top-right corner, straddling the border,
                            // so the label never hides the element's leading text.
                            val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                            layout(placeable.width, placeable.height) {
                                val scroll = state.scroll
                                val px = scale * density
                                val right = (badge.box.x + badge.box.width).toFloat() - scroll.x
                                placeable.place(
                                    ((right * px) - placeable.width + 6.dp.toPx()).roundToInt(),
                                    (((badge.box.y.toFloat() - scroll.y) * px) - placeable.height / 2f).roundToInt(),
                                )
                            }
                        }
                        .clip(RoundedCornerShape(10.dp))
                        .combinedClickable(
                            onClickLabel = "Click ${badge.ref}",
                            onLongClickLabel = "Write a command for ${badge.ref}",
                            onLongClick = { onLongPress(badge) },
                            onClick = { state.run("click ${badge.ref}") },
                        )
                        .semantics { contentDescription = "${badge.ref}, ${badge.role} ${badge.name}" },
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.tertiary,
                    contentColor = MaterialTheme.colorScheme.onTertiary,
                    shadowElevation = 3.dp,
                ) {
                    Text(
                        badge.ref.removePrefix("@"),
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
