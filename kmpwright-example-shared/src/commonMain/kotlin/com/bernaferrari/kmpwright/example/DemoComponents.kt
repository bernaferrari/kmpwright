package com.bernaferrari.kmpwright.example.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

enum class DemoStatusKind { Info, Success, Error }

internal enum class CoordinateMove { Left, Right, Up, Down }

internal fun moveCoordinate(
    x: Float,
    y: Float,
    maxX: Float,
    maxY: Float,
    move: CoordinateMove,
    step: Float = 10f
): Pair<Float, Float> = when (move) {
    CoordinateMove.Left -> (x - step).coerceIn(0f, maxX) to y
    CoordinateMove.Right -> (x + step).coerceIn(0f, maxX) to y
    CoordinateMove.Up -> x to (y - step).coerceIn(0f, maxY)
    CoordinateMove.Down -> x to (y + step).coerceIn(0f, maxY)
}

fun demoStatusKind(message: String): DemoStatusKind = when {
    message.startsWith("Error", ignoreCase = true) -> DemoStatusKind.Error
    message.contains("ready", ignoreCase = true) ||
        message.contains("begin", ignoreCase = true) ||
        message.contains("select", ignoreCase = true) ||
        message.contains("try ", ignoreCase = true) -> DemoStatusKind.Info
    else -> DemoStatusKind.Success
}

@Composable
fun DemoHeader(
    @Suppress("UNUSED_PARAMETER")
    eyebrow: String,
    title: String,
    description: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun DemoSection(
    title: String,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.46f)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                supportingText?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            content()
        }
    }
}

@Composable
fun ValueSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(
                    value.toInt().toString(),
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
        Slider(value = value, onValueChange = onValueChange, valueRange = valueRange)
    }
}

@Composable
fun CoordinatePad(
    x: Float,
    y: Float,
    maxX: Float = 360f,
    maxY: Float = 720f,
    onPositionChange: (Float, Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val primary = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
    val container = MaterialTheme.colorScheme.surface
    var editorOpen by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    var draftX by remember { mutableStateOf(x.toInt().toString()) }
    var draftY by remember { mutableStateOf(y.toInt().toString()) }

    fun move(direction: CoordinateMove): Boolean {
        val (nextX, nextY) = moveCoordinate(x, y, maxX, maxY, direction)
        onPositionChange(nextX, nextY)
        return true
    }

    fun openEditor(): Boolean {
        draftX = x.toInt().toString()
        draftY = y.toInt().toString()
        editorOpen = true
        return true
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1.55f)
            .background(container, RoundedCornerShape(22.dp))
            .border(
                width = 2.dp,
                color = if (focused) primary else Color.Transparent,
                shape = RoundedCornerShape(22.dp)
            )
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionLeft -> move(CoordinateMove.Left)
                    Key.DirectionRight -> move(CoordinateMove.Right)
                    Key.DirectionUp -> move(CoordinateMove.Up)
                    Key.DirectionDown -> move(CoordinateMove.Down)
                    Key.Enter -> openEditor()
                    Key.Spacebar -> openEditor()
                    else -> false
                }
            }
            .focusable()
            .pointerInput(maxX, maxY) {
                fun update(position: Offset) {
                    onPositionChange(
                        (position.x / size.width).coerceIn(0f, 1f) * maxX,
                        (position.y / size.height).coerceIn(0f, 1f) * maxY
                    )
                }
                awaitEachGesture {
                    val down = awaitFirstDown()
                    update(down.position)
                    drag(down.id) { change ->
                        update(change.position)
                        change.consume()
                    }
                }
            }
            .semantics {
                contentDescription = "Coordinate pad"
                stateDescription = "X ${x.toInt()}, Y ${y.toInt()}"
                onClick(label = "Set exact coordinates") { openEditor() }
                customActions = listOf(
                    CustomAccessibilityAction("Move left") { move(CoordinateMove.Left) },
                    CustomAccessibilityAction("Move right") { move(CoordinateMove.Right) },
                    CustomAccessibilityAction("Move up") { move(CoordinateMove.Up) },
                    CustomAccessibilityAction("Move down") { move(CoordinateMove.Down) }
                )
            }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            for (step in 1..3) {
                val lineX = size.width * step / 4f
                val lineY = size.height * step / 4f
                drawLine(grid, Offset(lineX, 0f), Offset(lineX, size.height), strokeWidth = 1f)
                drawLine(grid, Offset(0f, lineY), Offset(size.width, lineY), strokeWidth = 1f)
            }
            val point = Offset(
                (x / maxX).coerceIn(0f, 1f) * size.width,
                (y / maxY).coerceIn(0f, 1f) * size.height
            )
            drawCircle(primary.copy(alpha = 0.18f), radius = 22.dp.toPx(), center = point)
            drawCircle(primary, radius = 7.dp.toPx(), center = point)
        }
        Surface(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(10.dp)
                .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                .clickable(onClickLabel = "Set exact coordinates") { openEditor() },
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            shape = RoundedCornerShape(10.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    "${x.toInt()}, ${y.toInt()}",
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }

    if (editorOpen) {
        AlertDialog(
            onDismissRequest = { editorOpen = false },
            title = { Text("Set coordinates") },
            text = {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = draftX,
                        onValueChange = { draftX = it.filter(Char::isDigit) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("X") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = draftY,
                        onValueChange = { draftY = it.filter(Char::isDigit) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Y") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    onPositionChange(
                        (draftX.toFloatOrNull() ?: x).coerceIn(0f, maxX),
                        (draftY.toFloatOrNull() ?: y).coerceIn(0f, maxY)
                    )
                    editorOpen = false
                }) { Text("Apply") }
            },
            dismissButton = { TextButton(onClick = { editorOpen = false }) { Text("Cancel") } }
        )
    }
}

