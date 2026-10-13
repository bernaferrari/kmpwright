@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.bernaferrari.kmpwright.core

import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDateCreate
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringCreateWithCString
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.Security.SecPolicyCreateSSL
import platform.Security.SecTrustCreateWithCertificates
import platform.Security.SecTrustRefVar
import platform.Security.SecTrustSetVerifyDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ReadOptions.caCertificatePath on iOS: PEM anchors drive URLSession server-trust evaluation.
 * Trust evaluation needs trustd, which a bare simulator executable cannot reach
 * (errSecInternalComponent), so this runs in the app host: `scripts/run-ios-webkit-tests.sh`
 * (the `*DocumentTest` suffix excludes it from the bare `iosSimTest`).
 */
class IOSReaderTrustDocumentTest {
    private val ca = CA
    private val leaf = LEAF
    private val unrelated = UNRELATED

    internal companion object {
        const val CA = """-----BEGIN CERTIFICATE-----
MIIDKTCCAhGgAwIBAgIUGeaKte+irggCQj5fTnbsZcOzAd0wDQYJKoZIhvcNAQEL
BQAwHDEaMBgGA1UEAwwRS01Qd3JpZ2h0IFRlc3QgQ0EwHhcNMjYxMDEyMjAzNjU2
WhcNMzYxMDA5MjAzNjU2WjAcMRowGAYDVQQDDBFLTVB3cmlnaHQgVGVzdCBDQTCC
ASIwDQYJKoZIhvcNAQEBBQADggEPADCCAQoCggEBALyd6RPEItsdaktmywI/wXnH
oU/JEH1+XW1VE52+d2Zhi9uhFWZrqj89FB7vj0vYhXDGpww5oSq83JzNWwwmqE6f
tMxRbH5zSaaWVG70XJXdzwM0+75ew8AxcTEGqDiOyyCfYtXBGzCpqC0O6lu309q/
Z0rINAIhiWqfDN8ogVVnQO7tNrXBfkUAJ3I+nJf7Wd2U5iiIVy16kEWMV2kFn5I2
1HnIi+9ZgqBntXR8VPJkMUCs1VSCSSx5NW+ZRB+YWM/bRhQrXAGcSO5z2iDuRcOZ
w473GJj+rw48B6PiAZR1STGjMoaItzQ5mIoEqqqGqeQ6JaC1WEI5dhfdlim/+Y8C
AwEAAaNjMGEwHQYDVR0OBBYEFLV2QfZTwAZhovj1HIBb6i76LparMB8GA1UdIwQY
MBaAFLV2QfZTwAZhovj1HIBb6i76LparMA8GA1UdEwEB/wQFMAMBAf8wDgYDVR0P
AQH/BAQDAgEGMA0GCSqGSIb3DQEBCwUAA4IBAQAjkDR0gUkqYHeLYsFBZoETM3LG
pmJ6iAXnJ++Ihm1V9FmphNeWHlv1gRjyUwWavjMxjuscpYoWAovB6h+DLbuoxqpW
dUDZfI9mTaT8os6z1lXw2Ijc1a/KTu46Iv2wfEGiGHL0i8y1OQ0yPASKUM0XUCVN
yF8aob37JjNQ9X7+49LUb3nFcpXU5WYp5bA9zFCgPVLlTe6TxgMiR6erE2QsP1CF
0CFQipKuYK1XwadvgIkbDvL973bffl+qIm/xTfmlu9JhNiGjgyW+KkxBo2eSsBAW
O2P5V2jn8ZPRV9j26gvkaVWlFHEV0h6Mo2TQlKgng1PaSE3hMJiVl01+maQa
-----END CERTIFICATE-----"""
        const val LEAF = """-----BEGIN CERTIFICATE-----
MIIDSDCCAjCgAwIBAgIUJLIIo+GFl1i8aaLgRsj36NBXkOgwDQYJKoZIhvcNAQEL
BQAwHDEaMBgGA1UEAwwRS01Qd3JpZ2h0IFRlc3QgQ0EwHhcNMjYxMDEyMjAzNjU3
WhcNMjcxMDEyMjAzNjU3WjAUMRIwEAYDVQQDDAlsb2NhbGhvc3QwggEiMA0GCSqG
SIb3DQEBAQUAA4IBDwAwggEKAoIBAQC/s43005W/g5NdVF4HcbRotls7vtrd+UCh
XtdLJEbhedMTZJF+/0P1YXyWD+uwIS/6kQHaoijEwoMRegt7skPagqRmES/buPU8
iZ09Xv4dNqu+BLvfecqPd0AHXQFCbguYCXDXOdyIZZ+1wrecowKnI75mV+5lrhCX
MAWD6scnMcgdYm4VwAzWqncIHsjUJpmzEye8uQayz57qHGVjvGzT0l9oifYweGuN
KjR4asr0uG6VMg5RvhzQVPLYQnlubHnfQ44bUc0rlJ4hORqfMYDHieiIVBZLa9fb
H2w1e4FyIviVwpcGp+4ZUOeASEriZKsYNhBz8i2YXk/xMLra5xqLAgMBAAGjgYkw
gYYwFAYDVR0RBA0wC4IJbG9jYWxob3N0MBMGA1UdJQQMMAoGCCsGAQUFBwMBMAkG
A1UdEwQCMAAwDgYDVR0PAQH/BAQDAgWgMB0GA1UdDgQWBBTbSFrq/Yej3HbPFsas
Zon9/iGq6zAfBgNVHSMEGDAWgBS1dkH2U8AGYaL49RyAW+ou+i6WqzANBgkqhkiG
9w0BAQsFAAOCAQEAjp56jzYNvc5zPAxNRdNYTuc7EpaKHfxJa/Za8lCgGvxd2QIU
+04cdwngLyKtuQqMEGQLYDHZKRtSx7lnbMf0XE+/TDHOertUC/6MrQEMSQm4mLLF
MjMdC9GaLWiq35SFaTQUPQfbRM5LdyJNooAyXHyVzMDh/4C4tXoPWEXl/zc+Y+O+
WHar4/bHK5dpOl7U88AHW5vYxeACLO518YJAS3a6u64aZvBUXUD1t+dC02tuUu7l
DeSmVP5MkHX69tffOC0U1eB/pPqCs8ixoqueZ/C14sw2+kUi/6e+gtuvWlr8p1io
V14dU+4S8qvMDblsO8dXjY4EU5PYkEimwhZbNA==
-----END CERTIFICATE-----"""
        const val UNRELATED = """-----BEGIN CERTIFICATE-----
MIIDDzCCAfegAwIBAgIUFh42l29EdBiaBiWTlRb9P8hEjR4wDQYJKoZIhvcNAQEL
BQAwFzEVMBMGA1UEAwwMVW5yZWxhdGVkIENBMB4XDTI2MTAxMjIwMzY1N1oXDTM2
MTAwOTIwMzY1N1owFzEVMBMGA1UEAwwMVW5yZWxhdGVkIENBMIIBIjANBgkqhkiG
9w0BAQEFAAOCAQ8AMIIBCgKCAQEA0AS2zmTbAo38YLRXcGXoiRbO1TDiYT/JKDkA
egVTHgnFP5+tA1KW9viVtP31yoTu1Oy56g/102ArqTa4cYN6TlubbE2bXdmAJX6/
ofGf1K9XKKlxe+oXkuQEI8kCV9AEHkQSz6/S9scIk9qweoPEhvGaXEq57zpaHzhS
y1xKStIrN599c92thkY980wTZl1NHQQL4U5KGYiqCUTQvqAWWM8YNECEI+Qb7q80
POc9hFNv48Ho1HLwjonDXGraaMyxvjMC2FFH1TPzXeuGD0NSexosQlCxyJKqO3Aq
DW8ar/IXaMTXBkYf9D2fAFLYSc3HLJfVGVpLEkEh+uPFPa7CvQIDAQABo1MwUTAd
BgNVHQ4EFgQUgc+wCKehDicaVvZAnaul1ol2NH8wHwYDVR0jBBgwFoAUgc+wCKeh
DicaVvZAnaul1ol2NH8wDwYDVR0TAQH/BAUwAwEB/zANBgkqhkiG9w0BAQsFAAOC
AQEAcdm+x4jNYEPSCJXeQ/WvFfcHXe8BSvcs37q3MgjmIiBJzqWes1osTWjl2YGi
qK509kU9N5udSB5k0nsCY/dUvkdL67oxs1v8931vH5hLrBHzEJt9Z2g12pfOBLq1
g5PLAymq7IKntfEpIY1s9FMsSIvw7hz7dzU46kecZZche7aXBkGpaXDsrIhSXBFd
1L/Drm58/ktlZmu15nTgfQBG/Txt0+m/j6SdhitALggX0bzM++UjjNzlKkIBzgE5
YjKkjaX211c70kNfUP2SUflO21T9PCcNe2BQhUwmcDwtBfGwLSg9xUmN78iAe6YJ
HvLuEFgPAenkzUnamdsxOlyElA==
-----END CERTIFICATE-----"""
    }

