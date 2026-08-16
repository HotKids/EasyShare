package me.pipi.easyshare.utils

object ArchiveEntryNames {
    private val windowsDrivePrefix = Regex("^[A-Za-z]:/")

    fun validate(path: String) {
        require(path.isNotBlank()) { "Archive entry name is blank" }
        val normalized = path.replace('\\', '/')
        require(!normalized.startsWith('/') && !windowsDrivePrefix.containsMatchIn(normalized)) {
            "Archive entry uses an absolute path"
        }

        val segments = normalized.split('/')
        require(segments.none { it == ".." }) { "Archive entry escapes its destination" }
    }

    fun safeFileName(path: String): String {
        validate(path)
        val normalized = path.replace('\\', '/')
        val segments = normalized.split('/')
        return segments.lastOrNull()
            ?.takeIf { it.isNotBlank() && it != "." && it != ".." }
            ?: throw IllegalArgumentException("Archive entry has no file name")
    }
}
