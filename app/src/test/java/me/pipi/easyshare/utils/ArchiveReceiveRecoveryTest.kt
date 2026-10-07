package me.pipi.easyshare.utils

import java.io.EOFException
import java.io.IOException
import java.util.zip.ZipException
import kotlinx.coroutines.CancellationException
import me.pipi.easyshare.exceptions.CancelledByUserException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchiveReceiveRecoveryTest {
    @Test
    fun keepsCompletedFilesAfterTransportInterruption() {
        assertTrue(ArchiveReceiveRecovery.canKeepCompletedFiles(EOFException(), 1))
        assertTrue(ArchiveReceiveRecovery.canKeepCompletedFiles(IOException("closed"), 2))
    }

    @Test
    fun cancellationKeepsOnlyAlreadyCompletedFiles() {
        assertTrue(ArchiveReceiveRecovery.canKeepCompletedFiles(CancelledByUserException(false), 5))
        assertTrue(ArchiveReceiveRecovery.canKeepCompletedFiles(CancelledByUserException(true), 5))
        assertTrue(ArchiveReceiveRecovery.canKeepCompletedFiles(CancellationException("session closed"), 1))
        assertFalse(ArchiveReceiveRecovery.canKeepCompletedFiles(CancelledByUserException(false), 0))
    }

    @Test
    fun rollsBackWhenNothingCompletedOrArchiveIsInvalid() {
        assertFalse(ArchiveReceiveRecovery.canKeepCompletedFiles(EOFException(), 0))
        assertFalse(ArchiveReceiveRecovery.canKeepCompletedFiles(ZipException("invalid"), 1))
        assertFalse(
            ArchiveReceiveRecovery.canKeepCompletedFiles(
                IllegalArgumentException("invalid metadata"),
                1,
            ),
        )
    }
}
