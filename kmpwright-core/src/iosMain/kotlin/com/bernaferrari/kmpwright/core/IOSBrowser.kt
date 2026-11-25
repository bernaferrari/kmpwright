package com.bernaferrari.kmpwright.core

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.NSDate
import platform.Foundation.distantPast
import platform.WebKit.WKWebView
import platform.WebKit.WKWebsiteDataStore

@OptIn(ExperimentalForeignApi::class)
public actual class Browser public actual constructor(
    private val context: PlatformContext,
    private val config: KmpwrightConfig
) {
    private val pages = mutableListOf<Page>()
    private val closed = kotlinx.atomicfu.atomic(false)
    private val iosContext = context as? IOSPlatformContext
    private val backingStore = onMainThread {
        selectBackingStore(
            embeddedStore = iosContext?.webView?.configuration?.websiteDataStore,
            providedStore = iosContext?.websiteDataStore
        ) { WKWebsiteDataStore.nonPersistentDataStore() }
    }
    private val websiteDataStore: WKWebsiteDataStore = backingStore.store
    private val ownsWebsiteDataStore: Boolean = backingStore.owned
    private val tabRuntime = object : PageTabRuntime {
        override suspend fun openTab(source: Page, url: String?): NavigationResult {
            val page = newPage()
            try { (source as? BasePage)?.inheritSetupTo(page) }
            catch (error: Throwable) { page.close(); throw error }
            return page.goto(url ?: "about:blank")
        }

        override fun pageClosed(page: Page) {
            pages.remove(page)
        }
    }

    private val ownedContexts = mutableListOf<BrowserContext>()
    private var leaseContext: BrowserContext? = null

    internal actual suspend fun beginIndependentLease() {
        check(leaseContext == null) { "Browser already has an active lease" }
        endIndependentLease()
        leaseContext = newContext()
    }

    internal actual suspend fun endIndependentLease() {
        ownedContexts.toList().forEach { it.close() }
        ownedContexts.clear()
        leaseContext = null
        pages.toList().forEach { it.close() }
        pages.clear()
    }

    private val _isConnected = kotlinx.coroutines.flow.MutableStateFlow(true)
    public actual val isConnected: kotlinx.coroutines.flow.StateFlow<Boolean> = _isConnected

    private val _connectionError = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    public actual val connectionError: kotlinx.coroutines.flow.StateFlow<String?> = _connectionError

    init {
        val controller = IOSWebViewController(config, iosContext?.webView, websiteDataStore)
        pages.add(controller.createPage(tabRuntime))
    }

    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public actual suspend fun newPage(): Page = withContext(Dispatchers.Main) {
        check(!closed.value) { "Browser is closed" }
        leaseContext?.let { return@withContext it.newPage() }
        val controller = IOSWebViewController(config, websiteDataStore = websiteDataStore)
        controller.createPage(tabRuntime).also { pages.add(it) }
    }

    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public actual suspend fun targets(): List<BrowserTarget> = throw KmpwrightUnsupportedException("Browser target discovery requires CDP")
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public actual suspend fun attachTarget(targetId: String): Page = throw KmpwrightUnsupportedException("Browser target attachment requires CDP")
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public actual suspend fun activateTarget(targetId: String): Unit = throw KmpwrightUnsupportedException("Browser target activation requires CDP")
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public actual suspend fun closeTarget(targetId: String): Boolean = throw KmpwrightUnsupportedException("Browser target closing requires CDP")
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public actual suspend fun newWindow(): Page = throw KmpwrightUnsupportedException("Browser windows require CDP")

    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public actual suspend fun newContext(): BrowserContext = withContext(Dispatchers.Main) {
        check(!closed.value) { "Browser is closed" }
        BrowserContext(
        IOSPlatformContext(websiteDataStore = onMainThread { WKWebsiteDataStore.nonPersistentDataStore() }),
        config
    ).also { ownedContexts.add(it) }
    }
    public actual fun pages(): List<Page> = pages.toList() + ownedContexts.flatMap { it.pages() }
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class)
    public actual fun page(): Page = leaseContext?.let { it.pages().lastOrNull() ?: it.createPage() } ?: pages.lastOrNull() ?: throw IllegalStateException("No page available")

    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public actual suspend fun version(): BrowserVersion {
        // WebKit ships with the OS, so the OS version identifies the engine build.
        val systemVersion = onMainThread { platform.UIKit.UIDevice.currentDevice.systemVersion }
        val userAgent = config.userAgent
            ?: pages.firstOrNull()?.let { page -> runCatching { page.evaluate("navigator.userAgent").value }.getOrNull() }
            ?: ""
        return BrowserVersion("WKWebView", "iOS $systemVersion", userAgent)
    }

    public actual fun dispose() {
        if (!closed.compareAndSet(false, true)) return
        onMainThread { finishDispose() }
    }

    private fun finishDispose() {
        _isConnected.value = false
        ownedContexts.toList().forEach { it.dispose() }
        ownedContexts.clear()
        leaseContext = null
        pages.forEach { it.dispose() }
        pages.clear()
        if (ownsWebsiteDataStore) {
            clearOwnedData()
        }
    }

    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public actual suspend fun close(): Unit = withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) {
        if (!closed.compareAndSet(false, true)) return@withContext
        _isConnected.value = false
        try {
            ownedContexts.toList().forEach { it.close() }
            ownedContexts.clear()
            leaseContext = null
            pages.toList().forEach { it.close() }
        } finally { finishDispose() }
    }

    private fun clearOwnedData() = onMainThread {
        websiteDataStore.removeDataOfTypes(WKWebsiteDataStore.allWebsiteDataTypes(), modifiedSince = NSDate.distantPast) {}
    }
}

