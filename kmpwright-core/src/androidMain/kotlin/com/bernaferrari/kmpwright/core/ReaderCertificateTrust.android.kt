package com.bernaferrari.kmpwright.core

import java.security.KeyStore
import java.security.cert.X509Certificate
import java.security.cert.CertificateException
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** Per-reader trust; never changes JVM/global platform SSL defaults. Hostname verification stays enabled. */
internal fun readerTrustManager(path: String?, useSystemCa: Boolean): X509TrustManager {
    require(path != null || useSystemCa) { "At least one certificate trust source is required" }
    fun manager(store: KeyStore?): X509TrustManager = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
        .trustManagers.filterIsInstance<X509TrustManager>().single()
    val managers = mutableListOf<X509TrustManager>()
    if (useSystemCa) managers += manager(null)
    if (path != null) {
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
        loadCaCertificates(path).forEachIndexed { index, certificate -> store.setCertificateEntry("kmpwright-$index", certificate) }
        managers += manager(store)
    }
    return object : X509TrustManager {
        override fun getAcceptedIssuers(): Array<X509Certificate> = managers.flatMap { it.acceptedIssuers.toList() }.toTypedArray()
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = verify(chain, authType, true)
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = verify(chain, authType, false)
        private fun verify(chain: Array<X509Certificate>, authType: String, client: Boolean) {
            var failure: CertificateException? = null
            for (manager in managers) {
                try { if (client) manager.checkClientTrusted(chain, authType) else manager.checkServerTrusted(chain, authType); return }
                catch (error: CertificateException) { failure = error }
            }
            throw failure ?: CertificateException("No trusted certificate authority")
        }
    }
}
