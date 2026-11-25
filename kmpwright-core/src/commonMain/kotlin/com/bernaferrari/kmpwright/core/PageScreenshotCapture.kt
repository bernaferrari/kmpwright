package com.bernaferrari.kmpwright.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/** Binds a screenshot to the caller's timeout and the page lifetime. */
internal suspend fun capturePageScreenshot(
    controller: ScreenshotController,
    closeSignal: Deferred<Unit>,
    timeout: Long,
    options: ScreenshotOptions,
): ScreenshotResult = try {
    awaitPageOperation(closeSignal) {
        withKmpwrightTimeout(timeout, { "Screenshot timed out after ${timeout}ms" }) { controller.capture(options) }
    }
} catch (error: KmpwrightException) {
    throw error
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Exception) {
    throw KmpwrightException("Screenshot failed: ${error.message}", error)
}

/** Keep annotation mutations scoped to one screenshot attempt, including setup failures. */
internal suspend fun captureAnnotatedScreenshot(
    options: ScreenshotOptions,
    snapshot: suspend () -> Snapshot,
    runWithRuntime: suspend (String) -> JsonElement,
    evalString: suspend (String) -> String?,
    capture: suspend (ScreenshotOptions) -> ScreenshotResult,
): ScreenshotResult {
    val snapshot = snapshot()
    val entries = snapshot.refs.values.toList()
    val refs = kotlinx.serialization.json.buildJsonArray { entries.forEach { add(JsonPrimitive(it.ref)) } }
    val bboxes: List<Map<String, Double>>
    val screenshotResult = try {
        val boxes = runWithRuntime("""
        var refs = $refs, labels = [];
        for (var i = 0; i < refs.length; i++) {
            var el = H.refElement(refs[i]);
            if (!el) continue;
            var r = el.getBoundingClientRect();
            if (!(r.width > 0 && r.height > 0)) continue;
            // Elements inside same-origin iframes: shift by each frame's content box.
            var dx = 0, dy = 0, frame = el.ownerDocument && el.ownerDocument.defaultView && el.ownerDocument.defaultView.frameElement;
            while (frame) {
                var fr = frame.getBoundingClientRect();
                dx += fr.left + frame.clientLeft; dy += fr.top + frame.clientTop;
                frame = frame.ownerDocument && frame.ownerDocument.defaultView && frame.ownerDocument.defaultView.frameElement;
            }
            r = {left: r.left + dx, top: r.top + dy, width: r.width, height: r.height};
            var lbl = document.createElement('div');
            // Label [N] stands for ref @eN, like agent-browser.
            lbl.textContent = refs[i].slice(2);
            lbl.setAttribute('data-ww-anno', 'true');
            lbl.style.cssText = 'position:absolute;top:' + (r.top + window.scrollY) + 'px;left:' + (r.left + window.scrollX) + 'px;background:rgba(255,0,0,0.8);color:white;padding:2px 4px;font-size:12px;font-weight:bold;z-index:2147483647;pointer-events:none;border-radius:2px;';
            (document.body || document.documentElement).appendChild(lbl);
            labels.push({i: i, x: r.left, y: r.top, w: r.width, h: r.height});
        }
        return JSON.stringify(labels);
        """.trimIndent())
        bboxes = try {
            Json.decodeFromString<List<Map<String, Double>>>(PageHelpers.valueAsString(boxes) ?: "[]")
        } catch (e: Exception) {
            throw KmpwrightException("Could not decode annotation boxes", e)
        }
        capture(options.copy(annotate = false))
    } finally {
        restoreScreenshotState {
            evalString("(function() { document.querySelectorAll('[data-ww-anno]').forEach(function(el) { el.remove(); }); })()")
        }
    }

    val annotations = bboxes.mapNotNull { bbox ->
        val idx = bbox["i"]?.toInt() ?: return@mapNotNull null
        val entry = entries.getOrNull(idx) ?: return@mapNotNull null
        idx to Annotation(
            ref = entry.ref,
            role = entry.role,
            name = entry.name,
            box = Rectangle(bbox["x"] ?: 0.0, bbox["y"] ?: 0.0, bbox["w"] ?: 0.0, bbox["h"] ?: 0.0)
        )
    }.sortedBy { it.first }.map { it.second }

    return screenshotResult.copy(annotations = annotations)
}
