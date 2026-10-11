package com.bernaferrari.kmpwright.core

import kotlinx.coroutines.flow.StateFlow

expect class Browser {
    val isConnected: StateFlow<Boolean>
    val connectionError: StateFlow<String?>

    constructor(context: PlatformContext, config: KmpwrightConfig = KmpwrightConfig())

    fun newPage(): Page
    suspend fun newPageAsync(): Page
    /** Enumerate actual browser tabs, including pages not created by this library (CDP only). */
    suspend fun targetsAsync(): List<BrowserTarget>
    /** Attach without taking ownership of the target. Page.closeAsync detaches; closeTargetAsync closes the tab. */
    suspend fun attachTargetAsync(targetId: String): Page
    suspend fun activateTargetAsync(targetId: String)
    suspend fun closeTargetAsync(targetId: String): Boolean
    suspend fun newWindowAsync(): Page
    fun newContext(): BrowserContext
    fun pages(): List<Page>
    fun page(): Page
    fun closePage(page: Page)
    fun version(): BrowserVersion
    suspend fun versionAsync(): BrowserVersion
    fun close()
    suspend fun closeAsync()
}

data class BrowserTarget(val id: String, val title: String, val url: String,
    val attached: Boolean, val browserContextId: String? = null)

data class BrowserVersion(
    val product: String,
    val version: String,
    val userAgent: String
)

expect class BrowserContext {
    constructor(context: PlatformContext?, config: KmpwrightConfig = KmpwrightConfig())

    fun newPage(): Page
    suspend fun newPageAsync(): Page
    fun pages(): List<Page>
    fun clearCookies()
    suspend fun clearCookiesAsync()
    fun clearData()
    suspend fun clearDataAsync()
    fun close()
    suspend fun closeAsync()
}

data class Viewport(
    val width: Int,
    val height: Int,
    val deviceScaleFactor: Double = 1.0,
    val isMobile: Boolean = false,
    val hasTouch: Boolean = false,
    val isLandscape: Boolean = true
) {
    companion object {
        val DEFAULT = Viewport(1280, 800)
        val MOBILE = Viewport(390, 844, isMobile = true, hasTouch = true)
        val TABLET = Viewport(768, 1024, isMobile = true, hasTouch = true)
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
data class DeviceDescriptor(
    val name: String,
    val viewport: Viewport,
    val userAgent: String
)

/**
 * Built-in device presets for emulation.
 * User agent strings are based on real device UA strings (Chrome on each platform).
 */
object Devices {
    // --- iPhones ---
    val iPhone15 = DeviceDescriptor(
        name = "iPhone 15",
        viewport = Viewport(393, 852, deviceScaleFactor = 3.0, isMobile = true, hasTouch = true, isLandscape = false),
        userAgent = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"
    )
    val iPhone15ProMax = DeviceDescriptor(
        name = "iPhone 15 Pro Max",
        viewport = Viewport(430, 932, deviceScaleFactor = 3.0, isMobile = true, hasTouch = true, isLandscape = false),
        userAgent = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"
    )
    val iPhone14 = DeviceDescriptor(
        name = "iPhone 14",
        viewport = Viewport(390, 844, deviceScaleFactor = 3.0, isMobile = true, hasTouch = true, isLandscape = false),
        userAgent = "Mozilla/5.0 (iPhone; CPU iPhone OS 16_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.0 Mobile/15E148 Safari/604.1"
    )
    val iPhoneSE = DeviceDescriptor(
        name = "iPhone SE",
        viewport = Viewport(375, 667, deviceScaleFactor = 2.0, isMobile = true, hasTouch = true, isLandscape = false),
        userAgent = "Mozilla/5.0 (iPhone; CPU iPhone OS 16_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.0 Mobile/15E148 Safari/604.1"
    )

    // --- iPads ---
    val iPadAir = DeviceDescriptor(
        name = "iPad Air",
        viewport = Viewport(820, 1180, deviceScaleFactor = 2.0, isMobile = true, hasTouch = true, isLandscape = false),
        userAgent = "Mozilla/5.0 (iPad; CPU OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"
    )
    val iPadPro11 = DeviceDescriptor(
        name = "iPad Pro 11\"",
        viewport = Viewport(834, 1194, deviceScaleFactor = 2.0, isMobile = true, hasTouch = true, isLandscape = false),
        userAgent = "Mozilla/5.0 (iPad; CPU OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"
    )
    val iPadPro12_9 = DeviceDescriptor(
        name = "iPad Pro 12.9\"",
        viewport = Viewport(1024, 1366, deviceScaleFactor = 2.0, isMobile = true, hasTouch = true, isLandscape = false),
        userAgent = "Mozilla/5.0 (iPad; CPU OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"
    )

    // --- Android ---
    val Pixel7 = DeviceDescriptor(
        name = "Pixel 7",
        viewport = Viewport(412, 915, deviceScaleFactor = 2.625, isMobile = true, hasTouch = true, isLandscape = false),
        userAgent = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36"
    )
    val Pixel7Pro = DeviceDescriptor(
        name = "Pixel 7 Pro",
        viewport = Viewport(412, 915, deviceScaleFactor = 2.625, isMobile = true, hasTouch = true, isLandscape = false),
        userAgent = "Mozilla/5.0 (Linux; Android 13; Pixel 7 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36"
    )
    val GalaxyS23 = DeviceDescriptor(
        name = "Galaxy S23",
        viewport = Viewport(360, 780, deviceScaleFactor = 3.0, isMobile = true, hasTouch = true, isLandscape = false),
        userAgent = "Mozilla/5.0 (Linux; Android 13; SM-S911B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36"
    )

    // --- Desktop ---
    val MacBook14 = DeviceDescriptor(
        name = "MacBook Pro 14\"",
        viewport = Viewport(1512, 982, deviceScaleFactor = 2.0),
        userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"
    )
    val DesktopHD = DeviceDescriptor(
        name = "Desktop 1920x1080",
        viewport = Viewport(1920, 1080),
        userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"
    )
    val DesktopLaptop = DeviceDescriptor(
        name = "Desktop 1366x768",
        viewport = Viewport(1366, 768),
        userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"
    )

    /** All built-in devices as a map keyed by name. */
    val all: Map<String, DeviceDescriptor> = listOf(
        iPhone15, iPhone15ProMax, iPhone14, iPhoneSE,
        iPadAir, iPadPro11, iPadPro12_9,
        Pixel7, Pixel7Pro, GalaxyS23,
        MacBook14, DesktopHD, DesktopLaptop
    ).associateBy { it.name }
}
