package com.bernaferrari.kmpwright.example.shared.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.bernaferrari.kmpwright.core.ScreenshotOptions
import com.bernaferrari.kmpwright.core.ScreenshotResult
import com.bernaferrari.kmpwright.example.shared.CodeSnippet
import com.bernaferrari.kmpwright.example.shared.DemoHeader
import com.bernaferrari.kmpwright.example.shared.DemoSection
import com.bernaferrari.kmpwright.example.shared.DemoStatus
import com.bernaferrari.kmpwright.example.shared.DemoStatusKind
import com.bernaferrari.kmpwright.example.shared.LocalPageManager
import com.bernaferrari.kmpwright.example.shared.loadDemoPage
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import com.bernaferrari.kmpwright.core.ScreencastOptions
import com.bernaferrari.kmpwright.core.SupportLevel
import org.jetbrains.compose.resources.decodeToImageBitmap

@Composable
fun ScreenshotDemoScreen(modifier: Modifier = Modifier) {
    val page = LocalPageManager.current.page
    val scope = rememberCoroutineScope()
    var loaded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var kind by remember { mutableStateOf(DemoStatusKind.Info) }
    var code by remember { mutableStateOf<String?>(null) }
    var capturedImage by remember { mutableStateOf<ScreenshotResult?>(null) }

    val html = """
        <!doctype html><html><head><title>Capture playground</title>
        <style>body{font-family:system-ui;margin:0}.hero{height:70vh;display:grid;place-items:center;background:linear-gradient(135deg,#4359a9,#006b5c);color:white}.more{height:100vh;padding:32px}</style>
        </head><body><section class="hero"><h1>Kmpwright</h1></section><section class="more"><h2>Full-page content</h2></section></body></html>
    """.trimIndent()

    fun capture(fullPage: Boolean) {
        if (!loaded || busy) return
        busy = true
        scope.launch {
            try {
                val image = page.screenshot(ScreenshotOptions(fullPage = fullPage))
                capturedImage = image
                code = "page.screenshot(ScreenshotOptions(fullPage = $fullPage))"
                result = "Captured ${image.width} × ${image.height} · ${image.bytes.size} bytes"
                kind = DemoStatusKind.Success
            } catch (error: Exception) {
                result = error.message ?: "Capture failed"; kind = DemoStatusKind.Error
            } finally { busy = false }
        }
    }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        DemoHeader("", "Screenshots", "Capture the viewport or the entire page.")
        Button(
            onClick = {
                busy = true
                scope.launch {
                    try {
                        loadDemoPage(page, html)
                        loaded = true
                        capturedImage = null
                        result = "Playground loaded"
                        kind = DemoStatusKind.Success
                    }
                    catch (error: Exception) { result = error.message ?: "Could not load playground"; kind = DemoStatusKind.Error }
                    finally { busy = false }
                }
            }, enabled = !busy, modifier = Modifier.fillMaxWidth()
        ) { Text(if (loaded) "Reload playground" else "Load playground") }

        DemoSection("Capture") {
            Button(
                onClick = { capture(false) },
                enabled = loaded && !busy,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Capture viewport") }
            FilledTonalButton(
                onClick = { capture(true) },
                enabled = loaded && !busy,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Capture full page") }
            FilledTonalButton(
                onClick = {
                    busy = true
                    scope.launch {
                        try {
                            val frame = withTimeout(10_000) { page.screencast(ScreencastOptions()).first() }
                            val bitmap = frame.bytes.decodeToImageBitmap()
                            capturedImage = ScreenshotResult(frame.bytes, bitmap.width, bitmap.height)
                            code = "page.screencast(ScreencastOptions()).first()"
                            result = "Captured a live frame; capture stopped automatically"
                            kind = DemoStatusKind.Success
                        } catch (error: Exception) { result = error.message ?: "Live capture failed"; kind = DemoStatusKind.Error }
                        finally { busy = false }
                    }
                }, enabled = loaded && !busy && page.capabilities().screencast != SupportLevel.Unsupported,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Capture a live frame") }
            if (page.capabilities().screencast == SupportLevel.Unsupported) Text("Live capture requires a CDP browser.")
        }

        result?.let { DemoStatus(it, kind = kind) }
        capturedImage?.let { ScreenshotPreview(it) }
        code?.let { CodeSnippet(it) }
    }
}

@Composable
private fun ScreenshotPreview(screenshot: ScreenshotResult) {
    val bitmap = remember(screenshot.bytes) {
        runCatching { screenshot.bytes.decodeToImageBitmap() }.getOrNull()
    }
    DemoSection(
        title = "Preview",
        supportingText = "The image below is the screenshot returned by Kmpwright."
    ) {
        if (bitmap == null) {
            DemoStatus(
                message = "The screenshot was captured, but its image data could not be decoded.",
                kind = DemoStatusKind.Error
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 160.dp, max = 320.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(androidx.compose.material3.MaterialTheme.colorScheme.surface)
            ) {
                Image(
                    bitmap = bitmap,
                    contentDescription = "Screenshot preview, ${screenshot.width} by ${screenshot.height} pixels",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            }
        }
    }
}
