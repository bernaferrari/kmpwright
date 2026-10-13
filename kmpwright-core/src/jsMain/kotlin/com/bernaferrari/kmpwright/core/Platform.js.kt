package com.bernaferrari.kmpwright.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.js.Js
import kotlinx.browser.localStorage
import kotlinx.browser.window
import kotlinx.coroutines.await
import org.khronos.webgl.Uint8Array
import org.khronos.webgl.get
import kotlin.coroutines.cancellation.CancellationException
import kotlin.js.Date

// Platform services of the browser backend. Files are not reachable from a web page, so the
// state helpers persist to localStorage; desktop-only features report KmpwrightUnsupportedException.

public actual fun currentTimeMillis(): Long = Date.now().toLong()

internal actual fun secureRandomBytes(size: Int): ByteArray {
    val buffer = Uint8Array(size)
    window.asDynamic().crypto.getRandomValues(buffer)
    return ByteArray(size) { buffer[it] }
}

internal actual fun controlOrMetaModifier(): String {
    val platform = window.navigator.platform
    return if (platform.startsWith("Mac") || platform.startsWith("iP")) KeyboardModifiers.META else KeyboardModifiers.CONTROL
}

/** Same contract as the other actuals: non-2xx responses throw. Subject to the page's CORS rules. */
internal actual suspend fun platformHttpGetText(url: String, headers: Map<String, String>): String {
    val init: dynamic = js("({})")
    init.method = "GET"
    val requestHeaders: dynamic = js("({})")
    headers.forEach { (name, value) -> requestHeaders[name] = value }
    init.headers = requestHeaders
    val response = try {
        window.fetch(url, init).await()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        throw KmpwrightException("HTTP GET $url failed: ${error.message}")
    }
    if (response.status !in 200..299) {
        throw KmpwrightException("HTTP GET $url failed with status ${response.status} ${defaultHttpStatusText(response.status.toInt())}")
    }
    return response.text().await()
}

internal actual fun readerHttpClient(timeoutMs: Long, caCertificatePath: String?, useSystemCa: Boolean): HttpClient {
    if (caCertificatePath != null || !useSystemCa) {
        throw KmpwrightUnsupportedException("Custom certificate trust is unavailable in the browser")
    }
    return HttpClient(Js) { configureReaderTransport(timeoutMs) }
}

internal actual fun isReaderTransportTimeout(error: Exception): Boolean =
    error is io.ktor.client.plugins.HttpRequestTimeoutException

private const val FILE_PREFIX = "kmpwright-file:"

internal actual fun fileWrite(path: String, content: String) = localStorage.setItem(FILE_PREFIX + path, content)
internal actual fun fileRead(path: String): String? = localStorage.getItem(FILE_PREFIX + path)
internal actual fun fileExists(path: String): Boolean = localStorage.getItem(FILE_PREFIX + path) != null

internal actual fun fileDelete(path: String): Boolean {
    if (!fileExists(path)) return false
    localStorage.removeItem(FILE_PREFIX + path)
    return true
}

internal actual fun fileList(dir: String): List<String> {
    val prefix = FILE_PREFIX + dir.trimEnd('/') + "/"
    return (0 until localStorage.length).mapNotNull { index -> localStorage.key(index) }
        .filter { it.startsWith(prefix) && '/' !in it.removePrefix(prefix) }
        .map { it.removePrefix(prefix) }
}

internal actual fun mkdirs(path: String) {}
internal actual fun platformHomeDir(): String = "/kmpwright"
internal actual fun authProfileDir(): String = "${platformHomeDir()}/.kmpwright/auth"

internal actual fun platformEncrypt(plainText: String, password: String): String =
    throw KmpwrightUnsupportedException("Encrypted session state is unavailable in the browser")

internal actual fun platformDecrypt(cipherText: String, password: String): String? =
    throw KmpwrightUnsupportedException("Encrypted session state is unavailable in the browser")

internal actual fun formatIso8601(timestamp: Long): String = Date(timestamp.toDouble()).toISOString()

internal actual fun parseIso8601ToMillis(iso: String): Long =
    Date.parse(iso).takeUnless { it.isNaN() }?.toLong() ?: currentTimeMillis()

/** PNG decoding is asynchronous in the browser; screenshots are unavailable on this backend anyway. */
public actual fun ScreenshotResult.toRgbaImage(): RgbaImage? = null

public actual object ChromeProfiles {
    public actual suspend fun discoverUserDataDirectory(): String? =
        throw KmpwrightUnsupportedException("Desktop Chrome profiles are unavailable in the browser")
    public actual suspend fun list(userDataDirectory: String): List<ChromeProfile> =
        throw KmpwrightUnsupportedException("Desktop Chrome profiles are unavailable in the browser")
    public actual suspend fun resolve(source: ChromeProfileSource): ChromeProfile =
        throw KmpwrightUnsupportedException("Desktop Chrome profiles are unavailable in the browser")
}

internal actual suspend fun launchPlatformBrowser(options: BrowserLaunchOptions, config: KmpwrightConfig): LaunchedBrowser =
    throw KmpwrightUnsupportedException("Desktop browser process launch requires the JVM backend")

internal actual val fileDispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.Default
