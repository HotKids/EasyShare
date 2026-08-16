package me.pipi.easyshare.utils

import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BleUtilsTest {
    @Test
    fun senderIdUsesUnsignedAdvertisementBytes() {
        val data = ByteArray(27).apply {
            this[8] = 0x80.toByte()
            this[9] = 0xff.toByte()
        }

        assertEquals("80ff", BleUtils.senderIdFromAdvertisement(data))
    }

    @Test
    fun emptyAdvertisementNameIsRejected() {
        assertNull(BleUtils.deviceNameFromAdvertisement(ByteArray(27)))
    }

    @Test
    fun advertisementNameIsUtf8SafeAndMarkedWhenTruncated() {
        val encoded = BleUtils.advertisementNameBytes("像素手机 Pixel 9 Pro")
        val data = ByteArray(27).apply { encoded.copyInto(this, destinationOffset = 10) }

        assertTrue(encoded.size <= 16)
        assertTrue(BleUtils.deviceNameFromAdvertisement(data)!!.endsWith("..."))
    }

    @Test
    fun protocolDeviceNameIsBounded() {
        val normalized = BleUtils.normalizeDeviceName("手".repeat(100))
        assertTrue(normalized.toByteArray(Charsets.UTF_8).size <= BleUtils.MAX_DEVICE_NAME_BYTES)
    }

    @Test
    fun p2pPayloadFramesRoundTripWithinGattLimit() {
        val payload = ByteArray(1400) { (it % 251).toByte() }
        val frames = BleUtils.frameP2pPayload(payload)
        val restored = ByteArray(payload.size)
        var restoredLength = 0

        assertTrue(frames.size > 1)
        frames.forEach { frame ->
            assertTrue(frame.size <= BleUtils.MAX_P2P_GATT_FRAME_BYTES)
            val chunk = BleUtils.parseP2pPayloadChunk(frame)!!
            assertEquals(payload.size, chunk.totalSize)
            assertEquals(restoredLength, chunk.offset)
            chunk.payload.copyInto(restored, destinationOffset = restoredLength)
            restoredLength += chunk.payload.size
        }

        assertEquals(payload.size, restoredLength)
        assertArrayEquals(payload, restored)
    }

    @Test
    fun legacyP2pJsonIsNotTreatedAsAFrame() {
        assertNull(BleUtils.parseP2pPayloadChunk("{\"ssid\":\"DIRECT-test\"}".toByteArray()))
    }

    @Test
    fun malformedP2pFrameIsRejected() {
        val frame = BleUtils.frameP2pPayload(ByteArray(600) { 1 }).first().clone()
        ByteBuffer.wrap(frame, 4, 4).putInt(1)

        try {
            BleUtils.parseP2pPayloadChunk(frame)
            fail("Expected malformed frame to be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }
}
