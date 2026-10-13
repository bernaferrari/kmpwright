package com.bernaferrari.kmpwright.core

import kotlin.coroutines.cancellation.CancellationException

/**
 * Result of [Page.runCommand].
 *
 * [output] is plain text meant to be shown to a person or returned to a model
 * as a tool result. Commands that produce structured data also expose it, so
 * UIs can render it without parsing [output].
 */
public data class AgentCommandResult(
    val command: String,
    val output: String,
    val snapshot: Snapshot? = null,
    val screenshot: ScreenshotResult? = null,
)

/**
 * Runs one command line written in the
 * [agent-browser](https://github.com/vercel-labs/agent-browser) command style.
 *
 * This is the smallest useful surface to hand to an LLM as a single
 * "browser" tool: the model reads `snapshot -i`, picks an `@eN` ref, and acts
 * with `click @e3` or `fill @e2 "hello"`. Targets accept refs from the latest
 * snapshot and any selector understood by [Page.locator].
 *
 * Arguments are split on whitespace; wrap values containing spaces in single
 * or double quotes. Run `help` for the list of commands.
 *
 * Flags may appear anywhere after the command name (`wait --timeout 5000 @e3`), and a flag value
 * is one argument: quote values with spaces (`wait --text "Welcome back"`).
 *
 * @throws KmpwrightException when the command is unknown, malformed, unsupported by this backend
 *   or fails; every failure surfaces as [KmpwrightException] (or a subclass such as
 *   [KmpwrightTimeoutException]) so tool loops can catch a single type.
 */
@Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
public suspend fun Page.runCommand(command: String): AgentCommandResult {
    val args = AgentCommands.tokenize(command)
    if (args.isEmpty()) throw KmpwrightException("Empty command. Run `help` to list commands.")
    val name = args[0].lowercase()
    return try {
        runParsedCommand(command, name, args.drop(1))
    } catch (e: kotlin.coroutines.cancellation.CancellationException) {
        throw e
    } catch (e: KmpwrightException) {
        throw e
    } catch (e: UnsupportedOperationException) {
        throw KmpwrightUnsupportedException("`$name` is unavailable on this backend: ${e.message ?: "unsupported"}", e)
    } catch (e: Exception) {
        throw KmpwrightException("`$name` failed: ${e.message ?: e::class.simpleName}", e)
    }
}

