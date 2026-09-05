package me.pipi.easyshare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BleSecurityTest {
    @Test
    fun modernCipherRoundTripsBetweenTwoPartiesAndUsesFreshNonces() {
        val local = BleSecurity.SessionKeyPair.generate()
        val peer = BleSecurity.SessionKeyPair.generate()
        val sender = local.deriveSessionKey(peer.encodedPublicKey, BleSecurity.MODERN_CRYPTO_VERSION)
        val receiver = peer.deriveSessionKey(local.encodedPublicKey, BleSecurity.MODERN_CRYPTO_VERSION)

        val first = sender.encrypt("ssid", "DIRECT-Test")
        val second = sender.encrypt("ssid", "DIRECT-Test")

        assertNotEquals(first, second)
        assertEquals("DIRECT-Test", receiver.decrypt("ssid", first))
        assertEquals("DIRECT-Test", receiver.decrypt("ssid", second))
    }

    @Test
    fun modernCipherRejectsWrongAssociatedField() {
        val local = BleSecurity.SessionKeyPair.generate()
        val peer = BleSecurity.SessionKeyPair.generate()
        val sender = local.deriveSessionKey(peer.encodedPublicKey, BleSecurity.MODERN_CRYPTO_VERSION)
        val receiver = peer.deriveSessionKey(local.encodedPublicKey, BleSecurity.MODERN_CRYPTO_VERSION)
        val encrypted = sender.encrypt("ssid", "DIRECT-Test")

        assertThrows(Throwable::class.java) { receiver.decrypt("psk", encrypted) }
    }

    @Test
    fun protectedSessionVersionStillUsesAuthenticatedEncryption() {
        val local = BleSecurity.SessionKeyPair.generate()
        val peer = BleSecurity.SessionKeyPair.generate()
        val sender = local.deriveSessionKey(
            peer.encodedPublicKey,
            BleSecurity.PROTECTED_SESSION_CRYPTO_VERSION,
        )
        val receiver = peer.deriveSessionKey(
            local.encodedPublicKey,
            BleSecurity.PROTECTED_SESSION_CRYPTO_VERSION,
        )
        val token = SessionSecurity.generateToken()

        assertEquals(token, receiver.decrypt("token", sender.encrypt("token", token)))
        assertThrows(Throwable::class.java) {
            receiver.decrypt("cert", sender.encrypt("token", token))
        }
    }

    @Test
    fun legacyCipherRoundTripsBetweenTwoParties() {
        val local = BleSecurity.SessionKeyPair.generate()
        val peer = BleSecurity.SessionKeyPair.generate()
        val sender = local.deriveSessionKey(peer.encodedPublicKey, null)
        val receiver = peer.deriveSessionKey(local.encodedPublicKey, null)

        assertEquals("password", receiver.decrypt("psk", sender.encrypt("psk", "password")))
    }

    @Test
    fun sessionKeyPairsAreUnique() {
        assertNotEquals(
            BleSecurity.SessionKeyPair.generate().encodedPublicKey,
            BleSecurity.SessionKeyPair.generate().encodedPublicKey,
        )
    }
}
