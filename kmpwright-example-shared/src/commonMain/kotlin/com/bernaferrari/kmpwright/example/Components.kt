package com.bernaferrari.kmpwright.example

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Large expressive title used at the top of each panel. */
@Composable
internal fun PanelHeader(title: String, subtitle: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A connected button group acting as a single-choice segmented control. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun <T> ConnectedChoice(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier,
    icon: ((T) -> ImageVector)? = null,
) {
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        options.forEachIndexed { index, option ->
            ToggleButton(
                checked = option == selected,
                onCheckedChange = { onSelect(option) },
                modifier = Modifier.weight(1f).semantics { role = Role.RadioButton },
                contentPadding = PaddingValues(horizontal = 8.dp),
                shapes = when (index) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
            ) {
                if (icon != null && option == selected) {
                    Icon(icon(option), contentDescription = null, modifier = Modifier.padding(end = 6.dp).size(18.dp))
                }
                Text(label(option), maxLines = 1, softWrap = false)
            }
        }
    }
}

private object CodeColors {
    val background = Color(0xFF16141F)
    val text = Color(0xFFE7E3F4)
    val keyword = Color(0xFFC8A6FF)
    val string = Color(0xFF8FE3C3)
    val comment = Color(0xFF8B87A0)
    val number = Color(0xFFFFC27A)
    val call = Color(0xFF8CC8FF)
}

private val keywords = setOf(
    "val", "var", "fun", "suspend", "return", "if", "else", "when", "for", "in", "true", "false", "null", "try", "finally",
)

/** Minimal Kotlin highlighter: enough to make snippets readable at a glance. */
internal fun highlightKotlin(code: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < code.length) {
        val c = code[i]
        when {
            code.startsWith("//", i) -> {
                val end = code.indexOf('\n', i).let { if (it < 0) code.length else it }
                withStyle(SpanStyle(color = CodeColors.comment)) { append(code, i, end) }
                i = end
            }
            c == '"' -> {
                var end = i + 1
                while (end < code.length && code[end] != '"' && code[end] != '\n') {
                    if (code[end] == '\\') end++
                    end++
                }
                end = (end + 1).coerceAtMost(code.length)
                withStyle(SpanStyle(color = CodeColors.string)) { append(code, i, end) }
                i = end
            }
            c.isDigit() -> {
                var end = i
                while (end < code.length && (code[end].isLetterOrDigit() || code[end] == '_' || code[end] == '.')) end++
                withStyle(SpanStyle(color = CodeColors.number)) { append(code, i, end) }
                i = end
            }
            c.isLetter() || c == '_' -> {
                var end = i
                while (end < code.length && (code[end].isLetterOrDigit() || code[end] == '_')) end++
                val word = code.substring(i, end)
                val style = when {
                    word in keywords -> SpanStyle(color = CodeColors.keyword, fontWeight = FontWeight.SemiBold)
                    end < code.length && code[end] == '(' -> SpanStyle(color = CodeColors.call)
                    word.first().isUpperCase() -> SpanStyle(color = CodeColors.number)
                    else -> null
                }
                if (style != null) withStyle(style) { append(word) } else append(word)
                i = end
            }
            else -> { append(c); i++ }
        }
    }
}

@Composable
internal fun CodeBlock(code: String, modifier: Modifier = Modifier) {
    val highlighted = remember(code) { highlightKotlin(code) }
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = CodeColors.background,
        contentColor = CodeColors.text,
        shape = MaterialTheme.shapes.large,
    ) {
        Text(
            text = highlighted,
            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(16.dp),
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            lineHeight = 20.sp,
            softWrap = false,
        )
    }
}