    /** A localhost leaf issued by [ca], evaluated at a fixed date inside its validity period. */
    private fun evaluate(anchors: String, anchorsOnly: Boolean): Boolean = failure(anchors, anchorsOnly) == null

    private fun failure(anchors: String, anchorsOnly: Boolean): String? = memScoped {
        val certificate = parsePemCertificates(leaf, "leaf").single()
        val host = CFStringCreateWithCString(null, "localhost", kCFStringEncodingUTF8)
        val policy = SecPolicyCreateSSL(true, host)
        val trustVar = alloc<SecTrustRefVar>()
        assertEquals(0, SecTrustCreateWithCertificates(certificate, policy, trustVar.ptr))
        val trust = trustVar.value!!
        // 2027-01-01T00:00:00Z in CFAbsoluteTime (seconds since 2001-01-01).
        val date = CFDateCreate(null, 820_454_400.0)
        SecTrustSetVerifyDate(trust, date)
        try {
            readerTrustFailure(trust, parsePemCertificates(anchors, "anchors"), anchorsOnly)
        } finally {
            CFRelease(date); CFRelease(trust); CFRelease(policy); CFRelease(host)
        }
    }

    @Test
    fun customCaAnchorsTrustTheirLeaf() {
        assertNull(failure(ca, anchorsOnly = true))
        assertNull(failure(ca, anchorsOnly = false))
    }

    @Test
    fun unrelatedAnchorsDoNotTrustTheLeaf() {
        assertFalse(evaluate(unrelated, anchorsOnly = true))
        assertFalse(evaluate(unrelated, anchorsOnly = false))
    }
}

/** PEM handling for ReadOptions.caCertificatePath; runs in the bare `iosSimTest`. */
class IOSReaderPemTest {
    private val ca = IOSReaderTrustDocumentTest.CA
    private val unrelated = IOSReaderTrustDocumentTest.UNRELATED

    @Test
    fun pemParsingReportsBadInput() {
        assertEquals(2, parsePemCertificates(ca + "\n" + unrelated, "bundle").size)
        assertFailsWith<KmpwrightException> { parsePemCertificates("not a certificate", "junk") }
        assertFailsWith<KmpwrightException> { loadPemCertificates("/nonexistent/kmpwright-ca.pem") }
        assertFailsWith<KmpwrightException> { readerHttpClient(1_000, caCertificatePath = null, useSystemCa = false) }
    }
}
