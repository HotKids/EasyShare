package me.pipi.easyshare.ui.transfer

import me.pipi.easyshare.nearbyDeviceColumns
import org.junit.Assert.assertEquals
import org.junit.Test

class AdaptiveTransferLayoutTest {
    @Test
    fun normalHeightKeepsTheExistingHalfSheetFractions() {
        assertEquals(480f * 0.48f, sheetHeightDp(480f, 24f, 0.48f), 0f)
        assertEquals(1024f * 0.42f, sheetHeightDp(1024f, 24f, 0.42f), 0f)
    }

    @Test
    fun shortWindowsUseAvailableHeightRatherThanCompressingActions() {
        assertEquals(455f, sheetHeightDp(479f, 24f, 0.48f), 0f)
        assertEquals(296f, sheetHeightDp(320f, 24f, 0.42f), 0f)
    }

    @Test
    fun discoveryUsesThreeColumnsOnlyWhenCellsCanFit() {
        assertEquals(2, nearbyDeviceColumns(307f))
        assertEquals(2, nearbyDeviceColumns(308f))
        assertEquals(2, nearbyDeviceColumns(393f))
        assertEquals(3, nearbyDeviceColumns(394f))
        assertEquals(3, nearbyDeviceColumns(427f))
    }

    @Test
    fun discoveryAdaptsContinuouslyForWideAndNarrowWindows() {
        assertEquals(1, nearbyDeviceColumns(200f))
        assertEquals(1, nearbyDeviceColumns(275f))
        assertEquals(2, nearbyDeviceColumns(276f))
        assertEquals(5, nearbyDeviceColumns(640f))
    }
}
