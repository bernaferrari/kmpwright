package com.bernaferrari.kmpwright.core

import kotlinx.serialization.json.Json
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Measures snapshot refs in one document operation, in root viewport CSS pixels.
 * Missing/detached refs are omitted. CDP retains its frame-session coordinate conversion.
 */
@Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
public suspend fun Page.refBoundingBoxes(refs: List<String>): Map<String, Rectangle> {
    require(refs.all { Regex("@e[0-9]+").matches(it) }) { "Expected snapshot refs such as @e1" }
    if (refs.isEmpty()) return emptyMap()
    val root = locator(":root") as? BaseLocator
    if (root == null || capabilities().backend == BackendKind.JvmCdp) {
        return refs.mapNotNull { ref ->
            val box = try { locator(ref).boundingBox() }
            catch (error: KmpwrightException) { if (BaseLocator.isRefError(error.message.orEmpty())) null else throw error }
            box?.let { ref to it }
        }.toMap()
    }
    val result = awaitPageOperation(root.lifetime) { root.runWithRuntime("""
        var boxes = {};
        ${Json.encodeToString(refs.distinct())}.forEach(function(ref) {
            var el = H.refElement(ref);
            if (!el) return;
            var r = el.getBoundingClientRect(), x = r.x, y = r.y, width = r.width, height = r.height, w = el.ownerDocument.defaultView;
            try {
                while (w && w !== window) {
                    var f = w.frameElement;
                    if (!f) return;
                    var p = f.getBoundingClientRect(), sx = f.offsetWidth ? p.width / f.offsetWidth : 1, sy = f.offsetHeight ? p.height / f.offsetHeight : 1;
                    x = p.x + (f.clientLeft + x) * sx; y = p.y + (f.clientTop + y) * sy;
                    width *= sx; height *= sy;
                    w = f.ownerDocument.defaultView;
                }
            } catch (e) { return; }
            boxes[ref] = {x:x, y:y, width:width, height:height};
        });
        return boxes;
    """.trimIndent()) } as? JsonObject ?: return emptyMap()
    return result.mapValues { (_, value) ->
        val box = value.jsonObject
        fun number(key: String) = box[key]?.jsonPrimitive?.doubleOrNull ?: 0.0
        Rectangle(number("x"), number("y"), number("width"), number("height"))
    }
}
