package com.bernaferrari.kmpwright.core

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.util.UUID

/** Observe trusted human or CDP pointer events without injecting pixels into clean captures. */
internal class CdpRecordingPointer(private val connection: CdpEventTransport, private val session: String?,
    private val controller: CdpWebViewController, private val timeline: RecordingCursorTimeline) {
    private val key = "__kmpwrightPointer_" + UUID.randomUUID().toString().replace("-", "")
    private var scriptId: String? = null
    private var subscription: CdpEventSubscription? = null
    private suspend fun send(method: String, params: Map<String, Any?>): CdpResponse =
        if (session == null) connection.send(method, params) else connection.sendSession(session, method, params)
    suspend fun start() {
        val script = """
            (() => {
              const key = '${key}';
              globalThis[key]?.();
              const installed = new Map(); let disposed = false;
              function install(win) {
                if (disposed || installed.has(win) || installed.size >= 128) return;
                let doc; try { doc = win.document; if (!doc.documentElement) return; } catch (_) { return; }
                const removers = [];
                function report(event) {
                  if (!event.isTrusted || event.pointerType !== 'mouse') return;
                  if (event.type === 'pointerout' && event.relatedTarget && event.relatedTarget.localName !== 'iframe') return;
                  let x = event.clientX, y = event.clientY, current = win;
                  try {
                    while (current !== window) {
                      const frame = current.frameElement; if (!frame) return;
                      const rect = frame.getBoundingClientRect();
                      x = rect.left + frame.clientLeft + x * frame.clientWidth / current.innerWidth;
                      y = rect.top + frame.clientTop + y * frame.clientHeight / current.innerHeight;
                      current = current.parent;
                    }
                    window[key + '_binding'](JSON.stringify({x,y,buttons:event.buttons,type:event.type,visible:event.type !== 'pointerout',timestamp:Date.now()}));
                  } catch (_) {}
                }
                for (const type of ['pointermove','pointerdown','pointerup','pointerout']) {
                  win.addEventListener(type, report, {capture:true, passive:true});
                  removers.push(() => win.removeEventListener(type, report, true));
                }
                function scan() {
                  for (const [other, entry] of installed) {
                    let stale = false; try { stale = other !== window && (!other.frameElement?.isConnected || other.document !== entry.doc); } catch (_) { stale = true; }
                    if (stale) { entry.cleanup(); installed.delete(other); }
                  }
                  for (const frame of doc.querySelectorAll('iframe,frame')) { try { install(frame.contentWindow); } catch (_) {} }
                }
                const observer = new win.MutationObserver(records => { if (records.some(r => r.addedNodes.length || r.removedNodes.length)) scan(); });
                observer.observe(doc.documentElement, {childList:true, subtree:true}); removers.push(() => observer.disconnect());
                doc.addEventListener('load', scan, true); removers.push(() => doc.removeEventListener('load', scan, true));
                installed.set(win, {doc,cleanup:() => removers.forEach(remove => remove())}); scan();
              }
              install(window);
              const ready = () => install(window); document.addEventListener('DOMContentLoaded', ready, {once:true});
              globalThis[key] = () => { disposed = true; document.removeEventListener('DOMContentLoaded',ready); for (const entry of installed.values()) entry.cleanup(); installed.clear(); delete globalThis[key]; };
            })();
        """.trimIndent()
        subscription = connection.onEvent("Runtime.bindingCalled", session) { params ->
            if (params["name"]?.jsonPrimitive?.content != key + "_binding") return@onEvent
            val payload = params["payload"]?.jsonPrimitive?.content ?: return@onEvent
            if (payload.length > 1024) return@onEvent
            val parsed = runCatching { Json.parseToJsonElement(payload).jsonObject }.getOrNull() ?: return@onEvent
            val x = parsed["x"]?.jsonPrimitive?.doubleOrNull ?: return@onEvent
            val y = parsed["y"]?.jsonPrimitive?.doubleOrNull ?: return@onEvent
            val buttons = parsed["buttons"]?.jsonPrimitive?.intOrNull ?: 0
            if (x.isFinite() && y.isFinite()) timeline.record(mapOf("x" to x, "y" to y, "buttons" to buttons, "visible" to (parsed["visible"]?.jsonPrimitive?.booleanOrNull != false)))
        }
        try {
            val response = send("Runtime.addBinding", mapOf("name" to key + "_binding"))
            response.error?.let { throw KmpwrightException("Could not observe recording pointers: ${it.message}") }
            scriptId = controller.addInitScript(script)
            val evaluated = send("Runtime.evaluate", mapOf("expression" to script, "returnByValue" to true))
            if (evaluated.error != null || evaluated.result?.get("exceptionDetails") != null) throw KmpwrightException("Could not install recording pointer observer")
        } catch (error: Throwable) { close(); throw error }
    }
    suspend fun close() = withContext(NonCancellable) {
        subscription?.cancel(); subscription = null
        withTimeoutOrNull(2_000) {
            runCatching { send("Runtime.evaluate", mapOf("expression" to "globalThis['$key']?.(); delete globalThis['${key}_binding'];", "returnByValue" to true)) }
            scriptId?.let { runCatching { controller.removeInitScript(it) } }; scriptId = null
            runCatching { send("Runtime.removeBinding", mapOf("name" to key + "_binding")) }
        }
    }
}
