package me.pipi.easyshare.utils

import java.io.EOFException
import java.io.IOException
import java.util.zip.ZipException
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
