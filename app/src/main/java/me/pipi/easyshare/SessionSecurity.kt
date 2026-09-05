package me.pipi.easyshare

import android.annotation.SuppressLint
import java.net.InetAddress
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.X509TrustManager

object SessionSecurity {
    fun usesModernProtocol(cryptoVersion: Int?): Boolean =
        cryptoVersion != null && cryptoVersion >= BleSecurity.MODERN_CRYPTO_VERSION

    fun isPeerAllowed(secureReceiveOnly: Boolean, cryptoVersion: Int?): Boolean =
        !secureReceiveOnly || usesModernProtocol(cryptoVersion)

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

    /**
     * Compares two textual IP addresses. Only numeric literals are accepted so that no name
     * resolution can happen on the calling thread; anything else counts as a mismatch.
     */
    fun isSameAddress(actual: String?, expected: String?): Boolean {
        val left = parseNumericAddress(actual) ?: return false
        val right = parseNumericAddress(expected) ?: return false
        return left == right
    }

    private fun parseNumericAddress(value: String?): InetAddress? {
        if (value.isNullOrBlank() || !NUMERIC_ADDRESS.matches(value)) return null
        return runCatching { InetAddress.getByName(value) }.getOrNull()
    }

    private val NUMERIC_ADDRESS = Regex("[0-9A-Fa-f:.]+")

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
