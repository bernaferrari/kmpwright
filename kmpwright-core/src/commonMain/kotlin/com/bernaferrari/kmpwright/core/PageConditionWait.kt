package com.bernaferrari.kmpwright.core

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** Await a boolean condition even when a WebView cannot await JS Promises. */
internal suspend fun awaitPageCondition(
    controller: WebViewScriptController,
    condition: String,
    timeoutMs: Long
): Boolean {
    val started = kotlin.time.TimeSource.Monotonic.markNow()
    val observed = if (!controller.supportsPromiseEvaluation) {
        try {
            PageScriptTask(controller).evaluate(BaseLocator.waitForConditionJs(condition, timeoutMs), timeoutMs)
        } catch (failure: KmpwrightException) {
            if (failure.message == "Timeout awaiting JavaScript task") return false
            throw failure
        }
    } else withTimeoutOrNull(timeoutMs) {
        controller.normalizeJavaScriptResult(controller.evaluateJavaScriptAsync(BaseLocator.waitForConditionJs(condition, timeoutMs)))
    }
    when (observed) {
        "true" -> return true
        "false", "__kmpwright_timeout__" -> return false
    }
    while (started.elapsedNow().inWholeMilliseconds < timeoutMs) {
        val remaining = timeoutMs - started.elapsedNow().inWholeMilliseconds
        val ready = withTimeoutOrNull(remaining.coerceAtLeast(1)) {
            controller.normalizeJavaScriptResult(controller.evaluateJavaScriptAsync(condition)) == "true"
        }
        if (ready == true) return true
        delay(minOf(50L, remaining.coerceAtLeast(1)))
    }
    return false
}
