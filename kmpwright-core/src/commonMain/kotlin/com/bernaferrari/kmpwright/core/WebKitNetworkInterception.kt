package com.bernaferrari.kmpwright.core

internal fun buildWebKitNetworkInterceptionScript(
    messageHandlerName: String = "kmpwright",
    bridgeNonce: String? = null,
    bridgeTimeoutMs: Long = 10_000
): String {
    val jsHandlerName = jsStringLiteral(messageHandlerName)
    val jsBridgeNonce = jsStringLiteral(bridgeNonce ?: "")
    return """
        (function() {
            if (window.__kmpwright_network_installed) return;
            window.__kmpwright_network_installed = true;
            var pending = {};
            var requestCounter = 0;
            var handlerName = $jsHandlerName;
            var bridgeNonce = $jsBridgeNonce;
            var originalFetch = window.fetch ? window.fetch.bind(window) : null;
            var originalXHROpen = window.XMLHttpRequest && window.XMLHttpRequest.prototype.open;
            var originalXHRSend = window.XMLHttpRequest && window.XMLHttpRequest.prototype.send;
            var originalXHRSetRequestHeader = window.XMLHttpRequest && window.XMLHttpRequest.prototype.setRequestHeader;
            var originalXHRGetResponseHeader = window.XMLHttpRequest && window.XMLHttpRequest.prototype.getResponseHeader;
            var originalXHRGetAllResponseHeaders = window.XMLHttpRequest && window.XMLHttpRequest.prototype.getAllResponseHeaders;

            function nextRequestId(prefix) {
                requestCounter += 1;
                return prefix + '-' + Date.now() + '-' + requestCounter + '-' + Math.random().toString(16).slice(2);
            }

            function postToNative(payload) {
                try {
                    payload.nonce = bridgeNonce;
                    var bridge = window.webkit && window.webkit.messageHandlers && window.webkit.messageHandlers[handlerName];
                    if (!bridge) return false;
                    bridge.postMessage(JSON.stringify(payload));
                    return true;
                } catch (e) { return false; }
            }

            function finishPending(id) {
                var entry = pending[id];
                if (!entry) return null;
                delete pending[id];
                clearTimeout(entry.timer);
                if (entry.signal && entry.onAbort) entry.signal.removeEventListener('abort', entry.onAbort);
                return entry;
            }

            function failPending(id, error) {
                var entry = finishPending(id);
                if (!entry) return;
                if (entry.kind === 'fetch') entry.reject(error);
                else abortXhr(entry);
                postToNative({ type: 'requestFailed', id: id, url: entry.url || entry.meta.url, error: error.message });
            }

            function armPending(entry) {
                pending[entry.id] = entry;
                entry.timer = setTimeout(function() {
                    failPending(entry.id, new Error('KMPwright network bridge timed out'));
                }, $bridgeTimeoutMs);
                if (entry.signal) {
                    entry.onAbort = function() { failPending(entry.id, entry.signal.reason || new DOMException('Aborted', 'AbortError')); };
                    entry.signal.addEventListener('abort', entry.onAbort, { once: true });
                    if (entry.signal.aborted) entry.onAbort();
                }
            }

            function bytesFromBase64(base64Value) {
                if (!base64Value) return null;
                var binary = atob(base64Value);
                var bytes = new Uint8Array(binary.length);
                for (var i = 0; i < binary.length; i++) {
                    bytes[i] = binary.charCodeAt(i);
                }
                return bytes;
            }

            function textFromBytes(bytes, fallbackText) {
                if (!bytes) return fallbackText || '';
                try {
                    if (typeof TextDecoder !== 'undefined') {
                        return new TextDecoder('utf-8').decode(bytes);
                    }
                } catch (e) {}
                var out = '';
                for (var i = 0; i < bytes.length; i++) {
                    out += String.fromCharCode(bytes[i]);
                }
                return out;
            }

            function bytesFromText(text) {
                var value = text || '';
                try {
                    if (typeof TextEncoder !== 'undefined') {
                        return new TextEncoder().encode(value);
                    }
                } catch (e) {}
                var bytes = new Uint8Array(value.length);
                for (var i = 0; i < value.length; i++) {
                    bytes[i] = value.charCodeAt(i) & 255;
                }
                return bytes;
            }

            function normalizedHeaderMap(headers, contentType) {
                var result = {};
                if (headers) {
                    Object.keys(headers).forEach(function(key) {
                        result[String(key).toLowerCase()] = String(headers[key]);
                    });
                }
                if (contentType && !result['content-type']) {
                    result['content-type'] = contentType;
                }
                return result;
            }

            function headerLookup(headers, name) {
                return headers[String(name || '').toLowerCase()] || null;
            }

            function headerString(headers) {
                return Object.keys(headers).map(function(key) {
                    return key + ': ' + headers[key];
                }).join('\r\n');
            }

            function headersToObject(headers) {
                var out = {};
                if (!headers || !headers.forEach) return out;
                headers.forEach(function(value, key) {
                    out[String(key)] = String(value);
                });
                return out;
            }

            function parseHeaderString(raw) {
                var out = {};
                if (!raw) return out;
                raw.split(/\r?\n/).forEach(function(line) {
                    if (!line) return;
                    var index = line.indexOf(':');
                    if (index <= 0) return;
                    var key = line.slice(0, index).trim();
                    var value = line.slice(index + 1).trim();
                    if (key) out[key] = value;
                });
                return out;
            }

            function dispatchXhrEvent(xhr, type) {
                var event;
                try {
                    event = new Event(type);
                } catch (e) {
                    event = document.createEvent('Event');
                    event.initEvent(type, false, false);
                }
                var handler = xhr['on' + type];
                if (typeof handler === 'function') {
                    try { handler.call(xhr, event); } catch (e) {}
                }
                if (typeof xhr.dispatchEvent === 'function') {
                    try { xhr.dispatchEvent(event); } catch (e) {}
                }
            }

            function defineReadonly(xhr, property, value) {
                try {
                    Object.defineProperty(xhr, property, {
                        configurable: true,
                        get: function() { return value; }
                    });
                    return;
                } catch (e) {}
                try {
                    xhr[property] = value;
                } catch (e) {}
            }

            function buildXhrResponse(xhr, msg, state) {
                var bytes = bytesFromBase64(msg.bodyBase64) || bytesFromText(msg.body || '');
                var text = textFromBytes(bytes, msg.body || '');
                var headers = normalizedHeaderMap(msg.headers, msg.contentType);
                var responseType = xhr.responseType || '';
                var responseValue = text;
                if (responseType === 'arraybuffer') {
                    responseValue = bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength);
                } else if (responseType === 'blob' && typeof Blob !== 'undefined') {
                    responseValue = new Blob([bytes], { type: headerLookup(headers, 'content-type') || 'application/octet-stream' });
                } else if (responseType === 'json') {
                    try {
                        responseValue = text ? JSON.parse(text) : null;
                    } catch (e) {
                        responseValue = null;
                    }
                } else if (responseType === 'document' && typeof DOMParser !== 'undefined') {
                    try {
                        responseValue = new DOMParser().parseFromString(text, headerLookup(headers, 'content-type') || 'text/html');
                    } catch (e) {
                        responseValue = null;
                    }
                }
                state.meta.responseHeaders = headers;
                state.meta.responseText = text;
                state.meta.response = responseValue;
                defineReadonly(xhr, 'status', msg.status || 200);
                defineReadonly(xhr, 'statusText', msg.statusText || 'OK');
                defineReadonly(xhr, 'responseURL', msg.url || state.meta.url || '');
                defineReadonly(xhr, 'responseText', text);
                defineReadonly(xhr, 'response', responseValue);
                defineReadonly(xhr, 'readyState', 4);
            }

            function continueXhr(pendingEntry, msg) {
                var xhr = pendingEntry.xhr;
                var meta = pendingEntry.meta;
                var overrideHeaders = msg && msg.headers;
                var hasOverrides = !!(
                    msg &&
                    (msg.url ||
                        msg.method ||
                        overrideHeaders ||
                        Object.prototype.hasOwnProperty.call(msg, 'postData') ||
                        Object.prototype.hasOwnProperty.call(msg, 'body'))
                );
                observeXhrNetworkResult(pendingEntry.id, xhr, meta);
                if (!hasOverrides) {
                    originalXHRSend.call(xhr, pendingEntry.body);
                    return;
                }
                originalXHROpen.call(
                    xhr,
                    msg.method || meta.method || 'GET',
                    msg.url || meta.url || '',
                    meta.async,
                    meta.user,
                    meta.password
                );
                var headersToApply = overrideHeaders || meta.headers || {};
                Object.keys(headersToApply).forEach(function(key) {
                    originalXHRSetRequestHeader.call(xhr, key, headersToApply[key]);
                });
                var body = Object.prototype.hasOwnProperty.call(msg, 'postData')
                    ? msg.postData
                    : (Object.prototype.hasOwnProperty.call(msg, 'body') ? msg.body : pendingEntry.body);
                originalXHRSend.call(xhr, body);
            }

            function observeFetchNetworkResult(id, promise) {
                return promise.then(function(response) {
                    postToNative({
                        type: 'response',
                        id: id,
                        url: response && response.url ? response.url : '',
                        status: response && typeof response.status === 'number' ? response.status : 0,
                        statusText: response && response.statusText ? response.statusText : '',
                        headers: response ? headersToObject(response.headers) : {}
                    });
                    return response;
                }).catch(function(error) {
                    postToNative({
                        type: 'requestFailed',
                        id: id,
                        url: '',
                        error: String((error && error.message) || error || 'fetch failed')
                    });
                    throw error;
                });
            }

            function observeXhrNetworkResult(id, xhr, meta) {
                if (xhr.__kmpwrightObservedRequestId === id) return;
                xhr.__kmpwrightObservedRequestId = id;

                xhr.addEventListener('load', function() {
                    try {
                        meta.responseHeaders = parseHeaderString(originalXHRGetAllResponseHeaders.call(xhr));
                    } catch (e) {}
                    postToNative({
                        type: 'response',
                        id: id,
                        url: xhr.responseURL || meta.url || '',
                        status: typeof xhr.status === 'number' ? xhr.status : 0,
                        statusText: xhr.statusText || '',
                        headers: normalizedHeaderMap(meta.responseHeaders || {}, null)
                    });
                }, { once: true });

                function reportFailure(kind) {
                    postToNative({
                        type: 'requestFailed',
                        id: id,
                        url: xhr.responseURL || meta.url || '',
                        error: kind
                    });
                }

                xhr.addEventListener('error', function() { reportFailure('error'); }, { once: true });
                xhr.addEventListener('abort', function() { reportFailure('aborted'); }, { once: true });
                xhr.addEventListener('timeout', function() { reportFailure('timeout'); }, { once: true });
            }

            function fulfillXhr(pendingEntry, msg) {
                var xhr = pendingEntry.xhr;
                observeXhrNetworkResult(pendingEntry.id, xhr, pendingEntry.meta);
                buildXhrResponse(xhr, msg, pendingEntry);
                dispatchXhrEvent(xhr, 'readystatechange');
                dispatchXhrEvent(xhr, 'load');
                dispatchXhrEvent(xhr, 'loadend');
            }

            function abortXhr(pendingEntry) {
                var xhr = pendingEntry.xhr;
                observeXhrNetworkResult(pendingEntry.id, xhr, pendingEntry.meta);
                defineReadonly(xhr, 'status', 0);
                defineReadonly(xhr, 'statusText', '');
                defineReadonly(xhr, 'readyState', 4);
                dispatchXhrEvent(xhr, 'readystatechange');
                dispatchXhrEvent(xhr, 'abort');
                dispatchXhrEvent(xhr, 'loadend');
            }

            window.__kmpwrightNetworkRespond = function(msg) {
                var pendingEntry = finishPending(msg.id);
                if (!pendingEntry) return;

                if (pendingEntry.kind === 'fetch') {
                    if (msg.action === 'continue') {
                        var nextInput = !msg.url || msg.url === pendingEntry.url ? pendingEntry.input : msg.url;
                        var nextInit = Object.assign({}, pendingEntry.init || {});
                        if (nextInput !== pendingEntry.input) {
                            var original = pendingEntry.input;
                            nextInit.method = original.method;
                            nextInit.headers = original.headers;
                            nextInit.body = original.body;
                            nextInit.credentials = original.credentials;
                            nextInit.mode = original.mode;
                            nextInit.redirect = original.redirect;
                            nextInit.signal = original.signal;
                            nextInit.duplex = 'half';
                        }
                        if (msg.method) nextInit.method = msg.method;
                        if (msg.headers) nextInit.headers = msg.headers;
                        if (Object.prototype.hasOwnProperty.call(msg, 'body')) {
                            nextInit.body = msg.body;
                        }
                        try {
                            observeFetchNetworkResult(msg.id, originalFetch(nextInput, nextInit))
                                .then(pendingEntry.resolve).catch(pendingEntry.reject);
                        } catch (error) { pendingEntry.reject(error); }
                        return;
                    }
                    if (msg.action === 'abort') {
                        postToNative({
                            type: 'requestFailed',
                            id: msg.id,
                            url: pendingEntry.url || (pendingEntry.input && pendingEntry.input.url ? pendingEntry.input.url : ''),
                            error: msg.error || 'aborted'
                        });
                        pendingEntry.reject(new Error(msg.error || 'aborted'));
                        return;
                    }
                    if (msg.action === 'fulfill') {
                        var headers = new Headers(msg.headers || {});
                        var bytes = bytesFromBase64(msg.bodyBase64);
                        var body = msg.body || '';
                        if (!headers.has('content-type') && msg.contentType) {
                            headers.set('content-type', msg.contentType);
                        }
                        postToNative({
                            type: 'response',
                            id: msg.id,
                            url: msg.url || pendingEntry.url || '',
                            status: msg.status || 200,
                            statusText: msg.statusText || 'OK',
                            headers: headersToObject(headers)
                        });
                        try {
                            var status = msg.status || 200;
                            var responseBody = [204, 205, 304].indexOf(status) !== -1 ? null : (bytes || body);
                            pendingEntry.resolve(new Response(responseBody, { status: status, statusText: msg.statusText || 'OK', headers: headers }));
                        } catch (error) { pendingEntry.reject(error); }
                        return;
                    }
                    observeFetchNetworkResult(msg.id, originalFetch(pendingEntry.input, pendingEntry.init))
                        .then(pendingEntry.resolve)
                        .catch(pendingEntry.reject);
                    return;
                }

                if (pendingEntry.kind === 'xhr') {
                    if (msg.action === 'continue') {
                        continueXhr(pendingEntry, msg);
                        return;
                    }
                    if (msg.action === 'abort') {
                        abortXhr(pendingEntry);
                        return;
                    }
                    if (msg.action === 'fulfill') {
                        fulfillXhr(pendingEntry, msg);
                        return;
                    }
                    continueXhr(pendingEntry, msg);
                }
            };

            window.__kmpwrightNetworkDispose = function() {
                Object.keys(pending).forEach(function(id) { failPending(id, new Error('KMPwright network bridge detached')); });
                if (originalFetch) window.fetch = originalFetch;
                if (window.XMLHttpRequest) {
                    var prototype = window.XMLHttpRequest.prototype;
                    if (originalXHROpen) prototype.open = originalXHROpen;
                    if (originalXHRSend) prototype.send = originalXHRSend;
                    if (originalXHRSetRequestHeader) prototype.setRequestHeader = originalXHRSetRequestHeader;
                    if (originalXHRGetAllResponseHeaders) prototype.getAllResponseHeaders = originalXHRGetAllResponseHeaders;
                    if (originalXHRGetResponseHeader) prototype.getResponseHeader = originalXHRGetResponseHeader;
                }
                delete window.__kmpwright_network_installed;
                delete window.__kmpwrightNetworkRespond;
                delete window.__kmpwrightNetworkDispose;
            };

            if (originalFetch) {
                window.fetch = function(input, init) {
                    var request;
                    try { request = new Request(input, init); }
                    catch (error) { return Promise.reject(error); }
                    var id = nextRequestId('fetch');
                    var url = request.url;
                    var headers = headersToObject(request.headers);
                    return new Promise(function(resolve, reject) {
                        armPending({ kind: 'fetch', id: id, resolve: resolve, reject: reject,
                            input: request, init: undefined, url: url, signal: request.signal });
                        // Read a clone so fallthrough preserves the native body bytes,
                        // multipart boundaries and Request semantics.
                        request.clone().text().then(function(body) {
                            if (!pending[id]) return;
                            if (!postToNative({ type: 'fetch', id: id, url: url, method: request.method,
                                    headers: headers, body: request.body == null ? null : body })) {
                                failPending(id, new Error('KMPwright network bridge is unavailable'));
                            }
                        }).catch(function(error) { failPending(id, error); });
                    });
                };
            }

            if (originalXHROpen && originalXHRSend && originalXHRSetRequestHeader) {
                window.XMLHttpRequest.prototype.open = function(method, url, async, user, password) {
                    this.__kmpwrightMeta = {
                        method: method || 'GET',
                        url: url || '',
                        async: async !== false,
                        user: user,
                        password: password,
                        headers: {}
                    };
                    return originalXHROpen.apply(this, arguments);
                };

                window.XMLHttpRequest.prototype.setRequestHeader = function(name, value) {
                    if (this.__kmpwrightMeta) {
                        this.__kmpwrightMeta.headers[name] = value;
                    }
                    return originalXHRSetRequestHeader.apply(this, arguments);
                };

                window.XMLHttpRequest.prototype.send = function(body) {
                    if (!this.__kmpwrightMeta) {
                        return originalXHRSend.apply(this, arguments);
                    }
                    var id = nextRequestId('xhr');
                    armPending({
                        kind: 'xhr',
                        id: id,
                        xhr: this,
                        meta: this.__kmpwrightMeta,
                        body: body
                    });
                    dispatchXhrEvent(this, 'loadstart');
                    if (!postToNative({
                        type: 'xhr',
                        id: id,
                        url: this.__kmpwrightMeta.url || '',
                        method: this.__kmpwrightMeta.method || 'GET',
                        headers: this.__kmpwrightMeta.headers || {},
                        body: typeof body === 'string' ? body : null
                    })) failPending(id, new Error('KMPwright network bridge is unavailable'));
                };

                window.XMLHttpRequest.prototype.getAllResponseHeaders = function() {
                    return this.__kmpwrightMeta && this.__kmpwrightMeta.responseHeaders
                        ? headerString(this.__kmpwrightMeta.responseHeaders)
                        : '';
                };

                window.XMLHttpRequest.prototype.getResponseHeader = function(name) {
                    return this.__kmpwrightMeta && this.__kmpwrightMeta.responseHeaders
                        ? headerLookup(this.__kmpwrightMeta.responseHeaders, name)
                        : null;
                };
            }
        })();
    """.trimIndent()
}
