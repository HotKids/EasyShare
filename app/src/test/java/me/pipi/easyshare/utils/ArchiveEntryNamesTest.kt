package me.pipi.easyshare.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ArchiveEntryNamesTest {
    @Test
    fun acceptsNestedAllianceEntryAndReturnsOnlyFileName() {
        assertEquals("photo.jpg", ArchiveEntryNames.safeFileName("0/photos/photo.jpg"))
        ArchiveEntryNames.validate("0/photos/")
    }

    @Test
    fun rejectsTraversalAndAbsolutePaths() {
        listOf("../secret", "folder/../../secret", "/absolute/file", "C:/absolute/file").forEach {
            assertThrows(IllegalArgumentException::class.java) {
                ArchiveEntryNames.safeFileName(it)
            }
        }
    }

    @Test
    fun treatsBackslashesAsPathSeparators() {
        assertEquals("file.txt", ArchiveEntryNames.safeFileName("folder\\file.txt"))
        assertThrows(IllegalArgumentException::class.java) {
            ArchiveEntryNames.safeFileName("..\\secret")
        }
    }
}
