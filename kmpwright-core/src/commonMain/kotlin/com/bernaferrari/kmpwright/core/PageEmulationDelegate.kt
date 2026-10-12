package com.bernaferrari.kmpwright.core

/**
 * Delegate for emulation operations (color scheme, geolocation, offline, viewport, device).
 * Extracted from BasePage to reduce class size and improve testability.
 */
internal class PageEmulationDelegate(
    private val evalString: suspend (String) -> String?,
    private val controller: WebViewCaptureController
) {
    suspend fun emulateColorScheme(scheme: ColorScheme) {
        val mediaValue = when (scheme) {
            ColorScheme.Light -> "light"
            ColorScheme.Dark -> "dark"
            ColorScheme.NoPreference -> "no-preference"
        }
        val escapedValue = escapeJsString(mediaValue)
        evalString("""
            (function() {
                var meta = document.querySelector('meta[name="color-scheme"]');
                if (!meta) {
                    meta = document.createElement('meta');
                    meta.name = 'color-scheme';
                    document.head.appendChild(meta);
                }
                meta.content = '$escapedValue';
                var existing = document.getElementById('__kmpwright_color_scheme_style');
                if (existing) existing.remove();
                var style = document.createElement('style');
                style.id = '__kmpwright_color_scheme_style';
                style.textContent = '@media (prefers-color-scheme: $escapedValue) { :root { color-scheme: $escapedValue !important; } }';
                document.head.appendChild(style);
            })()
        """.trimIndent())
    }

    suspend fun resetColorScheme() {
        evalString("""
            (function() {
                var meta = document.querySelector('meta[name="color-scheme"]');
                if (meta) meta.remove();
                var style = document.getElementById('__kmpwright_color_scheme_style');
                if (style) style.remove();
            })()
        """.trimIndent())
    }

    suspend fun emulateGeolocation(latitude: Double, longitude: Double) {
        evalString("""
            (function() {
                if (!navigator.geolocation) return;
                var origGetCurrent = navigator.geolocation.getCurrentPosition;
                var origWatch = navigator.geolocation.watchPosition;
                navigator.geolocation.getCurrentPosition = function(success, error) {
                    success({ coords: { latitude: $latitude, longitude: $longitude, accuracy: 10 }, timestamp: Date.now() });
                };
                navigator.geolocation.watchPosition = function(success, error) {
                    success({ coords: { latitude: $latitude, longitude: $longitude, accuracy: 10 }, timestamp: Date.now() });
                    return 0;
                };
                window.__kmpwright_origGeolocation = { getCurrentPosition: origGetCurrent, watchPosition: origWatch };
            })()
        """.trimIndent())
    }

    suspend fun resetGeolocation() {
        evalString("""
            (function() {
                if (window.__kmpwright_origGeolocation) {
                    navigator.geolocation.getCurrentPosition = window.__kmpwright_origGeolocation.getCurrentPosition;
                    navigator.geolocation.watchPosition = window.__kmpwright_origGeolocation.watchPosition;
                    delete window.__kmpwright_origGeolocation;
                }
            })()
        """.trimIndent())
    }

    suspend fun emulateOffline(offline: Boolean) {
        evalString("""
            (function() {
                if (!window.__kmpwright_origOnline) {
                    window.__kmpwright_origOnline = Object.getOwnPropertyDescriptor(Navigator.prototype, 'onLine');
                }
                Object.defineProperty(navigator, 'onLine', { get: function() { return ${!offline}; } });
                if ($offline) {
                    window.addEventListener('offline', function() {}, { once: true });
                } else {
                    window.addEventListener('online', function() {}, { once: true });
                }
            })()
        """.trimIndent())
    }

    suspend fun emulateNetwork(offline: Boolean, latency: Long, downloadThroughput: Long, uploadThroughput: Long) {
        emulateOffline(offline)
        evalString("""
            (function() {
                if (!window.__kmpwright_origFetch) {
                    window.__kmpwright_origFetch = window.fetch;
                    window.__kmpwright_origXHR = window.XMLHttpRequest.prototype.open;
                }
                if ($offline) {
                    window.fetch = function() { return Promise.reject(new Error('Offline')); };
                    window.XMLHttpRequest.prototype.open = function() { throw new Error('Offline'); };
                } else {
                    window.fetch = window.__kmpwright_origFetch;
                    window.XMLHttpRequest.prototype.open = window.__kmpwright_origXHR;
                }
            })()
        """.trimIndent())
    }

    suspend fun setUserAgent(userAgent: String) {
        controller.setUserAgentAsync(userAgent)
    }

    suspend fun emulateDevice(device: DeviceDescriptor) {
        controller.setViewportSizeAsync(device.viewport.width, device.viewport.height)
        controller.setUserAgentAsync(device.userAgent)
        controller.setDeviceScaleFactorAsync(device.viewport.deviceScaleFactor)
    }

    suspend fun setViewportSize(width: Int, height: Int) {
        controller.setViewportSizeAsync(width, height)
    }

    fun viewportSize(): Pair<Int, Int> {
        return controller.getViewportSize()
    }

    suspend fun setDeviceScaleFactor(scale: Double) {
        controller.setDeviceScaleFactorAsync(scale)
    }
}