@Composable
fun CodeSnippet(
    code: String,
    modifier: Modifier = Modifier,
    @Suppress("UNUSED_PARAMETER") label: String = "Kotlin"
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = Color(0xFF171A22),
        contentColor = Color(0xFFE6E9F5),
        shape = RoundedCornerShape(10.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
            horizontalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            Icon(
                Icons.Default.Terminal,
                contentDescription = null,
                modifier = Modifier.padding(top = 1.dp),
                tint = Color(0xFF9EF2DD)
            )
            Text(
                text = code.trimIndent(),
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .weight(1f),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = Color(0xFFE6E9F5)
            )
        }
    }
}

@Composable
fun DemoStatus(
    message: String,
    modifier: Modifier = Modifier,
    kind: DemoStatusKind = DemoStatusKind.Info
) {
    if (message.contains("ready", ignoreCase = true)) return

    val container = when (kind) {
        DemoStatusKind.Info -> MaterialTheme.colorScheme.secondaryContainer
        DemoStatusKind.Success -> MaterialTheme.colorScheme.tertiaryContainer
        DemoStatusKind.Error -> MaterialTheme.colorScheme.errorContainer
    }
    val content = when (kind) {
        DemoStatusKind.Info -> MaterialTheme.colorScheme.onSecondaryContainer
        DemoStatusKind.Success -> MaterialTheme.colorScheme.onTertiaryContainer
        DemoStatusKind.Error -> MaterialTheme.colorScheme.onErrorContainer
    }
    val icon = when (kind) {
        DemoStatusKind.Info -> Icons.Default.Info
        DemoStatusKind.Success -> Icons.Default.CheckCircle
        DemoStatusKind.Error -> Icons.Default.Error
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                liveRegion = if (kind == DemoStatusKind.Error) {
                    LiveRegionMode.Assertive
                } else {
                    LiveRegionMode.Polite
                }
            },
        color = container,
        contentColor = content,
        shape = RoundedCornerShape(10.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(icon, contentDescription = null)
            Text(message, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun FeaturePill(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(999.dp)
            )
            .padding(horizontal = 11.dp, vertical = 7.dp),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}
