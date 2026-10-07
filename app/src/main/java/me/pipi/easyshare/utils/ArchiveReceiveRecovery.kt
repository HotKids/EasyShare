package me.pipi.easyshare.utils

import java.io.IOException
import java.util.zip.ZipException
import kotlinx.coroutines.CancellationException

object ArchiveReceiveRecovery {
    fun canKeepCompletedFiles(error: Throwable, completedFileCount: Int): Boolean =
        completedFileCount > 0 &&
            (error is CancellationException || error is IOException && error !is ZipException)
}
