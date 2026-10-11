package com.bernaferrari.kmpwright.example.shared.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bernaferrari.kmpwright.core.MouseButton
import com.bernaferrari.kmpwright.core.MouseClickOptions
import com.bernaferrari.kmpwright.example.shared.CodeSnippet
import com.bernaferrari.kmpwright.example.shared.DemoHeader
import com.bernaferrari.kmpwright.example.shared.DemoSection
import com.bernaferrari.kmpwright.example.shared.DemoStatus
import com.bernaferrari.kmpwright.example.shared.DemoStatusKind
import com.bernaferrari.kmpwright.example.shared.LocalPageManager
import com.bernaferrari.kmpwright.example.shared.CoordinatePad
import com.bernaferrari.kmpwright.example.shared.loadDemoPage
import kotlinx.coroutines.launch

@Composable
fun MouseDemoScreen(modifier: Modifier = Modifier) {
    val page = LocalPageManager.current.page
    val scope = rememberCoroutineScope()
    var x by remember { mutableStateOf(150f) }
    var y by remember { mutableStateOf(200f) }
    var selectedClick by remember { mutableStateOf(0) }
    var loaded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var kind by remember { mutableStateOf(DemoStatusKind.Info) }
    var code by remember { mutableStateOf<String?>(null) }

    val html = """
        <!doctype html><html><head><title>Mouse playground</title>
        <style>body{font-family:system-ui;padding:24px}.box{display:inline-grid;place-items:center;width:120px;height:90px;margin:8px;border-radius:14px;background:#dde2ff}.target{background:#9ef2dd}</style>
        </head><body><button id="button">Click me</button><div><div id="source" class="box" draggable="true">Drag</div><div id="target" class="box target">Drop</div></div></body></html>
    """.trimIndent()

    fun run(actionCode: String, success: String, action: suspend () -> Unit) {
        if (!loaded || busy) return
        busy = true
        scope.launch {
            try {
                action(); code = actionCode; result = success; kind = DemoStatusKind.Success
            } catch (error: Exception) {
                result = error.message ?: "Mouse action failed"; kind = DemoStatusKind.Error
            } finally { busy = false }
        }
    }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        DemoHeader("", "Mouse", "Click by coordinate or interact through a locator.")
        Button(
            onClick = {
                busy = true
                scope.launch {
                    try { loadDemoPage(page, html, zoom = 0.8); loaded = true; result = "Playground loaded"; kind = DemoStatusKind.Success }
                    catch (error: Exception) { result = error.message ?: "Could not load playground"; kind = DemoStatusKind.Error }
                    finally { busy = false }
                }
            }, enabled = !busy, modifier = Modifier.fillMaxWidth()
        ) { Text(if (loaded) "Reload playground" else "Load playground") }

        DemoSection(
            title = "Pointer",
            supportingText = "Click or drag the pad. With a keyboard, focus it and use the arrow keys; press Enter to type exact coordinates."
        ) {
            val clickTypes = listOf("Click", "Double", "Right")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                clickTypes.forEachIndexed { index, label ->
                    SegmentedButton(
                        selected = selectedClick == index,
                        onClick = { selectedClick = index },
                        modifier = Modifier.heightIn(min = 48.dp),
                        shape = SegmentedButtonDefaults.itemShape(index, clickTypes.size),
                        label = { Text(label) }
                    )
                }
            }
            CoordinatePad(x, y, onPositionChange = { nextX, nextY -> x = nextX; y = nextY })
            Button(
                onClick = {
                    val px = x.toDouble(); val py = y.toDouble()
                    when (selectedClick) {
                        0 -> run("page.mouse.click($px, $py)", "Clicked at ${x.toInt()}, ${y.toInt()}") { page.mouse.click(px, py) }
                        1 -> run("page.mouse.dblclick($px, $py)", "Double-clicked") { page.mouse.dblclick(px, py) }
                        else -> run("page.mouse.click($px, $py, rightClick)", "Right-clicked") { page.mouse.click(px, py, MouseClickOptions(button = MouseButton.RIGHT)) }
                    }
                },
                enabled = loaded && !busy,
                modifier = Modifier.fillMaxWidth()
            ) { Text(clickTypes[selectedClick]) }
        }

        DemoSection("Locators") {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(
                    onClick = { run("page.locator(\"#button\").click()", "Button clicked") { page.locator("#button").click() } },
                    enabled = loaded && !busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    label = { Text("Click element") }
                )
                AssistChip(
                    onClick = { run("page.locator(\"#button\").hover()", "Button hovered") { page.locator("#button").hover() } },
                    enabled = loaded && !busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    label = { Text("Hover") }
                )
            }
            AssistChip(
                onClick = { run("page.locator(\"#source\").dragTo(page.locator(\"#target\"))", "Dragged to target") { page.locator("#source").dragTo(page.locator("#target")) } },
                enabled = loaded && !busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                label = { Text("Drag to target") }
            )
        }

        result?.let { DemoStatus(it, kind = kind) }
        code?.let { CodeSnippet(it) }
    }
}
