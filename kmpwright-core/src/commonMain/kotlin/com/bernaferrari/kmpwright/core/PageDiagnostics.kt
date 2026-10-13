package com.bernaferrari.kmpwright.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Receives page diagnostics from a backend: console API calls, uncaught exceptions / unhandled
 * rejections, and main-frame document commits (the boundary of [PageMessageFilter.SinceNavigation]).
 * Backends call it from any thread; implementations must not block.
 */
internal interface PageDiagnosticsListener {
    fun onConsoleMessage(message: ConsoleMessage)
    fun onPageError(error: PageError)
    fun onMainFrameDocumentCommitted()
}

/**
 * Diagnostics capability. The defaults are for test doubles only; every shipping backend
 * (Android WebView, WKWebView, CDP) produces console messages, page errors and commits.
 */
internal interface WebViewDiagnosticsController {
    fun addDiagnosticsListener(listener: PageDiagnosticsListener) {}
    fun removeDiagnosticsListener(listener: PageDiagnosticsListener) {}
}

/** Playwright's `ConsoleMessage.type()` values. */
internal val PLAYWRIGHT_CONSOLE_TYPES: Set<String> = setOf(
    "log", "debug", "info", "error", "warning", "dir", "dirxml", "table", "trace", "clear",
    "startGroup", "startGroupCollapsed", "endGroup", "assert", "profile", "profileEnd", "count", "timeEnd"
)

/** Upper bound for bridged console text, error messages and stacks. */
internal const val MAX_DIAGNOSTIC_TEXT_CHARS: Int = 32 * 1024

/** A diagnostic decoded from an injected-script payload. */
internal sealed interface PageDiagnostic {
    data class Console(val message: ConsoleMessage) : PageDiagnostic
    data class Error(val error: PageError) : PageDiagnostic
    /** The main-frame document started; sent before any page script of that document runs. */
    data object DocumentStarted : PageDiagnostic
}

/**
 * Playwright's `splitErrorMessage`: `"TypeError: x is undefined"` -> name `TypeError`, message
 * `x is undefined`; text without a colon has an empty name.
 */
internal fun splitErrorMessage(text: String): Pair<String, String> {
    val separator = text.indexOf(':')
    if (separator == -1) return "" to text
    val name = text.substring(0, separator)
    // A name never contains whitespace; "Uncaught (in promise) x: y" style text is all message.
    if (name.isEmpty() || name.any { it.isWhitespace() }) return "" to text
    return name to text.substring(minOf(text.length, separator + 2))
}

private val diagnosticJson = Json { ignoreUnknownKeys = true; isLenient = false }

/**
 * Decodes a payload posted by [buildPageDiagnosticsScript]. Untrusted page input: the nonce must
 * match, only known kinds and console types are accepted, and every string is bounded.
 */
internal fun parsePageDiagnosticPayload(json: String, nonce: String, maxPayloadChars: Int = 4 * MAX_DIAGNOSTIC_TEXT_CHARS): PageDiagnostic? {
    if (json.length > maxPayloadChars || nonce.isEmpty()) return null
    val payload = runCatching { diagnosticJson.parseToJsonElement(json).jsonObject }.getOrNull() ?: return null
    if (payload.string("nonce") != nonce) return null
    val url = payload.string("url")
    val line = (payload["line"] as? JsonPrimitive)?.intOrNull
    val column = (payload["column"] as? JsonPrimitive)?.intOrNull
    val location = if (url.isNullOrEmpty() && line == null) null
        else SourceLocation(url.orEmpty().take(4096), (line ?: 0).coerceAtLeast(0), (column ?: 0).coerceAtLeast(0))
    return when (payload.string("kind")) {
        "console" -> {
            val type = payload.string("type")?.takeIf { it in PLAYWRIGHT_CONSOLE_TYPES } ?: return null
            PageDiagnostic.Console(ConsoleMessage(type, payload.string("text").orEmpty().take(MAX_DIAGNOSTIC_TEXT_CHARS), location))
        }
        "pageerror" -> PageDiagnostic.Error(PageError(
            name = payload.string("name").orEmpty().take(256),
            message = payload.string("message").orEmpty().take(MAX_DIAGNOSTIC_TEXT_CHARS),
            stack = payload.string("stack")?.take(MAX_DIAGNOSTIC_TEXT_CHARS)?.ifEmpty { null },
            location = location
        ))
        "document" -> PageDiagnostic.DocumentStarted
        else -> null
    }
}

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

/**
 * Document-start script, injected in every frame, that reports uncaught exceptions and unhandled
 * promise rejections and, when [wrapConsole] is set (WKWebView, which has no console API),
 * console calls. The main frame also announces itself (`document`) before any page script runs,
 * which delimits [PageMessageFilter.SinceNavigation] in the same ordered channel. [post] is a JavaScript expression evaluating to a `function(string)` that delivers
 * a JSON payload carrying [nonce], or to a falsy value when no channel is available. Everything is
 * captured at document start, so later page code cannot redirect the channel; the script defines
 * no globals.
 */
