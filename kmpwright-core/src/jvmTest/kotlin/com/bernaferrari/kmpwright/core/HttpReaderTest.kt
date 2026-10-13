package com.bernaferrari.kmpwright.core

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.test.assertFailsWith
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPOutputStream
import java.io.ByteArrayOutputStream

class HttpReaderTest {
    private class Fixture(handler: (HttpExchange) -> Unit) : AutoCloseable {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply { createContext("/", handler); start() }
        val base = "http://127.0.0.1:${server.address.port}"
        override fun close() { server.stop(0) }
    }
    private fun HttpExchange.respond(body: String, type: String = "text/markdown", status: Int = 200) {
        responseHeaders.add("Content-Type", type)
        val bytes = body.toByteArray()
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    @Test fun `disabled content extraction fails before any HTTP request`() = runBlocking<Unit> {
        assertFailsWith<KmpwrightException> {
            HttpReader(KmpwrightConfig(allowDomExtraction = false)).read("https://example.com")
        }
    }

    @Test fun `negotiation and md fallback select the right response`() = runBlocking {
        Fixture { request -> when (request.requestURI.path) {
            "/negotiated" -> { assertTrue(request.requestHeaders.getFirst("Accept").contains("text/markdown")); request.respond("# Negotiated") }
            "/page.md" -> request.respond("# Fallback")
            else -> request.respond("<h1>HTML</h1>", "text/html")
        } }.use { fixture ->
            assertEquals("# Negotiated", HttpReader().read(fixture.base + "/negotiated").content)
            val fallback = HttpReader().read(fixture.base + "/page", requireMarkdown = true)
            assertEquals("markdown-url", fallback.source)
            assertEquals(fixture.base + "/page.md", fallback.finalUrl)
            assertEquals("# Fallback", fallback.content)
        }
    }

    @Test fun `explicit accept suppresses negotiation fallbacks`() = runBlocking {
        val attempts = mutableListOf<String>()
        Fixture { request -> attempts += request.requestURI.path; request.respond("<h1>HTML &amp; entities</h1><script>hidden()</script>", "text/html") }.use { fixture ->
            val result = HttpReader().read(fixture.base + "/page", headers = mapOf("aCcEpT" to "text/html"))
            assertEquals(listOf("/page"), attempts)
            assertEquals("# HTML & entities", result.content)
            assertFalse(result.content.contains("hidden"))
        }
    }

    @Test fun `nearest llms index and relative document links take precedence`() = runBlocking {
        Fixture { request -> when (request.requestURI.path) {
            "/guide/llms.txt" -> request.respond("# Guide\n- [Start](./content-start.md)\n- [Other](./other.md)")
            "/guide/content-start.md" -> request.respond("# Start\n## Setup\nUse Kotlin")
            "/llms.txt" -> request.respond("# Root\n- [Wrong](./wrong.md)")
            "/guide/start" -> request.respond("<h1>Shell</h1>", "text/html")
            else -> request.respond("Missing", "text/plain", 404)
        } }.use { fixture ->
            val result = HttpReader().read(fixture.base + "/guide/start", requireMarkdown = true)
            assertEquals("llms-link", result.source)
            assertTrue(result.content.contains("Use Kotlin"))
            val index = HttpReader().read(fixture.base + "/guide/start", llms = LlmsReadMode.Index, filter = "Start")
            assertTrue(index.content.contains(fixture.base + "/guide/content-start.md"))
            assertFalse(index.content.contains("Wrong"))
            assertFalse(index.content.contains("[Other]"))
        }
    }

    @Test fun `redirect origin restrictions are checked before destination IO`() = runBlocking {
        val destinationRequests = AtomicInteger()
        Fixture { request -> destinationRequests.incrementAndGet(); request.respond("leaked") }.use { destination ->
            Fixture { request -> request.responseHeaders.add("Location", destination.base + "/private"); request.sendResponseHeaders(302, -1); request.close() }.use { source ->
                try { HttpReader(KmpwrightConfig(allowedOriginRules = setOf(source.base))).read(source.base + "/redirect"); fail("Origin restriction must block redirect") }
                catch (error: KmpwrightException) { assertTrue(error.message.orEmpty().contains("not allowed")) }
                assertEquals(0, destinationRequests.get())
            }
        }
    }

    @Test fun `all custom secrets stay on their original origin`() = runBlocking {
        Fixture { request ->
            assertNull(request.requestHeaders.getFirst("Authorization"))
            assertNull(request.requestHeaders.getFirst("X-Private-Key"))
            assertTrue(request.requestHeaders.getFirst("Accept").contains("markdown"))
            request.respond("# Safe")
        }.use { destination ->
            Fixture { request ->
                assertEquals("Bearer private", request.requestHeaders.getFirst("Authorization"))
                request.responseHeaders.add("Location", destination.base + "/target")
                request.sendResponseHeaders(307, -1); request.close()
            }.use { source ->
                val result = HttpReader().read(source.base, headers = mapOf("Authorization" to "Bearer private", "X-Private-Key" to "secret"))
                assertEquals("# Safe", result.content)
            }
        }
    }

    @Test fun `decompressed response bytes are bounded and unicode remains valid`() = runBlocking {
        Fixture { request ->
            val bytes = ByteArrayOutputStream().also { buffer -> GZIPOutputStream(buffer).use { it.write("雪".repeat(10_000).toByteArray()) } }.toByteArray()
            request.responseHeaders.add("Content-Type", "text/plain; charset=utf-8")
            request.responseHeaders.add("Content-Encoding", "gzip")
            request.sendResponseHeaders(200, bytes.size.toLong())
            request.responseBody.use { it.write(bytes) }
        }.use { fixture ->
            val result = HttpReader().read(fixture.base, raw = true, maxBytes = 1024)
            assertTrue(result.truncated)
            assertTrue(result.content.toByteArray().size <= 1024)
            assertFalse(result.content.contains('\uFFFD'))
            assertEquals("雪".repeat(341), result.content)
        }
    }

    @Test fun `declared non utf8 charset is decoded`() = runBlocking {
        Fixture { request ->
            val bytes = "Café".toByteArray(Charsets.ISO_8859_1)
            request.responseHeaders.add("Content-Type", "text/plain; charset=ISO-8859-1")
            request.sendResponseHeaders(200, bytes.size.toLong())
            request.responseBody.use { it.write(bytes) }
        }.use { fixture -> assertEquals("Café", HttpReader().read(fixture.base, raw = true).content) }
    }

    @Test fun `reader preserves caller cancellation and cleans timed out IO`() = runBlocking {
        Fixture { request -> Thread.sleep(200); runCatching { request.respond("late") } }.use { fixture ->
            assertNull(withTimeoutOrNull(10) { HttpReader().read(fixture.base, timeoutMs = 1_000) })
            try { HttpReader().read(fixture.base, timeoutMs = 30); fail("Read deadline must terminate IO") }
            catch (error: KmpwrightException) { assertTrue(error.message.orEmpty().contains("Timeout")) }
        }
    }
}
