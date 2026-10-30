package com.bernaferrari.kmpwright.example

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.Cookie
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException

private enum class InspectTab(val label: String, val icon: ImageVector) {
    Console("Console", Icons.Default.Terminal),
    Network("Network", Icons.Default.SwapVert),
    Storage("Storage", Icons.Default.Cookie),
    Audit("A11y", Icons.Default.Accessibility),
}

private enum class Tone { Neutral, Good, Warn, Bad }

/** A row in an inspector list: a tag, a detail line and an optional trailing value. */
private data class InspectRow(val tag: String, val detail: String, val trailing: String? = null, val tone: Tone = Tone.Neutral)

private class InspectResult(val summary: String?, val rows: List<InspectRow>)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun InspectPanel(state: StudioState, modifier: Modifier = Modifier) {
    var tab by rememberSaveable { mutableStateOf(InspectTab.Console) }
    var result by remember { mutableStateOf<InspectResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshKey by remember { mutableIntStateOf(0) }
    val url by state.page.url.collectAsState()

    LaunchedEffect(tab, url, refreshKey) {
        result = null
        error = null
        try {
            result = state.withPage { page -> load(tab, page) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            error = failure.message ?: failure.toString()
        }
    }

    LazyColumn(
        modifier = modifier,
        contentPadding = panelPadding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item(key = "header") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(bottom = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(start = 4.dp)) {
                        Text("Inspect", style = MaterialTheme.typography.headlineSmallEmphasized)
                        Text(
                            "What the page is doing right now.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { refreshKey++ }, shapes = IconButtonDefaults.shapes()) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                }
                ConnectedChoice(InspectTab.entries, tab, { tab = it }, { it.label })
            }
        }
        val current = result
        when {
            error != null -> item(key = "error") { Note(error.orEmpty(), Tone.Bad) }
            current == null -> item(key = "loading") {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { LoadingIndicator() }
            }
            else -> {
                current.summary?.let { summary -> item(key = "summary") { Summary(tab, summary) } }
                if (current.rows.isEmpty()) {
                    item(key = "empty") { Note(emptyMessage(tab), Tone.Neutral) }
                } else {
                    items(current.rows) { row -> RowCard(row) }
                }
            }
        }
    }
}

private suspend fun load(tab: InspectTab, page: com.bernaferrari.kmpwright.core.Page): InspectResult = when (tab) {
    InspectTab.Console -> InspectResult(
        summary = null,
        rows = page.pageErrors().map { InspectRow("error", it, tone = Tone.Bad) } +
            page.consoleMessages().takeLast(200).reversed().map {
                InspectRow(
                    it.type,
                    it.text,
                    tone = when (it.type) {
                        "error" -> Tone.Bad
                        "warning", "warn" -> Tone.Warn
                        else -> Tone.Neutral
                    },
                )
            },
    )
    InspectTab.Network -> InspectResult(
        summary = null,
        rows = page.network.requests(limit = 200).reversed().map { record ->
            val status = record.response?.status
            InspectRow(
                tag = record.request.method,
                detail = record.request.url,
                trailing = status?.toString() ?: record.state.name.lowercase(),
                tone = when {
                    record.error != null || (status ?: 0) >= 400 -> Tone.Bad
                    status in 300..399 -> Tone.Warn
                    status != null -> Tone.Good
                    else -> Tone.Neutral
                },
            )
        },
    )
    InspectTab.Storage -> InspectResult(
        summary = null,
        rows = page.cookies().map { InspectRow("cookie", "${it.name}=${it.value}", it.domain) } +
            page.localStorage.entries().map { (key, value) -> InspectRow("local", "$key=$value") } +
            page.sessionStorage.entries().map { (key, value) -> InspectRow("session", "$key=$value") },
    )
    InspectTab.Audit -> {
        val report = page.accessibilityAudit()
        InspectResult(
            summary = "${report.counts.violations} violations · ${report.counts.incomplete} to review · ${report.counts.passes} passes · axe ${report.axeVersion}",
            rows = report.violations.map {
                InspectRow(
                    it.impact,
                    it.help,
                    "${it.nodeCount}×",
                    tone = if (it.impact in setOf("serious", "critical")) Tone.Bad else Tone.Warn,
                )
            },
        )
    }
}

private fun emptyMessage(tab: InspectTab) = when (tab) {
    InspectTab.Console -> "No console messages yet. Try eval console.log('hi') in the Agent tab."
    InspectTab.Network -> "No requests recorded. Open a website to see traffic."
    InspectTab.Storage -> "No cookies or storage for this page."
    InspectTab.Audit -> "No accessibility violations found."
}

@Composable
private fun toneColors(tone: Tone): Pair<Color, Color> = when (tone) {
    Tone.Neutral -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
    Tone.Good -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
    Tone.Warn -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
    Tone.Bad -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
}

@Composable
private fun Summary(tab: InspectTab, text: String) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(
                modifier = Modifier.size(44.dp),
                shape = MaterialShapes.Sunny.toShape(),
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) { Box(contentAlignment = Alignment.Center) { Icon(tab.icon, contentDescription = null) } }
            Text(text, modifier = Modifier.padding(start = 14.dp), style = MaterialTheme.typography.titleSmall)
        }
    }
}

@Composable
private fun RowCard(row: InspectRow) {
    val (chip, onChip) = toneColors(row.tone)
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(shape = CircleShape, color = chip, contentColor = onChip, modifier = Modifier.widthIn(min = 56.dp)) {
                Text(
                    row.tag,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    maxLines = 1,
                )
            }
            Text(
                row.detail,
                modifier = Modifier.weight(1f),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            row.trailing?.let {
                Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
    }
}

@Composable
private fun Note(text: String, tone: Tone) {
    val (container, content) = if (tone == Tone.Bad) toneColors(tone) else
        MaterialTheme.colorScheme.surfaceContainerHigh to MaterialTheme.colorScheme.onSurfaceVariant
    Surface(shape = MaterialTheme.shapes.large, color = container, contentColor = content, modifier = Modifier.fillMaxWidth()) {
        Text(text, modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
    }
}
