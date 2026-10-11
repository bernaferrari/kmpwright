package com.bernaferrari.kmpwright.example.shared.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bernaferrari.kmpwright.example.shared.CodeSnippet
import com.bernaferrari.kmpwright.example.shared.DemoHeader
import com.bernaferrari.kmpwright.example.shared.DemoSection
import com.bernaferrari.kmpwright.example.shared.DemoStatus
import com.bernaferrari.kmpwright.example.shared.DemoStatusKind
import com.bernaferrari.kmpwright.example.shared.LocalPageManager
import com.bernaferrari.kmpwright.example.shared.loadDemoPage
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(modifier: Modifier = Modifier) {
    val page = LocalPageManager.current.page
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var resultKind by remember { mutableStateOf(DemoStatusKind.Info) }

    val html = """
        <!doctype html>
        <html>
          <head><title>Quick start</title></head>
          <body>
            <button id="run" onclick="result.textContent = 'Clicked'">Run</button>
            <p id="result">Waiting</p>
          </body>
        </html>
    """.trimIndent()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        DemoHeader(
            eyebrow = "",
            title = "Examples",
            description = "Runnable Kmpwright recipes for Android and iOS."
        )

        DemoSection(
            title = "Quick start",
            supportingText = "Load local HTML and click a button."
        ) {
            CodeSnippet(
                """
                page.setContent(html)
                page.locator("#run").click()
                """
            )
            Button(
                onClick = {
                    if (running) return@Button
                    running = true
                    result = null
                    scope.launch {
                        try {
                            loadDemoPage(page, html)
                            page.locator("#run").click()
                            result = page.locator("#result").textContent() ?: "Done"
                            resultKind = DemoStatusKind.Success
                        } catch (error: Exception) {
                            result = error.message ?: "Could not run example"
                            resultKind = DemoStatusKind.Error
                        } finally {
                            running = false
                        }
                    }
                },
                enabled = !running,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (running) "Running…" else "Run example")
            }
        }

        result?.let { DemoStatus(it, kind = resultKind) }
    }
}
