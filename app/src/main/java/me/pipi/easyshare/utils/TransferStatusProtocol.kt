package me.pipi.easyshare.utils

enum class RemoteTransferOutcome {
    SUCCESS,
    PARTIAL,
    REJECTED,
    TIMED_OUT,
    FAILED,
}

object TransferStatusProtocol {
    const val TYPE_SUCCESS = 1
    const val TYPE_TERMINATED = 3
    const val REASON_OK = "ok"
    const val REASON_PARTIAL = "partial"
    const val REASON_USER_REFUSED = "user refuse"
    const val REASON_TIMEOUT = "timeout"

    fun classify(type: Int, reason: String): RemoteTransferOutcome = when {
        type == TYPE_SUCCESS && reason.equals(REASON_PARTIAL, ignoreCase = true) -> {
            RemoteTransferOutcome.PARTIAL
        }
        type == TYPE_SUCCESS -> RemoteTransferOutcome.SUCCESS
        type == TYPE_TERMINATED && reason.equals(REASON_USER_REFUSED, ignoreCase = true) -> {
            RemoteTransferOutcome.REJECTED
        }
        type == TYPE_TERMINATED && reason.equals(REASON_TIMEOUT, ignoreCase = true) -> {
            RemoteTransferOutcome.TIMED_OUT
        }
        else -> RemoteTransferOutcome.FAILED
    }
}
