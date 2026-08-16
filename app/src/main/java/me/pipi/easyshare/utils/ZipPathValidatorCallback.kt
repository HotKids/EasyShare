package me.pipi.easyshare.utils

import android.os.Build
import androidx.annotation.RequiresApi
import dalvik.system.ZipPathValidator.Callback

@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
object ZipPathValidatorCallback : Callback {
    override fun onZipEntryAccess(path: String) {
        super.onZipEntryAccess(path)
    }
}
