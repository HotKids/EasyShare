package me.pipi.easyshare.ui.main

import me.pipi.easyshare.R

internal enum class HomeReceiveRecovery { PERMISSIONS, WIFI, BLUETOOTH, RETRY, NONE }

internal fun homeReceiveRecovery(state: MainUiState): HomeReceiveRecovery = when {
    !state.receivePermissionsGranted -> HomeReceiveRecovery.PERMISSIONS
    !state.wifiEnabled -> HomeReceiveRecovery.WIFI
    !state.bluetoothEnabled -> HomeReceiveRecovery.BLUETOOTH
    !state.receiverRunning -> HomeReceiveRecovery.RETRY
    else -> HomeReceiveRecovery.NONE
}

internal fun receiveAvailabilityText(state: MainUiState): Int = when (homeReceiveRecovery(state)) {
    HomeReceiveRecovery.PERMISSIONS -> R.string.home_receive_permissions_required
    HomeReceiveRecovery.WIFI -> if (state.bluetoothEnabled) R.string.brand_receive_wifi_off else R.string.brand_receive_radios_off
    HomeReceiveRecovery.BLUETOOTH -> R.string.brand_receive_bluetooth_off
    HomeReceiveRecovery.RETRY -> R.string.home_receive_not_ready
    HomeReceiveRecovery.NONE -> R.string.brand_receive_ready
}
