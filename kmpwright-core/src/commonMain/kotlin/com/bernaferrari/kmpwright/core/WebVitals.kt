package com.bernaferrari.kmpwright.core

import com.bernaferrari.kmpwright.core.generated.webVitalsBundle
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** Options for [Page.webVitals]; a null [timeoutMs] uses [TimeoutConfig.default]. */
@Serializable
public data class WebVitalsOptions(val url: String? = null, val reload: Boolean = true,
    val settleMs: Long = 3_000, val timeoutMs: Long? = null)

/** One Core Web Vitals measurement. */
@Serializable
public data class WebVitalMetric(val value: Double, val rating: String, val id: String, val delta: Double)

/** The largest contentful paint element. */
@Serializable
public data class LargestContentfulPaint(val startTime: Double, val size: Double, val element: String?, val url: String?)

/** A single layout shift. */
@Serializable
public data class LayoutShiftEntry(val value: Double, val startTime: Double)

/** Cumulative layout shift and its entries. */
@Serializable
public data class LayoutShiftReport(val score: Double?, val entries: List<LayoutShiftEntry>)

/** A named phase of the page load timeline. */
@Serializable
public data class PerformancePhase(val label: String, val startTime: Double, val endTime: Double) {
    val duration: Double get() = endTime - startTime
}

/** Web Vitals measured by [Page.webVitals]. */
@Serializable
public data class WebVitalsReport(val url: String, val metrics: Map<String, WebVitalMetric>,
    val ttfb: Double?, val lcp: LargestContentfulPaint?, val cls: LayoutShiftReport,
    val fcp: Double?, val inp: Double?, val hydration: PerformancePhase?,
    val phases: List<PerformancePhase>, val hydratedComponents: List<PerformancePhase>,
    val supportedEntryTypes: Set<String>, val droppedTimingEntries: Int)

