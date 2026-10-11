package com.bernaferrari.kmpwright.core

import com.bernaferrari.kmpwright.core.generated.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

interface ReactInspector {
    /** Install DevTools before app scripts. Reload an already mounted app to let its renderer discover the hook. */
    suspend fun enable(reload: Boolean = false)
    suspend fun disable()
    suspend fun tree(timeoutMs: Long = 30_000): List<ReactComponent>
    suspend fun inspect(id: Long, timeoutMs: Long = 30_000): ReactInspection
    suspend fun suspense(timeoutMs: Long = 30_000): List<ReactSuspenseBoundary>
    suspend fun startRenderProfile()
    suspend fun stopRenderProfile(): ReactRenderProfile
}

@Serializable
data class ReactComponent(val id: Long, val type: Int, val name: String?, val key: String?, val parent: Long)
@Serializable
data class ReactInspection(val text: String, val source: JsonArray? = null)
@Serializable
data class ReactSuspenseBoundary(val id: Long, val parentID: Long, val name: String?, val isSuspended: Boolean,
    val environments: List<String>, val suspendedBy: List<JsonObject>, val unknownSuspenders: String? = null,
    val owners: List<JsonObject>, val jsxSource: JsonArray? = null)
@Serializable
data class ReactFrameRate(val avg: Int, val min: Int, val max: Int, val drops: Int)
@Serializable
data class ReactRenderChange(val type: String, val name: String? = null, val prev: String? = null, val next: String? = null)
@Serializable
data class ReactRenderComponent(val name: String, val count: Long, val mounts: Long, val reRenders: Long,
    val instanceCount: Int, val totalTime: Double, val selfTime: Double, val domMutations: Long,
    val changes: List<ReactRenderChange>, val changeSummary: Map<String, Int>)
@Serializable
data class ReactRenderProfile(val elapsed: Double, val fps: ReactFrameRate, val totalRenders: Long,
    val totalMounts: Long, val totalReRenders: Long, val totalComponents: Int, val components: List<ReactRenderComponent>)

