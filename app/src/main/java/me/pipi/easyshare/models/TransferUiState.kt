package me.pipi.easyshare.models

enum class TransferUiStatus {
    WAITING,
    SENDING,
    SUCCESS,
    PARTIAL,
    FAILED,
    CANCELED,
    REJECTED,
    TIMEOUT
}

data class TransferUiState(
    val taskId: Int,
    val deviceId: String,
    val status: TransferUiStatus,
    val progress: Int = 0
)
