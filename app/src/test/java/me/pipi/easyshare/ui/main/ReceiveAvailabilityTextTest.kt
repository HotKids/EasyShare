package me.pipi.easyshare.ui.main

import me.pipi.easyshare.R
import org.junit.Assert.assertEquals
import org.junit.Test

class ReceiveAvailabilityTextTest {
    @Test
    fun runningReceiverNeedsBothRadios() {
        val running = MainUiState(receiverRunning = true, receivePermissionsGranted = true)
        assertEquals(R.string.brand_receive_ready, receiveAvailabilityText(running.copy(wifiEnabled = true, bluetoothEnabled = true)))
        assertEquals(R.string.brand_receive_bluetooth_off, receiveAvailabilityText(running.copy(wifiEnabled = true)))
        assertEquals(R.string.brand_receive_wifi_off, receiveAvailabilityText(running.copy(bluetoothEnabled = true)))
        assertEquals(R.string.brand_receive_radios_off, receiveAvailabilityText(running))
    }

    @Test
    fun enabledRadiosDoNotMeanTheReceiverIsRunning() {
        val state = MainUiState(wifiEnabled = true, bluetoothEnabled = true, receivePermissionsGranted = true)
        assertEquals(R.string.home_receive_not_ready, receiveAvailabilityText(state))
        assertEquals(HomeReceiveRecovery.RETRY, homeReceiveRecovery(state))
    }

    @Test
    fun missingPermissionBlocksReadinessEvenWithStaleRunningState() {
        val state = MainUiState(wifiEnabled = true, bluetoothEnabled = true, receiverRunning = true)
        assertEquals(R.string.home_receive_permissions_required, receiveAvailabilityText(state))
        assertEquals(HomeReceiveRecovery.PERMISSIONS, homeReceiveRecovery(state))
    }

    @Test
    fun foregroundReadinessDoesNotRequireTheBackgroundSwitch() {
        val state = MainUiState(wifiEnabled = true, bluetoothEnabled = true, receiverRunning = true,
            receivePermissionsGranted = true, receiverEnabled = false)
        assertEquals(R.string.brand_receive_ready, receiveAvailabilityText(state))
        assertEquals(HomeReceiveRecovery.NONE, homeReceiveRecovery(state))
    }

    @Test
    fun recoveryFixesOneRadioAtATimeBeforeRetryingTheReceiver() {
        val state = MainUiState(receivePermissionsGranted = true)
        assertEquals(HomeReceiveRecovery.WIFI, homeReceiveRecovery(state))
        assertEquals(HomeReceiveRecovery.BLUETOOTH, homeReceiveRecovery(state.copy(wifiEnabled = true)))
        assertEquals(HomeReceiveRecovery.RETRY, homeReceiveRecovery(state.copy(wifiEnabled = true, bluetoothEnabled = true)))
    }
}
