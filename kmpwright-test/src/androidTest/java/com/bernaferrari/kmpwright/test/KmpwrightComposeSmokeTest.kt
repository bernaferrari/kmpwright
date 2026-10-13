package com.bernaferrari.kmpwright.test

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bernaferrari.kmpwright.compose.KmpwrightWebView
import com.bernaferrari.kmpwright.compose.KmpwrightWebViewController
import com.bernaferrari.kmpwright.compose.rememberKmpwrightController
import com.bernaferrari.kmpwright.core.KmpwrightConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KmpwrightComposeSmokeTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun webViewAttachesNavigatesAndPublishesState() {
        LocalHttpServer { request ->
            LocalHttpServer.FixtureResponse(200, "OK", "<title>${request.path.removePrefix("/")}</title><p>compose</p>")
        }.use { server ->
            lateinit var controller: KmpwrightWebViewController
            compose.setContent {
                // Origin rules must not reject Kmpwright's own about:blank document.
                controller = rememberKmpwrightController(KmpwrightConfig(allowedOriginRules = setOf(server.origin)))
                KmpwrightWebView(controller, Modifier.fillMaxSize(), startUrl = "${server.origin}/first")
            }
            compose.waitUntil(10_000) { controller.title == "first" && !controller.isLoading }
            assertNull(controller.lastError)
            assertEquals("${server.origin}/first", controller.url)

            runBlocking(Dispatchers.Default) {
                assertTrue(withContext(Dispatchers.Main) { controller.goto("${server.origin}/second") }.ok)
                assertEquals("compose", withContext(Dispatchers.Main) {
                    controller.evaluate("document.querySelector('p').textContent")
                })
            }
            compose.waitUntil(5_000) { controller.title == "second" && controller.canGoBack }
            compose.runOnIdle { controller.goBack() }
            compose.waitUntil(5_000) { controller.title == "first" && controller.canGoForward }
            assertNull(controller.lastError)
        }
    }
}
