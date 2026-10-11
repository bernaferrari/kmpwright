package com.bernaferrari.kmpwright.example.shared.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.bernaferrari.kmpwright.example.shared.CodeSnippet
import com.bernaferrari.kmpwright.example.shared.DemoHeader
import com.bernaferrari.kmpwright.example.shared.DemoSection
import com.bernaferrari.kmpwright.example.shared.DemoStatus
import com.bernaferrari.kmpwright.example.shared.DemoStatusKind
import com.bernaferrari.kmpwright.example.shared.LocalPageManager
import com.bernaferrari.kmpwright.example.shared.PageStatusBar
import com.bernaferrari.kmpwright.example.shared.explainNavigationFailure
import kotlinx.coroutines.launch

@Composable
fun NavigationDemoScreen(modifier: Modifier = Modifier) {
    val page = LocalPageManager.current.page
    val pageUrl by page.url.collectAsState(null)
    val pageTitle by page.title.collectAsState(null)
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf("https://example.com") }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var kind by remember { mutableStateOf(DemoStatusKind.Info) }
    var code by remember { mutableStateOf<String?>(null) }
    var harActive by remember { mutableStateOf(false) }
    var harSummary by remember { mutableStateOf<String?>(null) }
    var readerSummary by remember { mutableStateOf<String?>(null) }
    var networkSummary by remember { mutableStateOf<String?>(null) }
    var reactSummary by remember { mutableStateOf<String?>(null) }
    var auditSummary by remember { mutableStateOf<String?>(null) }
    var vitalsSummary by remember { mutableStateOf<String?>(null) }

    fun run(
        actionCode: String,
        success: String,
        failureAction: String,
        suggestion: String,
        action: suspend () -> Unit
    ) {
        if (busy) return
        busy = true
        scope.launch {
            try { action(); code = actionCode; result = success; kind = DemoStatusKind.Success }
            catch (error: Exception) {
                result = explainNavigationFailure(failureAction, suggestion, error)
                kind = DemoStatusKind.Error
            }
            finally { busy = false }
        }
    }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        DemoHeader("", "Navigation", "Open a URL and control browser history.")

        DemoSection("Open page") {
            OutlinedTextField(
                value = url, onValueChange = { url = it }, label = { Text("URL") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Example" to "https://example.com", "Kotlin" to "https://kotlinlang.org").forEach { (label, value) ->
                    FilterChip(
                        selected = url == value,
                        onClick = { url = value },
                        modifier = Modifier.heightIn(min = 48.dp),
                        label = { Text(label) }
                    )
                }
            }
            Button(
                onClick = {
                    run(
                        actionCode = "page.goto(\"$url\")",
                        success = "Page loaded",
                        failureAction = "open $url",
                        suggestion = "Check the address and network connection, then try again."
                    ) { page.goto(url) }
                },
                enabled = !busy && url.isNotBlank(), modifier = Modifier.fillMaxWidth()
            ) { Text(if (busy) "Loading…" else "Open") }
        }

        DemoSection("History") {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    onClick = { run("page.back()", "Went back", "go back", "There may not be an earlier page in this tab’s history.") { page.back() } },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Back") }
                FilledTonalButton(
                    onClick = { run("page.reload()", "Reloaded", "reload the page", "Check the network connection, then try again.") { page.reload() } },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Reload") }
                FilledTonalButton(
                    onClick = { run("page.forward()", "Went forward", "go forward", "There may not be a later page in this tab’s history.") { page.forward() } },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Forward") }
            }
        }

        DemoSection("Readable content and requests") {
            FilledTonalButton(
                onClick = { run("page.read()", "Read the current document", "read content", "Open a page first.") {
                    readerSummary = page.read().content.take(2000)
                } }, enabled = !busy, modifier = Modifier.fillMaxWidth()
            ) { Text("Read current page") }
            FilledTonalButton(
                onClick = { run("page.read(url)", "Read through HTTP", "read URL", "Check the URL and network connection.") {
                    readerSummary = page.read(url).content.take(2000)
                } }, enabled = !busy, modifier = Modifier.fillMaxWidth()
            ) { Text("Read URL without navigating") }
            readerSummary?.let { Text(it) }
            FilledTonalButton(
                onClick = { run("page.network.requests(limit = 10)", "Read recent requests", "inspect requests", "Open a page first.") {
                    networkSummary = page.network.requests(limit = 10).joinToString("\n") {
                        "${it.request.method} ${it.response?.status ?: it.state} ${it.request.url}"
                    }.ifEmpty { "No requests observed" }
                } }, enabled = !busy, modifier = Modifier.fillMaxWidth()
            ) { Text("Recent requests") }
            networkSummary?.let { Text(it) }
        }

        DemoSection("Network archive") {
            Text("Capture requests while browsing. Mobile archives include the metadata available from their WebView.")
            FilledTonalButton(onClick = {
                run(if (harActive) "page.stopHarCapture()" else "page.startHarCapture()", "Updated network capture", "capture network requests", "Open a page first.") {
                    if (harActive) {
                        val archive = page.stopHarCapture()
                        harActive = false
                        harSummary = "HAR ready · ${archive.length} characters"
                    } else { page.startHarCapture(); harActive = true; harSummary = "Capturing requests" }
                }
            }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(if (harActive) "Finish HAR capture" else "Start HAR capture") }
            harSummary?.let { Text(it) }
        }

        DemoSection("Routes and performance") {
            FilledTonalButton(
                onClick = {
                    run("page.pushState(\"#kmpwright-demo\")", "Updated the route", "update the route",
                        "Open a page first, then try again.") { page.pushState("#kmpwright-demo") }
                }, enabled = !busy, modifier = Modifier.fillMaxWidth()
            ) { Text("Change route without reloading") }
            FilledTonalButton(
                onClick = {
                    run("page.webVitals(WebVitalsOptions(reload = false))", "Collected performance metrics",
                        "measure performance", "Open a page with visible content, then try again.") {
                        val report = page.webVitals(com.bernaferrari.kmpwright.core.WebVitalsOptions(reload = false, settleMs = 500))
                        val fcp = report.fcp?.let { "${it.toLong()} ms" } ?: "not observed"
                        val lcp = report.lcp?.startTime?.let { "${it.toLong()} ms" } ?: "not observed"
                        val cls = report.cls.score?.let { "${kotlin.math.round(it * 1000) / 1000}" } ?: "not observed"
                        vitalsSummary = "FCP $fcp · LCP $lcp · CLS $cls"
                    }
                }, enabled = !busy, modifier = Modifier.fillMaxWidth()
            ) { Text("Measure Web Vitals") }
            vitalsSummary?.let { Text(it) }
        }

        DemoSection("React inspection") {
            Text("Open a React application, then enable inspection before its scripts run.")
            FilledTonalButton(
                onClick = { run("page.react.enable(reload = true)", "Reloaded with React inspection enabled",
                    "enable React inspection", "Open your React application first.") { page.react.enable(reload = true) } },
                enabled = !busy, modifier = Modifier.fillMaxWidth()
            ) { Text("Enable inspection and reload") }
            FilledTonalButton(
                onClick = { run("page.react.tree()", "Read the component tree", "inspect React components",
                    "Enable inspection and load a React application first.") {
                    val tree = page.react.tree()
                    reactSummary = tree.mapNotNull { it.name }.take(12).joinToString(" · ").ifEmpty { "No named components" }
                } }, enabled = !busy, modifier = Modifier.fillMaxWidth()
            ) { Text("Inspect components") }
            reactSummary?.let { Text(it) }
        }

        DemoSection("Accessibility audit") {
            FilledTonalButton(
                onClick = {
                    run("page.accessibilityAudit()", "Collected accessibility issues", "audit accessibility",
                        "Open a page with visible content, then try again.") {
                        val report = page.accessibilityAudit()
                        auditSummary = "${report.counts.violations} violations · ${report.counts.incomplete} need review · " +
                            "${report.coverage.auditedFrames} frames audited · ${report.coverage.skippedFrames.size} skipped"
                    }
                }, enabled = !busy, modifier = Modifier.fillMaxWidth()
            ) { Text("Check accessibility") }
            auditSummary?.let { Text(it) }
        }

        DemoSection("Document-start scripts") {
            Text("Register code before a new page's scripts, then remove it when finished.")
            FilledTonalButton(
                onClick = {
                    run(
                        actionCode = "val id = page.addInitScript(\"window.ready = true\")\npage.setContent(html)\npage.removeInitScript(id)",
                        success = "The init script ran before the page script",
                        failureAction = "register a document-start script",
                        suggestion = "This feature requires a WebView with document-start script support."
                    ) {
                        val id = page.addInitScript("window.kmpwrightEarly = 'Ready before page scripts';")
                        try {
                            page.setContent("<h1>Document-start script</h1><script>document.title = window.kmpwrightEarly</script>")
                            check(page.evaluate("document.title").value == "Ready before page scripts")
                        } finally { page.removeInitScript(id) }
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Try an init script") }
        }

        result?.let { DemoStatus(it, kind = kind) }
        PageStatusBar(pageUrl, pageTitle)
        code?.let { CodeSnippet(it) }
    }
}
