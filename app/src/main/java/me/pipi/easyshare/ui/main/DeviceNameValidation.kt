package me.pipi.easyshare.ui.main

import me.pipi.easyshare.utils.BleUtils

internal enum class DeviceNameError { EMPTY, TOO_LONG }

internal fun deviceNameError(name: String): DeviceNameError? {
    val trimmed = name.trim()
    return when {
        trimmed.isEmpty() -> DeviceNameError.EMPTY
        trimmed.toByteArray(Charsets.UTF_8).size > BleUtils.MAX_DEVICE_NAME_BYTES -> DeviceNameError.TOO_LONG
        else -> null
    }
}
