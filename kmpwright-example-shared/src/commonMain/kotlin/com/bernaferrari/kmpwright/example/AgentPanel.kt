package com.bernaferrari.kmpwright.example

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TextButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val suggestions = listOf("snapshot -i", "screenshot", "get title", "scroll down", "back", "help")

/** The agent console: agent-browser commands in, page results out. */
@Composable
internal fun AgentPanel(state: StudioState, modifier: Modifier = Modifier) {
    LazyColumn(
        modifier = modifier,
        contentPadding = panelPadding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "composer") { Composer(state) }
        if (state.console.isEmpty()) {
            welcome(state)
        } else {
            items(state.console, key = { it.id }) { entry ->
                ConsoleCard(entry, onRef = { ref -> state.draft = "click $ref" }, modifier = Modifier.animateItem())
            }
            item(key = "clear") {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    TextButton(onClick = { state.console.clear() }) { Text("Clear history") }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Composer(state: StudioState) {
    val expandPanel = LocalExpandPanel.current
    fun submit() {
        state.run(state.draft)
        state.draft = ""
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextField(
                value = state.draft,
                onValueChange = { state.draft = it },
                // On phones, open the sheet so the keyboard can't cover the field.
                modifier = Modifier.weight(1f).onFocusChanged { if (it.isFocused) expandPanel() },
                placeholder = {
                    Text("snapshot -i", fontFamily = FontFamily.Monospace, maxLines = 1)
                },
                prefix = {
                    Text("› ", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                shape = CircleShape,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Send,
                ),
                keyboardActions = KeyboardActions(onSend = { submit() }),
            )
            FilledIconButton(
                onClick = ::submit,
                enabled = state.draft.isNotBlank(),
                shapes = IconButtonDefaults.shapes(),
                modifier = Modifier.size(56.dp),
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Run command")
            }
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            suggestions.forEach { suggestion ->
                SuggestionChip(
                    onClick = { state.run(suggestion) },
                    label = { Text(suggestion, fontFamily = FontFamily.Monospace, fontSize = 13.sp) },
                    shape = CircleShape,
                )
            }
        }
    }
}

private class Starter(val title: String, val body: String, val command: String, val icon: ImageVector, val shape: Shape)

private fun LazyListScope.welcome(state: StudioState) {
    item(key = "hero") {
        Column(
            Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "Drive the page like an agent",
                style = MaterialTheme.typography.headlineSmallEmphasized,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                "Every highlighted element on the page has a ref. Tap one to click it, long-press to write a command.",
                modifier = Modifier.padding(top = 6.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    item(key = "starters") {
        val starters = listOf(
            Starter("Read the page", "List every interactive element with its ref.", "snapshot -i", Icons.Default.AutoAwesome, MaterialShapes.Cookie9Sided.toShape()),
            Starter("Act on a ref", "Fill the search box, then press Enter.", "fill @e3 \"snapshot\"", Icons.Default.TouchApp, MaterialShapes.Clover4Leaf.toShape()),
            Starter("See the result", "Capture the page with ref labels.", "screenshot --annotate", Icons.Default.PhotoCamera, MaterialShapes.Sunny.toShape()),
            Starter("Go somewhere real", "Open Wikipedia and snapshot it.", "open en.wikipedia.org/wiki/Kotlin", Icons.Default.Public, MaterialShapes.SoftBurst.toShape()),
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            starters.forEach { starter -> StarterCard(starter) { state.run(starter.command) } }
        }
    }
}

@Composable
private fun StarterCard(starter: Starter, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(
                modifier = Modifier.size(48.dp),
                shape = starter.shape,
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                Box(contentAlignment = Alignment.Center) { Icon(starter.icon, contentDescription = null) }
            }
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(starter.title, style = MaterialTheme.typography.titleMedium)
                Text(starter.body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    "› ${starter.command}",
                    modifier = Modifier.padding(top = 4.dp),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ConsoleCard(entry: ConsoleEntry, onRef: (String) -> Unit, modifier: Modifier = Modifier) {
    val error = entry.status == ConsoleEntry.Status.Error
    Surface(
        modifier = modifier.fillMaxWidth().animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec()),
        shape = MaterialTheme.shapes.large,
        color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface,
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusShape(entry.status)
                Text(
                    entry.command,
                    modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    maxLines = 2,
                )
                if (entry.status != ConsoleEntry.Status.Running) {
                    Text(
                        "${entry.durationMs} ms",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (entry.output.isNotBlank()) Output(entry, onRef)
            entry.image?.let { image ->
                Image(
                    bitmap = image,
                    contentDescription = "Screenshot result",
                    modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp).clip(MaterialTheme.shapes.medium),
                    contentScale = ContentScale.Fit,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun StatusShape(status: ConsoleEntry.Status) {
    Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
        when (status) {
            ConsoleEntry.Status.Running -> LoadingIndicator(Modifier.size(32.dp))
            ConsoleEntry.Status.Ok -> Surface(
                modifier = Modifier.size(28.dp),
                shape = MaterialShapes.Cookie4Sided.toShape(),
                color = MaterialTheme.colorScheme.tertiary,
                contentColor = MaterialTheme.colorScheme.onTertiary,
            ) { Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.Check, "Succeeded", Modifier.size(16.dp)) } }
            ConsoleEntry.Status.Error -> Surface(
                modifier = Modifier.size(28.dp),
                shape = MaterialShapes.Cookie4Sided.toShape(),
                color = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            ) { Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.Close, "Failed", Modifier.size(16.dp)) } }
        }
    }
}

private const val COLLAPSED_LINES = 14

@Composable
private fun Output(entry: ConsoleEntry, onRef: (String) -> Unit) {
    val error = entry.status == ConsoleEntry.Status.Error
    var expanded by remember(entry.id) { mutableStateOf(false) }
    val lines = entry.output.lines()
    val shown = if (expanded || lines.size <= COLLAPSED_LINES) entry.output else lines.take(COLLAPSED_LINES).joinToString("\n")
    val refColor = MaterialTheme.colorScheme.primary
    val text = remember(shown, refColor) { linkRefs(shown, refColor, onRef) }
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = if (error) Color.Transparent else MaterialTheme.colorScheme.surfaceContainerLowest,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Text(
                text,
                modifier = Modifier
                    .then(if (error) Modifier else Modifier.horizontalScroll(rememberScrollState()))
                    .padding(horizontal = if (error) 0.dp else 12.dp, vertical = if (error) 0.dp else 10.dp),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                softWrap = error,
            )
            if (lines.size > COLLAPSED_LINES) {
                TextButton(onClick = { expanded = !expanded }, modifier = Modifier.padding(start = 4.dp)) {
                    Text(if (expanded) "Show less" else "Show all ${lines.size} lines")
                }
            }
        }
    }
}

private val refPattern = Regex("@e\\d+")

/** Makes every `@eN` in command output tappable, so a ref can go straight into the next command. */
private fun linkRefs(text: String, color: Color, onRef: (String) -> Unit): AnnotatedString = buildAnnotatedString {
    var last = 0
    for (match in refPattern.findAll(text)) {
        append(text, last, match.range.first)
        withLink(
            LinkAnnotation.Clickable(
                tag = match.value,
                styles = TextLinkStyles(SpanStyle(color = color, fontWeight = FontWeight.Bold)),
                linkInteractionListener = { onRef(match.value) },
            )
        ) { append(match.value) }
        last = match.range.last + 1
    }
    append(text, last, text.length)
}
