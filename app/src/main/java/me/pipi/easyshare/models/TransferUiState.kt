package me.pipi.easyshare.models

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable
import me.pipi.easyshare.utils.LiveStage

@Serializable
enum class TransferUiStatus {
    WAITING,
    SENDING,
    SUCCESS,
    PARTIAL,
    FAILED,
    CANCELED,
    REJECTED,
    TIMEOUT,
    UNCONFIRMED,
}

@Serializable
@Parcelize
data class TransferUiState(
    val taskId: Int,
    val deviceId: String,
    val status: TransferUiStatus,
    val progress: Int = 0,
    val stage: LiveStage = LiveStage.INIT,
    val errorMessage: String? = null,
    val cancelRequested: Boolean = false,
) : Parcelable
