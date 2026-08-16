package me.pipi.easyshare.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TransferLimitsTest {
    @Test
    fun rejectsInvalidMetadata() {
        assertThrows(TransferLimitException::class.java) {
            TransferLimits.validateMetadata(0, 1, null)
        }
        assertThrows(TransferLimitException::class.java) {
            TransferLimits.validateMetadata(1, -1, null)
        }
        assertThrows(TransferLimitException::class.java) {
            TransferLimits.validateMetadata(1, 0, TransferLimits.MAX_TEXT_BYTES + 1)
        }
    }

    @Test
    fun unknownSizeGetsAConservativeRuntimeCap() {
        assertEquals(TransferLimits.MAX_UNKNOWN_SIZE_BYTES, TransferLimits.maxActualBytes(0))
    }

    @Test
    fun declaredSizeGetsBoundedTolerance() {
        assertEquals(2L * 1024 * 1024, TransferLimits.maxActualBytes(1024 * 1024))
    }
}
