package com.bernaferrari.kmpwright.core

import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
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
        assertEquals(1, controller.navigationCallbackSetCount)
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
        assertEquals(1, controller.navigationCallbackSetCount)
        assertEquals(1, controller.removeRequestListenerCount)
        assertEquals(1, controller.removeDialogListenerCount)
        assertEquals(routeSyncCount + 1, controller.removeAllRouteHandlersCount)
        assertEquals(1, controller.disposeCount)
        assertEquals(1, registryRemovalCount)
    }
}
