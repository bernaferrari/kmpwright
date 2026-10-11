package com.bernaferrari.kmpwright.core

/**
 * Domain filter that restricts network access to a set of allowed domains.
 * Supports wildcard patterns like "*.example.com".
 *
 * Two enforcement layers:
 * 1. **JS-level** (all platforms): Overrides WebSocket, EventSource, sendBeacon constructors.
 * 2. **CDP-level** (JVM only): Uses Fetch.enable to block all requests to disallowed domains.
 */
data class DomainFilter(
    val allowedDomains: Set<String> = emptySet()
) {
    /**
     * Check if a hostname matches the allowed list.
     * Empty allowedDomains = allow all (no restriction).
     * Supports wildcard patterns: "*.example.com" matches "example.com", "api.example.com", etc.
     */
    fun isAllowed(hostname: String): Boolean {
        if (allowedDomains.isEmpty()) return true
        val host = hostname.trim().lowercase().trimEnd('.')
        for (pattern in allowedDomains) {
            val normalizedPattern = pattern.trim().lowercase().trimEnd('.')
            if (normalizedPattern.startsWith("*.")) {
                val suffix = normalizedPattern.removePrefix("*.")
                if (host == suffix || host.endsWith(".$suffix")) return true
            } else if (host == normalizedPattern) {
                return true
            }
        }
        return false
    }

    /**
     * Generates JavaScript that overrides WebSocket, EventSource, and sendBeacon
     * to block connections to domains not in the allowed list.
     * Also patches the Fetch API to block cross-origin XHR/fetch requests.
     */
    fun injectionScript(): String {
        val domainsJson = allowedDomains.joinToString(",") { "\"${escapeJsString(it)}\"" }
        return """
        (function() {
            window.__kmpwright_allowedDomains = [$domainsJson];
            if (window.__kmpwright_domainFilter) return;
            window.__kmpwright_domainFilter = true;
            function _isAllowed(hostname) {
                hostname = (hostname || '').trim().toLowerCase().replace(/\.+$/, '');
                var _allowed = window.__kmpwright_allowedDomains || [];
                if (_allowed.length === 0) return true;
                for (var i = 0; i < _allowed.length; i++) {
                    var p = _allowed[i].trim().toLowerCase().replace(/\.+$/, '');
                    if (p.indexOf('*.') === 0) {
                        var suffix = p.slice(2);
                        if (hostname === suffix || hostname.endsWith('.' + suffix)) return true;
                    } else if (hostname === p) return true;
                }
                return false;
            }
            function _getHostname(url) {
                try { return new URL(url, location.href).hostname; } catch(e) { return ''; }
            }
            // These transports bypass HTTP Fetch interception. Block them while
            // an allowlist is active because their destinations cannot be bounded
            // reliably through WebView/CDP HTTP routing.
            ['RTCPeerConnection', 'webkitRTCPeerConnection', 'Worker', 'SharedWorker'].forEach(function(name) {
                var Original = window[name];
                if (typeof Original !== 'function') return;
                window[name] = function() {
                    if ((window.__kmpwright_allowedDomains || []).length) {
                        throw new DOMException(name + ' blocked while domain filtering is active', 'SecurityError');
                    }
                    return Reflect.construct(Original, Array.from(arguments));
                };
                // Do not expose Original.prototype.constructor as a bypass.
            });
            // Override WebSocket
            var _OrigWS = window.WebSocket;
            window.WebSocket = function(url, protocols) {
                var h = _getHostname(url);
                if (!_isAllowed(h)) throw new DOMException('WebSocket blocked: ' + h, 'SecurityError');
                return new _OrigWS(url, protocols);
            };
            window.WebSocket.prototype = _OrigWS.prototype;
            window.WebSocket.CONNECTING = _OrigWS.CONNECTING;
            window.WebSocket.OPEN = _OrigWS.OPEN;
            window.WebSocket.CLOSING = _OrigWS.CLOSING;
            window.WebSocket.CLOSED = _OrigWS.CLOSED;
            // Override EventSource
            var _OrigES = window.EventSource;
            window.EventSource = function(url, opts) {
                var h = _getHostname(url, location.href);
                if (!_isAllowed(h)) throw new DOMException('EventSource blocked: ' + h, 'SecurityError');
                return new _OrigES(url, opts);
            };
            window.EventSource.prototype = _OrigES.prototype;
            window.EventSource.CONNECTING = _OrigES.CONNECTING;
            window.EventSource.OPEN = _OrigES.OPEN;
            window.EventSource.CLOSED = _OrigES.CLOSED;
            // Override sendBeacon
            var _origBeacon = navigator.sendBeacon.bind(navigator);
            navigator.sendBeacon = function(url, data) {
                var h = _getHostname(url);
                if (!_isAllowed(h)) return false;
                return _origBeacon(url, data);
            };
            // Patch fetch to block disallowed domains
            if (window.__kmpwright_origFetch === undefined) {
                window.__kmpwright_origFetch = window.fetch;
            }
            window.fetch = function(input, init) {
                var url = (typeof input === 'string') ? input : (input && input.url);
                var h = _getHostname(url);
                if (!_isAllowed(h)) return Promise.reject(new DOMException('Fetch blocked: ' + h, 'SecurityError'));
                return window.__kmpwright_origFetch.call(this, input, init);
            };
            // Patch XMLHttpRequest
            var _OrigXHROpen = XMLHttpRequest.prototype.open;
            XMLHttpRequest.prototype.open = function(method, url) {
                this.__kmpwright_url = url;
                return _OrigXHROpen.apply(this, arguments);
            };
            var _OrigXHRSend = XMLHttpRequest.prototype.send;
            XMLHttpRequest.prototype.send = function(body) {
                if (this.__kmpwright_url) {
                    var h = _getHostname(this.__kmpwright_url);
                    if (!_isAllowed(h)) throw new DOMException('XHR blocked: ' + h, 'SecurityError');
                }
                return _OrigXHRSend.apply(this, arguments);
            };
        })();
        """.trimIndent()
    }
}
