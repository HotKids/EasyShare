package me.pipi.easyshare.ui.transfer

import me.pipi.easyshare.nearbyDeviceColumns
import org.junit.Assert.assertEquals
import org.junit.Test

class AdaptiveTransferLayoutTest {
    @Test
    fun measuredSheetsAreOnlyClampedByTheAvailableWindow() {
        assertEquals(776f, availableSheetHeightDp(800f, 24f), 0f)
        assertEquals(296f, availableSheetHeightDp(320f, 24f), 0f)
        assertEquals(0f, availableSheetHeightDp(20f, 24f), 0f)
    }

    @Test
    fun settingsRetainTheirExplicitHeightCapAndFontScaling() {
        assertEquals(480f * 0.48f, sheetHeightDp(480f, 24f, 0.48f), 0f)
        assertEquals(768f, sheetHeightDp(800f, 24f, 0.48f, 2f), 0f)
        assertEquals(296f, sheetHeightDp(320f, 24f, 0.48f), 0f)
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

    @Test
    fun largeTextReducesDeviceColumnsWithoutChangingNormalText() {
        assertEquals(1, nearbyDeviceColumns(427f, 2f))
        assertEquals(2, nearbyDeviceColumns(640f, 2f))
        assertEquals(3, nearbyDeviceColumns(427f, 0.85f))
    }

}
