package me.pipi.easyshare.models

import android.os.Parcelable
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import me.pipi.easyshare.utils.LiveStage

@Serializable
enum class IncomingTransferUiStatus {
    REQUESTED,
    RECEIVING,
    SUCCESS,
    PARTIAL,
    FAILED,
    CANCELED,
}

@Serializable
@Parcelize
data class IncomingTransferUiState(
    val taskId: Int,
    val senderName: String,
    val brandId: Int?,
    val fileName: String,
    val fileCount: Int,
    val totalSize: Long,
    val status: IncomingTransferUiStatus,
    val progress: Int = 0,
    @Transient @IgnoredOnParcel val receivedFiles: List<ReceivedFile> = emptyList(),
    val receivedFilesToken: String? = null,
    val errorMessage: String? = null,
    val isText: Boolean = false,
    val mimeType: String? = null,
    val currentFileName: String? = null,
    val stage: LiveStage = LiveStage.WAITING_AUTH,
    val requestExpiresAtMillis: Long = 0L,
    val cancelEnabledAtMillis: Long = 0L,
    val cancelRequested: Boolean = false,
    val monitorInBackground: Boolean = false,
    val receiveDirectoryUri: String? = null,
) : Parcelable
