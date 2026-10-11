package com.bernaferrari.kmpwright.example.shared.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bernaferrari.kmpwright.core.TypeOptions
import com.bernaferrari.kmpwright.example.shared.CodeSnippet
import com.bernaferrari.kmpwright.example.shared.DemoHeader
import com.bernaferrari.kmpwright.example.shared.DemoSection
import com.bernaferrari.kmpwright.example.shared.DemoStatus
import com.bernaferrari.kmpwright.example.shared.DemoStatusKind
import com.bernaferrari.kmpwright.example.shared.LocalPageManager
import com.bernaferrari.kmpwright.example.shared.loadDemoPage
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun KeyboardDemoScreen(modifier: Modifier = Modifier) {
    val page = LocalPageManager.current.page
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("Hello Kmpwright") }
    var textMode by remember { mutableStateOf(0) }
    val modifiers = remember { mutableStateListOf<String>() }
    var loaded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var kind by remember { mutableStateOf(DemoStatusKind.Info) }
    var code by remember { mutableStateOf<String?>(null) }

    val html = """
        <!doctype html><html><head><title>Keyboard playground</title></head>
        <body style="font-family:system-ui;padding:24px"><label>Message <input id="message" style="display:block;margin-top:8px;padding:12px;width:80%" /></label><p id="log">Waiting</p>
        <script>message.addEventListener('input',()=>log.textContent=message.value)</script></body></html>
    """.trimIndent()

    fun run(actionCode: String, success: String, action: suspend () -> Unit) {
        if (!loaded || busy) return
        busy = true
        scope.launch {
            try { action(); code = actionCode; result = success; kind = DemoStatusKind.Success }
            catch (error: Exception) { result = error.message ?: "Keyboard action failed"; kind = DemoStatusKind.Error }
            finally { busy = false }
        }
    }

    fun pressKey(key: String) {
        val activeModifiers = modifiers.toList()
        val expression = (activeModifiers.map { "page.keyboard.down(\"$it\")" } + "page.keyboard.press(\"$key\")").joinToString("; ")
        run(expression, "Pressed ${(activeModifiers + key).joinToString(" + ")}") {
            activeModifiers.forEach { page.keyboard.down(it) }
            try { page.keyboard.press(key) } finally { activeModifiers.asReversed().forEach { page.keyboard.up(it) } }
        }
    }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        DemoHeader("", "Keyboard", "Type text or compose a key chord.")
        Button(
            onClick = {
                busy = true
                scope.launch {
                    try {
                        loadDemoPage(page, html); page.locator("#message").click(); loaded = true
                        result = "Input focused"; kind = DemoStatusKind.Success
                    } catch (error: Exception) { result = error.message ?: "Could not load playground"; kind = DemoStatusKind.Error }
                    finally { busy = false }
                }
            }, enabled = !busy, modifier = Modifier.fillMaxWidth()
        ) { Text(if (loaded) "Reload playground" else "Load playground") }

        DemoSection("Text input") {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf("Type", "Insert").forEachIndexed { index, label ->
                    SegmentedButton(
                        selected = textMode == index,
                        onClick = { textMode = index },
                        modifier = Modifier.heightIn(min = 48.dp),
                        shape = SegmentedButtonDefaults.itemShape(index, 2),
                        label = { Text(label) }
                    )
                }
            }
            OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), label = { Text("Text") }, singleLine = true)
            Button(
                onClick = {
                    if (textMode == 0) run("page.keyboard.type(\"$text\")", "Typed text") { page.keyboard.type(text, TypeOptions(delayMs = 40)) }
                    else run("page.keyboard.insertText(\"$text\")", "Inserted text") { page.keyboard.insertText(text) }
                },
                enabled = loaded && !busy && text.isNotEmpty(),
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (textMode == 0) "Type text" else "Insert text") }
        }

        DemoSection("Key chord") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Control", "Shift", "Alt").forEach { modifierKey ->
                    FilterChip(
                        selected = modifierKey in modifiers,
                        onClick = { if (modifierKey in modifiers) modifiers.remove(modifierKey) else modifiers.add(modifierKey) },
                        modifier = Modifier.heightIn(min = 48.dp),
                        label = { Text(modifierKey) }
                    )
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Enter", "Tab", "Backspace", "ArrowUp", "ArrowDown", "Escape").forEach { key ->
                    AssistChip(
                        onClick = { pressKey(key) },
                        enabled = loaded && !busy,
                        modifier = Modifier.heightIn(min = 48.dp),
                        label = { Text(key.removePrefix("Arrow")) }
                    )
                }
            }
        }

        result?.let { DemoStatus(it, kind = kind) }
        code?.let { CodeSnippet(it) }
    }
}
