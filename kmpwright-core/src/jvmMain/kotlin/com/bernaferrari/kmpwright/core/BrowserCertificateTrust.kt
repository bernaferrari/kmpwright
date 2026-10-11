package com.bernaferrari.kmpwright.core

import java.nio.file.*
import java.nio.file.attribute.PosixFilePermissions
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit

/** Bounded PEM/DER parsing shared by Chromium and the standalone HTTP reader. */
internal fun loadCaCertificates(path: String): List<X509Certificate> {
    val file = Path.of(path)
    require(Files.isRegularFile(file) && Files.size(file) in 1..4L * 1024 * 1024) { "CA file must contain between one byte and 4 MiB" }
    val bytes = Files.readAllBytes(file)
    val text = bytes.toString(Charsets.US_ASCII)
    val factory = CertificateFactory.getInstance("X.509")
    val certificates = if (text.contains("-----BEGIN")) {
        val blocks = Regex("-----BEGIN CERTIFICATE-----([\\s\\S]*?)-----END CERTIFICATE-----").findAll(text).toList()
        require(blocks.isNotEmpty() && blocks.size == Regex("-----BEGIN CERTIFICATE-----").findAll(text).count() && blocks.size == Regex("-----END CERTIFICATE-----").findAll(text).count()) { "Invalid PEM certificate bundle" }
        require(blocks.size <= 128) { "CA bundle exceeds 128 certificates" }
        blocks.map { block ->
            val der = java.util.Base64.getDecoder().decode(block.groupValues[1].filterNot(Char::isWhitespace))
            der.inputStream().use { factory.generateCertificate(it) as X509Certificate }
        }
    } else bytes.inputStream().use { stream ->
        listOf(factory.generateCertificate(stream) as X509Certificate).also { require(stream.available() == 0) { "DER certificate contains trailing data" } }
    }
    return certificates.distinctBy { java.util.Base64.getEncoder().encodeToString(it.encoded) }
}

internal fun prepareBrowserCaTrust(path: String): Path {
    require(System.getProperty("os.name").startsWith("Linux")) { "Isolated Chromium CA trust is supported only on Linux" }
    val certificates = loadCaCertificates(path)
    val home = Files.createTempDirectory("kmpwright-nss-")
    try {
        Files.setPosixFilePermissions(home, PosixFilePermissions.fromString("rwx------"))
        val database = Files.createDirectories(home.resolve(".local/share/pki/nssdb"))
        Files.createSymbolicLink(home.resolve(".pki"), Path.of(".local/share/pki"))
        runCertutil(listOf("-N", "--empty-password", "-d", "sql:$database"))
        certificates.forEachIndexed { index, certificate ->
            val staged = home.resolve("ca-$index.der")
            Files.write(staged, certificate.encoded)
            Files.setPosixFilePermissions(staged, PosixFilePermissions.fromString("rw-------"))
            try { runCertutil(listOf("-A", "-d", "sql:$database", "-t", "C,,", "-n", "kmpwright-ca-$index", "-i", staged.toString())) }
            finally { Files.deleteIfExists(staged) }
        }
        return home
    } catch (error: Throwable) { deleteBrowserDirectory(home); throw error }
}

private fun runCertutil(arguments: List<String>) {
    val process = try { ProcessBuilder(listOf("certutil") + arguments).redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .redirectError(ProcessBuilder.Redirect.DISCARD).start() }
    catch (error: java.io.IOException) { throw KmpwrightException("Isolated CA trust requires NSS certutil (libnss3-tools/nss-tools): ${error.message}") }
    try {
        if (!process.waitFor(5, TimeUnit.SECONDS)) throw KmpwrightException("NSS certificate import timed out")
        if (process.exitValue() != 0) throw KmpwrightException("NSS certificate import failed (code ${process.exitValue()})")
    } finally { if (process.isAlive) { process.destroyForcibly(); process.waitFor(2, TimeUnit.SECONDS) } }
}