private suspend fun Page.runParsedCommand(command: String, name: String, rest: List<String>): AgentCommandResult {

    fun result(output: String) = AgentCommandResult(command, output)
    fun arg(index: Int, usage: String): String =
        rest.getOrNull(index) ?: throw KmpwrightException("Usage: $usage")
    fun textArg(from: Int, usage: String): String =
        rest.drop(from).joinToString(" ").ifEmpty { throw KmpwrightException("Usage: $usage") }

    return when (name) {
        "help" -> result(AgentCommands.HELP)
        "open", "goto", "navigate" -> {
            val url = AgentCommands.normalizeUrl(arg(0, "open <url>"))
            val navigation = goto(url)
            if (!navigation.ok) throw KmpwrightException(navigation.error ?: "Navigation to $url failed")
            result("Opened ${navigation.url}" + (title.value?.takeIf(String::isNotBlank)?.let { " — $it" } ?: ""))
        }
        "back" -> { goBack(); result("Went back to ${url.value.orEmpty()}") }
        "forward" -> { goForward(); result("Went forward to ${url.value.orEmpty()}") }
        "reload" -> { reload(); result("Reloaded ${url.value.orEmpty()}") }

        "snapshot" -> {
            val flags = AgentCommands.parseFlags(rest, "snapshot", AgentCommands.SNAPSHOT_FLAGS)
            flags.noPositionals("snapshot [-i] [-u] [-c] [-d <depth>] [-s <selector>]")
            val interactive = flags.has("-i", "--interactive")
            val snapshot = snapshot(
                SnapshotOptions(
                    interactive = interactive,
                    compact = flags.has("-c", "--compact"),
                    maxDepth = flags.value("-d", "--depth")?.let {
                        it.toIntOrNull()?.takeIf { depth -> depth >= 0 } ?: throw KmpwrightException("--depth expects a non-negative number, got '$it'")
                    },
                    selector = flags.value("-s", "--selector"),
                    cursor = flags.has("-C", "--cursor"),
                    urls = flags.has("-u", "--urls"),
                )
            )
            val empty = if (interactive) "(no interactive elements)" else "(empty page)"
            AgentCommandResult(command, snapshot.tree.trimEnd().ifEmpty { empty }, snapshot = snapshot)
        }

        "click" -> { locator(arg(0, "click <target>")).click(); result("Clicked ${rest[0]}") }
        "dblclick" -> { locator(arg(0, "dblclick <target>")).dblclick(); result("Double-clicked ${rest[0]}") }
        "tap" -> { locator(arg(0, "tap <target>")).tap(); result("Tapped ${rest[0]}") }
        "hover" -> { locator(arg(0, "hover <target>")).hover(); result("Hovered ${rest[0]}") }
        "focus" -> { locator(arg(0, "focus <target>")).focus(); result("Focused ${rest[0]}") }
        "check" -> { locator(arg(0, "check <target>")).check(); result("Checked ${rest[0]}") }
        "uncheck" -> { locator(arg(0, "uncheck <target>")).uncheck(); result("Unchecked ${rest[0]}") }
        "highlight" -> { locator(arg(0, "highlight <target>")).highlight(); result("Highlighted ${rest[0]}") }
        "scrollintoview", "scrollinto" -> {
            locator(arg(0, "scrollintoview <target>")).scrollIntoViewIfNeeded()
            result("Scrolled ${rest[0]} into view")
        }
        "fill" -> {
            val target = arg(0, "fill <target> <text>")
            val text = rest.drop(1).joinToString(" ")
            locator(target).fill(text)
            result("Filled $target")
        }
        "type" -> {
            val target = arg(0, "type <target> <text>")
            locator(target).pressSequentially(textArg(1, "type <target> <text>"))
            result("Typed into $target")
        }
        "press", "key" -> {
            val key = arg(0, "press <key> [target]")
            val target = rest.getOrNull(1)
            if (target != null) locator(target).press(key) else keyboard.press(key)
            result("Pressed $key")
        }
        "select" -> {
            val target = arg(0, "select <target> <value>")
            locator(target).selectOption(textArg(1, "select <target> <value>"))
            result("Selected ${rest.drop(1).joinToString(" ")} in $target")
        }
        "drag" -> {
            val source = arg(0, "drag <source> <target>")
            val target = arg(1, "drag <source> <target>")
            locator(source).dragTo(locator(target))
            result("Dragged $source to $target")
        }
        "scroll" -> {
            val direction = arg(0, "scroll <up|down|left|right|top|bottom> [pixels]").lowercase()
            val distance = rest.getOrNull(1)?.toDoubleOrNull() ?: AgentCommands.DEFAULT_SCROLL
            when (direction) {
                "down" -> scroll(ScrollDirection.Down, distance)
                "up" -> scroll(ScrollDirection.Up, distance)
                "left" -> scroll(ScrollDirection.Left, distance)
                "right" -> scroll(ScrollDirection.Right, distance)
                "top" -> scrollToTop()
                "bottom" -> scrollToBottom()
                else -> throw KmpwrightException("Usage: scroll <up|down|left|right|top|bottom> [pixels]")
            }
            result("Scrolled $direction")
        }

        "get" -> {
            val what = arg(0, "get <text|html|value|attr|count|box|title|url> [target]").lowercase()
            when (what) {
                "title" -> result((this as? BasePage)?.liveTitle() ?: title.value.orEmpty())
                "url" -> result(url.value.orEmpty())
                "text" -> result(locator(arg(1, "get text <target>")).innerText())
                "html" -> result(locator(arg(1, "get html <target>")).innerHTML())
                "value" -> result(locator(arg(1, "get value <target>")).inputValue().orEmpty())
                "count" -> result(locator(arg(1, "get count <selector>")).count().toString())
                "attr" -> result(locator(arg(1, "get attr <target> <name>")).getAttribute(arg(2, "get attr <target> <name>")) ?: "null")
                "box" -> result(
                    locator(arg(1, "get box <target>")).boundingBox()
                        ?.let { "x=${it.x} y=${it.y} width=${it.width} height=${it.height}" } ?: "null"
                )
                else -> throw KmpwrightException("Unknown `get $what`. Try text, html, value, attr, count, box, title or url.")
            }
        }
        "is" -> {
            val state = arg(0, "is <visible|enabled|checked|focused|editable> <target>").lowercase()
            val locator = locator(arg(1, "is $state <target>"))
            val value = when (state) {
                "visible" -> locator.isVisible()
                "enabled" -> locator.isEnabled()
                "checked" -> locator.isChecked()
                "focused" -> locator.isFocused()
                "editable" -> locator.isEditable()
                else -> throw KmpwrightException("Unknown `is $state`. Try visible, enabled, checked, focused or editable.")
            }
            result(value.toString())
        }
        "wait" -> {
            val usage = "wait <ms|target> | --text <text> | --url <pattern> | --load <state> | --fn <js> [--timeout <ms>]"
            val flags = AgentCommands.parseFlags(rest, "wait", AgentCommands.WAIT_FLAGS)
            val timeout = flags.value("--timeout")?.let {
                it.toLongOrNull()?.takeIf { ms -> ms > 0 } ?: throw KmpwrightException("--timeout expects a positive number of ms, got '$it'")
            } ?: AgentCommands.DEFAULT_WAIT_MS
            val text = flags.value("--text", "-t")
            val url = flags.value("--url", "-u")
            val load = flags.value("--load", "-l")
            val fn = flags.value("--fn", "-f")
            val conditions = listOfNotNull(text, url, load, fn).size
            if (conditions > 1) throw KmpwrightException("Usage: $usage (one condition at a time)")
            if (conditions == 1) flags.noPositionals(usage)
            else if (flags.positionals.size > 1) throw KmpwrightException("Usage: $usage (got ${flags.positionals.size} targets; quote a selector that contains spaces)")
            val first = flags.positionals.firstOrNull()
            when {
                text != null -> { waitForText(text, timeout); result("Found text \"$text\"") }
                fn != null -> { waitForFunction(fn, timeout); result("Condition is true") }
                url != null -> { waitForURL(url, WaitForURLOptions(timeoutMs = timeout)); result("Reached ${this.url.value.orEmpty()}") }
                load != null -> {
                    val state = AgentCommands.loadState(load)
                    waitForLoadState(state, timeout)
                    result("Reached $load")
                }
                first == null -> throw KmpwrightException("Usage: $usage")
                first.toLongOrNull() != null -> { waitForTimeout(first.toLong()); result("Waited ${first}ms") }
                else -> {
                    locator(first).waitFor(SelectorState.Visible, timeout)
                    result("$first is visible")
                }
            }
        }

        "eval" -> result(evaluate(textArg(0, "eval <javascript>")).value)
        "screenshot" -> {
            val flags = AgentCommands.parseFlags(rest, "screenshot", AgentCommands.SCREENSHOT_FLAGS)
            if (flags.positionals.isNotEmpty()) throw KmpwrightException(
                "Usage: screenshot [--full] [--annotate] (no file path: the image is returned in the command result)"
            )
            val fullPage = flags.has("--full", "-f")
            if (flags.has("--annotate", "-a")) {
                val annotated = screenshot(ScreenshotOptions(fullPage = fullPage, annotate = true))
                val legend = annotated.annotations.joinToString("\n") { AgentCommands.annotationLine(it) }
                AgentCommandResult(
                    command,
                    "Screenshot ${annotated.width}×${annotated.height}" +
                        if (legend.isEmpty()) "" else "\n$legend",
                    screenshot = annotated,
                )
            } else {
                val shot = screenshot(ScreenshotOptions(fullPage = fullPage))
                AgentCommandResult(command, "Screenshot ${shot.width}×${shot.height}", screenshot = shot)
            }
        }

        "console" -> result(
            consoleMessages().joinToString("\n") { "[${it.type}] ${it.text}" }.ifEmpty { "No console messages" }
        )
        "errors" -> result(pageErrors().joinToString("\n").ifEmpty { "No page errors" })
        "cookies" -> when (rest.firstOrNull()?.lowercase()) {
            null, "get", "list" -> result(
                cookies().joinToString("\n") { "${it.name}=${it.value}  (${it.domain}${it.path})" }
                    .ifEmpty { "No cookies" }
            )
            "clear" -> { clearCookies(); result("Cleared cookies") }
            else -> throw KmpwrightException("Usage: cookies [list|clear]")
        }
        "storage" -> {
            val area = arg(0, "storage <local|session> [clear]").lowercase()
            val clear = rest.getOrNull(1)?.lowercase() == "clear"
            val entries = when (area) {
                "local" -> if (clear) { localStorage.clear(); null } else localStorage.entries()
                "session" -> if (clear) { sessionStorage.clear(); null } else sessionStorage.entries()
                else -> throw KmpwrightException("Usage: storage <local|session> [clear]")
            }
            result(
                entries?.entries?.joinToString("\n") { "${it.key}=${it.value}" }?.ifEmpty { "No $area storage" }
                    ?: "Cleared $area storage"
            )
        }
        else -> throw KmpwrightException("Unknown command `$name`. Run `help` to list commands.")
    }
}

