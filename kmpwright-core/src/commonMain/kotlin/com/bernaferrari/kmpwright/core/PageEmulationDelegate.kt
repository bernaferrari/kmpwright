package com.bernaferrari.kmpwright.core

import kotlinx.atomicfu.atomic
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** JavaScript-level emulation applied to every document of a page (WebView fallback path). */
internal data class EmulationState(
    val colorScheme: ColorScheme? = null,
    val geolocation: Geolocation? = null,
    val offline: Boolean = false,
    val allowedDomains: Set<String> = emptySet()
) {
    val isEmpty: Boolean get() = colorScheme == null && geolocation == null && !offline && allowedDomains.isEmpty()
}

/**
 * The emulation runtime: one closure per document holding the emulated state. Patches read the state
 * at call time, so updates in an already-loaded document take effect immediately. The runtime's
 * update entry point lives under a random, non-writable, non-configurable slot and requires the
 * session seal, so page scripts cannot switch emulation off. When a domain allowlist is active the
 * patched network APIs are additionally locked (non-writable, non-configurable).
 */
internal object EmulationScripts {
    private val key = PageHelpers.key + "_emu"

    fun script(state: EmulationState): String {
        val json = buildJsonObject {
            put("scheme", state.colorScheme?.let {
                when (it) { ColorScheme.Light -> "light"; ColorScheme.Dark -> "dark"; ColorScheme.NoPreference -> "no-preference" }
            })
            if (state.geolocation != null) put("geo", buildJsonObject {
                put("latitude", state.geolocation.latitude); put("longitude", state.geolocation.longitude); put("accuracy", state.geolocation.accuracy)
            }) else put("geo", JsonNull)
            put("offline", state.offline)
            put("domains", buildJsonArray { state.allowedDomains.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
        }
        return """
(function(){
  var KEY = ${jsStringLiteral(key)}, SEAL = ${jsStringLiteral(PageHelpers.seal)};
  var api = window[KEY];
  if (!api) {
    var win = window, O = Object, nav = win.navigator, state = {scheme: null, geo: null, offline: false, domains: []}, locked = false;
    function define(target, name, value, lock) {
      try { O.defineProperty(target, name, {value: value, writable: !lock, configurable: !lock, enumerable: false}); } catch (e) {}
    }
    function hostOf(url) { try { return new win.URL(String(url), win.location.href).hostname; } catch (e) { return ''; } }
    function allowed(url) {
      var list = state.domains;
      if (!list.length) return true;
      var host = hostOf(url).toLowerCase().replace(/\.+${'$'}/, '');
      for (var i = 0; i < list.length; i++) {
        var p = String(list[i]).trim().toLowerCase().replace(/\.+${'$'}/, '');
        if (p.indexOf('*.') === 0) { var suffix = p.slice(2); if (host === suffix || host.slice(-suffix.length - 1) === '.' + suffix) return true; }
        else if (host === p) return true;
      }
      return false;
    }
    function blocked(kind, url) { return new win.DOMException(kind + ' blocked by KMPwright domain filter: ' + hostOf(url), 'SecurityError'); }
    // prefers-color-scheme through matchMedia
    var origMatchMedia = win.matchMedia;
    var schemeQuery = /^\s*\(\s*prefers-color-scheme\s*:\s*(light|dark|no-preference)\s*\)\s*${'$'}/i;
    function fakeList(query, matches) {
      return {matches: matches, media: query, onchange: null,
        addListener: function() {}, removeListener: function() {},
        addEventListener: function() {}, removeEventListener: function() {}, dispatchEvent: function() { return true; }};
    }
    var patchedMatchMedia = function matchMedia(query) {
      var q = String(query), m = schemeQuery.exec(q);
      if (state.scheme && m) return fakeList(q, m[1].toLowerCase() === state.scheme);
      return origMatchMedia.call(win, query);
    };
    if (typeof origMatchMedia === 'function') define(win, 'matchMedia', patchedMatchMedia, false);
    // geolocation
    var geo = nav.geolocation, origGet = geo && geo.getCurrentPosition, origWatch = geo && geo.watchPosition, watchId = 1e6;
    function position() { return {coords: {latitude: state.geo.latitude, longitude: state.geo.longitude, accuracy: state.geo.accuracy || 0, altitude: null,
      altitudeAccuracy: null, heading: null, speed: null}, timestamp: Date.now()}; }
    if (geo) {
      define(geo, 'getCurrentPosition', function getCurrentPosition(success, error, options) {
        if (!state.geo) return origGet.apply(geo, arguments);
        setTimeout(function() { success(position()); }, 0);
      }, false);
      define(geo, 'watchPosition', function watchPosition(success, error, options) {
        if (!state.geo) return origWatch.apply(geo, arguments);
        setTimeout(function() { success(position()); }, 0);
        return ++watchId;
      }, false);
    }
    // offline + domain filter for script-initiated network APIs
    var onLine = O.getOwnPropertyDescriptor(win.Navigator.prototype, 'onLine');
    try { O.defineProperty(nav, 'onLine', {configurable: true, get: function() { return state.offline ? false : (onLine ? onLine.get.call(nav) : true); }}); } catch (e) {}
    var origFetch = win.fetch;
    var patchedFetch = typeof origFetch === 'function' ? function fetch(input, init) {
      var url = (typeof input === 'string' || input instanceof win.URL) ? String(input) : (input && input.url);
      if (state.offline) return Promise.reject(new TypeError('Failed to fetch (offline emulation)'));
      if (!allowed(url)) return Promise.reject(blocked('fetch', url));
      return origFetch.apply(this, arguments);
    } : null;
    if (patchedFetch) define(win, 'fetch', patchedFetch, false);
    var XHR = win.XMLHttpRequest && win.XMLHttpRequest.prototype, origOpen = XHR && XHR.open, origSend = XHR && XHR.send, urls = new win.WeakMap();
    var patchedOpen = function open(method, url) { urls.set(this, url); return origOpen.apply(this, arguments); };
    var patchedSend = function send() {
      if (state.offline) throw new win.DOMException('Network request failed (offline emulation)', 'NetworkError');
      var url = urls.get(this);
      if (!allowed(url)) throw blocked('XMLHttpRequest', url);
      return origSend.apply(this, arguments);
    };
    if (XHR) { define(XHR, 'open', patchedOpen, false); define(XHR, 'send', patchedSend, false); }
    function wrapConstructor(name, kind) {
      var Original = win[name];
      if (typeof Original !== 'function') return null;
      var Wrapped = function() {
        var url = arguments[0];
        if (kind === 'url' && !allowed(url)) throw blocked(name, url);
        if (kind === 'any' && state.domains.length) throw blocked(name, '');
        return Reflect.construct(Original, Array.prototype.slice.call(arguments), new.target || Wrapped);
      };
      Wrapped.prototype = Original.prototype;
      ['CONNECTING', 'OPEN', 'CLOSING', 'CLOSED'].forEach(function(c) { if (c in Original) define(Wrapped, c, Original[c], true); });
      define(win, name, Wrapped, false);
      return Wrapped;
    }
    var wrapped = {
      WebSocket: wrapConstructor('WebSocket', 'url'),
      EventSource: wrapConstructor('EventSource', 'url'),
      Worker: wrapConstructor('Worker', 'url'),
      SharedWorker: wrapConstructor('SharedWorker', 'url'),
      RTCPeerConnection: wrapConstructor('RTCPeerConnection', 'any'),
      webkitRTCPeerConnection: wrapConstructor('webkitRTCPeerConnection', 'any')
    };
    var origBeacon = nav.sendBeacon;
    var patchedBeacon = typeof origBeacon === 'function' ? function sendBeacon(url, data) {
      if (state.offline || !allowed(url)) return false;
      return origBeacon.apply(nav, arguments);
    } : null;
    if (patchedBeacon) define(nav, 'sendBeacon', patchedBeacon, false);
    function lock() {
      if (locked) return;
      locked = true;
      if (patchedFetch) define(win, 'fetch', patchedFetch, true);
      if (XHR) { define(XHR, 'open', patchedOpen, true); define(XHR, 'send', patchedSend, true); }
      if (patchedBeacon) define(nav, 'sendBeacon', patchedBeacon, true);
      for (var name in wrapped) if (wrapped[name]) define(win, name, wrapped[name], true);
    }
    function applyScheme() {
      var root = win.document && win.document.documentElement;
      if (!root) return;
      if (state.scheme && state.scheme !== 'no-preference') root.style.setProperty('color-scheme', state.scheme);
      else root.style.removeProperty('color-scheme');
    }
    api = O.freeze({
      apply: function(seal, next) {
        if (seal !== SEAL) throw new Error('Forbidden');
        var wasOffline = state.offline;
        state = {scheme: next.scheme || null, geo: next.geo || null, offline: !!next.offline, domains: (next.domains || []).slice()};
        O.freeze(state.domains);
        if (state.domains.length) lock();
        applyScheme();
        if (wasOffline !== state.offline) { try { win.dispatchEvent(new win.Event(state.offline ? 'offline' : 'online')); } catch (e) {} }
        return true;
      }
    });
    O.defineProperty(win, KEY, {value: api, writable: false, enumerable: false, configurable: false});
  }
  return api.apply(SEAL, $json);
})()
        """.trimIndent()
    }
}

/**
 * Emulation for one page. Native backend hooks ([WebViewEmulationController]) are preferred; when a
 * hook is unavailable the JavaScript runtime above is applied to the current document and registered
 * as a document-start script so it survives navigations.
 */
internal class PageEmulationDelegate(
    private val evalString: suspend (String) -> String?,
    private val controller: WebViewController
) {
    private val mutex = Mutex()
    private val state = atomic(EmulationState())
    private var initScriptId: String? = null

    /** True when the JS fallback could not be registered for future documents (current document only). */
    var persistentFallbackUnavailable: Boolean = false
        private set

    val current: EmulationState get() = state.value

    private suspend fun update(transform: (EmulationState) -> EmulationState) = mutex.withLock {
        val previousState = state.value
        val next = transform(previousState)
        // Nothing emulated before or after (e.g. a native hook handled it): never patch the document.
        if (next.isEmpty && previousState.isEmpty) return@withLock
        state.value = next
        val script = EmulationScripts.script(next)
        evalString(script)
        val previous = initScriptId
        initScriptId = null
        if (!next.isEmpty) {
            initScriptId = try {
                controller.addInitScript(script).also { persistentFallbackUnavailable = false }
            } catch (e: KmpwrightException) {
                persistentFallbackUnavailable = true
                null
            }
        }
        if (previous != null) runCatching { controller.removeInitScript(previous) }
    }

    suspend fun emulateMedia(scheme: ColorScheme?) {
        if (controller.setColorSchemeOverride(scheme)) {
            update { it.copy(colorScheme = null) }
            return
        }
        update { it.copy(colorScheme = scheme) }
    }

    suspend fun setGeolocation(coordinates: Geolocation?) {
        coordinates?.let {
            require(it.latitude in -90.0..90.0 && it.longitude in -180.0..180.0) { "Geolocation must be within latitude -90..90 and longitude -180..180" }
            require(it.accuracy.isFinite() && it.accuracy >= 0) { "Geolocation accuracy must not be negative" }
        }
        if (controller.setGeolocationOverride(coordinates)) {
            update { it.copy(geolocation = null) }
            return
        }
        update { it.copy(geolocation = coordinates) }
    }

    suspend fun emulateNetworkConditions(offline: Boolean, latency: Long, downloadThroughput: Long, uploadThroughput: Long) {
        require(latency >= 0 && downloadThroughput >= 0 && uploadThroughput >= 0) { "Network conditions must not be negative" }
        if (controller.setNetworkConditions(offline, latency, downloadThroughput, uploadThroughput)) {
            update { it.copy(offline = false) }
            return
        }
        if (latency > 0 || downloadThroughput > 0 || uploadThroughput > 0) {
            throw KmpwrightUnsupportedException(
                "This backend cannot throttle latency or throughput; only offline emulation is available (see PageCapabilities.networkEmulation)."
            )
        }
        update { it.copy(offline = offline) }
    }

    /** Applies the JavaScript allowlist fallback. Returns false when nothing could persist across navigations. */
    suspend fun setDomainFilterFallback(domains: Set<String>): Boolean {
        update { it.copy(allowedDomains = domains) }
        return domains.isEmpty() || !persistentFallbackUnavailable
    }

    suspend fun setUserAgent(userAgent: String) {
        controller.setUserAgentAsync(userAgent)
    }

    /**
     * Validates everything before changing anything, then applies the viewport (CDP: size, scale,
     * mobile and touch; WebViews: size only) and the user agent. Never fails half-applied because a
     * WebView cannot emulate the device scale factor.
     */
    suspend fun emulateDevice(device: DeviceDescriptor) {
        val viewport = device.viewport
        require(viewport.width > 0 && viewport.height > 0) { "Viewport size must be positive" }
        require(viewport.deviceScaleFactor.isFinite() && viewport.deviceScaleFactor > 0) { "Device scale factor must be positive" }
        require(device.userAgent.isNotBlank()) { "Device user agent must not be blank" }
        controller.emulateViewportAsync(viewport)
        controller.setUserAgentAsync(device.userAgent)
    }

    suspend fun setViewportSize(width: Int, height: Int) {
        require(width > 0 && height > 0) { "Viewport size must be positive" }
        controller.setViewportSizeAsync(width, height)
    }

    fun viewportSize(): ViewportSize = controller.getViewportSize().let { (width, height) -> ViewportSize(width, height) }

    suspend fun setDeviceScaleFactor(scale: Double) {
        require(scale.isFinite() && scale > 0) { "Device scale factor must be positive" }
        controller.setDeviceScaleFactorAsync(scale)
    }
}
