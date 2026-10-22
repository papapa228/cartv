package com.bmwflow.launcher

import android.annotation.SuppressLint
import okhttp3.OkHttpClient
import java.security.KeyStore
import java.security.cert.CertPathValidator
import java.security.cert.CertificateException
import java.security.cert.CertificateExpiredException
import java.security.cert.CertificateFactory
import java.security.cert.CertificateNotYetValidException
import java.security.cert.PKIXParameters
import java.security.cert.X509Certificate
import java.util.Date
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

object Http {
    val client: OkHttpClient by lazy {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(null as KeyStore?)
        val system = tmf.trustManagers.first { it is X509TrustManager } as X509TrustManager
        val tm = ClockAwareTrustManager(system)
        val ssl = SSLContext.getInstance("TLS")
        ssl.init(null, arrayOf(tm), null)
        OkHttpClient.Builder()
            .sslSocketFactory(ssl.socketFactory, tm)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(25, TimeUnit.SECONDS)
            .build()
    }
}

/**
 * Полная проверка сертификатов сохраняется. Если системная проверка упала ТОЛЬКО
 * из-за срока действия (системные часы сброшены после отключения питания),
 * цепочка проверяется повторно — теми же корневыми сертификатами Android,
 * но на дату из NTP. Проверку имени хоста по-прежнему выполняет OkHttp.
 */
@SuppressLint("CustomX509TrustManager")
class ClockAwareTrustManager(private val system: X509TrustManager) : X509TrustManager {

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
        system.checkClientTrusted(chain, authType)

    override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        try {
            system.checkServerTrusted(chain, authType)
        } catch (e: CertificateException) {
            if (chain == null || chain.isEmpty() || !TrustedClock.synced || !isValidityError(e)) throw e
            try {
                validateAt(chain, Date(TrustedClock.now()))
            } catch (inner: Exception) {
                throw e
            }
        }
    }

    private fun isValidityError(e: Throwable): Boolean {
        var t: Throwable? = e
        while (t != null) {
            if (t is CertificateExpiredException || t is CertificateNotYetValidException) return true
            val msg = t.message?.lowercase() ?: ""
            if ("expired" in msg || "not yet valid" in msg || "validation time" in msg) return true
            t = t.cause
        }
        return false
    }

    private fun validateAt(chain: Array<out X509Certificate>, date: Date) {
        val store = KeyStore.getInstance("AndroidCAStore")
        store.load(null)
        val params = PKIXParameters(store).apply {
            isRevocationEnabled = false
            this.date = date
        }
        // Самоподписанный корень, если сервер его прислал, не должен входить в путь.
        val path = chain.filter { it.subjectX500Principal != it.issuerX500Principal }
            .ifEmpty { chain.toList() }
        val certPath = CertificateFactory.getInstance("X.509").generateCertPath(path)
        CertPathValidator.getInstance("PKIX").validate(certPath, params)
    }
}