/** Parsing helpers and reference text for [Page.runCommand]. */
public object AgentCommands {
    internal const val DEFAULT_SCROLL = 300.0
    internal const val DEFAULT_WAIT_MS = 10_000L

    /** Short reference suitable for a tool description or a `help` command. */
    public val HELP: String = """
        Navigation   open <url> · back · forward · reload
        Observe      snapshot [-i interactive] [-u urls] [-c compact] [-d depth] [-s selector]
                     screenshot [--full] [--annotate]
        Act          click|dblclick|tap|hover|focus|check|uncheck|highlight <target>
                     fill <target> <text> · type <target> <text> · press <key> [target]
                     select <target> <value> · drag <source> <target>
                     scroll <up|down|left|right|top|bottom> [px] · scrollintoview <target>
        Query        get <text|html|value|count|box|title|url> [target] · get attr <target> <name>
                     is <visible|enabled|checked|focused|editable> <target>
        Wait         wait <ms|target> · wait --text <text> · wait --load <load|domcontentloaded|networkidle>
                     wait --url <pattern> · wait --fn <js>  (all accept --timeout <ms>)
        Page         eval <js> · console · errors · cookies [clear] · storage <local|session> [clear]

        Targets are snapshot refs such as @e3, or any selector: css, text=Save, role=button, label=Email.
        Flags go anywhere; quote values with spaces: wait --text "Welcome back".
    """.trimIndent()

