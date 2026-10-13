package com.bernaferrari.kmpwright.core

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/**
 * One frame hop: the frame owner matched by [selector], looked up inside the elements of [scopeSteps]
 * (a locator's runtime steps, for `Locator.frameLocator`) or in the whole parent document.
 */
internal data class CdpFrameStep(val selector: String, val scopeSteps: String? = null)

internal fun cdpFrameSteps(selector: String, scopeSteps: String? = null): List<CdpFrameStep> =
    splitSelectorChain(selector).filter { it.isNotEmpty() }.mapIndexed { index, part -> CdpFrameStep(part, scopeSteps.takeIf { index == 0 }) }

/** Frame contexts are resolved afresh so reloads, process swaps and detached owners cannot reuse stale DOM. */
internal class CdpFrameRuntime(private val transport: CdpSessionTransport, private val rootSession: String?,
    private val rootController: WebViewController, private val config: KmpwrightConfig,
    private val scope: CoroutineScope, private val onFrameGone: suspend (String) -> Unit = {},
    private val onFrameSession: suspend (String?) -> Unit = {}) {
    private val worlds = CdpFrameWorlds(transport, rootSession, "__kmpwright_frame_locator", refreshContexts = true, onDetached = onFrameGone)
    private val mutex = Mutex()
    private val objects = mutableMapOf<String, String?>()
    private var closed = false
    private data class Resolved(val world: CdpFrameWorlds.World, val parents: List<Pair<CdpFrameWorlds.World, CdpFrameStep>>)

    private suspend fun resolve(path: List<CdpFrameStep>): Resolved {
        check(!closed) { "Frame runtime is closed" }
        var world = worlds.root()
        val parents = mutableListOf<Pair<CdpFrameWorlds.World, CdpFrameStep>>()
        for (selector in path) {
            parents += world to selector
            world = worlds.child(world, owner(selector))
        }
        val url = worlds.evaluate(world, "location.href")["value"]?.jsonPrimitive?.content.orEmpty()
        if (config.allowedOriginRules.isNotEmpty() && config.allowedOriginRules.none { originRuleMatches(it, url) }) {
            throw KmpwrightException("Frame origin is not allowed by KmpwrightConfig: $url")
        }
        onFrameSession(world.sessionId)
        return Resolved(world, parents)
    }

    private fun owner(step: CdpFrameStep): String {
        val selector = step.selector
        val lookup = if (step.scopeSteps == null) "Array.from(document.querySelectorAll(${jsStringLiteral(selector)}))" else """(function(){
              var H = ${PageHelpers.runtimeExpression};
              if (!H) { ${PageHelpers.installScript()}; H = ${PageHelpers.runtimeExpression}; }
              if (!H) throw new Error('KMPwright page runtime is unavailable');
              return H.resolveAll(${step.scopeSteps.removeSuffix("]")}${if (step.scopeSteps == "[]") "" else ","}${jsStringLiteral(selector)}]);
            })()"""
        return ownerScript(selector, lookup)
    }

    private fun ownerScript(selector: String, lookup: String) = """
        (function(){
          var matches = $lookup;
          if (${config.strictSelectors} && matches.length > 1) throw new Error('Strict frame selector matched ' + matches.length + ' elements');
          var el = matches[0];
          if (!el) throw new Error('Frame not found: ' + ${jsStringLiteral(selector)});
          if (!/^(IFRAME|FRAME)$/.test(el.tagName)) throw new Error('Selected element is not a frame');
          return el;
        })()
    """.trimIndent()

    suspend fun evaluate(path: List<CdpFrameStep>, script: String): String? = mutex.withLock {
        val expression = if (script.trimStart().startsWith("return ")) "(function(){$script})()" else script
        val value = worlds.evaluate(resolve(path).world, expression)["value"]
        when (value) { null, JsonNull -> null; is JsonPrimitive -> value.content; else -> value.toString() }
    }

    suspend fun command(path: List<CdpFrameStep>, method: String, params: Map<String, Any?>): CdpResponse = mutex.withLock {
        if (method.startsWith("Input.")) return@withLock (rootController as CdpInputBridge).sendCdpCommand(method, params)
        val objectId = params["objectId"] as? String
        val world = if (objectId != null && objects.containsKey(objectId)) null else resolve(path).world
        val session = if (world != null) world.sessionId else objects[objectId]
        val actual = when (method) {
            "Runtime.evaluate" -> params + ("contextId" to requireNotNull(world).contextId)
            "DOM.resolveNode" -> params + ("executionContextId" to requireNotNull(world).contextId)
            "Accessibility.getFullAXTree" -> params + ("frameId" to requireNotNull(world).frameId)
            else -> params
        }
        val response = send(session, method, actual)
        if ((method == "Runtime.evaluate" && params["returnByValue"] == false) || method == "DOM.resolveNode") {
            val objectKey = if (method == "DOM.resolveNode") "object" else "result"
            val id = response.result?.get(objectKey)?.jsonObject?.get("objectId")?.jsonPrimitive?.content
            if (id != null) {
                if (objects.size >= 128) {
                    send(session, "Runtime.releaseObject", mapOf("objectId" to id))
                    throw KmpwrightException("Too many live frame objects")
                }
                objects[id] = session
            }
        }
        if (method == "Runtime.releaseObject" && objectId != null) objects.remove(objectId)
        response
    }

    private suspend fun send(session: String?, method: String, params: Map<String, Any?>): CdpResponse =
        if (session == null) transport.send(method, params) else transport.sendSession(session, method, params)

    suspend fun point(path: List<CdpFrameStep>, x: Double, y: Double, force: Boolean, scroll: Boolean): Pair<Double, Double> = mutex.withLock {
        var pointX = x
        var pointY = y
        val resolved = resolve(path)
        for ((parent, selector) in resolved.parents.asReversed()) {
            val result = worlds.evaluate(parent, """
                (function(){
                  var el = ${owner(selector)};
                  if ($scroll) el.scrollIntoView({block:'center',inline:'center'});
                  var r=el.getBoundingClientRect(), sx=r.width/el.offsetWidth, sy=r.height/el.offsetHeight;
                  var transform=getComputedStyle(el).transform;
                  if (transform !== 'none') { var m=new DOMMatrix(transform); if (!m.is2D || m.b || m.c) throw new Error('Rotated or 3D frame input is unavailable'); }
                  var x=r.left+(el.clientLeft+$pointX)*sx, y=r.top+(el.clientTop+$pointY)*sy;
                  if (!$force) {
                    if (!r.width || !r.height || x<0 || y<0 || x>=innerWidth || y>=innerHeight) throw new Error('Frame point is outside the viewport');
                    var hit=document.elementFromPoint(x,y);
                    if (hit !== el) throw new Error('Frame pointer is intercepted');
                  }
                  return JSON.stringify({x:x,y:y});
                })()
            """.trimIndent()).getValue("value").jsonPrimitive.content
            val mapped = Json.parseToJsonElement(result).jsonObject
            pointX = mapped.getValue("x").jsonPrimitive.double
            pointY = mapped.getValue("y").jsonPrimitive.double
        }
        pointX to pointY
    }

    fun controller(path: List<CdpFrameStep>): WebViewLocatorController = object : WebViewLocatorController,
        WebViewCaptureController by rootController, CdpInputBridge {
        override val inputState get() = (rootController as CdpInputBridge).inputState
        override val nativeAccessibility: Boolean get() = true
        override suspend fun prepareAccessibilitySnapshot() = CdpAccessibility.prepare(this)
        override fun normalizeJavaScriptResult(raw: String?): String? = raw
        override suspend fun evaluateJavaScriptAsync(script: String): String? = evaluate(path, script)
        override fun evaluateJavaScript(script: String, callback: (String?) -> Unit) {
            scope.launch { callback(runCatching { evaluate(path, script) }.getOrNull()) }
        }
        override fun injectJavaScript(script: String, callback: ((String?) -> Unit)?) {
            scope.launch { callback?.invoke(evaluate(path, script)) }
        }
        override suspend fun sendCdpCommand(method: String, params: Map<String, Any?>): CdpResponse = command(path, method, params)
        override suspend fun pointerInRoot(x: Double, y: Double, force: Boolean): Pair<Double, Double> = point(path, x, y, force, true)
        override suspend fun rectangleInRoot(rect: Rectangle): Rectangle {
            val start = point(path, rect.x, rect.y, true, false)
            val end = point(path, rect.x + rect.width, rect.y + rect.height, true, false)
            return Rectangle(start.first, start.second, end.first-start.first, end.second-start.second)
        }
        override suspend fun prepareFrameScreenshot() { point(path, 0.0, 0.0, true, true) }

    }

    suspend fun ownerInfo(path: List<CdpFrameStep>, body: String): String? {
        if (path.isEmpty()) return null
        return evaluate(path.dropLast(1), "(function(){var el=${owner(path.last())};$body})()")
    }

    suspend fun close() = mutex.withLock {
        closed = true
        withContext(NonCancellable) {
            withTimeoutOrNull(3_000) {
                objects.toMap().entries.chunked(16).forEach { batch -> coroutineScope {
                    batch.map { (id, session) -> launch { withTimeoutOrNull(1_000) { runCatching {
                        send(session, "Runtime.releaseObject", mapOf("objectId" to id))
                    } } } }.joinAll()
                } }
            }
            objects.clear()
            worlds.close()
        }
    }
}

