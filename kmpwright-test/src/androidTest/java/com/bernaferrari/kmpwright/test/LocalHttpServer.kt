package com.bernaferrari.kmpwright.test

import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/** Minimal HTTP/1.1 server for instrumentation fixtures (one response per connection). */
class LocalHttpServer(
    private val respond: (request: FixtureRequest) -> FixtureResponse
) : Closeable {
    data class FixtureRequest(val path: String, val headers: Map<String, String>) {
        fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
    }

    data class FixtureResponse(
        val status: Int,
        val reason: String,
        val body: String,
        val contentType: String = "text/html; charset=UTF-8",
        val headers: Map<String, String> = emptyMap()
    )

    private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
    val origin: String get() = "http://127.0.0.1:${server.localPort}"

    private val acceptor = thread(name = "kmpwright-fixture-server", isDaemon = true) {
        while (!server.isClosed) {
            val socket = try { server.accept() } catch (_: Exception) { break }
            thread(isDaemon = true) { serve(socket) }
        }
    }

    private fun serve(socket: Socket) {
        // A client may abort a request (stopLoading, blocked redirect); never crash the test process.
        try { socket.use { respondTo(it) } } catch (_: java.io.IOException) {}
    }

    private fun respondTo(socket: Socket) {
        val reader = socket.getInputStream().bufferedReader()
        val requestLine = reader.readLine() ?: return
        val headers = LinkedHashMap<String, String>()
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            val separator = line.indexOf(':')
            if (separator > 0) headers[line.substring(0, separator).trim()] = line.substring(separator + 1).trim()
        }
        val path = requestLine.split(' ').getOrElse(1) { "/" }
        val response = respond(FixtureRequest(path, headers))
        val body = response.body.toByteArray()
        val head = buildString {
            append("HTTP/1.1 ${response.status} ${response.reason}\r\n")
            append("Content-Type: ${response.contentType}\r\n")
            append("Content-Length: ${body.size}\r\n")
            response.headers.forEach { (name, value) -> append("$name: $value\r\n") }
            append("Connection: close\r\n\r\n")
        }
        val output = socket.getOutputStream()
        output.write(head.toByteArray())
        output.write(body)
        output.flush()
    }

    override fun close() {
        server.close()
        acceptor.join(1_000)
    }
}
