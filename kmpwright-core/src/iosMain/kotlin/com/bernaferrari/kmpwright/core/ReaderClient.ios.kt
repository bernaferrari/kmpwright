package com.bernaferrari.kmpwright.core

import com.bernaferrari.kmpwright.core.ios.toNSData
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UnsafeNumber
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArrayOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.value
import platform.CoreFoundation.CFArrayCreate
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFErrorRefVar
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.kCFTypeArrayCallBacks
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSURLAuthenticationMethodServerTrust
import platform.Foundation.NSURLCredential
import platform.Foundation.NSURLSessionAuthChallengeCancelAuthenticationChallenge
import platform.Foundation.NSURLSessionAuthChallengePerformDefaultHandling
import platform.Foundation.NSURLSessionAuthChallengeUseCredential
import platform.Foundation.credentialForTrust
import platform.Foundation.serverTrust
import platform.Security.SecCertificateCreateWithData
import platform.Security.SecCertificateRef
import platform.Security.SecTrustEvaluateWithError
import platform.Security.SecTrustRef
import platform.Security.SecTrustSetAnchorCertificates
import platform.Security.SecTrustSetAnchorCertificatesOnly
import kotlin.io.encoding.Base64

/**
 * [caCertificatePath] is honored through URLSession server-trust evaluation: the PEM
 * certificates become trust anchors, in addition to the system roots, or instead of them when
 * [ReadOptions.useSystemCa] is false.
 */
@OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)
internal actual fun readerHttpClient(timeoutMs: Long, caCertificatePath: String?, useSystemCa: Boolean): HttpClient {
    val anchors = caCertificatePath?.let(::loadPemCertificates).orEmpty()
    if (caCertificatePath == null && !useSystemCa) {
        throw KmpwrightException("useSystemCa = false needs caCertificatePath: no certificate would be trusted")
    }
    return HttpClient(Darwin) {
        configureReaderTransport(timeoutMs)
        if (anchors.isNotEmpty()) engine {
            handleChallenge { _, _, challenge, completionHandler ->
                val trust = challenge.protectionSpace.serverTrust
                if (challenge.protectionSpace.authenticationMethod != NSURLAuthenticationMethodServerTrust || trust == null) {
                    completionHandler(NSURLSessionAuthChallengePerformDefaultHandling.convert(), null)
                } else {
                    val failure = readerTrustFailure(trust, anchors, anchorsOnly = !useSystemCa)
                    if (failure == null) {
                        completionHandler(NSURLSessionAuthChallengeUseCredential.convert(), NSURLCredential.credentialForTrust(trust))
                    } else {
                        iosLog("HTTP reader rejected ${challenge.protectionSpace.host}: $failure")
                        completionHandler(NSURLSessionAuthChallengeCancelAuthenticationChallenge.convert(), null)
                    }
                }
            }
        }
    }
}

/** Reads every `CERTIFICATE` block of a PEM file. */
@OptIn(ExperimentalForeignApi::class)
internal fun loadPemCertificates(path: String): List<SecCertificateRef> {
    val pem = fileRead(path) ?: throw KmpwrightException("CA certificate file not found: $path")
    return parsePemCertificates(pem, path)
}

@OptIn(ExperimentalForeignApi::class)
internal fun parsePemCertificates(pem: String, source: String): List<SecCertificateRef> {
    val blocks = Regex("-----BEGIN CERTIFICATE-----([\\s\\S]*?)-----END CERTIFICATE-----").findAll(pem)
        .map { it.groupValues[1].filterNot(Char::isWhitespace) }.toList()
    if (blocks.isEmpty()) throw KmpwrightException("No PEM certificate found in $source")
    return blocks.map { base64 ->
        val der = try { Base64.decode(base64) } catch (error: IllegalArgumentException) {
            throw KmpwrightException("Invalid PEM certificate in $source", error)
        }
        @Suppress("UNCHECKED_CAST")
        val data = CFBridgingRetain(der.toNSData()) as CFDataRef
        try {
            SecCertificateCreateWithData(null, data) ?: throw KmpwrightException("Invalid X.509 certificate in $source")
        } finally {
            CFRelease(data)
        }
    }
}

/** Evaluates [trust] with [anchors] as extra roots (or the only roots when [anchorsOnly]). */
@OptIn(ExperimentalForeignApi::class)
internal fun evaluateReaderTrust(trust: SecTrustRef, anchors: List<SecCertificateRef>, anchorsOnly: Boolean): Boolean =
    readerTrustFailure(trust, anchors, anchorsOnly) == null

/** Null when [trust] evaluates as trusted, otherwise Security's reason. */
@OptIn(ExperimentalForeignApi::class)
internal fun readerTrustFailure(trust: SecTrustRef, anchors: List<SecCertificateRef>, anchorsOnly: Boolean): String? = memScoped {
    val values = allocArrayOf(anchors)
    val array = CFArrayCreate(null, values.reinterpret(), anchors.size.toLong(), kCFTypeArrayCallBacks.ptr)
        ?: return@memScoped "Could not build the anchor certificate array"
    try {
        SecTrustSetAnchorCertificates(trust, array).takeIf { it != 0 }?.let { return@memScoped "SecTrustSetAnchorCertificates failed: $it" }
        SecTrustSetAnchorCertificatesOnly(trust, anchorsOnly).takeIf { it != 0 }?.let { return@memScoped "SecTrustSetAnchorCertificatesOnly failed: $it" }
        val error = alloc<CFErrorRefVar>()
        if (SecTrustEvaluateWithError(trust, error.ptr)) return@memScoped null
        val description = error.value?.let { (CFBridgingRelease(it) as? platform.Foundation.NSError)?.localizedDescription }
        description ?: "Server trust evaluation failed"
    } finally {
        CFRelease(array)
    }
}

internal actual fun isReaderTransportTimeout(error: Exception): Boolean = error is io.ktor.client.plugins.HttpRequestTimeoutException