internal class CdpFrameLocator(private val runtime: CdpFrameRuntime, private val path: List<CdpFrameStep>,
    private val config: KmpwrightConfig, private val lifetime: Deferred<Unit>? = null, private val policy: suspend (ActionType, String?) -> Unit) : FrameLocator {
    private val controller = runtime.controller(path)
    override fun locator(selector: String): Locator = CdpLocator(controller, selector,
        config,
        frameFactory = { frameSelector, scope -> CdpFrameLocator(runtime, path + cdpFrameSteps(frameSelector, scope), config, lifetime, policy) })
        .also { it.policyChecker = { action -> policy(action, url()) }; it.lifetime = lifetime }
    /** `>>` separates nested frames, exactly like `Page.frameLocator`. */
    override fun frameLocator(selector: String): FrameLocator = CdpFrameLocator(runtime, path + cdpFrameSteps(selector), config, lifetime, policy)
    override suspend fun url(): String? = runtime.evaluate(path, "location.href")
    override suspend fun name(): String? = runtime.ownerInfo(path, "return el.getAttribute('name') || el.id || null;")
    override suspend fun boundingBox(): Rectangle? {
        val raw = runtime.ownerInfo(path, "var r=el.getBoundingClientRect();return JSON.stringify({x:r.x,y:r.y,width:r.width,height:r.height});") ?: return null
        val box = Json.decodeFromString<BaseLocator.BoundingBoxJson>(raw)
        val start = runtime.point(path.dropLast(1), box.x, box.y, true, false)
        val end = runtime.point(path.dropLast(1), box.x + box.width, box.y + box.height, true, false)
        return Rectangle(start.first, start.second, end.first-start.first, end.second-start.second)
    }
}
