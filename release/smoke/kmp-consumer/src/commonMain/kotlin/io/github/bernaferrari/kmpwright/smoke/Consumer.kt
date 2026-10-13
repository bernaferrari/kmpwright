package io.github.bernaferrari.kmpwright.smoke

import com.bernaferrari.kmpwright.core.KmpwrightConfig
import com.bernaferrari.kmpwright.core.CdpConfig

fun resolvedFromPublishedKmp(): KmpwrightConfig =
    KmpwrightConfig(cdp = CdpConfig(remoteBrowserSharesFileSystem = false))

// Public signature dependencies must be available through the published artifact.
suspend fun verifyBrowserFeatureApi(page: com.bernaferrari.kmpwright.core.Page) {
    val urls: kotlinx.coroutines.flow.StateFlow<String?> = page.url
    println(urls.value)
    val input = kotlinx.serialization.json.buildJsonObject {
        put("name", kotlinx.serialization.json.JsonPrimitive("Kotlin"))
    }
    page.webMcp.invoke("greet", input, waitForResult = false)
    page.network.startHar()
    println(page.network.stopHar())
    page.read(raw = true)
}
