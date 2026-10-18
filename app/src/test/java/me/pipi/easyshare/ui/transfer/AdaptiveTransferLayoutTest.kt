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
    fun discoveryUsesFourColumnsAtStandardPhoneWidths() {
        assertEquals(4, nearbyDeviceColumns(360f))
        assertEquals(4, nearbyDeviceColumns(393f))
        assertEquals(4, nearbyDeviceColumns(427f))
    }

    @Test
    fun discoveryReducesColumnsInNarrowWindowsAndCapsWideWindowsAtFour() {
        assertEquals(1, nearbyDeviceColumns(100f))
        assertEquals(2, nearbyDeviceColumns(200f))
        assertEquals(3, nearbyDeviceColumns(320f))
        assertEquals(4, nearbyDeviceColumns(640f))
    }

    @Test
    fun largeTextReducesDeviceColumnsWithoutChangingNormalText() {
        assertEquals(2, nearbyDeviceColumns(427f, 2f))
        assertEquals(4, nearbyDeviceColumns(640f, 2f))
        assertEquals(4, nearbyDeviceColumns(427f, 0.85f))
    }

}
