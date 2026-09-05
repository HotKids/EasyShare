package me.pipi.easyshare

import io.ktor.network.tls.certificates.buildKeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionSecurityTest {
    @Test
    fun secureReceivePolicyRejectsLegacyPeersOnlyWhenEnabled() {
        assertTrue(SessionSecurity.isPeerAllowed(false, null))
        assertTrue(SessionSecurity.isPeerAllowed(false, 1))
        assertTrue(
            SessionSecurity.isPeerAllowed(false, BleSecurity.MODERN_CRYPTO_VERSION),
        )

        assertFalse(SessionSecurity.isPeerAllowed(true, null))
        assertFalse(SessionSecurity.isPeerAllowed(true, 1))
        assertTrue(
            SessionSecurity.isPeerAllowed(true, BleSecurity.MODERN_CRYPTO_VERSION),
        )
        assertTrue(
            SessionSecurity.isPeerAllowed(true, BleSecurity.MODERN_CRYPTO_VERSION + 1),
        )
    }

    @Test
    fun sessionMetadataProtectionStartsAtVersionThree() {
        assertFalse(SessionSecurity.protectsSessionMetadata(null))
        assertFalse(SessionSecurity.protectsSessionMetadata(BleSecurity.MODERN_CRYPTO_VERSION))
        assertTrue(
            SessionSecurity.protectsSessionMetadata(BleSecurity.PROTECTED_SESSION_CRYPTO_VERSION),
        )
        assertTrue(
            SessionSecurity.usesModernProtocol(BleSecurity.PROTECTED_SESSION_CRYPTO_VERSION),
        )
    }

    @Test
    fun tokensAreUniqueAndUrlSafe() {
        val first = SessionSecurity.generateToken()
        val second = SessionSecurity.generateToken()

        assertNotEquals(first, second)
        assertTrue(first.matches(Regex("[A-Za-z0-9_-]{43}")))
    }

    @Test
    fun constantTimeComparisonRejectsMissingOrDifferentValues() {
        assertTrue(SessionSecurity.constantTimeEquals("same", "same"))
        assertFalse(SessionSecurity.constantTimeEquals("same", "other"))
        assertFalse(SessionSecurity.constantTimeEquals("same", null))
    }

    @Test
    fun authorizationRequiresTokenOnlyForModernSessions() {
        assertTrue(SessionSecurity.isAuthorized(false, "expected", null))
        assertFalse(SessionSecurity.isAuthorized(true, "expected", null))
        assertFalse(SessionSecurity.isAuthorized(true, "expected", "different"))
        assertTrue(SessionSecurity.isAuthorized(true, "expected", "expected"))
    }

    @Test
    fun addressComparisonAcceptsOnlyEqualNumericLiterals() {
        assertTrue(SessionSecurity.isSameAddress("192.168.49.1", "192.168.49.1"))
        assertTrue(SessionSecurity.isSameAddress("::ffff:192.168.49.1", "192.168.49.1"))
        assertFalse(SessionSecurity.isSameAddress("192.168.1.20", "192.168.49.1"))
        assertFalse(SessionSecurity.isSameAddress("localhost", "127.0.0.1"))
        assertFalse(SessionSecurity.isSameAddress(null, "192.168.49.1"))
        assertFalse(SessionSecurity.isSameAddress("192.168.49.1", null))
        assertFalse(SessionSecurity.isSameAddress("", ""))
    }

    @Test
    fun pinnedTrustManagerAcceptsOnlyExpectedCertificate() {
        val first = testCertificate("first")
        val second = testCertificate("second")
        val trustManager = SessionTrustManager(SessionSecurity.certificateSha256(first))

        trustManager.checkServerTrusted(arrayOf(first), "RSA")
        assertThrows(CertificateException::class.java) {
            trustManager.checkServerTrusted(arrayOf(second), "RSA")
        }
    }

    @Test
    fun allianceTrustManagerKeepsLegacyCertificateCompatibility() {
        SessionTrustManager(null).checkServerTrusted(arrayOf(testCertificate("legacy")), "RSA")
    }

    private fun testCertificate(alias: String): X509Certificate {
        val keyStore = buildKeyStore {
            certificate(alias) {
                password = "test-password"
                domains = listOf("localhost")
            }
        }
        return keyStore.getCertificate(alias) as X509Certificate
    }
}
