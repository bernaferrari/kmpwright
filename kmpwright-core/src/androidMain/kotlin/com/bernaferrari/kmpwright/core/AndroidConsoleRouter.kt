package com.bernaferrari.kmpwright.core

/**
 * Routes `WebChromeClient.onConsoleMessage` callbacks. Android WebView has no script bridge by design,
 * so the document-start diagnostics hook reports through a nonce-prefixed `console.debug` call in the
 * same ordered channel; those sentinel messages are consumed here and never reach the host client or
 * [Page.consoleMessages].
 *
 * Chromium also logs every uncaught exception as an `error` console message (`Uncaught Error: boom`,
 * `Uncaught (in promise) ...`). Playwright reports those only as page errors, so such a message is
 * dropped when the hook already reported it (the hook's sentinel always precedes it), and otherwise
 * (hook unavailable for this document) becomes the page error itself.
 */
internal class AndroidConsoleRouter(
    private val nonce: String,
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) {
    sealed interface Route {
        /** Internal sentinel: consume, do not forward to the host. */
        data object Internal : Route
        /** A real console message reported by the hook's page error already: forward to the host only. */
        data object HostOnly : Route
        data class Console(val message: ConsoleMessage) : Route
        data class Error(val error: PageError, val internal: Boolean) : Route
        data object DocumentStarted : Route
    }

    private class Expected(val message: String, val atMs: Long)
    private val expected = ArrayDeque<Expected>()
    private val lock = Any()

    /** [type] is the Playwright console type mapped from `ConsoleMessage.MessageLevel`. */
    fun route(type: String, text: String, sourceId: String?, lineNumber: Int): Route {
        if (text.startsWith(nonce)) {
            return when (val diagnostic = parsePageDiagnosticPayload(text.substring(nonce.length), nonce)) {
                is PageDiagnostic.Error -> {
                    synchronized(lock) {
                        prune()
                        if (expected.size >= MAX_EXPECTED) expected.removeFirst()
                        expected.addLast(Expected(diagnostic.error.message, nowMs()))
                    }
                    Route.Error(diagnostic.error, internal = true)
                }
                PageDiagnostic.DocumentStarted -> Route.DocumentStarted
                else -> Route.Internal
            }
        }
        val location = SourceLocation(sourceId.orEmpty(), lineNumber, 0)
        if (type == "error" && text.startsWith(UNCAUGHT)) {
            val consumed = synchronized(lock) {
                prune()
                val match = expected.firstOrNull { it.message.isEmpty() || text.contains(it.message) }
                if (match != null) expected.remove(match)
                match != null
            }
            if (consumed) return Route.HostOnly
            val detail = text.removePrefix(UNCAUGHT).removePrefix(IN_PROMISE)
            val (name, message) = splitErrorMessage(detail)
            return Route.Error(PageError(name, message, null, location), internal = false)
        }
        return Route.Console(ConsoleMessage(type, text, location))
    }

    private fun prune() {
        val cutoff = nowMs() - EXPECTATION_TTL_MS
        while (expected.isNotEmpty() && expected.first().atMs < cutoff) expected.removeFirst()
    }

    private companion object {
        const val UNCAUGHT = "Uncaught "
        const val IN_PROMISE = "(in promise) "
        const val MAX_EXPECTED = 32
        const val EXPECTATION_TTL_MS = 5_000L
    }
}

/** `ConsoleMessage.MessageLevel` name to Playwright's console type. */
internal fun androidConsoleType(level: String?): String = when (level) {
    "DEBUG" -> "debug"
    "TIP" -> "info"
    "WARNING" -> "warning"
    "ERROR" -> "error"
    else -> "log"
}
