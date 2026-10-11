package com.bernaferrari.kmpwright.core

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

internal object CdpAccessibilityAudit {
    private data class Task(val world: CdpFrameWorlds.World?, val frameContext: JsonElement?, val label: String)

    suspend fun run(transport: CdpSessionTransport, session: String?, options: AccessibilityAuditOptions): String {
        val frames = CdpFrameWorlds(transport, session, "__kmpwright_audit_4_14_0")
        try {
            val root = frames.root()
            val tasks = ArrayDeque<Task>().apply { addLast(Task(root, null, root.frameId)) }
            val partials = mutableListOf<JsonElement>()
            val skipped = mutableListOf<String>()
            var bytes = 0
            var audited = 0
            while (tasks.isNotEmpty()) {
                if (partials.size >= 128) throw KmpwrightException("Accessibility audit exceeds 128 frames")
                val task = tasks.removeLast()
                val world = task.world
                if (world == null) { partials += JsonPrimitive(false); skipped += task.label; continue }
                val frameOptions = if (world == root) options else options.copy(selector = null)
                val expression = """
                    (async function(){
                      ${AccessibilityAuditScripts.setup()}
                      ${AccessibilityAuditScripts.options(frameOptions)}
                      ${AccessibilityAuditScripts.context(frameOptions)}
                      ${if (task.frameContext != null) "context = ${task.frameContext};" else ""}
                      var contexts = agentAxe.utils.getFrameContexts(context).map(function(f){return f.frameContext});
                      var partial = await agentAxe.runPartial(context, options);
                      return JSON.stringify({partial:partial, contexts:contexts});
                    })()
                """.trimIndent()
                val payload = try { frames.evaluateJson(world, expression) }
                catch (error: Exception) {
                    if (error is CancellationException || world == root) throw error
                    partials += JsonPrimitive(false); skipped += "${task.label}: ${error.message}"; continue
                }
                val partial = payload.getValue("partial")
                bytes += partial.toString().encodeToByteArray().size
                if (bytes > 32 * 1024 * 1024) throw KmpwrightException("Accessibility partials exceed 32 MiB")
                partials += partial
                audited++
                val specs = partial.jsonObject["frames"]?.jsonArray.orEmpty()
                val contexts = payload.getValue("contexts").jsonArray
                val children = specs.mapIndexed { index, spec ->
                    val path = spec.jsonObject.getValue("selector").jsonArray
                    var reason: String? = null
                    val child = try { frames.child(world, ownerExpression(path)) }
                    catch (error: Exception) { if (error is CancellationException) throw error; reason = error.message?.take(512); null }
                    Task(if (contexts.getOrNull(index) != null) child else null, contexts.getOrNull(index),
                        path.toString() + (reason?.let { ": $it" } ?: ""))
                }
                children.asReversed().forEach(tasks::addLast)
            }
            val result = frames.evaluateJson(root, """
                (async function(){
                  ${AccessibilityAuditScripts.setup()}
                  ${AccessibilityAuditScripts.options(options)}
                  var r = await agentAxe.finishRun(${JsonArray(partials)}, options);
                  ${AccessibilityAuditScripts.report(options)}
                })()
            """.trimIndent())
            return JsonObject(result + ("coverage" to buildJsonObject {
                put("auditedFrames", audited); put("skippedFrames", JsonArray(skipped.map(::JsonPrimitive)))
            })).toString()
        } finally { frames.close() }
    }

    private fun ownerExpression(path: JsonArray): String = """
        (function(){
          var selector = ${path}.slice(-1)[0];
          if (!Array.isArray(selector)) return document.querySelector(selector);
          var root = document, element;
          for (var i=0;i<selector.length;i++) {
            element = root.querySelector(selector[i]);
            if (!element) return null;
            if (i+1<selector.length) { root = element.shadowRoot; if (!root) return null; }
          }
          return element;
        })()
    """.trimIndent()
}
