package me.pipi.easyshare.utils

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.pipi.easyshare.models.TransferUiState

object TransferUiCoordinator {
    private val _states = MutableStateFlow<Map<String, TransferUiState>>(emptyMap())
    val states: StateFlow<Map<String, TransferUiState>> = _states.asStateFlow()

    @Synchronized
    fun publish(state: TransferUiState) {
        _states.value = _states.value + (state.deviceId to state)
    }

    @Synchronized
    fun clear() {
        _states.value = emptyMap()
    }
}
