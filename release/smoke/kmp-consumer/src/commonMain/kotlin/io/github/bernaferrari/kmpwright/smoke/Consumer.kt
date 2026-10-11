package io.github.bernaferrari.kmpwright.smoke

import com.bernaferrari.kmpwright.core.KmpwrightConfig

fun resolvedFromPublishedKmp(): KmpwrightConfig = KmpwrightConfig()

// Public signature dependencies must be available through the published artifact.
suspend fun verifyBrowserFeatureApi(page: com.bernaferrari.kmpwright.core.Page) {
    val urls: kotlinx.coroutines.flow.StateFlow<String?> = page.url
    println(urls.value)
    val input = kotlinx.serialization.json.buildJsonObject {
        put("name", kotlinx.serialization.json.JsonPrimitive("Kotlin"))
    }
    page.webMcp.invoke("greet", input, waitForResult = false)
    page.startHarCapture()
    println(page.stopHarCapture())
    page.read(options = com.bernaferrari.kmpwright.core.ReadOptions(raw = true))
}
