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
import com.bernaferrari.kmpwright.core.refBoundingBoxes
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
    scope: CoroutineScope,
    private val releaseKeyboard: () -> Unit = {},
) {
    val page: Page = browser.page()

    private val sessionJob = SupervisorJob(scope.coroutineContext[Job])
    private val scope = CoroutineScope(scope.coroutineContext + sessionJob)
    private val pageLock = Mutex()
    private var nextId = 0L
    private var measureJob: Job? = null
    private var activityJob: Job? = null
    private var disposed = false
    private var refGeneration = 0
    private var watcherJob: Job? = null

    init {
        scope.launch {
            page.events.navigation.collect { clearRefs(); refreshRefs() }
        }
        watcherJob = scope.launch {
            var previous: String? = null
            while (isActive && !disposed) {
                delay(350)
                if (!showRefs || !pageLock.tryLock()) continue
                val signature = try {
                    page.evaluate(DOCUMENT_REVISION).value
                } catch (_: Exception) { null }
                finally { pageLock.unlock() }
                if (signature != null && signature != previous) {
                    previous = signature
                    clearRefs()
                    refreshRefs()
                }
            }
        }
    }

    fun showActivity(outcome: Activity) {
        activityJob?.cancel()
        activity = outcome
        activityJob = scope.launch { delay(4_000); if (activity == outcome) activity = null }
    }

    private fun trimConsole() {
        while (console.size > 100) console.removeAt(console.lastIndex)
        var bytes = 0L
        for (i in console.indices) {
            val image = console[i].image ?: continue
            val cost = image.width.toLong() * image.height * 4
            if (bytes + cost > 32L * 1024 * 1024) console[i] = console[i].copy(image = null)
            else bytes += cost
        }
    }

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
        if (disposed || line.isEmpty()) return
        if (line == "clear") {
            console.clear()
            return
        }
        val id = nextId++
        console.add(0, ConsoleEntry(id, line))
        trimConsole()
        scope.launch {
            val start = TimeSource.Monotonic.markNow()
            val entry = try {
                val result = withPage { it.runCommand(line) }
                result.snapshot?.let { showRefs(it) }
                ConsoleEntry(
                    id = id,
                    command = line,
                    output = result.output.take(100_000),
                    status = ConsoleEntry.Status.Ok,
                    durationMs = start.elapsedNow().inWholeMilliseconds,
                    image = result.screenshot?.takeIf { it.width.toLong() * it.height * 4 <= 32L * 1024 * 1024 }
                        ?.let { shot -> runCatching { shot.bytes.decodeToImageBitmap() }.getOrNull() },
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
            trimConsole()
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
        refGeneration++
        measureJob?.cancel()
        refs = emptyList()
    }

    private suspend fun showRefs(snapshot: Snapshot) {
        if (!showRefs) return
        val generation = refGeneration
        val measured = withPage { page ->
            val viewport = Json.parseToJsonElement(page.evaluate("JSON.stringify({width:innerWidth,x:scrollX,y:scrollY})").value).jsonObject
            val width = viewport["width"]?.jsonPrimitive?.doubleOrNull ?: 0.0
            val scrollX = viewport["x"]?.jsonPrimitive?.doubleOrNull ?: 0.0
            val scrollY = viewport["y"]?.jsonPrimitive?.doubleOrNull ?: 0.0
            val entries = snapshot.refs.values.sortedBy { it.ref.removePrefix("@e").toIntOrNull() ?: Int.MAX_VALUE }.take(MAX_BADGES)
            val boxes = page.refBoundingBoxes(entries.map { it.ref })
            width to entries.mapNotNull { entry ->
                boxes[entry.ref]?.takeIf { it.width > 0 && it.height > 0 }?.let { box ->
                    RefBadge(entry.ref, entry.role, entry.name, box.copy(x = box.x + scrollX, y = box.y + scrollY))
                }
            }
        }
        if (generation == refGeneration && !disposed) { viewportWidth = measured.first; refs = measured.second }
    }

    private fun changesPage(command: String): Boolean {
        val name = runCatching { AgentCommands.tokenize(command).firstOrNull()?.lowercase() }.getOrNull() ?: return false
        return name !in QUERY_COMMANDS
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        refGeneration++
        measureJob?.cancel()
        activityJob?.cancel()
        watcherJob?.cancel()
        sessionJob.cancel()
        console.clear()
        browser.dispose()
    }

    private companion object {
        const val MAX_BADGES = 80
        val DOCUMENT_REVISION = """
            (function() {
                var key = '__kmpwrightStudioRevision';
                var state = window[key];
                if (!state) {
                    state = {id: Math.random(), revision: 0}; window[key] = state;
                    var changed = function() { state.revision++; };
                    new MutationObserver(changed).observe(document, {subtree:true, childList:true, attributes:true, characterData:true});
                    document.addEventListener('input', changed, true);
                    document.addEventListener('change', changed, true);
                    window.addEventListener('scroll', changed, true);
                    if (typeof ResizeObserver !== 'undefined') new ResizeObserver(changed).observe(document.documentElement);
                }
                return [state.id, state.revision, innerWidth, innerHeight, location.href].join('|');
            })()
        """.trimIndent()
        val QUERY_COMMANDS = setOf(
            "help", "snapshot", "get", "is", "console", "errors", "cookies", "storage", "screenshot", "wait",
        )
    }
}
