package me.pipi.easyshare.utils

import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.Arrays
import java.util.UUID
import kotlin.math.abs


object BleUtils {
    const val MAX_DEVICE_NAME_BYTES = 64
    const val MAX_P2P_GATT_PAYLOAD_BYTES = 4096
    const val MAX_P2P_GATT_FRAME_BYTES = 480
    private const val MAX_ADVERTISEMENT_NAME_BYTES = 16
    private const val P2P_GATT_FRAME_HEADER_BYTES = 12
    private val P2P_GATT_FRAME_MAGIC = byteArrayOf('E'.code.toByte(), 'S'.code.toByte(), 'P'.code.toByte(), 1)
    val ADV_SERVICE_UUID = UUID.fromString("00003331-0000-1000-8000-008123456789")
    val SERVICE_UUID = UUID.fromString("00009955-0000-1000-8000-00805f9b34fb")
    val CHAR_STATUS_UUID = UUID.fromString("00009954-0000-1000-8000-00805f9b34fb")
    val CHAR_P2P_UUID = UUID.fromString("00009953-0000-1000-8000-00805f9b34fb")

    val RANDOM_DATA: ByteArray = run {
        val random = SecureRandom()
        Arrays.copyOfRange(
            ByteBuffer.allocate(8).putLong(abs(random.nextLong())).array(),
            0,
            2
        )
    }

    fun getSenderId(): String {
        val senderIdRaw = unsignedShort(RANDOM_DATA[0], RANDOM_DATA[1])
        return String.format("%04x", senderIdRaw)
    }

    fun senderIdFromAdvertisement(data: ByteArray): String? {
        if (data.size < 10) return null
        return String.format("%04x", unsignedShort(data[8], data[9]))
    }

    fun deviceNameFromAdvertisement(data: ByteArray): String? {
        if (data.size != 27) return null
        val end = (10..25).firstOrNull { data[it] == 0.toByte() } ?: 26
        if (end == 10) return null
        val decoded = runCatching {
            data.copyOfRange(10, end).decodeToString(throwOnInvalidSequence = true)
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
        return if (decoded.endsWith('\t')) decoded.removeSuffix("\t") + "..." else decoded
    }

    fun normalizeDeviceName(value: String): String {
        val trimmed = value.trim().ifBlank { "Android" }
        return truncateUtf8(trimmed, MAX_DEVICE_NAME_BYTES).ifBlank { "Android" }
    }

    fun advertisementNameBytes(value: String): ByteArray {
        val normalized = normalizeDeviceName(value)
        val full = normalized.toByteArray(Charsets.UTF_8)
        if (full.size <= MAX_ADVERTISEMENT_NAME_BYTES) return full
        return (truncateUtf8(normalized, MAX_ADVERTISEMENT_NAME_BYTES - 1) + "\t")
            .toByteArray(Charsets.UTF_8)
    }

    data class P2pPayloadChunk(
        val totalSize: Int,
        val offset: Int,
        val payload: ByteArray,
    )

    /**
     * Splits secure Easy Share metadata into writes that stay below Android's
     * per-attribute GATT limit. Each write is self-describing so the receiver
     * can reject missing, reordered, or mixed chunks instead of parsing partial JSON.
     */
    fun frameP2pPayload(payload: ByteArray): List<ByteArray> {
        require(payload.size in 1..MAX_P2P_GATT_PAYLOAD_BYTES)
        val maxBodySize = MAX_P2P_GATT_FRAME_BYTES - P2P_GATT_FRAME_HEADER_BYTES
        return payload.asList().chunked(maxBodySize).mapIndexed { index, body ->
            val offset = index * maxBodySize
            ByteBuffer.allocate(P2P_GATT_FRAME_HEADER_BYTES + body.size)
                .put(P2P_GATT_FRAME_MAGIC)
                .putInt(payload.size)
                .putInt(offset)
                .put(body.toByteArray())
                .array()
        }
    }

    /** Returns null for the legacy, unframed JSON format. */
    fun parseP2pPayloadChunk(value: ByteArray): P2pPayloadChunk? {
        if (value.size < P2P_GATT_FRAME_MAGIC.size ||
            !value.copyOfRange(0, P2P_GATT_FRAME_MAGIC.size).contentEquals(P2P_GATT_FRAME_MAGIC)
        ) {
            return null
        }

        require(value.size in (P2P_GATT_FRAME_HEADER_BYTES + 1)..MAX_P2P_GATT_FRAME_BYTES)
        val buffer = ByteBuffer.wrap(value)
        buffer.position(P2P_GATT_FRAME_MAGIC.size)
        val totalSize = buffer.int
        val offset = buffer.int
        val bodySize = value.size - P2P_GATT_FRAME_HEADER_BYTES
        require(totalSize in 1..MAX_P2P_GATT_PAYLOAD_BYTES)
        require(offset in 0 until totalSize)
        require(offset <= totalSize - bodySize)
        val body = ByteArray(bodySize)
        buffer.get(body)
        return P2pPayloadChunk(totalSize, offset, body)
    }

    internal fun truncateUtf8(value: String, maxBytes: Int): String {
        require(maxBytes >= 0)
        val result = StringBuilder()
        var used = 0
        var index = 0
        while (index < value.length) {
            val codePoint = value.codePointAt(index)
            val chars = String(Character.toChars(codePoint))
            val byteCount = chars.toByteArray(Charsets.UTF_8).size
            if (used + byteCount > maxBytes) break
            result.append(chars)
            used += byteCount
            index += Character.charCount(codePoint)
        }
        return result.toString()
    }

    private fun unsignedShort(high: Byte, low: Byte): Int =
        ((high.toInt() and 0xff) shl 8) or (low.toInt() and 0xff)
}
