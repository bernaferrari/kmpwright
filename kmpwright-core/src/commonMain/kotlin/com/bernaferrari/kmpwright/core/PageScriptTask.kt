package com.bernaferrari.kmpwright.core

import kotlinx.atomicfu.atomic
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/** Promise bridge for WebViews whose native evaluate callback does not await JavaScript promises. */
internal class PageScriptTask(private val controller: WebViewScriptController) {
    private val counter = atomic(0L)
    private val namespace = secureRandomBytes(12).joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }

    suspend fun evaluate(expression: String, timeoutMs: Long): String? {
        require(timeoutMs > 0) { "Script timeout must be positive" }
        val expressionBody = expression.trim().removeSuffix(";")
        val key = "__kmpwrightTask_${namespace}_${counter.incrementAndGet()}"
        try {
            return withTimeout(timeoutMs) {
                controller.evaluateJavaScriptAsync("""
                    (function(){
                      var state = {done:false}; window[${jsStringLiteral(key)}] = state;
                      Promise.resolve().then(function(){return ($expressionBody);}).then(function(value){
                        try { state.result = JSON.stringify({value:value}); if (new TextEncoder().encode(state.result).length > 4194304) throw new Error('JavaScript task result exceeds 4 MiB'); state.done = true; }
                        catch(error) { state.error = String(error.message || error).slice(0,16384); state.done = true; }
                      },function(error){state.error = String(error && error.message || error).slice(0,16384); state.done = true;});
                      return true;
                    })()
                """.trimIndent())
                while (true) {
                    val raw = controller.normalizeJavaScriptResult(controller.evaluateJavaScriptAsync(
                        "JSON.stringify(window[${jsStringLiteral(key)}])"))
                    val state = raw?.let { Json.parseToJsonElement(it).jsonObject }
                        ?: throw KmpwrightException("Script execution context changed")
                    if (state["done"]?.jsonPrimitive?.booleanOrNull == true) {
                        state["error"]?.let { throw KmpwrightException("JavaScript task failed: ${it.jsonPrimitive.content}") }
                        val result = Json.parseToJsonElement(state.getValue("result").jsonPrimitive.content).jsonObject["value"]
                        return@withTimeout when (result) {
                            null, JsonNull -> null
                            is JsonPrimitive -> result.content
                            else -> result.toString()
                        }
                    }
                    delay(25)
                }
                @Suppress("UNREACHABLE_CODE") null
            }
        } catch (_: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            throw KmpwrightTimeoutException("Timeout awaiting JavaScript task")
        } finally {
            withContext(NonCancellable) { withTimeoutOrNull(1_000) { runCatching {
                controller.evaluateJavaScriptAsync("delete window[${jsStringLiteral(key)}]")
            } } }
        }
    }
}
