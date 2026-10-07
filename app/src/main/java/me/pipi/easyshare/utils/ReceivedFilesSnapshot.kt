package me.pipi.easyshare.utils

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.pipi.easyshare.models.ReceivedFile
import java.io.File
import java.security.MessageDigest

@Serializable
internal data class ReceivedFileRecord(val name: String, val uri: String, val mimeType: String)

internal object ReceivedFilesSnapshot {
    fun encode(files: List<ReceivedFileRecord>): String {
        require(files.size in 1..TransferLimits.MAX_FILE_COUNT)
        return Json.encodeToString(files)
    }

    fun decode(json: String): List<ReceivedFileRecord> =
        Json.decodeFromString<List<ReceivedFileRecord>>(json).also {
            require(it.size in 1..TransferLimits.MAX_FILE_COUNT)
        }

    fun token(json: String): String = MessageDigest.getInstance("SHA-256")
        .digest(json.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    fun isValidToken(token: String): Boolean = token.matches(Regex("[a-f0-9]{64}"))

    @Synchronized
    fun save(context: Context, files: List<ReceivedFile>): String {
        val json = encode(files.map { ReceivedFileRecord(it.name, it.uri.toString(), it.mimeType) })
        val token = token(json)
        val directory = File(context.filesDir, "received-results")
        check(directory.isDirectory || directory.mkdirs())
        val snapshot = File(directory, "$token.json")
        if (!snapshot.exists()) {
            // A notification must remain usable after the transfer service and its in-memory state disappear.
            val atomicFile = AtomicFile(snapshot)
            val output = atomicFile.startWrite()
            try {
                output.write(json.toByteArray(Charsets.UTF_8))
                atomicFile.finishWrite(output)
            } catch (error: Throwable) {
                atomicFile.failWrite(output)
                throw error
            }
        }
        return token
    }

    fun load(context: Context, token: String): List<ReceivedFile> {
        require(isValidToken(token))
        val snapshot = AtomicFile(File(context.filesDir, "received-results/$token.json"))
        val json = snapshot.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }
        return decode(json).map { ReceivedFile(it.name, Uri.parse(it.uri), it.mimeType) }
    }
}
