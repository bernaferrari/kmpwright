@file:OptIn(ExperimentalJsExport::class)

package com.bernaferrari.kmpwright.demo

import com.bernaferrari.kmpwright.core.AgentCommands
import com.bernaferrari.kmpwright.core.Browser
import com.bernaferrari.kmpwright.core.KmpwrightConfig
import com.bernaferrari.kmpwright.core.Page
import com.bernaferrari.kmpwright.core.WebPlatformContext
import com.bernaferrari.kmpwright.core.runCommand
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.promise
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.w3c.dom.HTMLIFrameElement
import kotlin.js.Promise
import kotlin.time.TimeSource

/** The result of one command, as shown in the demo terminal. */
@JsExport
public class CommandOutcome(
    public val ok: Boolean,
    public val output: String,
    public val durationMs: Int,
    /** Whether the command may have changed the page, so the ref overlay should be measured again. */
    public val changedPage: Boolean,
)

/** An interactive element of the latest snapshot; the box is in CSS pixels of the frame's *document*. */
@JsExport
public class RefBox(
    public val ref: String,
    public val role: String,
    public val name: String,
    public val x: Double,
    public val y: Double,
    public val width: Double,
    public val height: Double,
)

/**
 * Runs agent-browser style commands (`snapshot -i`, `click @e3`, `fill @e5 "Ada"`) against [frame]
 * with the same `Page.runCommand` the Android, iOS and JVM backends use.
 */
@JsExport
public class KmpwrightDemo(frame: HTMLIFrameElement) {
    private val browser = Browser(WebPlatformContext(frame), KmpwrightConfig())
    private val page: Page = browser.page()
    private val scope = MainScope()
    private val lock = Mutex()

    public fun run(command: String): Promise<CommandOutcome> = scope.promise {
        val line = command.trim()
        val start = TimeSource.Monotonic.markNow()
        lock.withLock {
            try {
                val result = page.runCommand(line)
                CommandOutcome(true, result.output, start.elapsedNow().inWholeMilliseconds.toInt(), changesPage(line))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                CommandOutcome(false, error.message ?: error.toString(), start.elapsedNow().inWholeMilliseconds.toInt(), changesPage(line))
            }
        }
    }

    /** Snapshots the interactive elements and measures them for the `@eN` overlay. */
    public fun refs(): Promise<Array<RefBox>> = scope.promise {
        lock.withLock {
            val snapshot = page.snapshot(interactive = true)
            val scrollX = page.evaluate("window.scrollX").value.toDoubleOrNull() ?: 0.0
            val scrollY = page.evaluate("window.scrollY").value.toDoubleOrNull() ?: 0.0
            snapshot.refs.values
                .sortedBy { it.ref.removePrefix("@").removePrefix("e").toIntOrNull() ?: Int.MAX_VALUE }
                .mapNotNull { entry ->
                    val box = runCatching { page.locator(entry.ref).boundingBox() }.getOrNull()
                        ?.takeIf { it.width > 0 && it.height > 0 } ?: return@mapNotNull null
                    RefBox(entry.ref, entry.role, entry.name, box.x + scrollX, box.y + scrollY, box.width, box.height)
                }
                .toTypedArray()
        }
    }

    public fun dispose() {
        scope.cancel()
        browser.dispose()
    }

    private fun changesPage(command: String): Boolean {
        val name = runCatching { AgentCommands.tokenize(command).firstOrNull()?.lowercase() }.getOrNull() ?: return false
        return name !in QUERY_COMMANDS
    }

    private companion object {
        val QUERY_COMMANDS = setOf("help", "snapshot", "get", "is", "console", "errors", "cookies", "storage", "wait")
    }
}
