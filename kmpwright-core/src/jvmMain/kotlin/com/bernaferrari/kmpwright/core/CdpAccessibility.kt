package com.bernaferrari.kmpwright.core

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*

/** Enrich DOM snapshots without replacing the registry that gives locators stable references. */
internal object CdpAccessibility {
    suspend fun prepare(bridge: CdpInputBridge) {
        val response = bridge.sendCdpCommand("Accessibility.getFullAXTree")
        response.error?.let { throw KmpwrightException("Could not read the browser accessibility tree: ${it.message}") }
        val nodes = response.result?.get("nodes")?.jsonArray
            ?: throw KmpwrightException("Browser returned no accessibility tree")
        val reset = bridge.sendCdpCommand("Runtime.evaluate", mapOf(
            "expression" to "void(window.__kmpwright_browserAX = new WeakMap(), window.__kmpwright_browserAXNodes = new Map(), window.__kmpwright_browserAXTree = null)",
            "returnByValue" to false
        ))
        reset.error?.let { throw KmpwrightException("Could not initialize accessibility metadata: ${it.message}") }
        if (nodes.size > 50_000 || nodes.toString().length > 16 * 1024 * 1024) throw KmpwrightException("Accessibility tree exceeds its node or metadata budget")
        val objects = nodes.map { it.jsonObject }
            .filter { it["backendDOMNodeId"] != null }
            .distinctBy { it.getValue("backendDOMNodeId").jsonPrimitive.content }
        // Limit concurrent protocol traffic while avoiding a round trip per node in series.
        for (batch in objects.chunked(16)) coroutineScope {
            batch.map { node -> async { attach(bridge, node) } }.awaitAll()
        }
        val tree = bridge.sendCdpCommand("Runtime.evaluate", mapOf("expression" to
            "void(window.__kmpwright_browserAXTree = $nodes)", "returnByValue" to false))
        tree.error?.let { throw KmpwrightException("Could not retain accessibility hierarchy: ${it.message}") }
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
                "functionDeclaration" to "function(data) { var w = this.nodeType === 9 ? this.defaultView : this.ownerDocument.defaultView; if (!w.__kmpwright_browserAXNodes) w.__kmpwright_browserAXNodes = new Map(); w.__kmpwright_browserAXNodes.set(data.id, new WeakRef(this)); if (this.nodeType === 1 && !data.ignored) { if (!w.__kmpwright_browserAX) w.__kmpwright_browserAX = new WeakMap(); w.__kmpwright_browserAX.set(this, data); } }",
                "arguments" to listOf(mapOf("value" to metadata)),
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
