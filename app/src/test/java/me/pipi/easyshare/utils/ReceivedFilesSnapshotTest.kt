package me.pipi.easyshare.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceivedFilesSnapshotTest {
    @Test
    fun resultRoundTripPreservesTheActualProviderAndFileIdentity() {
        val files = listOf(
            ReceivedFileRecord("image.heic", "content://media/external/downloads/1", "image/heic"),
            ReceivedFileRecord("long \"name\".pdf", "content://documents/tree/a/document/b", "application/pdf"),
        )
        assertEquals(files, ReceivedFilesSnapshot.decode(ReceivedFilesSnapshot.encode(files)))
    }

    @Test
    fun maximumFileResultUsesAConstantSizeNavigationToken() {
        val files = (1..TransferLimits.MAX_FILE_COUNT).map {
            ReceivedFileRecord("file-$it", "content://documents/tree/a/document/$it", "application/octet-stream")
        }
        val json = ReceivedFilesSnapshot.encode(files)
        val token = ReceivedFilesSnapshot.token(json)
        assertEquals(files, ReceivedFilesSnapshot.decode(json))
        assertEquals(64, token.length)
        assertTrue(ReceivedFilesSnapshot.isValidToken(token))
        assertEquals(token, ReceivedFilesSnapshot.token(json))
    }

    @Test
    fun snapshotTokensCannotSelectAnOutsideFile() {
        listOf("", "../private.json", "/tmp/file", "a".repeat(63), "A".repeat(64), "g".repeat(64)).forEach {
            assertFalse(ReceivedFilesSnapshot.isValidToken(it))
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun emptyResultsAreRejectedRatherThanOpeningAnUnrelatedDirectory() {
        ReceivedFilesSnapshot.encode(emptyList())
    }
}