internal fun buildPageDiagnosticsScript(post: String, nonce: String, wrapConsole: Boolean): String = """
(function() {
  var post = $post;
  if (typeof post !== 'function') return;
  var nonce = ${jsStringLiteral(nonce.filter { it.isLetterOrDigit() })};
  var MAX = $MAX_DIAGNOSTIC_TEXT_CHARS;
  var stringify = JSON.stringify, slice = Array.prototype.slice, ErrorCtor = Error, StringCtor = String;
  function clip(s) { s = StringCtor(s); return s.length > MAX ? s.slice(0, MAX) : s; }
  // The nonce is concatenated, never stored on an object, so page-defined setters or toJSON hooks cannot read it.
  var busy = false;
  function send(payload) {
    try {
      var body = stringify(payload);
      post('{"nonce":"' + nonce + '",' + body.substring(1));
    } catch (e) {}
  }
  function isError(v) { return v instanceof ErrorCtor || (v && typeof v === 'object' && typeof v.message === 'string' && typeof v.name === 'string' && 'stack' in v); }
  function format(v) {
    try {
      if (typeof v === 'string') return v;
      if (v === undefined) return 'undefined';
      if (v === null) return 'null';
      var t = typeof v;
      if (t === 'number' || t === 'boolean') return StringCtor(v);
      if (t === 'bigint') return StringCtor(v) + 'n';
      if (t === 'symbol') return v.toString();
      if (t === 'function') return StringCtor(v);
      if (isError(v)) return v.stack ? StringCtor(v.stack) : (v.name + ': ' + v.message);
      if (typeof Node !== 'undefined' && v instanceof Node) return v.nodeType === 1 ? v.outerHTML.slice(0, 256) : StringCtor(v.nodeName).toLowerCase();
      var seen = [];
      var out = stringify(v, function(key, value) {
        if (typeof value === 'bigint') return StringCtor(value) + 'n';
        if (typeof value === 'function') return '[Function' + (value.name ? ': ' + value.name : '') + ']';
        if (typeof value === 'symbol') return value.toString();
        if (value === undefined && key !== '') return '[undefined]';
        if (value && typeof value === 'object') {
          if (seen.indexOf(value) !== -1) return '[Circular]';
          seen.push(value);
        }
        return value;
      });
      return out === undefined ? StringCtor(v) : out;
    } catch (e) {
      try { return StringCtor(v); } catch (e2) { return '[object]'; }
    }
  }
  function callerLocation(stack) {
    var lines = StringCtor(stack || '').split('\n');
    for (var i = 1; i < lines.length; i++) {
      var match = /(?:@|\()?((?:[a-z][a-z0-9+.-]*:)[^\s]*?):(\d+):(\d+)\)?\s*$/i.exec(lines[i]);
      if (match) return { url: match[1], line: +match[2], column: +match[3] };
    }
    return null;
  }
  function reportError(value, fallbackMessage, url, line, column) {
    var payload = { kind: 'pageerror', name: '', message: '', url: url || location.href };
    if (line) payload.line = line;
    if (column) payload.column = column;
    if (isError(value)) {
      try { payload.name = clip(value.name || ''); } catch (e) {}
      try { payload.message = clip(value.message || ''); } catch (e) {}
      try { if (value.stack) payload.stack = clip(value.stack); } catch (e) {}
    } else if (value !== undefined && !(value === null && fallbackMessage)) {
      payload.message = clip(format(value));
    } else {
      // ErrorEvent without an error object (WebKit for some documents, sanitized cross-origin errors).
      var text = StringCtor(fallbackMessage || '');
      var named = /^([A-Za-z_${'$'}][\w${'$'}]*): ([\s\S]*)${'$'}/.exec(text);
      if (named) { payload.name = named[1]; payload.message = clip(named[2]); }
      else payload.message = clip(text);
    }
    send(payload);
  }
  try { if (window === window.top) send({ kind: 'document' }); } catch (e) {}
  addEventListener('error', function(event) {
    if (typeof ErrorEvent === 'undefined' || !(event instanceof ErrorEvent)) return;
    reportError(event.error, event.message, event.filename, event.lineno, event.colno);
  });
  addEventListener('unhandledrejection', function(event) {
    reportError(event.reason, '', '', 0, 0);
  });
  if (!${if (wrapConsole) "true" else "false"}) return;
  var c = window.console;
  if (!c) return;
  function report(type, args, stack) {
    // Formatting may run page code (getters, toString) that logs again; do not recurse.
    if (busy) return;
    busy = true;
    try { reportNow(type, args, stack); } finally { busy = false; }
  }
  function reportNow(type, args, stack) {
    var parts = [];
    for (var i = 0; i < args.length; i++) parts.push(format(args[i]));
    var payload = { kind: 'console', type: type, text: clip(parts.join(' ')) };
    var where = callerLocation(stack);
    payload.url = where ? where.url : location.href;
    if (where) { payload.line = where.line; payload.column = where.column; }
    send(payload);
  }
  var methods = { log: 'log', info: 'info', warn: 'warning', error: 'error', debug: 'debug', dir: 'dir', dirxml: 'dirxml', table: 'table', trace: 'trace', clear: 'clear', group: 'startGroup', groupCollapsed: 'startGroupCollapsed', groupEnd: 'endGroup' };
  Object.keys(methods).forEach(function(name) {
    var original = c[name];
    if (typeof original !== 'function') return;
    var type = methods[name];
    c[name] = function() {
      try { report(type, slice.call(arguments), new ErrorCtor().stack); } catch (e) {}
      return original.apply(this, arguments);
    };
  });
  var originalAssert = c.assert;
  if (typeof originalAssert === 'function') {
    c.assert = function(condition) {
      if (!condition) {
        try {
          var args = slice.call(arguments, 1);
          report('assert', args.length ? args : ['console.assert'], new ErrorCtor().stack);
        } catch (e) {}
      }
      return originalAssert.apply(this, arguments);
    };
  }
})();
""".trimIndent()
