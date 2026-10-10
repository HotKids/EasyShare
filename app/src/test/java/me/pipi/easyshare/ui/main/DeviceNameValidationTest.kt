package me.pipi.easyshare.ui.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeviceNameValidationTest {
    @Test
    fun emptyNamesCannotSilentlyBecomeAndroid() {
        listOf("", "  ", "\n\t").forEach {
            assertEquals(DeviceNameError.EMPTY, deviceNameError(it))
        }
    }

    @Test
    fun namesAreValidatedAgainstTheStoredUtf8LimitWithoutTruncation() {
        listOf("a".repeat(64), "机".repeat(21), "😀".repeat(16), "  Pixel 11 Pro  ").forEach {
            assertNull(deviceNameError(it))
        }
        listOf("a".repeat(65), "机".repeat(22), "😀".repeat(17)).forEach {
            assertEquals(DeviceNameError.TOO_LONG, deviceNameError(it))
        }
    }
}
