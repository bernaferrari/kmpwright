package com.bernaferrari.kmpwright.core

import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/** Bounded PEM/DER parsing shared by Chromium and the standalone HTTP reader. */
internal fun loadCaCertificates(path: String): List<X509Certificate> {
    val file = java.io.File(path)
    require(file.isFile && file.length() in 1..4L * 1024 * 1024) { "CA file must contain between one byte and 4 MiB" }
    val bytes = file.readBytes()
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
