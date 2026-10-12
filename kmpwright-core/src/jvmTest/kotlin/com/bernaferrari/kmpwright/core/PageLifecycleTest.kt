package com.bernaferrari.kmpwright.core

import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlinx.coroutines.async
import kotlinx.collections.immutable.persistentSetOf

class PageLifecycleTest {

    @Test
    fun `page close disposes underlying controller`() {
        val controller = MockWebViewController()
        val page = TestPage(controller)

        page.close()

        assertTrue(controller.disposed)
    }

    @Test
    fun `page close is exhaustive and idempotent`() {
        val controller = MockWebViewController()
        val page = TestPage(controller)
        page.route("*") { RouteHandler.Fallthrough }
        page.onDialog { DialogResponse.Dismiss }
        BaseLocator.injectedControllers = persistentSetOf<WebViewScriptController>(controller)
        val routeSyncCount = controller.removeAllRouteHandlersCount

        page.close()
        page.close()

        assertTrue(controller.disposed)
        assertTrue(controller !in BaseLocator.injectedControllers)
        assertEquals(1, controller.disposeCount)
        assertEquals(2, controller.navigationCallbackSetCount)
        assertEquals(1, controller.removeRequestListenerCount)
        assertEquals(1, controller.removeDialogListenerCount)
        assertEquals(routeSyncCount + 1, controller.removeAllRouteHandlersCount)
    }

    @Test
    fun `page close releases every owner when controller cleanup throws`() {
        val controller = MockWebViewController()
        var registryRemovalCount = 0
        val runtime = object : PageTabRuntime {
            override suspend fun openTab(source: Page, url: String?): NavigationResult =
                NavigationResult(url ?: "about:blank", 200, true)

            override suspend fun closeTab(page: Page): Boolean = true

            override fun pageClosed(page: Page) {
                registryRemovalCount += 1
            }
        }
        val page = TestPage(controller, runtime)
        page.route("*") { RouteHandler.Fallthrough }
        BaseLocator.injectedControllers = persistentSetOf<WebViewScriptController>(controller)
        val routeSyncCount = controller.removeAllRouteHandlersCount
        controller.apply {
            throwOnNavigationCallback = true
            throwOnRemoveRequestListener = true
            throwOnRemoveDialogListener = true
            throwOnRemoveAllRouteHandlers = true
            throwOnDispose = true
        }

        page.close()
        page.close()

        assertTrue(controller.disposed)
        assertTrue(controller !in BaseLocator.injectedControllers)
        assertEquals(2, controller.navigationCallbackSetCount)
        assertEquals(1, controller.removeRequestListenerCount)
        assertEquals(1, controller.removeDialogListenerCount)
        assertEquals(routeSyncCount + 1, controller.removeAllRouteHandlersCount)
        assertEquals(1, controller.disposeCount)
        assertEquals(1, registryRemovalCount)
    }

    @Test fun `closing a page completes navigation even when its deadline is far away`() = kotlinx.coroutines.runBlocking {
        val controller = MockWebViewController().apply { completeNavigationImmediately = false }
        val page = TestPage(controller)
        val navigation = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            runCatching { page.goto("https://example.com", GotoOptions(timeoutMs = 60_000)) }
        }
        page.close()
        val result = kotlinx.coroutines.withTimeout(1_000) { navigation.await() }
        assertTrue(result.exceptionOrNull() is KmpwrightException)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("closed"))
    }

    @Test fun `ordinary navigations keep updating page state after goto and cancellation`() = kotlinx.coroutines.runBlocking {
        val controller = MockWebViewController()
        val page = TestPage(controller)
        page.goto("https://example.com/first")
        controller.loadUrl("https://example.com/link")
        assertEquals("https://example.com/link", page.url.value)
        controller.completeNavigationImmediately = false
        val pending = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            page.goto("https://example.com/pending")
        }
        pending.cancel()
        pending.join()
        controller.completeNavigationImmediately = true
        controller.loadUrl("https://example.com/later")
        assertEquals("https://example.com/later", page.url.value)
        page.close()
    }
}
