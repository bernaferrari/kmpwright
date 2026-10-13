package com.bernaferrari.kmpwright.core

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*

/**
 * Enrich DOM snapshots without replacing the registry that gives locators stable references.
 *
 * The browser accessibility data is stored inside the sealed page runtime ([PageHelpers]) through its
 * seal-protected `nativeAX` entry point, never in page-visible globals: a hostile page could otherwise
 * pre-define those globals and forge roles/names or remap AX node ids to different elements (so a ref
 * described as "Cancel" would act on "Delete").
 */
internal object CdpAccessibility {
    private val seal get() = jsStringLiteral(PageHelpers.seal)

    /** Function body run with the element as `this`; `window` is the element's own realm. */
    private val attachFunction: String get() = """
        function(data, seal) {
          var H = ${PageHelpers.runtimeExpression};
          if (!H) throw new Error('KMPwright page runtime is not installed');
          return H.nativeAX(seal, 'attach', this, data);
        }
    """.trimIndent()

    private suspend fun runtimeCall(bridge: CdpInputBridge, body: String, what: String) {
        val response = bridge.sendCdpCommand("Runtime.evaluate", mapOf(
            "expression" to "(function(){var H=${PageHelpers.runtimeExpression};if(!H)throw new Error('KMPwright page runtime is not installed');$body})()",
            "returnByValue" to true
        ))
        response.error?.let { throw KmpwrightException("$what: ${it.message}") }
        response.result?.get("exceptionDetails")?.let { throw KmpwrightException("$what: $it") }
    }

    private suspend fun ensureRuntime(bridge: CdpInputBridge) {
        val response = bridge.sendCdpCommand("Runtime.evaluate", mapOf("expression" to PageHelpers.installScript(), "returnByValue" to true))
        response.error?.let { throw KmpwrightException("Could not install the KMPwright page runtime: ${it.message}") }
        val value = response.result?.get("result")?.jsonObject?.get("value")?.jsonPrimitive?.contentOrNull
        if (value == PageHelpers.TAMPERED) throw KmpwrightException("The page replaced or pre-defined the KMPwright runtime slot; refusing to automate this document")
    }

    suspend fun prepare(bridge: CdpInputBridge) {
        val response = bridge.sendCdpCommand("Accessibility.getFullAXTree")
        response.error?.let { throw KmpwrightException("Could not read the browser accessibility tree: ${it.message}") }
        val nodes = response.result?.get("nodes")?.jsonArray
            ?: throw KmpwrightException("Browser returned no accessibility tree")
        ensureRuntime(bridge)
        runtimeCall(bridge, "H.nativeAX($seal, 'reset');", "Could not initialize accessibility metadata")
        if (nodes.size > 50_000 || nodes.toString().length > 16 * 1024 * 1024) throw KmpwrightException("Accessibility tree exceeds its node or metadata budget")
        val objects = nodes.map { it.jsonObject }
            .filter { it["backendDOMNodeId"] != null }
            .distinctBy { it.getValue("backendDOMNodeId").jsonPrimitive.content }
        // Limit concurrent protocol traffic while avoiding a round trip per node in series.
        for (batch in objects.chunked(16)) coroutineScope {
            batch.map { node -> async { attach(bridge, node) } }.awaitAll()
        }
        runtimeCall(bridge, "H.nativeAX($seal, 'tree', $nodes);", "Could not retain accessibility hierarchy")
    }

    private suspend fun attach(bridge: CdpInputBridge, node: JsonObject) {
        val resolved = bridge.sendCdpCommand("DOM.resolveNode", mapOf(
            "backendNodeId" to node.getValue("backendDOMNodeId").jsonPrimitive.long
        ))
        // DOM mutation between the AX capture and resolution can detach a node.
        if (resolved.error != null) return
        val id = resolved.result?.get("object")?.jsonObject?.get("objectId")?.jsonPrimitive?.content ?: return
        try {
            val metadata = buildJsonObject {
                put("id", node.getValue("nodeId"))
                put("ignored", node["ignored"] ?: JsonPrimitive(false))
                put("role", node["role"]?.jsonObject?.get("value") ?: JsonPrimitive("generic"))
                put("name", node["name"]?.jsonObject?.get("value") ?: JsonPrimitive(""))
                put("states", buildJsonArray {
                    node["properties"]?.jsonArray?.forEach { property ->
                        val item = property.jsonObject
                        val name = item["name"]?.jsonPrimitive?.content
                        val value = item["value"]?.jsonObject?.get("value")?.jsonPrimitive
                        if (name in setOf("checked", "expanded", "disabled", "required", "selected", "readonly", "busy", "focused") && value?.content == "true") {
                            add(JsonPrimitive(name!!))
                        }
                        if (name == "checked" && value?.content == "mixed") add(JsonPrimitive("mixed"))
                    }
                })
            }
            val result = bridge.sendCdpCommand("Runtime.callFunctionOn", mapOf(
                "objectId" to id,
                "functionDeclaration" to attachFunction,
                "arguments" to listOf(mapOf("value" to metadata), mapOf("value" to PageHelpers.seal)),
                "returnByValue" to true
            ))
            result.error?.let { throw KmpwrightException("Could not apply browser accessibility metadata: ${it.message}") }
        } finally {
            withContext(NonCancellable) {
                withTimeoutOrNull(1_000) { runCatching { bridge.sendCdpCommand("Runtime.releaseObject", mapOf("objectId" to id)) } }
            }
        }
    }
}