internal class PageVitalsDelegate(private val controller: WebViewController) {
    private val mutex = Mutex()
    private val counter = atomic(0L)
    private val namespace = secureRandomBytes(12).joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }

    suspend fun capture(page: Page, options: WebVitalsOptions): WebVitalsReport {
        val timeoutMs = options.timeoutMs ?: TimeoutConfig().default
        require(timeoutMs > 0 && options.settleMs >= 0) { "Invalid Web Vitals timing options" }
        return try {
            withTimeout(timeoutMs) { mutex.withLock { captureDocument(page, options) } }
        } catch (_: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            throw KmpwrightTimeoutException("Timeout measuring Web Vitals")
        }
    }

    private suspend fun captureDocument(page: Page, options: WebVitalsOptions): WebVitalsReport {
        val key = "__kmpwrightVitals_${namespace}_${counter.incrementAndGet()}"
        val script = initScript(key)
        var registration: String? = null
        try {
            if (options.url != null || options.reload) registration = controller.addInitScript(script)
            if (options.url != null) {
                val navigation = page.goto(options.url)
                if (!navigation.committed) throw KmpwrightException(navigation.error ?: "Web Vitals navigation failed")
            } else if (options.reload) page.reload()
            controller.evaluateJavaScriptAsync(script)
            delay(options.settleMs)
            val raw = controller.normalizeJavaScriptResult(controller.evaluateJavaScriptAsync(
                "JSON.stringify(window[${jsStringLiteral(key)}].report())"))
                ?: throw KmpwrightException("Web Vitals collector returned no report")
            return parse(raw)
        } finally {
            withContext(NonCancellable) {
                withTimeoutOrNull(1_000) { runCatching {
                    controller.evaluateJavaScriptAsync("(function(){var s=window[${jsStringLiteral(key)}];if(s){s.stop();delete window[${jsStringLiteral(key)}];}})()")
                } }
                registration?.let { id -> withTimeoutOrNull(1_000) { runCatching { controller.removeInitScript(id) } } }
            }
        }
    }

    private fun initScript(key: String): String = """
        (function() {
          if (window[${jsStringLiteral(key)}]) return;
          var active = true, observers = [], listeners = [], metrics = {}, lcp = null, shifts = [], timing = [], dropped = 0;
          var NativeObserver = window.PerformanceObserver;
          var supported = NativeObserver ? Array.from(NativeObserver.supportedEntryTypes || []) : [];
          function PerformanceObserver(callback) {
            var observer = new NativeObserver(function(list, instance) { if (active) callback(list, instance); });
            observers.push(observer); return observer;
          }
          PerformanceObserver.supportedEntryTypes = supported;
          function addEventListener(type, listener, options) {
            var wrapped = function(event) { if (active) listener.call(this, event); };
            listeners.push({type:type, original:listener, wrapped:wrapped, options:options});
            window.addEventListener(type, wrapped, options);
          }
          function removeEventListener(type, listener, options) {
            listeners.filter(function(item){return item.type === type && item.original === listener;}).forEach(function(item){
              window.removeEventListener(type, item.wrapped, options);
            });
          }
          var originalStamp = console.timeStamp;
          var stamp = function(label, start, end, track, group, color) {
            if (active && typeof label === 'string' && typeof start === 'number' && typeof end === 'number' && Number.isFinite(start) && Number.isFinite(end) && end > start) {
              if (timing.length < 3000) timing.push({label:label.slice(0,256),startTime:start,endTime:end,track:String(track || '').slice(0,256),trackGroup:String(group || '').slice(0,256),color:String(color || '').slice(0,256)});
              else dropped++;
            }
            if (typeof originalStamp === 'function') return originalStamp.apply(console, arguments);
          };
          console.timeStamp = stamp;
          ${webVitalsBundle()}
          function onMetric(metric) {
            metrics[metric.name] = {value:metric.value,rating:metric.rating,id:metric.id,delta:metric.delta};
            var entries = metric.entries || [];
            if (metric.name === 'LCP' && entries.length) {
              var last = entries[entries.length-1];
              lcp = {startTime:metric.value,size:last.size || 0,element:last.element ? last.element.tagName.toLowerCase() : null,url:last.url || null};
            }
            if (metric.name === 'CLS') shifts = entries.slice(-100).map(function(entry){return {value:entry.value,startTime:entry.startTime};});
          }
          if (NativeObserver) ['onLCP','onCLS','onINP','onFCP','onTTFB'].forEach(function(name){
            try { webVitals[name](onMetric, {reportAllChanges:true}); } catch (error) {}
          });
          window[${jsStringLiteral(key)}] = {
            report:function(){return {url:location.href,metrics:metrics,lcp:lcp,shifts:shifts,timing:timing,supported:supported,dropped:dropped};},
            stop:function(){
              active = false; observers.forEach(function(observer){observer.disconnect();});
              listeners.forEach(function(item){window.removeEventListener(item.type,item.wrapped,item.options);});
              observers = []; listeners = [];
              if (console.timeStamp === stamp) console.timeStamp = originalStamp;
            }
          };
        })()
    """.trimIndent()

    private fun parse(raw: String): WebVitalsReport {
        val data = Json.parseToJsonElement(raw).jsonObject
        val metrics = data["metrics"]!!.jsonObject.mapValues { (_, rawMetric) ->
            val item = rawMetric.jsonObject
            WebVitalMetric(item.number("value")!!, item.string("rating"), item.string("id"), item.number("delta")!!)
        }
        val phases = mutableListOf<PerformancePhase>()
        val components = mutableListOf<PerformancePhase>()
        data["timing"]?.jsonArray?.forEach { rawTiming ->
            val item = rawTiming.jsonObject
            val phase = PerformancePhase(item.string("label"), item.number("startTime")!!, item.number("endTime")!!)
            if (item.string("trackGroup") == "Scheduler ⚛") phases += phase
            else if (item.string("track") == "Components ⚛" && item.string("color").startsWith("tertiary")) components += phase
        }
        val hydrated = phases.filter { it.label == "Hydrated" }
        val hydration = if (hydrated.isEmpty()) null else PerformancePhase("Hydrated", hydrated.minOf { it.startTime }, hydrated.maxOf { it.endTime })
        val lcp = (data["lcp"] as? JsonObject)?.let { item -> LargestContentfulPaint(item.number("startTime")!!,
            item.number("size")!!, item["element"]?.jsonPrimitive?.contentOrNull, item["url"]?.jsonPrimitive?.contentOrNull) }
        val shifts = data["shifts"]?.jsonArray?.map { item -> LayoutShiftEntry(item.jsonObject.number("value")!!, item.jsonObject.number("startTime")!!) } ?: emptyList()
        return WebVitalsReport(data.string("url"), metrics, metrics["TTFB"]?.value, lcp,
            LayoutShiftReport(metrics["CLS"]?.value, shifts), metrics["FCP"]?.value, metrics["INP"]?.value,
            hydration, phases, components.sortedByDescending { it.duration },
            data["supported"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet() ?: emptySet(), data["dropped"]?.jsonPrimitive?.int ?: 0)
    }

    private fun JsonObject.number(key: String): Double? = this[key]?.jsonPrimitive?.doubleOrNull
    private fun JsonObject.string(key: String): String = this[key]?.jsonPrimitive?.contentOrNull ?: ""
}