@OptIn(ExperimentalForeignApi::class)
public actual class BrowserContext public actual constructor(
    private val context: PlatformContext?,
    private val config: KmpwrightConfig
) {
    private val pages = mutableListOf<Page>()
    private val closed = kotlinx.atomicfu.atomic(false)
    private var consumedProvidedWebView = false
    private val iosContext = context as? IOSPlatformContext
    private val backingStore = onMainThread {
        selectBackingStore(
            embeddedStore = iosContext?.webView?.configuration?.websiteDataStore,
            providedStore = iosContext?.websiteDataStore
        ) { WKWebsiteDataStore.nonPersistentDataStore() }
    }
    private val websiteDataStore: WKWebsiteDataStore = backingStore.store
    private val ownsWebsiteDataStore: Boolean = backingStore.owned
    private val tabRuntime = object : PageTabRuntime {
        override suspend fun openTab(source: Page, url: String?): NavigationResult {
            val page = newPage()
            try { (source as? BasePage)?.inheritSetupTo(page) }
            catch (error: Throwable) { page.close(); throw error }
            return page.goto(url ?: "about:blank")
        }

        override fun pageClosed(page: Page) {
            pages.remove(page)
        }
    }

    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public actual suspend fun newPage(): Page = createPage()

    /** Creates a page synchronously (UI code reaches it through `Browser.page()`). */
    internal fun createPage(): Page = onMainThread {
        check(!closed.value) { "BrowserContext is closed" }
        val existingWebView = if (!consumedProvidedWebView) {
            iosContext?.webView?.also { consumedProvidedWebView = true }
        } else {
            null
        }
        val controller = IOSWebViewController(config, existingWebView, websiteDataStore)
        controller.createPage(tabRuntime).also { pages.add(it) }
    }

    public actual fun pages(): List<Page> = pages.toList()

    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public actual suspend fun clearCookies(): Unit = withContext(Dispatchers.Main) {
        websiteDataStore.httpCookieStore.deleteCookiesMatching { true }
    }

    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public actual suspend fun clearData(): Unit = withContext(Dispatchers.Main) {
        websiteDataStore.removeAllData()
    }

    /** Clears every website data type without waiting (teardown path). */
    private fun clearDataNow() = onMainThread {
        websiteDataStore.removeDataOfTypes(WKWebsiteDataStore.allWebsiteDataTypes(), modifiedSince = NSDate.distantPast) {}
    }

    public actual fun dispose() {
        if (!closed.compareAndSet(false, true)) return
        onMainThread { finishDispose() }
    }

    private fun finishDispose() {
        pages.forEach { it.dispose() }
        pages.clear()
        if (ownsWebsiteDataStore) {
            clearDataNow()
        }
    }

    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public actual suspend fun close(): Unit = withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) {
        if (!closed.compareAndSet(false, true)) return@withContext
        try { pages.toList().forEach { it.close() } } finally { finishDispose() }
    }
}
