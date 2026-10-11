package com.bernaferrari.kmpwright.core

import com.bernaferrari.kmpwright.core.generated.axeBundle
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** Rule tags such as wcag2a/wcag2aa. An empty set runs all enabled rules. */
@Serializable
data class AccessibilityAuditOptions(val tags: Set<String> = emptySet(), val selector: String? = null,
    val timeoutMs: Long = 30_000, val maxNodesPerRule: Int = 10)

@Serializable
data class AccessibilityAuditNode(val target: JsonArray, val html: String, val failureSummary: String)

@Serializable
data class AccessibilityAuditIssue(val id: String, val impact: String, val help: String,
    val helpUrl: String, val tags: List<String>, val nodeCount: Int, val nodes: List<AccessibilityAuditNode>)

@Serializable
data class AccessibilityAuditCounts(val violations: Int, val incomplete: Int, val passes: Int, val inapplicable: Int)

@Serializable
data class AccessibilityAuditCoverage(val auditedFrames: Int, val skippedFrames: List<String> = emptyList())

@Serializable
data class AccessibilityAuditReport(val url: String, val axeVersion: String, val counts: AccessibilityAuditCounts,
    val violations: List<AccessibilityAuditIssue>, val incomplete: List<AccessibilityAuditIssue>,
    val coverage: AccessibilityAuditCoverage)

internal object AccessibilityAuditScripts {
    private val engine by lazy { axeBundle() }
    fun setup(): String = """
        var previousAxe = Object.getOwnPropertyDescriptor(window, 'axe'), agentAxe;
        try {
          if (previousAxe && !previousAxe.configurable) throw new Error('Accessibility audit cannot replace a locked axe property');
          Object.defineProperty(window, 'axe', {value:undefined, writable:true, configurable:true,
            enumerable: previousAxe ? previousAxe.enumerable : false});
          var module = {exports:{}}, define = undefined;
          $engine
          agentAxe = module.exports;
        } finally {
          if (previousAxe) Object.defineProperty(window, 'axe', previousAxe); else delete window.axe;
        }
        if (!agentAxe || agentAxe.version !== '4.14.0') throw new Error('Could not initialize axe-core 4.14.0');
    """.trimIndent()

    fun options(options: AccessibilityAuditOptions): String = """
        var options = {resultTypes:['violations','incomplete'], iframes:false};
        var tags = ${JsonArray(options.tags.map(::JsonPrimitive))};
        if (tags.length) options.runOnly = {type:'tag', values:tags};
    """.trimIndent()

    fun context(options: AccessibilityAuditOptions): String = """
        var selector = ${options.selector?.let(::jsStringLiteral) ?: "null"};
        if (selector !== null) {
          var match;
          try { match = document.querySelector(selector); }
          catch(error) { throw new Error('Invalid selector: ' + selector); }
          if (!match) throw new Error('No element matches selector: ' + selector);
        }
        var context = selector === null ? document : selector;
    """.trimIndent()

    fun report(options: AccessibilityAuditOptions): String = """
        function trim(results) { return results.map(function(r) { return {
          id:r.id, impact:r.impact || 'unknown', help:r.help, helpUrl:r.helpUrl, tags:r.tags,
          nodeCount:r.nodes.length, nodes:r.nodes.slice(0,${options.maxNodesPerRule}).map(function(n) { return {
            target:n.target, html:typeof n.html === 'string' ? n.html.slice(0,300) : '',
            failureSummary:String(n.failureSummary || '').slice(0,16384)
          }; })
        }; }); }
        return JSON.stringify({url:r.url, axeVersion:r.testEngine.version,
          counts:{violations:r.violations.length, incomplete:r.incomplete.length,
            passes:r.passes.length, inapplicable:r.inapplicable.length},
          violations:trim(r.violations), incomplete:trim(r.incomplete)});
    """.trimIndent()

    fun singleDocument(options: AccessibilityAuditOptions): String = """
        (async function(){
          ${setup()}
          ${options(options)}
          ${context(options)}
          var skipped = Array.from(document.querySelectorAll('iframe,frame')).map(function(f){return f.id || f.src || '(unnamed frame)'});
          var r = await agentAxe.run(context, options);
          var result = (function(){${report(options)}})();
          var parsed = JSON.parse(result); parsed.coverage = {auditedFrames:1, skippedFrames:skipped};
          return JSON.stringify(parsed);
        })()
    """.trimIndent()
}

internal class PageAccessibilityAudit(private val controller: WebViewScriptController) {
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    suspend fun run(options: AccessibilityAuditOptions): AccessibilityAuditReport {
        require(options.timeoutMs > 0) { "Accessibility audit timeout must be positive" }
        require(options.maxNodesPerRule in 1..100) { "maxNodesPerRule must be between 1 and 100" }
        return try {
            withTimeout(options.timeoutMs) { mutex.withLock {
                val raw = controller.runAccessibilityAudit(options)
                if (raw.encodeToByteArray().size > 4 * 1024 * 1024) throw KmpwrightException("Accessibility report exceeds 4 MiB")
                json.decodeFromString<AccessibilityAuditReport>(raw)
            } }
        } catch (_: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            throw KmpwrightException("Timeout running accessibility audit")
        }
    }
}
