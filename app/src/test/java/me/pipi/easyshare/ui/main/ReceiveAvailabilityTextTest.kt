package me.pipi.easyshare.ui.main

import me.pipi.easyshare.R
import org.junit.Assert.assertEquals
import org.junit.Test

class ReceiveAvailabilityTextTest {
    @Test
    fun receiveReadinessDependsOnlyOnTheTwoRadioSwitches() {
        assertEquals(R.string.brand_receive_ready, receiveAvailabilityText(true, true))
        assertEquals(R.string.brand_receive_bluetooth_off, receiveAvailabilityText(true, false))
        assertEquals(R.string.brand_receive_wifi_off, receiveAvailabilityText(false, true))
        assertEquals(R.string.brand_receive_radios_off, receiveAvailabilityText(false, false))
    }
}
