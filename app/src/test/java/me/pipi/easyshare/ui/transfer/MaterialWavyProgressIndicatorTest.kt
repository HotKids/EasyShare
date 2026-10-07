package me.pipi.easyshare.ui.transfer

import org.junit.Assert.assertEquals
import org.junit.Test

class MaterialWavyProgressIndicatorTest {
    @Test
    fun percentageAndIndicatorUseTheSameBoundedValueWithoutClaimingCompletion() {
        assertEquals(0, transferProgressValue(null))
        assertEquals(0, transferProgressValue(-1))
        assertEquals(42, transferProgressValue(42))
        assertEquals(99, transferProgressValue(99))
        assertEquals(99, transferProgressValue(100))
        assertEquals(99, transferProgressValue(Int.MAX_VALUE))
    }
}
