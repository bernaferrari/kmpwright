package com.bernaferrari.kmpwright.example.shared.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
fun TouchDemoScreen(modifier: Modifier = Modifier) {
    val page = LocalPageManager.current.page
    val scope = rememberCoroutineScope()
    var x by remember { mutableStateOf(150f) }
    var y by remember { mutableStateOf(200f) }
    var selectedGesture by remember { mutableStateOf(0) }
    var loaded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var resultKind by remember { mutableStateOf(DemoStatusKind.Info) }
    var code by remember { mutableStateOf<String?>(null) }

    val html = """
        <!doctype html><html><head><title>Touch playground</title>
        <meta name="viewport" content="width=device-width, initial-scale=1" />
        <style>
          body { font-family: system-ui; padding: 20px; }
          #target { height: 180px; display:grid; place-items:center; color:white; border-radius:16px; background:#4359a9; }
          .space { height: 600px; }
        </style></head><body>
        <div id="target" onclick="this.textContent='Tapped'">Tap target</div><div class="space"></div><p>Bottom</p>
        </body></html>
    """.trimIndent()

    fun run(actionCode: String, action: suspend () -> Unit, success: String) {
        if (!loaded || busy) return
        busy = true
        scope.launch {
            try {
                action()
                code = actionCode
                result = success
                resultKind = DemoStatusKind.Success
            } catch (error: Exception) {
                result = error.message ?: "Gesture failed"
                resultKind = DemoStatusKind.Error
            } finally {
                busy = false
            }
        }
    }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        DemoHeader("", "Touch", "Tap, hold, swipe, and scroll a local page.")

        Button(
            onClick = {
                busy = true
                scope.launch {
                    try {
                        loadDemoPage(page, html, zoom = 0.7)
                        loaded = true
                        result = "Playground loaded"
                        resultKind = DemoStatusKind.Success
                    } catch (error: Exception) {
                        result = error.message ?: "Could not load playground"
                        resultKind = DemoStatusKind.Error
                    } finally { busy = false }
                }
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (loaded) "Reload playground" else "Load playground") }

        DemoSection(
            title = "Gesture",
            supportingText = "Tap or drag the pad. With a keyboard, focus it and use the arrow keys; press Enter to type exact coordinates."
        ) {
            val gestures = listOf("Tap", "Hold", "Swipe", "Scroll")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                gestures.forEachIndexed { index, label ->
                    SegmentedButton(
                        selected = selectedGesture == index,
                        onClick = { selectedGesture = index },
                        modifier = Modifier.heightIn(min = 48.dp),
                        shape = SegmentedButtonDefaults.itemShape(index, gestures.size),
                        label = { Text(label) }
                    )
                }
            }
            CoordinatePad(x, y, onPositionChange = { nextX, nextY -> x = nextX; y = nextY })
            Button(
                onClick = {
                    val px = x.toDouble(); val py = y.toDouble()
                    when (selectedGesture) {
                        0 -> run("page.touch.tap($px, $py)", { page.touch.tap(px, py) }, "Tapped at ${x.toInt()}, ${y.toInt()}")
                        1 -> run("page.touch.longTap($px, $py)", { page.touch.longTap(px, py) }, "Held at ${x.toInt()}, ${y.toInt()}")
                        2 -> run("page.touch.swipe($px, $py, ${px - 120}, $py)", { page.touch.swipe(px, py, (px - 120).coerceAtLeast(0.0), py) }, "Swiped left")
                        else -> run("page.touch.scroll($px, $py, 0.0, 160.0)", { page.touch.scroll(px, py, 0.0, 160.0) }, "Scrolled")
                    }
                },
                enabled = loaded && !busy,
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (busy) "Running…" else gestures[selectedGesture]) }
        }

        result?.let { DemoStatus(it, kind = resultKind) }
        code?.let { CodeSnippet(it) }
    }
}
