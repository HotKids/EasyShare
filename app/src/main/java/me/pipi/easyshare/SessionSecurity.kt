package me.pipi.easyshare

import android.annotation.SuppressLint
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.X509TrustManager

object SessionSecurity {
    fun generateToken(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(
        ByteArray(32).also(SecureRandom()::nextBytes),
    )

    fun certificateSha256(certificate: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(certificate.encoded).toHex()

    fun constantTimeEquals(expected: String?, actual: String?): Boolean {
        if (expected == null || actual == null) return false
        return MessageDigest.isEqual(
            expected.toByteArray(Charsets.US_ASCII),
            actual.toByteArray(Charsets.US_ASCII),
        )
    }

    fun isAuthorized(
        secureSessionRequired: Boolean,
        expectedToken: String,
        candidateToken: String?,
    ): Boolean = !secureSessionRequired || constantTimeEquals(expectedToken, candidateToken)

    private fun ByteArray.toHex(): String = joinToString("") {
        "%02x".format(it.toInt() and 0xff)
    }
}

@SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
class SessionTrustManager(private val expectedSha256: String?) : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) = Unit

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
        // Alliance peers do not expose a certificate fingerprint. New Easy Share peers always do.
        val expected = expectedSha256 ?: return
        val certificate = chain.firstOrNull() ?: throw CertificateException("Missing server certificate")
        val actual = SessionSecurity.certificateSha256(certificate)
        if (!SessionSecurity.constantTimeEquals(expected, actual)) {
            throw CertificateException("Server certificate does not match BLE session")
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}
