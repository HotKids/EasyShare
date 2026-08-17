package me.pipi.easyshare.utils

import android.os.Build
import androidx.annotation.RequiresApi
import dalvik.system.ZipPathValidator.Callback
import java.util.zip.ZipException

@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
object ZipPathValidatorCallback : Callback {
    override fun onZipEntryAccess(path: String) {
        try {
            ArchiveEntryNames.validate(path)
        } catch (error: IllegalArgumentException) {
            throw ZipException(error.message ?: "Invalid archive entry name").apply {
                initCause(error)
            }
        }
    }
}
