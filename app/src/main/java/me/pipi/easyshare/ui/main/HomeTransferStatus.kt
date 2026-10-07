package me.pipi.easyshare.ui.main

import androidx.annotation.StringRes
import me.pipi.easyshare.R
import me.pipi.easyshare.models.IncomingTransferUiState
import me.pipi.easyshare.models.IncomingTransferUiStatus
import me.pipi.easyshare.models.LiveUpdateState
import me.pipi.easyshare.models.TransferUiState
import me.pipi.easyshare.models.TransferUiStatus
import me.pipi.easyshare.utils.LiveStage
import me.pipi.easyshare.utils.NotificationUtils

data class HomeTransferStatus(@get:StringRes val textRes: Int, val progress: Int? = null)

internal fun homeTransferStatus(
    live: LiveUpdateState,
    incoming: Map<Int, IncomingTransferUiState>,
    outgoing: Map<String, TransferUiState>,
): HomeTransferStatus? {
    if (!live.ongoing || live.taskKey.isNullOrBlank()) return null
    var stage = live.stage ?: return null
    var isText = live.isText
    var actualProgress = live.progress
    if (stage == LiveStage.COMPLETED) return null
    val sending = when (live.channelId) {
        NotificationUtils.SENDER_CHAN_ID -> true
        NotificationUtils.RECEIVER_CHAN_ID -> false
        else -> return null
    }
    if (sending) {
        if (!live.taskKey.startsWith("send:")) return null
        val task = outgoing.values.firstOrNull { NotificationUtils.taskKey("send", it.taskId) == live.taskKey }
        if (task != null && task.status != TransferUiStatus.WAITING && task.status != TransferUiStatus.SENDING) return null
        if (task != null) {
            stage = task.stage
            actualProgress = task.progress
        }
    } else {
        if (!live.taskKey.startsWith("receive:")) return null
        val task = incoming.values.firstOrNull { NotificationUtils.taskKey("receive", it.taskId) == live.taskKey }
        if (task != null && task.status != IncomingTransferUiStatus.REQUESTED &&
            task.status != IncomingTransferUiStatus.RECEIVING) return null
        if (task != null) {
            stage = if (task.status == IncomingTransferUiStatus.REQUESTED) LiveStage.WAITING_AUTH else task.stage
            actualProgress = task.progress
            isText = task.isText
        }
    }
    val progress = actualProgress.takeIf { stage == LiveStage.TRANSFERRING && !isText && it >= 0 }
        ?.coerceIn(0, 99)
    val textRes = if (sending) {
        when (stage) {
            LiveStage.INIT, LiveStage.PREPARING, LiveStage.HANDSHAKE -> R.string.home_status_preparing_send
            LiveStage.REQUESTED, LiveStage.WAITING_AUTH -> R.string.home_status_waiting_peer
            LiveStage.TRANSFERRING -> if (progress != null) R.string.home_status_sending_progress else R.string.home_status_sending
            LiveStage.FINALIZING -> R.string.home_status_waiting_result
            LiveStage.COMPLETED -> return null
        }
    } else {
        when (stage) {
            LiveStage.INIT, LiveStage.PREPARING, LiveStage.HANDSHAKE -> R.string.home_status_preparing_receive
            LiveStage.REQUESTED, LiveStage.WAITING_AUTH -> R.string.home_status_waiting_consent
            LiveStage.TRANSFERRING -> if (progress != null) R.string.home_status_receiving_progress else R.string.home_status_receiving
            LiveStage.FINALIZING -> R.string.home_status_saving
            LiveStage.COMPLETED -> return null
        }
    }
    return HomeTransferStatus(textRes, progress)
}
