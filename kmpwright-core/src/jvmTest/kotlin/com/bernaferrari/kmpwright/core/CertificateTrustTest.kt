package com.bernaferrari.kmpwright.core

import com.sun.net.httpserver.HttpsServer
import com.sun.net.httpserver.HttpsConfigurator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

class CertificateTrustTest {
    private fun keytool(vararg args: String) {
        val process = ProcessBuilder(listOf(Path.of(System.getProperty("java.home"), "bin", "keytool").toString()) + args)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        try { assertTrue("keytool timed out", process.waitFor(15, TimeUnit.SECONDS)); assertEquals(0, process.exitValue()) }
        finally { if (process.isAlive) process.destroyForcibly() }
    }
    @Test fun `private CA reader preserves default isolation and rejects hostname mismatches`() = runBlocking<Unit> {
        val directory = Files.createTempDirectory("kmpwright-ca-test-")
        var server: HttpsServer? = null
        try {
            val storePath = directory.resolve("server.p12")
            val certificate = directory.resolve("ca.pem")
            keytool("-genkeypair", "-alias", "server", "-keyalg", "RSA", "-keysize", "2048", "-validity", "1", "-dname", "CN=localhost", "-ext", "SAN=dns:localhost", "-keystore", storePath.toString(), "-storetype", "PKCS12", "-storepass", "fixture-password", "-keypass", "fixture-password", "-noprompt")
            keytool("-exportcert", "-rfc", "-alias", "server", "-keystore", storePath.toString(), "-storepass", "fixture-password", "-file", certificate.toString())
            val store = KeyStore.getInstance("PKCS12").apply { Files.newInputStream(storePath).use { load(it, "fixture-password".toCharArray()) } }
            val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, "fixture-password".toCharArray()) }
            val context = SSLContext.getInstance("TLS").apply { init(keys.keyManagers, null, null) }
            server = HttpsServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                httpsConfigurator = HttpsConfigurator(context)
                createContext("/") { exchange ->
                    val body = "# Trusted private service".toByteArray()
                    exchange.responseHeaders.add("Content-Type", "text/markdown")
                    exchange.sendResponseHeaders(200, body.size.toLong()); exchange.responseBody.use { it.write(body) }
                }; start()
            }
            val port = server.address.port
            val privateRead: suspend (String) -> ReadResult = { url ->
                HttpReader().read(url, caCertificatePath = certificate.toString(), useSystemCa = false, timeoutMs = 3_000)
            }
            assertEquals("# Trusted private service", privateRead("https://localhost:$port/").content)
            try { HttpReader().read("https://localhost:$port/", timeoutMs = 3_000); fail("Private trust leaked into default reader") }
            catch (_: Exception) { }
            try { privateRead("https://127.0.0.1:$port/"); fail("Wrong hostname accepted") }
            catch (_: Exception) { }
            val chrome = System.getenv("KMPWRIGHT_TEST_CHROME")
            if (System.getProperty("os.name").startsWith("Linux") && !chrome.isNullOrBlank()) {
                val launched = launchBrowser(chrome, caCertificatePath = certificate.toString())
                try {
                    val page = launched.browser.newPage()
                    page.goto("https://localhost:$port/")
                    assertTrue(page.locator("body").innerText().orEmpty().contains("Trusted private service"))
                } finally { launched.close() }
            }
            val cert = loadCaCertificates(certificate.toString()).single()
            val der = directory.resolve("ca.der"); Files.write(der, cert.encoded)
            assertArrayEquals(cert.encoded, loadCaCertificates(der.toString()).single().encoded)
            val bad = directory.resolve("bad.pem"); Files.writeString(bad, Files.readString(certificate) + "\n-----BEGIN CERTIFICATE-----\ninvalid\n-----END CERTIFICATE-----")
            try { loadCaCertificates(bad.toString()); fail("Partially valid bundle accepted") } catch (_: Exception) { }
        } finally { server?.stop(0); directory.toFile().deleteRecursively() }
    }
    @Test fun `isolated Chromium trust rejects unsupported platforms and conflicting options`() {
        try { runBlocking { launchBrowser("chrome", caCertificatePath = "ca.pem", ignoreHttpsErrors = true) }; fail("Disabled verification accepted") }
        catch (_: IllegalArgumentException) { }
        if (!System.getProperty("os.name").startsWith("Linux")) {
            try { prepareBrowserCaTrust("ca.pem"); fail("Unsupported platform accepted") }
            catch (error: IllegalArgumentException) { assertTrue(error.message!!.contains("Linux")) }
        }
    }
}
