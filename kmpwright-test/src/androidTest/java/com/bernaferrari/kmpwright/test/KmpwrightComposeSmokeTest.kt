package com.bernaferrari.kmpwright.test

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bernaferrari.kmpwright.compose.KmpwrightWebView
import com.bernaferrari.kmpwright.compose.KmpwrightWebViewController
import com.bernaferrari.kmpwright.compose.rememberKmpwrightController
import com.bernaferrari.kmpwright.core.KmpwrightConfig
import com.bernaferrari.kmpwright.core.KmpwrightException
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
    @Test
    fun replacingAndRemovingControllersReleasesPagesAndClearsObservableState() {
        LocalHttpServer { request ->
            LocalHttpServer.FixtureResponse(200, "OK", "<title>${request.path.removePrefix("/")}</title><p>ready</p>")
        }.use { server ->
            val first = KmpwrightWebViewController()
            val second = KmpwrightWebViewController()
            val selected = mutableStateOf<KmpwrightWebViewController?>(first)
            compose.setContent {
                selected.value?.let { KmpwrightWebView(it, Modifier.fillMaxSize(), "${server.origin}/ready") }
            }
            compose.waitUntil(10_000) { first.title == "ready" && !first.isLoading }
            val oldPage = requireNotNull(first.page)
            compose.runOnIdle { selected.value = second }
            compose.waitUntil(10_000) { first.page == null && second.title == "ready" && !second.isLoading }
            assertNull(first.url)
            assertNull(first.title)
            assertTrue(!first.canGoBack && !first.canGoForward)
            runBlocking(Dispatchers.Main) {
                try {
                    oldPage.waitForLoadState(timeoutMs = 1_000)
                    throw AssertionError("The detached controller's page must be closed")
                } catch (closed: KmpwrightException) {
                    assertTrue(closed.message.orEmpty().contains("closed"))
                }
            }
            compose.runOnIdle { selected.value = null }
            compose.waitUntil(5_000) { second.page == null }
            assertNull(second.url)
            assertNull(second.title)
            assertNull(first.lastError)
            assertNull(second.lastError)
        }
    }

    @Test
    fun changingStartUrlCancelsTheOldNavigationWait() {
        val slowStarted = java.util.concurrent.atomic.AtomicBoolean(false)
        LocalHttpServer { request ->
            if (request.path == "/slow") {
                slowStarted.set(true)
                Thread.sleep(10_000)
            }
            LocalHttpServer.FixtureResponse(200, "OK", "<title>${request.path.removePrefix("/")}</title>")
        }.use { server ->
            val controller = KmpwrightWebViewController()
            val startUrl = mutableStateOf("${server.origin}/slow")
            compose.setContent { KmpwrightWebView(controller, Modifier.fillMaxSize(), startUrl.value) }
            // The server receiving the request proves goto has started. WebView may defer
            // onPageStarted (and isLoading) until response headers arrive.
            compose.waitUntil(10_000) { slowStarted.get() }
            compose.runOnIdle { startUrl.value = "${server.origin}/fast" }
            compose.waitUntil(5_000) { controller.title == "fast" && !controller.isLoading }
            assertEquals("${server.origin}/fast", controller.url)
            assertNull(controller.lastError)
        }
    }

}
