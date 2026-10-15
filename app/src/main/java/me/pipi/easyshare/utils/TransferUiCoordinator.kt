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
    private val visibleSheetOwners = mutableMapOf<Pair<Int, String>, MutableSet<Any>>()
    private val _sheetVisibility = MutableStateFlow<Set<Pair<Int, String>>>(emptySet())
    val sheetVisibility: StateFlow<Set<Pair<Int, String>>> = _sheetVisibility.asStateFlow()
    private var owner: OutgoingTransferPresentation? = null
    private var cancelRequested = false

    @Synchronized
    fun begin(presentation: OutgoingTransferPresentation): Boolean {
        if (owner != null) return false
        owner = presentation
        cancelRequested = false
        // The initiating sheet registers before starting the service to cover fast transfers.
        visibleSheetOwners.keys.retainAll(setOf(presentation.taskId to presentation.deviceId))
        _sheetVisibility.value = visibleSheetOwners.keys.toSet()
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
    fun isSheetVisible(taskId: Int, deviceId: String): Boolean =
        (taskId to deviceId) in _sheetVisibility.value

    @Synchronized
    fun showSheet(taskId: Int, deviceId: String, sheetOwner: Any): Boolean {
        if (owner != null && !owns(taskId, deviceId)) return false
        val key = taskId to deviceId
        val wasVisible = isSheetVisible(taskId, deviceId)
        visibleSheetOwners.getOrPut(key) { mutableSetOf() }.add(sheetOwner)
        _sheetVisibility.value = visibleSheetOwners.keys.toSet()
        return !wasVisible
    }

    @Synchronized
    fun hideSheet(taskId: Int, deviceId: String, sheetOwner: Any): Boolean {
        val key = taskId to deviceId
        val owners = visibleSheetOwners[key] ?: return false
        if (!owners.remove(sheetOwner) || owners.isNotEmpty()) return false
        visibleSheetOwners.remove(key)
        _sheetVisibility.value = visibleSheetOwners.keys.toSet()
        return true
    }

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
        visibleSheetOwners.remove(taskId to deviceId)
        _sheetVisibility.value = visibleSheetOwners.keys.toSet()
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
        visibleSheetOwners.clear()
        _sheetVisibility.value = emptySet()
    }
}
