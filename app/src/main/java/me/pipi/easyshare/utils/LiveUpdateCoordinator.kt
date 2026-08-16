package me.pipi.easyshare.utils

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.pipi.easyshare.models.LiveUpdateState

object LiveUpdateCoordinator {
    private val _state = MutableStateFlow(LiveUpdateState.IDLE)
    val state: StateFlow<LiveUpdateState> = _state.asStateFlow()

    private val sourceStates = mutableMapOf<String, LiveUpdateState>()

    @Synchronized
    fun publishState(sourceId: String, newState: LiveUpdateState) {
        sourceStates[sourceId] = newState
        refreshGlobalState()
    }

    @Synchronized
    fun clearState(sourceId: String) {
        sourceStates.remove(sourceId)
        refreshGlobalState()
    }

    private fun refreshGlobalState() {
        val highestState = sourceStates.values.maxByOrNull { it.priority.value }
        _state.value = highestState ?: LiveUpdateState.IDLE
    }
}