internal class PageReactInspector(private val page: Page, private val controller: WebViewController,
    private val check: suspend (Boolean) -> Unit) : ReactInspector {
    private val mutex = Mutex()
    private val task = PageScriptTask(controller)
    private val json = Json { ignoreUnknownKeys = true }
    private val key = "__kmpwrightReact_" + secureRandomBytes(12).joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
    private var hookRegistration: String? = null
    private var profileRegistration: String? = null

    private fun script(raw: String): String = raw.replace("__AB", key)
    private suspend fun <T> read(raw: String, timeoutMs: Long, decode: (String) -> T): T {
        check(true)
        val value = task.evaluate(script(raw), timeoutMs) ?: throw KmpwrightException("React inspection returned no result")
        return decode(value)
    }

    override suspend fun enable(reload: Boolean) = mutex.withLock {
        check(false)
        if (hookRegistration == null) hookRegistration = page.addInitScript(installScript())
        if (reload) page.reload() else controller.evaluateJavaScriptAsync(installScript())
        Unit
    }

    override suspend fun disable() = mutex.withLock {
        check(false)
        try { disableDocument() }
        finally { hookRegistration?.let { page.removeInitScript(it); hookRegistration = null } }
        Unit
    }

    override suspend fun tree(timeoutMs: Long): List<ReactComponent> = mutex.withLock {
        read(reactTreeScript(), timeoutMs) { json.decodeFromString(it) }
    }
    override suspend fun inspect(id: Long, timeoutMs: Long): ReactInspection = mutex.withLock {
        require(id > 0) { "React component id must be positive" }
        read(reactInspectScript().replace("{{ID}}", id.toString()), timeoutMs) { json.decodeFromString(it) }
    }
    override suspend fun suspense(timeoutMs: Long): List<ReactSuspenseBoundary> = mutex.withLock {
        read(reactSuspenseScript(), timeoutMs) { json.decodeFromString(it) }
    }

    override suspend fun startRenderProfile() = mutex.withLock {
        check(false)
        check(profileRegistration == null) { "React render profile is already active" }
        if (controller.normalizeJavaScriptResult(controller.evaluateJavaScriptAsync(
                "Boolean(window.__REACT_DEVTOOLS_GLOBAL_HOOK__)")) != "true") {
            throw KmpwrightException("Call page.react.enable() before profiling")
        }
        val registration = controller.addInitScript(script(reactRendersInitScript()))
        try {
            controller.evaluateJavaScriptAsync(script(reactRendersInitScript()))
            profileRegistration = registration
        } catch (error: Throwable) {
            withContext(NonCancellable) { withTimeoutOrNull(1_000) { runCatching { controller.removeInitScript(registration) } } }
            throw error
        }
    }

    override suspend fun stopRenderProfile(): ReactRenderProfile = mutex.withLock {
        check(false)
        if (profileRegistration == null) throw KmpwrightException("React render profile is not active")
        try { read(reactRendersStopScript(), 30_000) { json.decodeFromString(it) } }
        finally {
            withContext(NonCancellable) { withTimeoutOrNull(1_000) { runCatching {
                controller.evaluateJavaScriptAsync(cleanupProfileScript())
            } } }
            removeProfileRegistration()
        }
    }

    private fun cleanupProfileScript(): String = "(function(){ if (window.${key}_RENDERS_ACTIVE__) { ${script(reactRendersStopScript())}; } })()"

    internal suspend fun disposeAsync() {
        withContext(NonCancellable) {
            withTimeoutOrNull(2_000) { mutex.withLock {
                try { controller.evaluateJavaScriptAsync(cleanupScript()) }
                finally {
                    removeProfileRegistration()
                    hookRegistration?.let { registration ->
                        runCatching { controller.removeInitScript(registration) }
                        hookRegistration = null
                    }
                }
            } }
        }
    }

    private suspend fun removeProfileRegistration() {
        val registration = profileRegistration ?: return
        withContext(NonCancellable) { withTimeoutOrNull(1_000) { runCatching { controller.removeInitScript(registration) } } }
        profileRegistration = null
    }

    private suspend fun disableDocument() {
        try { controller.evaluateJavaScriptAsync(cleanupScript()) }
        finally { removeProfileRegistration() }
    }

    /** Queue a synchronous browser cleanup before the controller releases its SDK ownership. */
    fun dispose() { controller.evaluateJavaScript(cleanupScript()) {} }

    private fun cleanupScript(): String = """
        (function(){
          if (window.${key}_RENDERS_ACTIVE__) { ${script(reactRendersStopScript())}; }
          var state = window[${jsStringLiteral(key)}];
          if (state && state.bridge) state.bridge.shutdown();
          delete window[${jsStringLiteral(key)}];
        })()
    """.trimIndent()

    private fun installScript(): String = """
        (function(){
          if (window[${jsStringLiteral(key)}]) return;
          var hook = window.__REACT_DEVTOOLS_GLOBAL_HOOK__;
          if (hook && hook.rendererInterfaces && hook.rendererInterfaces.size) return;
          var privateReact = (function(){
            var module={exports:{}}, exports=module.exports, process={env:{NODE_ENV:'development'}};
            ${reactRuntimeBundle()}
            return module.exports;
          })();
          var require=function(name){if(name==='react')return privateReact;throw new Error('Unsupported DevTools import: '+name)};
          var module={exports:{}}, define=undefined;
          ${reactDevToolsBundle()}
          var backend=module.exports, listeners=new Set();
          backend.initialize(window);
          var bridge=backend.createBridge(window, {
            listen:function(callback){listeners.add(callback);return function(){listeners.delete(callback)}},
            send:function(event,payload){
              if(event==='getSavedPreferences') queueMicrotask(function(){listeners.forEach(function(callback){
                callback({event:'savedPreferences',payload:{componentFilters:[]}})
              })});
            }
          });
          backend.activate(window,{bridge:bridge});
          window[${jsStringLiteral(key)}]={bridge:bridge};
        })()
    """.trimIndent()
}
