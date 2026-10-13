package com.bernaferrari.kmpwright.core

import kotlinx.coroutines.*
import java.io.IOException
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException

@Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
internal actual suspend fun launchPlatformBrowser(options: BrowserLaunchOptions, config: KmpwrightConfig): LaunchedBrowser = withContext(Dispatchers.IO) {
    val extensions = options.extensionDirectories.map { path -> Path.of(path).toAbsolutePath().normalize().also { directory ->
        require(Files.isDirectory(directory) && Files.isRegularFile(directory.resolve("manifest.json"))) { "Extension directory must contain manifest.json: $path" }
    }.toString() }
    val ownsProfile = options.userDataDirectory == null
    val directory = options.userDataDirectory?.let { Path.of(it).toAbsolutePath().normalize().also(Files::createDirectories) }
        ?: Files.createTempDirectory("kmpwright-browser-")
    var caHome: Path? = null
    var process: Process? = null
    var browser: Browser? = null
    var endpointContents: String? = null
    var shutdownHook: Thread? = null
    val endpoint = directory.resolve("DevToolsActivePort")
    val before = if (Files.exists(endpoint)) Files.readString(endpoint) else null
    try {
        caHome = options.caCertificatePath?.let(::prepareBrowserCaTrust)
        val snapshotDirectory = options.profileSnapshot?.let { copyChromeProfile(it, directory) }
        val arguments = buildList {
            add(options.executablePath)
            addAll(listOf("--remote-debugging-port=0", "--remote-debugging-address=127.0.0.1", "--user-data-dir=$directory", "--no-first-run", "--no-default-browser-check"))
            if (snapshotDirectory != null) add("--profile-directory=$snapshotDirectory")
            if (options.headless) add("--headless=new")
            if (System.getProperty("os.name").startsWith("Linux") && "--disable-dev-shm-usage" !in options.arguments) {
                val available = runCatching { Files.getFileStore(Path.of("/dev/shm")).usableSpace }.getOrNull()
                if ((available != null && available < 256L * 1024 * 1024) ||
                    (available == null && (Files.exists(Path.of("/.dockerenv")) || Files.exists(Path.of("/run/.containerenv"))))) add("--disable-dev-shm-usage")
            }
            if (options.ignoreHttpsErrors) add("--ignore-certificate-errors")
            options.proxy?.let { proxy ->
                add("--proxy-server=${proxy.server}")
                if (proxy.bypass.isNotEmpty()) add("--proxy-bypass-list=" + proxy.bypass.joinToString(";"))
            }
            if (extensions.isNotEmpty()) add("--load-extension=" + extensions.joinToString(","))
            val features = options.arguments.filter { it.startsWith("--enable-features=") }
                .flatMap { it.substringAfter('=').split(',') }.toMutableList()
            if (options.webMcp) features.addAll(listOf("WebMCPTesting", "DevToolsWebMCPSupport"))
            if (options.webGpu && System.getProperty("os.name").startsWith("Linux")) features.add("Vulkan")
            if (features.isNotEmpty()) add("--enable-features=" + features.distinct().joinToString(","))
            if (options.webGpu) {
                add("--enable-unsafe-webgpu")
                if (System.getProperty("os.name").startsWith("Linux")) {
                    addAll(listOf("--use-angle=vulkan", "--use-vulkan=swiftshader", "--use-webgpu-adapter=swiftshader", "--disable-vulkan-surface"))
                }
            }
            if (!options.useSystemKeychain && snapshotDirectory == null) addAll(listOf("--password-store=basic", "--use-mock-keychain"))
            addAll(options.arguments.filterNot { it.startsWith("--enable-features=") })
            add("about:blank")
        }
            process = try { ProcessBuilder(arguments).apply {
            caHome?.let { environment()["HOME"] = it.toString(); environment()["XDG_DATA_HOME"] = it.resolve(".local/share").toString() }
        }.redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start() }
        catch (error: IOException) { throw KmpwrightException("Could not launch Chromium: ${error.message}") }
        val running = process
        // If the JVM exits without closeAsync(), still kill Chromium (and its
        // helper processes) and delete the temporary profile.
        shutdownHook = registerLaunchShutdownHook(running, directory, ownsProfile, caHome)
        val ws = withTimeout(options.startupTimeoutMs) {
            while (true) {
                if (!running.isAlive) throw KmpwrightException("Chromium exited before publishing a debugging endpoint (code ${running.exitValue()})")
                if (Files.exists(endpoint)) {
                    val contents = runCatching { Files.readString(endpoint) }.getOrNull()
                    val lines = contents?.lines().orEmpty()
                    val port = lines.firstOrNull()?.toIntOrNull()
                    val path = lines.getOrNull(1)
                    if (contents != before && port != null && port in 1..65535 && path?.startsWith("/devtools/browser/") == true) {
                        endpointContents = contents
                        return@withTimeout "ws://127.0.0.1:$port$path"
                    }
                }
                delay(25)
            }
            error("Unreachable")
        }
        val base = config.cdp ?: CdpConfig()
        val cdp = base.copy(
            websocketUrl = ws, provider = null, remoteBrowserSharesFileSystem = true,
            // Headed windows keep their scrollbars, as in Playwright; --show-scrollbars opts out too.
            hideScrollbars = base.hideScrollbars && options.headless &&
                options.arguments.none { it == "--show-scrollbars" || it.startsWith("--show-scrollbars=") },
        )
        browser = Browser(JvmPlatformContext(), config.copy(cdp = cdp))
        withTimeout(options.startupTimeoutMs) { browser.version() }
        OwnedLaunchedBrowser(browser, running, directory, ownsProfile, endpointContents, caHome, shutdownHook)
    } catch (error: Throwable) {
        withContext(NonCancellable) {
            shutdownHook?.let(::removeLaunchShutdownHook)
            try { browser?.close() } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
            try { cleanupLaunchedBrowser(process, directory, ownsProfile, endpointContents, caHome) }
            catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
        }
        // A startup deadline is a launch failure, not a cancellation of the caller.
        if (error is TimeoutCancellationException) {
            throw KmpwrightTimeoutException(
                "Chromium did not become ready within ${options.startupTimeoutMs}ms. " +
                    "Check the executablePath passed to launchBrowser, or raise startupTimeoutMs on slow machines.",
                error,
            )
        }
        throw error
    }
}

