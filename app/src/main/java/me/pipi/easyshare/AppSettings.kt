package me.pipi.easyshare

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import me.pipi.easyshare.utils.BleUtils

class AppSettings(private val context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("app", Context.MODE_PRIVATE)

    var deviceName: String
        get() = BleUtils.normalizeDeviceName(prefs.getString("deviceName", null) ?: "Android")
        set(value) {
            prefs.edit { putString("deviceName", BleUtils.normalizeDeviceName(value)) }
        }

    var downloadUri: String?
        get() = prefs.getString("downloadUri", null)
        set(value) {
            prefs.edit { putString("downloadUri", value) }
        }

    var enhancedModeEnabled: Boolean
        get() = prefs.getBoolean("enhancedModeEnabled", true)
        set(value) {
            prefs.edit { putBoolean("enhancedModeEnabled", value) }
        }

    var secureReceiveOnly: Boolean
        get() = prefs.getBoolean("secureReceiveOnly", false)
        set(value) {
            prefs.edit { putBoolean("secureReceiveOnly", value) }
        }

    var brandId: Int
        get() = prefs.getInt("brandId", -1)
        set(value) {
            prefs.edit { putInt("brandId", value) }
        }

}
