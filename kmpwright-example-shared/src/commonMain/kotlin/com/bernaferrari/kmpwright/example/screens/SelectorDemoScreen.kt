package com.bernaferrari.kmpwright.example.shared.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import com.bernaferrari.kmpwright.example.shared.PageStatusBar
import com.bernaferrari.kmpwright.example.shared.loadDemoPage
import kotlinx.coroutines.launch

private data class SelectorPreset(val label: String, val selector: String)

private val selectorPresets = listOf(
    SelectorPreset("Heading", "role=heading"),
    SelectorPreset("Buttons", "role=button"),
    SelectorPreset("Exact text", "text=Item 1"),
    SelectorPreset("Label", "label=Email"),
    SelectorPreset("XPath", "xpath=//h1")
)

@Composable
fun SelectorDemoScreen(modifier: Modifier = Modifier) {
    val page = LocalPageManager.current.page
    val currentUrl by page.url.collectAsState(null)
    val currentTitle by page.title.collectAsState(null)
    val scope = rememberCoroutineScope()
    var selectedPreset by remember { mutableStateOf(selectorPresets.first()) }
    var customSelector by remember { mutableStateOf("#items li") }
    var running by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var resultKind by remember { mutableStateOf(DemoStatusKind.Info) }
    var lastSelector by remember { mutableStateOf<String?>(null) }

    val testHtml = """
        <!doctype html>
        <html>
          <head><title>Selector playground</title></head>
          <body>
            <h1>Products</h1>
            <label>Email <input aria-label="Email" /></label>
            <ul id="items"><li>Item 1</li><li>Item 2</li><li>Item 3</li></ul>
            <button>Save</button><button>Cancel</button>
          </body>
        </html>
    """.trimIndent()

    fun runSelector(selector: String) {
        if (running || selector.isBlank()) return
        running = true
        result = null
        scope.launch {
            try {
                loadDemoPage(page, testHtml)
                val count = page.locator(selector).count()
                lastSelector = selector
                result = if (count == 1) "1 match" else "$count matches"
                resultKind = if (count > 0) DemoStatusKind.Success else DemoStatusKind.Error
            } catch (error: Exception) {
                lastSelector = selector
                result = error.message ?: "Invalid selector"
                resultKind = DemoStatusKind.Error
            } finally {
                running = false
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        DemoHeader(
            eyebrow = "",
            title = "Selectors",
            description = "Run Playwright-style selectors against a local page."
        )

        DemoSection(title = "Preset") {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(selectorPresets) { preset ->
                    FilterChip(
                        selected = selectedPreset == preset,
                        onClick = { selectedPreset = preset },
                        modifier = Modifier.heightIn(min = 48.dp),
                        label = { Text(preset.label) }
                    )
                }
            }
            Text(
                selectedPreset.selector,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(
                onClick = { runSelector(selectedPreset.selector) },
                enabled = !running,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (running) "Running…" else "Run selector")
            }
        }

        DemoSection(title = "Custom") {
            OutlinedTextField(
                value = customSelector,
                onValueChange = { customSelector = it },
                label = { Text("Selector") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            FilledTonalButton(
                onClick = { runSelector(customSelector) },
                enabled = !running && customSelector.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Run custom selector")
            }
        }

        result?.let { DemoStatus(it, kind = resultKind) }
        lastSelector?.let { CodeSnippet("page.locator(\"$it\").count()") }
        PageStatusBar(url = currentUrl, title = currentTitle)
    }
}
