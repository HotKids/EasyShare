package me.pipi.easyshare.utils

import java.io.IOException
import java.util.zip.ZipException

object ArchiveReceiveRecovery {
    fun canKeepCompletedFiles(error: Throwable, completedFileCount: Int): Boolean =
        completedFileCount > 0 && error is IOException && error !is ZipException
}
