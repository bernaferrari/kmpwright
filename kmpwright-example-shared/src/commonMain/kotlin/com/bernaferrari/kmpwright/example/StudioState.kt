package com.bernaferrari.kmpwright.example

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.decodeToImageBitmap
import com.bernaferrari.kmpwright.core.AgentCommands
import com.bernaferrari.kmpwright.core.Browser
import com.bernaferrari.kmpwright.core.Page
import com.bernaferrari.kmpwright.core.Rectangle
import com.bernaferrari.kmpwright.core.Snapshot
import com.bernaferrari.kmpwright.core.runCommand
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.TimeSource

/** A recipe's progress line; [done] is null while running, then true or false. */
data class Activity(val text: String, val done: Boolean? = null)

/** One line in the agent console. */
data class ConsoleEntry(
    val id: Long,
    val command: String,
    val output: String = "",
    val status: Status = Status.Running,
    val durationMs: Long = 0,
    val image: ImageBitmap? = null,
) {
    enum class Status { Running, Ok, Error }
}

/**
 * An interactive element from the latest snapshot. [box] is in CSS pixels
 * relative to the *document*, so the overlay only needs the live scroll
 * offset to place it — no JavaScript round trip while the page scrolls.
 */
data class RefBadge(val ref: String, val role: String, val name: String, val box: Rectangle)

/**
 * Owns the sample's browser page and everything the UI shows about it. Page
 * work is serialized through one lock so the console, recipes and the ref
 * overlay never interleave actions on the same WebView.
 */
@Stable
class StudioState(
    val browser: Browser,
    private val scope: CoroutineScope,
    private val releaseKeyboard: () -> Unit = {},
) {
    val page: Page = browser.page()

    private val pageLock = Mutex()
    private var nextId = 0L
    private var measureJob: Job? = null

    /** Newest first, so the latest result sits right under the composer. */
    val console = mutableStateListOf<ConsoleEntry>()

    /** Text in the command field; long-pressing a ref badge writes into it. */
    var draft by mutableStateOf("")

    var busy by mutableStateOf(false)
        private set

    /** What a recipe is doing (or just did), shown while the sheet is collapsed. */
    var activity by mutableStateOf<Activity?>(null)

    var showRefs by mutableStateOf(true)

    var refs by mutableStateOf<List<RefBadge>>(emptyList())
        private set

    /** CSS width of the layout viewport when [refs] were measured. */
    var viewportWidth by mutableStateOf(0.0)
        private set

    /** Live document scroll in CSS pixels, updated every frame by the browser pane. */
    var scroll by mutableStateOf(Offset.Zero)

    /** Runs a page operation exclusively. */
    suspend fun <T> withPage(block: suspend (Page) -> T): T = pageLock.withLock {
        busy = true
        try {
            block(page)
        } finally {
            busy = false
        }
    }

    /** Runs one agent-browser style command and records it in the console. */
    fun run(command: String) {
        val line = command.trim()
        if (line.isEmpty()) return
        if (line == "clear") {
            console.clear()
            return
        }
        val id = nextId++
        console.add(0, ConsoleEntry(id, line))
        scope.launch {
            val start = TimeSource.Monotonic.markNow()
            val entry = try {
                val result = withPage { it.runCommand(line) }
                result.snapshot?.let { showRefs(it) }
                ConsoleEntry(
                    id = id,
                    command = line,
                    output = result.output,
                    status = ConsoleEntry.Status.Ok,
                    durationMs = start.elapsedNow().inWholeMilliseconds,
                    image = result.screenshot?.let { shot -> runCatching { shot.bytes.decodeToImageBitmap() }.getOrNull() },
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                ConsoleEntry(
                    id = id,
                    command = line,
                    output = error.message ?: error.toString(),
                    status = ConsoleEntry.Status.Error,
                    durationMs = start.elapsedNow().inWholeMilliseconds,
                )
            }
            val index = console.indexOfFirst { it.id == id }
            if (index >= 0) console[index] = entry
            releaseKeyboard()
            if (entry.status == ConsoleEntry.Status.Ok && changesPage(line)) refreshRefs()
        }
    }

    /** Loads one of the bundled demo pages and takes a fresh snapshot. */
    fun loadDemo(html: String = DemoSite.playground) {
        scope.launch {
            runCatching { withPage { it.setContent(html) } }
            refreshRefs()
        }
    }

    /** Re-snapshots interactive elements so the overlay matches the page again. */
    fun refreshRefs() {
        if (!showRefs) return
        measureJob?.cancel()
        measureJob = scope.launch {
            try {
                showRefs(withPage { it.snapshot(interactive = true) })
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                refs = emptyList()
            }
        }
    }

    /** Hides the keyboard if automation left the page's input focused. */
    fun releasePageKeyboard() = releaseKeyboard()

    fun clearRefs() {
        measureJob?.cancel()
        refs = emptyList()
    }

    private suspend fun showRefs(snapshot: Snapshot) {
        if (!showRefs) return
        refs = withPage { page ->
            viewportWidth = page.evaluate("window.innerWidth").value.toDoubleOrNull() ?: 0.0
            val scrollX = page.evaluate("window.scrollX").value.toDoubleOrNull() ?: 0.0
            val scrollY = page.evaluate("window.scrollY").value.toDoubleOrNull() ?: 0.0
            snapshot.refs.values
                .sortedBy { it.ref.removePrefix("@").removePrefix("e").toIntOrNull() ?: Int.MAX_VALUE }
                .take(MAX_BADGES)
                .mapNotNull { entry ->
                    val box = runCatching { page.locator(entry.ref).boundingBox() }.getOrNull()
                        ?.takeIf { it.width > 0 && it.height > 0 }
                        ?: return@mapNotNull null
                    // Store document coordinates; the overlay subtracts the live scroll.
                    RefBadge(entry.ref, entry.role, entry.name, box.copy(x = box.x + scrollX, y = box.y + scrollY))
                }
        }
    }

    private fun changesPage(command: String): Boolean {
        val name = runCatching { AgentCommands.tokenize(command).firstOrNull()?.lowercase() }.getOrNull() ?: return false
        return name !in QUERY_COMMANDS
    }

    fun dispose() {
        measureJob?.cancel()
        browser.dispose()
    }

    private companion object {
        const val MAX_BADGES = 80
        val QUERY_COMMANDS = setOf(
            "help", "snapshot", "get", "is", "console", "errors", "cookies", "storage", "screenshot", "wait",
        )
    }
}
