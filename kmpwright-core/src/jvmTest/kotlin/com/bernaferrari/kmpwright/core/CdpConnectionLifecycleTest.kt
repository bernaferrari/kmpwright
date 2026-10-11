package com.bernaferrari.kmpwright.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

class CdpConnectionLifecycleTest {
    @Test
    fun `send while disconnected returns an error instead of suspending`() = runBlocking {
        val connection = CdpConnection("ws://127.0.0.1:1", connectionTimeoutMs = 100)
        try {
            val response = connection.send("Runtime.evaluate")
            assertNotNull(response.error)
        } finally {
            connection.close()
        }
    }

    @Test
    fun `events are delivered only to their matching session`() {
        val connection = CdpConnection("ws://127.0.0.1:1", connectionTimeoutMs = 100)
        val first = mutableListOf<String>()
        val second = mutableListOf<String>()
        val firstSubscription = connection.onEvent("Page.frameNavigated", "session-1") {
            first += it["value"].toString()
        }
        val secondSubscription = connection.onEvent("Page.frameNavigated", "session-2") {
            second += it["value"].toString()
        }
        try {
            connection.dispatchIncoming(
                """{"method":"Page.frameNavigated","sessionId":"session-1","params":{"value":"one"}}"""
            )
            assertEquals(listOf("\"one\""), first)
            assertEquals(emptyList<String>(), second)
        } finally {
            firstSubscription.cancel()
            secondSubscription.cancel()
            connection.close()
        }
    }

    @Test
    fun `a failing event listener does not prevent other listeners or later events`() {
        val connection = CdpConnection("ws://127.0.0.1:1", connectionTimeoutMs = 100)
        val delivered = AtomicInteger()
        connection.onEvent("Runtime.consoleAPICalled") { error("listener failed") }
        connection.onEvent("Runtime.consoleAPICalled") { delivered.incrementAndGet() }
        try {
            val event = """{"method":"Runtime.consoleAPICalled","params":{}}"""
            connection.dispatchIncoming(event)
            connection.dispatchIncoming(event)
            assertEquals(2, delivered.get())
        } finally {
            connection.close()
        }
    }

    @Test
    fun `failing pending command callbacks are isolated while pump termination fails commands`() {
        val connection = CdpConnection("ws://127.0.0.1:1", connectionTimeoutMs = 100)
        val responses = mutableListOf<CdpResponse>()
        val pendingField = CdpConnection::class.java.getDeclaredField("pendingResponses")
            .apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val pending = pendingField.get(connection) as java.util.concurrent.ConcurrentHashMap<Int, (CdpResponse) -> Unit>
        pending[101] = { error("first callback failed") }
        pending[102] = { responses += it }
        val failPending = CdpConnection::class.java.getDeclaredMethod("failPending", String::class.java)
            .apply { isAccessible = true }
        try {
            failPending.invoke(connection, "CDP event pump terminated: test")
            assertEquals(1, responses.size)
            assertEquals("CDP event pump terminated: test", responses.single().error?.message)
            assertEquals(0, pending.size)
        } finally {
            connection.close()
        }
    }

    @Test
    fun `concurrent page creation shares one bootstrap and retains every page`() = runBlocking {
        val browser = Browser(JvmPlatformContext(), KmpwrightConfig(cdp = CdpConfig()))
        val bootstraps = AtomicInteger()
        val connection = CdpConnection("ws://127.0.0.1:1")
        CdpConnection::class.java.getDeclaredField("_isConnected").apply { isAccessible = true }
            .let { (it.get(connection) as kotlinx.coroutines.flow.MutableStateFlow<Boolean>).value = true }
        browser.connectionBootstrapOverride = {
            bootstraps.incrementAndGet()
            CdpBootstrap(connection, ProviderSession("test", "", "ws://test", null), CdpEndpointScope.Browser)
        }
        browser.pageFactoryOverride = { _, _ -> TestPage() }
        try {
            coroutineScope {
                (1..32).map { async { browser.newPageAsync() } }.awaitAll()
            }
            assertEquals(1, bootstraps.get())
            assertEquals(32, browser.pages().size)
        } finally {
            browser.close()
            connection.close()
        }
    }

    @Test
    fun `failed bootstrap is retried after the in-flight attempt is cleared`() = runBlocking {
        val browser = Browser(JvmPlatformContext(), KmpwrightConfig(cdp = CdpConfig()))
        val attempts = AtomicInteger()
        val connection = CdpConnection("ws://127.0.0.1:1")
        CdpConnection::class.java.getDeclaredField("_isConnected").apply { isAccessible = true }
            .let { (it.get(connection) as kotlinx.coroutines.flow.MutableStateFlow<Boolean>).value = true }
        browser.connectionBootstrapOverride = {
            if (attempts.incrementAndGet() == 1) throw KmpwrightException("bootstrap failed")
            CdpBootstrap(connection, ProviderSession("test", "", "ws://test", null), CdpEndpointScope.Browser)
        }
        try {
            try {
                browser.ensureConnectionForTesting()
                error("expected bootstrap failure")
            } catch (_: KmpwrightException) {
            }
            assertEquals(connection, browser.ensureConnectionForTesting())
            assertEquals(2, attempts.get())
        } finally {
            browser.close()
            connection.close()
        }
    }

    @Test
    fun `endpoint resolution failure releases acquired provider session once`() = runBlocking {
        val browser = Browser(JvmPlatformContext(), KmpwrightConfig(cdp = CdpConfig()))
        val first = CdpConnection("ws://127.0.0.1:1")
        val second = CdpConnection("ws://127.0.0.1:1")
        listOf(first, second).forEach { connection ->
        CdpConnection::class.java.getDeclaredField("_isConnected").apply { isAccessible = true }
            .let { (it.get(connection) as kotlinx.coroutines.flow.MutableStateFlow<Boolean>).value = true }
        }
        val session = ProviderSession("test", "session", "ws://test", "http://stop")
        var releases = 0
        var attempts = 0
        browser.connectionBootstrapOverride = {
            attempts++
            CdpBootstrap(if (attempts == 1) first else second, session, CdpEndpointScope.Browser)
        }
        browser.providerSessionReleaser = { releases++ }
        browser.endpointScopeResolverOverride = { throw KmpwrightException("scope failed") }
        try {
            try { browser.ensureConnectionForTesting(); error("expected failure") } catch (_: KmpwrightException) { }
            assertEquals(1, releases)
            assertEquals(1, attempts)
            browser.endpointScopeResolverOverride = { CdpEndpointScope.Browser }
            assertEquals(second, browser.ensureConnectionForTesting())
            assertEquals(2, attempts)
        } finally {
            browser.close()
            first.close()
            second.close()
        }
    }
}