private class OwnedLaunchedBrowser(override val browser: Browser, private val process: Process,
    private val directory: Path, private val ownsProfile: Boolean, private val endpointContents: String?, private val caHome: Path?,
    private val shutdownHook: Thread?
) : LaunchedBrowser {
    private val closed = AtomicBoolean(false)
    override suspend fun close(): Unit = withContext(NonCancellable + Dispatchers.IO) {
        if (!closed.compareAndSet(false, true)) return@withContext
        shutdownHook?.let(::removeLaunchShutdownHook)
        var failure: Throwable? = null
        try { browser.close() } catch (error: Throwable) { failure = error }
        try { cleanupLaunchedBrowser(process, directory, ownsProfile, endpointContents, caHome) }
        catch (error: Throwable) { if (failure == null) failure = error else failure.addSuppressed(error) }
        failure?.let { throw it }
    }
}

/** Attempt every owned cleanup even when process termination or a filesystem operation fails. */
private fun cleanupLaunchedBrowser(process: Process?, directory: Path, ownsProfile: Boolean,
    endpointContents: String?, caHome: Path?) {
    var failure: Throwable? = null
    fun attempt(block: () -> Unit) {
        try { block() } catch (error: Throwable) { if (failure == null) failure = error else failure.addSuppressed(error) }
    }
    attempt { process?.let(::stopBrowserProcess) }
    attempt {
        if (ownsProfile) deleteBrowserDirectory(directory)
        else {
            val endpoint = directory.resolve("DevToolsActivePort")
            if (endpointContents != null && runCatching { Files.readString(endpoint) }.getOrNull() == endpointContents) Files.deleteIfExists(endpoint)
        }
    }
    attempt { caHome?.let(::deleteBrowserDirectory) }
    failure?.let { throw it }
}

private fun registerLaunchShutdownHook(process: Process, directory: Path, ownsProfile: Boolean, caHome: Path?): Thread? {
    val hook = Thread({
        runCatching { stopBrowserProcess(process, gracefulSeconds = 2) }
        if (ownsProfile) runCatching { deleteBrowserDirectory(directory) }
        caHome?.let { runCatching { deleteBrowserDirectory(it) } }
    }, "kmpwright-browser-shutdown")
    return try { Runtime.getRuntime().addShutdownHook(hook); hook }
    catch (_: IllegalStateException) { null } // JVM is already shutting down.
}

private fun removeLaunchShutdownHook(hook: Thread) {
    try { Runtime.getRuntime().removeShutdownHook(hook) } catch (_: IllegalStateException) { /* shutting down */ }
}

/**
 * Stops Chromium and every helper it spawned (renderer, GPU, utility,
 * crashpad). Helpers are snapshotted first: once the browser exits they are
 * re-parented and could no longer be found through it.
 */
internal fun stopBrowserProcess(process: Process, gracefulSeconds: Long = 5) {
    val descendants = runCatching { process.descendants().toList() }.getOrDefault(emptyList())
    process.destroy()
    if (!process.waitFor(gracefulSeconds, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        if (!process.waitFor(2, TimeUnit.SECONDS)) {
            descendants.forEach { runCatching { it.destroyForcibly() } }
            throw KmpwrightException("Owned Chromium process did not terminate")
        }
    }
    descendants.filter { it.isAlive }.forEach { runCatching { it.destroy() } }
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
    for (child in descendants) {
        val remaining = deadline - System.nanoTime()
        if (remaining > 0) runCatching { child.onExit().get(remaining, TimeUnit.NANOSECONDS) }
        if (child.isAlive) runCatching { child.destroyForcibly() }
    }
}

/** Walk without FOLLOW_LINKS so profile symlinks never cause deletion outside the owned directory. */
internal fun deleteBrowserDirectory(directory: Path) {
    Files.walkFileTree(directory, object : SimpleFileVisitor<Path>() {
        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult { Files.deleteIfExists(file); return FileVisitResult.CONTINUE }
        override fun postVisitDirectory(dir: Path, error: IOException?): FileVisitResult { if (error != null) throw error; Files.deleteIfExists(dir); return FileVisitResult.CONTINUE }
    })
}
