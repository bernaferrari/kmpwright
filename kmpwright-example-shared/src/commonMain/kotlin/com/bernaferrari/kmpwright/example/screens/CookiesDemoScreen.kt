package com.bernaferrari.kmpwright.example.shared.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bernaferrari.kmpwright.core.Cookie
import com.bernaferrari.kmpwright.example.shared.CodeSnippet
import com.bernaferrari.kmpwright.example.shared.DemoHeader
import com.bernaferrari.kmpwright.example.shared.DemoSection
import com.bernaferrari.kmpwright.example.shared.DemoStatus
import com.bernaferrari.kmpwright.example.shared.DemoStatusKind
import com.bernaferrari.kmpwright.example.shared.LocalPageManager
import com.bernaferrari.kmpwright.example.shared.loadDemoPage
import kotlinx.coroutines.launch

@Composable
fun CookiesDemoScreen(modifier: Modifier = Modifier) {
    val page = LocalPageManager.current.page
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("session_id") }
    var value by remember { mutableStateOf("abc123") }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var kind by remember { mutableStateOf(DemoStatusKind.Info) }
    var code by remember { mutableStateOf<String?>(null) }
    var confirmClear by remember { mutableStateOf(false) }

    val html = "<!doctype html><html><head><title>Cookie playground</title></head><body><h1>Cookies</h1></body></html>"

    fun run(actionCode: String, action: suspend () -> String) {
        if (busy) return
        busy = true
        scope.launch {
            try { result = action(); code = actionCode; kind = DemoStatusKind.Success }
            catch (error: Exception) { result = error.message ?: "Cookie action failed"; kind = DemoStatusKind.Error }
            finally { busy = false }
        }
    }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        DemoHeader("", "Cookies", "Set, inspect, and remove browser cookies.")
        DemoSection("Cookie") {
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Name") }, singleLine = true)
            OutlinedTextField(value, { value = it }, Modifier.fillMaxWidth(), label = { Text("Value") }, singleLine = true)
            Button(
                onClick = {
                    run("page.setCookie(Cookie(\"$name\", \"$value\"))") {
                        loadDemoPage(page, html)
                        page.setCookie(Cookie(name = name, value = value, domain = "example.com"))
                        "Cookie set"
                    }
                }, enabled = !busy && name.isNotBlank(), modifier = Modifier.fillMaxWidth()
            ) { Text("Set cookie") }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    onClick = { run("page.getAllCookies()") { val all = page.getAllCookies(); if (all.isEmpty()) "No cookies" else all.joinToString("\n") { "${it.name}=${it.value}" } } },
                    enabled = !busy, modifier = Modifier.weight(1f)
                ) { Text("List") }
                FilledTonalButton(
                    onClick = { run("page.deleteCookie(\"$name\", \"example.com\")") { page.deleteCookie(name, "example.com"); "Cookie deleted" } },
                    enabled = !busy && name.isNotBlank(), modifier = Modifier.weight(1f)
                ) { Text("Delete") }
            }
        }

        DemoSection("Session snapshot") {
            Text("Keep cookies and storage in memory, then restore them in this browser session.")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { run("page.saveState(\"example-session\")") { page.saveState("example-session"); "Session saved in memory" } },
                    enabled = !busy, modifier = Modifier.weight(1f)) { Text("Save session") }
                FilledTonalButton(onClick = { run("page.loadState(\"example-session\")") { if (page.loadState("example-session")) "Session restored" else "Save a session first" } },
                    enabled = !busy, modifier = Modifier.weight(1f)) { Text("Restore session") }
            }
        }

        result?.let { DemoStatus(it, kind = kind) }
        code?.let { CodeSnippet(it) }
        OutlinedButton(onClick = { confirmClear = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Clear all cookies") }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear all cookies?") },
            text = { Text("This clears the current browser session.") },
            confirmButton = {
                Button(onClick = {
                    confirmClear = false
                    run("page.deleteAllCookies()") { page.deleteAllCookies(); "All cookies cleared" }
                }) { Text("Clear") }
            },
            dismissButton = { OutlinedButton(onClick = { confirmClear = false }) { Text("Cancel") } }
        )
    }
}
