package com.bernaferrari.kmpwright.core

import kotlinx.coroutines.flow.StateFlow
import kotlin.coroutines.cancellation.CancellationException

/**
 * A browser, like Playwright's `Browser`: an Android WebView host, an iOS WKWebView host, or a Chromium
 * reached over CDP on the JVM. Pages are created with [newPage]; [page] returns the attached/default page.
 */
public expect class Browser {
    /** Whether the backend is connected. */
    public val isConnected: StateFlow<Boolean>

    /** The last connection failure, or null. */
    public val connectionError: StateFlow<String?>

    /** Creates a browser for [context] (Android/iOS host view or JVM CDP endpoint). */
    public constructor(context: PlatformContext, config: KmpwrightConfig = KmpwrightConfig())

    internal suspend fun beginIndependentLease()
    internal suspend fun endIndependentLease()

    /** Opens a new page (tab or WebView). */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun newPage(): Page

    /** Every browser tab, including pages not created by KMPwright (CDP only). */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun targets(): List<BrowserTarget>

    /** Attaches to an existing tab without owning it: `page.close()` detaches; [closeTarget] closes the tab (CDP only). */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun attachTarget(targetId: String): Page

    /** Brings a tab to the front (CDP only). */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun activateTarget(targetId: String)

    /** Closes a tab; true when it existed (CDP only). */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun closeTarget(targetId: String): Boolean

    /** Opens a page in a new browser window (CDP only). */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun newWindow(): Page

    /** Creates an isolated context with separate cookies and storage. Unsupported on Android WebView. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun newContext(): BrowserContext

    /** Open pages owned by this browser and its contexts. */
    public fun pages(): List<Page>

    /** The attached or default page (synchronous so UI code can use it). */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class)
    public fun page(): Page

    /** Product name, version and user agent of the backend. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun version(): BrowserVersion

    /** Closes every page and disconnects, waiting for teardown. Idempotent. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun close()

    /**
     * Non-suspending, best-effort teardown for lifecycle callbacks (Compose `onDispose`,
     * `Activity.onDestroy`). Returns immediately and is idempotent; prefer [close] when you can suspend.
     */
    public fun dispose()
}

/** A browser tab reported by [Browser.targets]. */
public data class BrowserTarget(val id: String, val title: String, val url: String,
    val attached: Boolean, val browserContextId: String? = null)

/** Backend identity reported by [Browser.version]. */
public data class BrowserVersion(
    val product: String,
    val version: String,
    val userAgent: String
)

