package me.pipi.easyshare.models

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolCompatibilityTest {
    @Test
    fun p2pInfoKeepsEstablishedWireVersionKey() {
        val payload = Json.encodeToString(
            P2pInfo(
                id = "sender",
                ssid = "DIRECT-test",
                psk = "password",
                mac = "02:00:00:00:00:00",
                port = 12345,
                easyShare = 260811,
            )
        )

        assertTrue(payload.contains("\"catShare\":260811"))
        assertFalse(payload.contains("\"easyShare\""))
    }

    @Test
    fun deviceInfoToleratesPeersWithoutSessionKey() {
        val decoded = Json.decodeFromString<DeviceInfo>(
            """{"state":0,"mac":"02:00:00:00:00:00"}"""
        )
        assertNull(decoded.key)
        assertNull(decoded.cryptoVersion)
    }

    @Test
    fun deviceInfoReadsAndWritesEstablishedWireVersionKey() {
        val decoded = Json.decodeFromString<DeviceInfo>(
            """{"state":0,"key":null,"mac":"02:00:00:00:00:00","catShare":42}"""
        )
        assertEquals(42, decoded.easyShare)

        val encoded = Json.encodeToString(decoded)
        assertTrue(encoded.contains("\"catShare\":42"))
        assertFalse(encoded.contains("\"easyShare\""))
    }
}