    /**
     * Splits a command line into arguments. Single and double quotes group
     * words, and a backslash escapes the next character.
     */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class)
    public fun tokenize(command: String): List<String> {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var inToken = false
        var index = 0
        while (index < command.length) {
            val char = command[index]
            when {
                char == '\\' && index + 1 < command.length -> {
                    current.append(command[++index]); inToken = true
                }
                quote != null && char == quote -> quote = null
                quote != null -> current.append(char)
                char == '"' || char == '\'' -> { quote = char; inToken = true }
                char.isWhitespace() -> if (inToken) {
                    tokens += current.toString(); current.clear(); inToken = false
                }
                else -> { current.append(char); inToken = true }
            }
            index++
        }
        if (quote != null) throw KmpwrightException("Unterminated $quote quote in command")
        if (inToken) tokens += current.toString()
        return tokens
    }

    /** Adds `https://` to bare hosts such as `example.com`, like agent-browser's `open`. */
    public fun normalizeUrl(input: String): String {
        val trimmed = input.trim()
        return when {
            trimmed.contains("://") || trimmed.startsWith("about:") || trimmed.startsWith("data:") -> trimmed
            trimmed.startsWith("localhost") || trimmed.startsWith("127.0.0.1") || trimmed.startsWith("10.0.2.2") ->
                "http://$trimmed"
            else -> "https://$trimmed"
        }
    }

    /**
     * One `screenshot --annotate` legend line in agent-browser's format: `[1] @e1 button "Submit"`,
     * where label `[N]` on the image is ref `@eN`.
     */
    internal fun annotationLine(annotation: Annotation): String {
        val number = annotation.ref.removePrefix("@e")
        val role = annotation.role.takeIf { Regex("[A-Za-z0-9_-]+").matches(it) } ?: "generic"
        val name = annotation.name.replace(Regex("\\s+"), " ").trim()
        return "[$number] ${annotation.ref} $role" + if (name.isEmpty()) "" else " ${AccessibilitySnapshotEngine.quote(name)}"
    }

    internal fun loadState(name: String): LoadState = when (name.lowercase()) {
        "load" -> LoadState.Load
        "domcontentloaded", "dom" -> LoadState.DOMContentLoaded
        "networkidle", "idle" -> LoadState.NetworkIdle
        else -> throw KmpwrightException("Unknown load state `$name`. Try load, domcontentloaded or networkidle.")
    }

    /** A command flag: its spellings and whether it takes a value. */
    internal class FlagSpec(val names: Set<String>, val takesValue: Boolean)

    private fun switch(vararg names: String) = FlagSpec(names.toSet(), takesValue = false)
    private fun option(vararg names: String) = FlagSpec(names.toSet(), takesValue = true)

    internal val SNAPSHOT_FLAGS = listOf(
        switch("-i", "--interactive"), switch("-c", "--compact"), switch("-C", "--cursor"), switch("-u", "--urls"),
        option("-d", "--depth"), option("-s", "--selector"),
    )
    internal val WAIT_FLAGS = listOf(
        option("--timeout"), option("-t", "--text"), option("-u", "--url"), option("-l", "--load"), option("-f", "--fn"),
    )
    internal val SCREENSHOT_FLAGS = listOf(switch("-f", "--full"), switch("-a", "--annotate"))

    /** Arguments split into flags (anywhere in the line) and the remaining positionals. */
    internal class Flags(
        val positionals: List<String>,
        private val switches: Set<String>,
        private val values: Map<String, String>,
    ) {
        fun has(vararg names: String): Boolean = names.any { it in switches }
        fun value(vararg names: String): String? = names.firstNotNullOfOrNull { values[it] }
        fun noPositionals(usage: String) {
            if (positionals.isNotEmpty()) throw KmpwrightException(
                "Usage: $usage (unexpected ${positionals.joinToString(" ") { "'$it'" }}; quote multi-word values)"
            )
        }
    }

    /**
     * Separates [specs] flags from positionals, in any order. A value flag consumes exactly the next
     * argument; unknown `-x` arguments are rejected rather than read as targets. Negative numbers
     * and a lone `-` stay positional.
     */
    internal fun parseFlags(args: List<String>, command: String, specs: List<FlagSpec>): Flags {
        val positionals = mutableListOf<String>()
        val switches = mutableSetOf<String>()
        val values = mutableMapOf<String, String>()
        var index = 0
        while (index < args.size) {
            val arg = args[index++]
            val spec = specs.firstOrNull { arg in it.names }
            when {
                spec == null && arg.length > 1 && arg.startsWith("-") && arg.toDoubleOrNull() == null ->
                    throw KmpwrightException("Unknown flag `$arg` for `$command`. Run `help` to list commands.")
                spec == null -> positionals += arg
                !spec.takesValue -> switches += spec.names
                else -> {
                    val value = args.getOrNull(index++) ?: throw KmpwrightException("`$command $arg` needs a value")
                    spec.names.forEach { values[it] = value }
                }
            }
        }
        return Flags(positionals, switches, values)
    }
}
