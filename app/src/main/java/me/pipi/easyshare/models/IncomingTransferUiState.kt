package me.pipi.easyshare.models

enum class IncomingTransferUiStatus {
    REQUESTED,
    RECEIVING,
    SUCCESS,
    PARTIAL,
    FAILED,
    CANCELED,
}

data class IncomingTransferUiState(
    val taskId: Int,
    val senderName: String,
    val brandId: Int?,
    val fileName: String,
    val fileCount: Int,
    val totalSize: Long,
    val status: IncomingTransferUiStatus,
    val isText: Boolean = false,
    val progress: Int = 0,
    val receivedFiles: List<ReceivedFile> = emptyList(),
    val errorMessage: String? = null,
)
