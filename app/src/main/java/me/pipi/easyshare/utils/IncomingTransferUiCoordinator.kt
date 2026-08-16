package me.pipi.easyshare.utils

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.pipi.easyshare.models.IncomingTransferUiState
import me.pipi.easyshare.models.IncomingTransferUiStatus
import me.pipi.easyshare.models.ReceivedFile

object IncomingTransferUiCoordinator {
    private val _states = MutableStateFlow<Map<Int, IncomingTransferUiState>>(emptyMap())
    val states: StateFlow<Map<Int, IncomingTransferUiState>> = _states.asStateFlow()

    @Synchronized
    fun publish(state: IncomingTransferUiState) {
        _states.value = _states.value + (state.taskId to state)
    }

    @Synchronized
    fun markReceiving(taskId: Int, progress: Int = 0, fileName: String? = null) {
        val current = _states.value[taskId] ?: return
        if (current.status.isTerminal()) return
        publish(
            current.copy(
                status = IncomingTransferUiStatus.RECEIVING,
                progress = progress.coerceIn(0, 100),
                fileName = fileName?.takeIf { it.isNotBlank() } ?: current.fileName,
                errorMessage = null,
            ),
        )
    }

    @Synchronized
    fun complete(taskId: Int, files: List<ReceivedFile>, partial: Boolean) {
        val current = _states.value[taskId] ?: return
        if (current.status.isTerminal()) return
        publish(
            current.copy(
                status = if (partial) {
                    IncomingTransferUiStatus.PARTIAL
                } else {
                    IncomingTransferUiStatus.SUCCESS
                },
                progress = 100,
                receivedFiles = files,
                errorMessage = null,
            ),
        )
    }

    @Synchronized
    fun fail(taskId: Int, message: String, canceled: Boolean = false) {
        val current = _states.value[taskId] ?: return
        if (current.status == IncomingTransferUiStatus.REQUESTED || current.status.isTerminal()) return
        publish(
            current.copy(
                status = if (canceled) {
                    IncomingTransferUiStatus.CANCELED
                } else {
                    IncomingTransferUiStatus.FAILED
                },
                errorMessage = message,
            ),
        )
    }

    fun get(taskId: Int): IncomingTransferUiState? = _states.value[taskId]

    @Synchronized
    fun clear(taskId: Int) {
        _states.value = _states.value - taskId
    }

    @Synchronized
    fun clearAll() {
        _states.value = emptyMap()
    }

    private fun IncomingTransferUiStatus.isTerminal(): Boolean = when (this) {
        IncomingTransferUiStatus.SUCCESS,
        IncomingTransferUiStatus.PARTIAL,
        IncomingTransferUiStatus.FAILED,
        IncomingTransferUiStatus.CANCELED -> true
        IncomingTransferUiStatus.REQUESTED,
        IncomingTransferUiStatus.RECEIVING -> false
    }
}
