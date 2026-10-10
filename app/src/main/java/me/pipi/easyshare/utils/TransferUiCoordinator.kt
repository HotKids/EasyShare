package me.pipi.easyshare.utils

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.pipi.easyshare.models.OutgoingTransferPresentation
import me.pipi.easyshare.models.TransferUiState
import me.pipi.easyshare.models.TransferUiStatus

object TransferUiCoordinator {
    private val _states = MutableStateFlow<Map<String, TransferUiState>>(emptyMap())
    val states: StateFlow<Map<String, TransferUiState>> = _states.asStateFlow()
    private val _activePresentation = MutableStateFlow<OutgoingTransferPresentation?>(null)
    val activePresentation: StateFlow<OutgoingTransferPresentation?> = _activePresentation.asStateFlow()
    private var owner: OutgoingTransferPresentation? = null
    private var cancelRequested = false

    @Synchronized
    fun begin(presentation: OutgoingTransferPresentation): Boolean {
        if (owner != null) return false
        owner = presentation
        cancelRequested = false
        _activePresentation.value = presentation
        _states.value = _states.value + (presentation.deviceId to TransferUiState(
            presentation.taskId, presentation.deviceId, TransferUiStatus.WAITING,
        ))
        return true
    }

    @Synchronized
    fun owns(taskId: Int, deviceId: String): Boolean =
        owner?.let { it.taskId == taskId && it.deviceId == deviceId } == true

    @Synchronized
    fun requestCancel(taskId: Int, deviceId: String): Boolean {
        if (!owns(taskId, deviceId) || _activePresentation.value == null || cancelRequested) return false
        val current = _states.value[deviceId]?.takeIf { it.taskId == taskId } ?: return false
        cancelRequested = true
        // Cleanup still owns the result; expose the accepted request before dispatching it.
        _states.value = _states.value + (deviceId to current.copy(cancelRequested = true))
        return true
    }

    @Synchronized
    fun isCancelRequested(taskId: Int, deviceId: String): Boolean =
        owns(taskId, deviceId) && cancelRequested

    @Synchronized
    fun finish(taskId: Int, deviceId: String): Boolean {
        if (!owns(taskId, deviceId)) return false
        owner = null
        cancelRequested = false
        _activePresentation.value = null
        return true
    }

    @Synchronized
    fun publish(state: TransferUiState): Boolean {
        if (owner != null && !owns(state.taskId, state.deviceId)) return false
        if (owns(state.taskId, state.deviceId) && cancelRequested &&
            (state.status.isActive() || state.status == TransferUiStatus.SUCCESS || state.status == TransferUiStatus.PARTIAL)) return false
        val current = _states.value[state.deviceId]?.takeIf { it.taskId == state.taskId }
        if (current != null) {
            if (!current.status.isActive()) return false
            // Parallel socket callbacks must not rewind an accepted transfer.
            if (state.status.isActive() && state.stage.ordinal < current.stage.ordinal) return false
        }
        _states.value = _states.value + (state.deviceId to state)
        if (owns(state.taskId, state.deviceId) && !state.status.isActive()) {
            _activePresentation.value = null
        }
        return true
    }

    private fun TransferUiStatus.isActive() = this == TransferUiStatus.WAITING || this == TransferUiStatus.SENDING

    @Synchronized
    fun clear() {
        _states.value = emptyMap()
        owner = null
        cancelRequested = false
        _activePresentation.value = null
    }
}
