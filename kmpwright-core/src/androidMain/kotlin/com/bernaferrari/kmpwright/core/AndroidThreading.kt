package com.bernaferrari.kmpwright.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import kotlin.coroutines.resume

/**
 * Android WebView is single-threaded: every WebView, WebSettings and callback
 * form of CookieManager must be used on the main looper. These helpers keep
 * the public, thread-agnostic Kmpwright API safe to call from any dispatcher.
 */
internal val mainHandler: Handler by lazy { Handler(Looper.getMainLooper()) }

internal fun isMainThread(): Boolean = Looper.myLooper() == Looper.getMainLooper()

/** Run [block] now when already on the main thread, otherwise post it (FIFO). */
internal fun runOnMain(block: () -> Unit) {
    if (isMainThread()) block() else mainHandler.post(block)
}

/** Suspend until [block] has completed on the main thread. Exceptions propagate to the caller. */
internal suspend fun <T> onMain(block: suspend CoroutineScope.() -> T): T =
    withContext(Dispatchers.Main.immediate, block)

internal fun checkMainThread(api: String) {
    check(isMainThread()) {
        "$api must be called on the Android main thread (WebView is single-threaded). " +
            "Create it from Activity/Fragment/Compose code or wrap it in withContext(Dispatchers.Main)."
    }
}

/**
 * Application storage root used by profile/state persistence. `user.home` is
 * empty on Android, so the app's private files directory is captured from the
 * first [Browser] (or [KmpwrightAndroid.initialize]).
 */
internal object AndroidAppStorage {
    @Volatile
    private var filesDir: File? = null

    fun remember(context: Context) {
        if (filesDir == null) filesDir = context.applicationContext.filesDir
    }

    fun requireFilesDir(): File = filesDir ?: throw KmpwrightException(
        "Kmpwright storage is not initialized on Android. Create a Browser with AndroidPlatformContext " +
            "or call KmpwrightAndroid.initialize(context) before using auth profiles or saved state."
    )
}

/** Android-specific entry points that do not belong to the multiplatform API. */
public object KmpwrightAndroid {
    /** Records the application's private files directory for auth profiles and saved state. */
    @JvmStatic
    public fun initialize(context: Context): Unit = AndroidAppStorage.remember(context)
}

/**
 * Maps public CSS-pixel coordinates (layout-viewport, as returned by
 * `getBoundingClientRect`) to WebView-local device pixels.
 */
internal data class CssToViewTransform(
    val pixelsPerCssPixel: Double,
    val visualOffsetX: Double,
    val visualOffsetY: Double
) {
    fun x(cssX: Double): Float = ((cssX - visualOffsetX) * pixelsPerCssPixel).toFloat()
    fun y(cssY: Double): Float = ((cssY - visualOffsetY) * pixelsPerCssPixel).toFloat()
    fun length(css: Double): Double = css * pixelsPerCssPixel
}

private const val CSS_TRANSFORM_SCRIPT = """(function(){var v=window.visualViewport;return JSON.stringify({dpr:window.devicePixelRatio||0,s:v?v.scale:1,x:v?v.offsetLeft:0,y:v?v.offsetTop:0});})()"""

internal fun WebView.displayDensity(): Double = resources.displayMetrics.density.toDouble().takeIf { it > 0 } ?: 1.0

/**
 * Reads the CSS→device mapping from the renderer: devicePixelRatio times the
 * visual-viewport (pinch/initial) scale. Falls back to the display density.
 */
internal suspend fun WebView.cssToViewTransform(): CssToViewTransform = onMain {
    val density = displayDensity()
    val raw = suspendCancellableCoroutine<String?> { cont ->
        evaluateJavascript(CSS_TRANSFORM_SCRIPT) { if (cont.isActive) cont.resume(normalizeJsResult(it)) }
    }
    runCatching {
        val json = JSONObject(raw ?: return@runCatching null)
        val dpr = json.optDouble("dpr", 0.0).takeIf { it > 0 && !it.isNaN() } ?: density
        val scale = json.optDouble("s", 1.0).takeIf { it > 0 && !it.isNaN() } ?: 1.0
        CssToViewTransform(dpr * scale, json.optDouble("x", 0.0), json.optDouble("y", 0.0))
    }.getOrNull() ?: CssToViewTransform(density, 0.0, 0.0)
}
