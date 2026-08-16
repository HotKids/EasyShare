package me.pipi.easyshare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BleSecurityTest {
    @Test
    fun modernCipherRoundTripsAndUsesFreshNonces() {
        val cipher = BleSecurity.deriveSessionKey(
            BleSecurity.getEncodedPublicKey(),
            BleSecurity.MODERN_CRYPTO_VERSION,
        )
        val first = cipher.encrypt("ssid", "DIRECT-Test")
        val second = cipher.encrypt("ssid", "DIRECT-Test")

        assertNotEquals(first, second)
        assertEquals("DIRECT-Test", cipher.decrypt("ssid", first))
    }

    @Test
    fun modernCipherRejectsWrongAssociatedField() {
        val cipher = BleSecurity.deriveSessionKey(
            BleSecurity.getEncodedPublicKey(),
            BleSecurity.MODERN_CRYPTO_VERSION,
        )
        val encrypted = cipher.encrypt("ssid", "DIRECT-Test")

        assertThrows(Throwable::class.java) { cipher.decrypt("psk", encrypted) }
    }
}
