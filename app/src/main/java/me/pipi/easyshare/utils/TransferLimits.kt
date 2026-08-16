package me.pipi.easyshare.utils

class TransferLimitException(message: String) : IllegalArgumentException(message)

object TransferLimits {
    const val MAX_FILE_COUNT = 10_000
    const val MAX_TRANSFER_BYTES = 20L * 1024 * 1024 * 1024
    const val MAX_ENTRY_BYTES = 10L * 1024 * 1024 * 1024
    const val MAX_UNKNOWN_SIZE_BYTES = 1024L * 1024 * 1024
    const val MAX_TEXT_BYTES = 2L * 1024 * 1024
    const val STORAGE_RESERVE_BYTES = 256L * 1024 * 1024
    private const val SIZE_TOLERANCE_BYTES = 1024L * 1024

    fun validateMetadata(fileCount: Int, totalSize: Long, textSize: Long?) {
        if (fileCount !in 1..MAX_FILE_COUNT) {
            throw TransferLimitException("Invalid file count")
        }
        if (totalSize < 0L || totalSize > MAX_TRANSFER_BYTES) {
            throw TransferLimitException("Invalid transfer size")
        }
        if (textSize != null && textSize > MAX_TEXT_BYTES) {
            throw TransferLimitException("Shared text is too large")
        }
    }

    fun maxActualBytes(declaredSize: Long): Long {
        if (declaredSize <= 0L) return MAX_UNKNOWN_SIZE_BYTES
        return minOf(
            MAX_TRANSFER_BYTES,
            declaredSize.saturatedAdd(maxOf(SIZE_TOLERANCE_BYTES, declaredSize / 100)),
        )
    }

    fun requiredAvailableBytes(declaredSize: Long): Long =
        declaredSize.coerceAtLeast(0L).saturatedAdd(STORAGE_RESERVE_BYTES)

    private fun Long.saturatedAdd(other: Long): Long =
        if (this > Long.MAX_VALUE - other) Long.MAX_VALUE else this + other
}