/** An isolated set of pages with their own cookies and storage, like Playwright's `BrowserContext`. */
public expect class BrowserContext {
    /** Creates a context; prefer [Browser.newContext]. */
    public constructor(context: PlatformContext?, config: KmpwrightConfig = KmpwrightConfig())

    /** Opens a page in this context. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun newPage(): Page

    /** Open pages of this context. */
    public fun pages(): List<Page>

    /** Removes every cookie of this context. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun clearCookies()

    /** Removes cookies, storage and caches of this context. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun clearData()

    /** Closes every page and the context, waiting for teardown. Idempotent. */
    @Throws(KmpwrightException::class, IllegalArgumentException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun close()

    /** Non-suspending, best-effort teardown for lifecycle callbacks; returns immediately. */
    public fun dispose()
}

/**
 * Viewport and device characteristics used by [DeviceDescriptor] and [KmpwrightConfig.viewport].
 *
 * On Chromium, [isMobile], [hasTouch] and [isLandscape] are emulated as in Playwright (orientation
 * only applies to mobile viewports). Android WebView and WKWebView use the real device for these.
 */
public data class Viewport(
    val width: Int,
    val height: Int,
    val deviceScaleFactor: Double = 1.0,
    val isMobile: Boolean = false,
    val hasTouch: Boolean = false,
    val isLandscape: Boolean = true
) {
    /** Common viewport presets. */
    public companion object {
        /** 1280×800 desktop viewport. */
        public val DEFAULT: Viewport = Viewport(1280, 800)
        /** 390×844 touch phone viewport. */
        public val MOBILE: Viewport = Viewport(390, 844, isMobile = true, hasTouch = true)
        /** 768×1024 touch tablet viewport. */
        public val TABLET: Viewport = Viewport(768, 1024, isMobile = true, hasTouch = true)
    }
}

/**
 * Describes a device for emulation, including viewport dimensions, user agent, and touch capabilities.
 *
 * Usage:
 * ```kotlin
 * val device = Devices.iPhone15
 * page.setViewportSize(device.viewport.width, device.viewport.height)
 * page.setUserAgent(device.userAgent)
 * ```
 */
public data class DeviceDescriptor(
    val name: String,
    val viewport: Viewport,
    val userAgent: String
)

/**
 * Built-in device presets for emulation.
 * User agent strings are based on real device UA strings (Chrome on each platform).
 */
public object Devices {
    // --- iPhones ---
    public val iPhone15: DeviceDescriptor = DeviceDescriptor(
        name = "iPhone 15",
        viewport = Viewport(393, 852, deviceScaleFactor = 3.0, isMobile = true, hasTouch = true, isLandscape = false),
        userAgent = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"
    )
    /** iPhone 15 Pro Max (430×932 @3x). */
    public val iPhone15ProMax: DeviceDescriptor = DeviceDescriptor(
        name = "iPhone 15 Pro Max",
        viewport = Viewport(430, 932, deviceScaleFactor = 3.0, isMobile = true, hasTouch = true, isLandscape = false),
        userAgent = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"
    )
    /** iPhone 14 (390×844 @3x). */
    public val iPhone14: DeviceDescriptor = DeviceDescriptor(
        name = "iPhone 14",
        viewport = Viewport(390, 844, deviceScaleFactor = 3.0, isMobile = true, hasTouch = true, isLandscape = false),
        userAgent = "Mozilla/5.0 (iPhone; CPU iPhone OS 16_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.0 Mobile/15E148 Safari/604.1"
    )
    /** iPhone SE (375×667 @2x). */
    public val iPhoneSE: DeviceDescriptor = DeviceDescriptor(
        name = "iPhone SE",
        viewport = Viewport(375, 667, deviceScaleFactor = 2.0, isMobile = true, hasTouch = true, isLandscape = false),
        userAgent = "Mozilla/5.0 (iPhone; CPU iPhone OS 16_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.0 Mobile/15E148 Safari/604.1"
    )

    // --- iPads ---
    public val iPadAir: DeviceDescriptor = DeviceDescriptor(
        name = "iPad Air",
        viewport = Viewport(820, 1180, deviceScaleFactor = 2.0, isMobile = true, hasTouch = true, isLandscape = false),
        userAgent = "Mozilla/5.0 (iPad; CPU OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"
    )
    /** iPad Pro 11" (834×1194 @2x). */
    public val iPadPro11: DeviceDescriptor = DeviceDescriptor(
        name = "iPad Pro 11\"",
        viewport = Viewport(834, 1194, deviceScaleFactor = 2.0, isMobile = true, hasTouch = true, isLandscape = false),
        userAgent = "Mozilla/5.0 (iPad; CPU OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"
    )
    /** iPad Pro 12.9" (1024×1366 @2x). */
    public val iPadPro12_9: DeviceDescriptor = DeviceDescriptor(
        name = "iPad Pro 12.9\"",
        viewport = Viewport(1024, 1366, deviceScaleFactor = 2.0, isMobile = true, hasTouch = true, isLandscape = false),
        userAgent = "Mozilla/5.0 (iPad; CPU OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"
    )

    // --- Android ---
    public val Pixel7: DeviceDescriptor = DeviceDescriptor(
        name = "Pixel 7",
        viewport = Viewport(412, 915, deviceScaleFactor = 2.625, isMobile = true, hasTouch = true, isLandscape = false),
        userAgent = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36"
    )
    /** Pixel 7 Pro (412×915 @2.625x). */
    public val Pixel7Pro: DeviceDescriptor = DeviceDescriptor(
        name = "Pixel 7 Pro",
        viewport = Viewport(412, 915, deviceScaleFactor = 2.625, isMobile = true, hasTouch = true, isLandscape = false),
        userAgent = "Mozilla/5.0 (Linux; Android 13; Pixel 7 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36"
    )
    /** Galaxy S23 (360×780 @3x). */
    public val GalaxyS23: DeviceDescriptor = DeviceDescriptor(
        name = "Galaxy S23",
        viewport = Viewport(360, 780, deviceScaleFactor = 3.0, isMobile = true, hasTouch = true, isLandscape = false),
        userAgent = "Mozilla/5.0 (Linux; Android 13; SM-S911B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36"
    )

    // --- Desktop ---
    public val MacBook14: DeviceDescriptor = DeviceDescriptor(
        name = "MacBook Pro 14\"",
        viewport = Viewport(1512, 982, deviceScaleFactor = 2.0),
        userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"
    )
    /** 1920×1080 desktop. */
    public val DesktopHD: DeviceDescriptor = DeviceDescriptor(
        name = "Desktop 1920x1080",
        viewport = Viewport(1920, 1080),
        userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"
    )
    /** 1366×768 laptop. */
    public val DesktopLaptop: DeviceDescriptor = DeviceDescriptor(
        name = "Desktop 1366x768",
        viewport = Viewport(1366, 768),
        userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"
    )

    /** All built-in devices as a map keyed by name. */
    public val all: Map<String, DeviceDescriptor> = listOf(
        iPhone15, iPhone15ProMax, iPhone14, iPhoneSE,
        iPadAir, iPadPro11, iPadPro12_9,
        Pixel7, Pixel7Pro, GalaxyS23,
        MacBook14, DesktopHD, DesktopLaptop
    ).associateBy { it.name }
}
