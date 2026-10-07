package me.pipi.easyshare.utils

import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import me.pipi.easyshare.models.IncomingTransferUiState
import me.pipi.easyshare.models.IncomingTransferUiStatus
import me.pipi.easyshare.models.ReceivedFile

enum class IncomingRequestDecision {
    ACCEPTED,
    REJECTED,
    TIMED_OUT,
}

object IncomingTransferUiCoordinator {
    private class PendingRequest(
        val expiresAtMillis: Long,
        val timeoutMessage: String,
        val rejectMessage: String?,
        val cancelGuardMillis: Long,
    ) {
        val result = CompletableDeferred<IncomingRequestDecision>()
        var decision: IncomingRequestDecision? = null
        var arrivalAlerted = false
    }

    private val _states = MutableStateFlow<Map<Int, IncomingTransferUiState>>(emptyMap())
    val states: StateFlow<Map<Int, IncomingTransferUiState>> = _states.asStateFlow()
    private val requests = mutableMapOf<Int, PendingRequest>()
    private val visibleSheets = mutableMapOf<Int, MutableSet<Any>>()

    @Synchronized
    fun beginRequest(
        state: IncomingTransferUiState,
        expiresAtMillis: Long,
        timeoutMessage: String,
        rejectMessage: String? = null,
        cancelGuardMillis: Long = 1_500L,
    ): Boolean {
        require(state.status == IncomingTransferUiStatus.REQUESTED)
        require(expiresAtMillis >= 0L && cancelGuardMillis >= 0L)
        if (requests.containsKey(state.taskId) ||
            _states.value[state.taskId]?.let { it.status != IncomingTransferUiStatus.REQUESTED } == true
        ) return false
        // Result PendingIntents own terminal snapshots; the live owner must not retain every file list forever.
        _states.value = _states.value.filterValues { !it.status.isTerminal() }
        visibleSheets.keys.retainAll(_states.value.keys)
        requests[state.taskId] = PendingRequest(expiresAtMillis, timeoutMessage, rejectMessage, cancelGuardMillis)
        // A launch attempt is not visibility: blocked background launches have no Activity acknowledgement.
        publish(state.copy(requestExpiresAtMillis = expiresAtMillis, cancelEnabledAtMillis = 0L, monitorInBackground = true))
        return true
    }

    suspend fun awaitDecision(
        taskId: Int,
        nowMillis: Long = SystemClock.elapsedRealtime(),
    ): IncomingRequestDecision {
        val request = synchronized(this) { requests[taskId] ?: error("No receive request for task $taskId") }
        if (nowMillis >= request.expiresAtMillis) {
            decide(taskId, IncomingRequestDecision.TIMED_OUT, nowMillis)
        } else {
            withTimeoutOrNull(request.expiresAtMillis - nowMillis) { request.result.await() }?.let { return it }
            decide(taskId, IncomingRequestDecision.TIMED_OUT, request.expiresAtMillis)
        }
        check(request.result.isCompleted) { "Receive request is no longer pending" }
        return request.result.await()
    }

    @Synchronized
    fun decide(
        taskId: Int,
        decision: IncomingRequestDecision,
        nowMillis: Long = SystemClock.elapsedRealtime(),
    ): Boolean {
        val request = requests[taskId] ?: return false
        if (request.decision != null) return false
        val current = _states.value[taskId] ?: return false
        if (current.status != IncomingTransferUiStatus.REQUESTED) return false
        val outcome = when {
            nowMillis >= request.expiresAtMillis -> IncomingRequestDecision.TIMED_OUT
            decision == IncomingRequestDecision.TIMED_OUT -> return false
            else -> decision
        }
        val next = when (outcome) {
            IncomingRequestDecision.ACCEPTED -> current.copy(
                status = IncomingTransferUiStatus.RECEIVING,
                stage = LiveStage.PREPARING,
                progress = 0,
                errorMessage = null,
                cancelEnabledAtMillis = if (request.cancelGuardMillis > Long.MAX_VALUE - nowMillis) {
                    Long.MAX_VALUE
                } else {
                    nowMillis + request.cancelGuardMillis
                },
            )
            IncomingRequestDecision.REJECTED -> current.copy(
                status = IncomingTransferUiStatus.CANCELED,
                errorMessage = request.rejectMessage,
            )
            IncomingRequestDecision.TIMED_OUT -> current.copy(
                status = IncomingTransferUiStatus.FAILED,
                errorMessage = request.timeoutMessage,
            )
        }
        request.decision = outcome
        publish(next)
        request.result.complete(outcome)
        return outcome == decision
    }

    @Synchronized
    fun publish(state: IncomingTransferUiState) {
        _states.value = _states.value + (state.taskId to state)
    }

    @Synchronized
    fun markReceiving(taskId: Int, progress: Int = 0, fileName: String? = null, stage: LiveStage = LiveStage.TRANSFERRING) {
        val current = _states.value[taskId] ?: return
        if (current.status.isTerminal() || current.cancelRequested) return
        if (requests[taskId]?.let { it.decision != IncomingRequestDecision.ACCEPTED } == true) return
        publish(
            current.copy(
                status = IncomingTransferUiStatus.RECEIVING,
                progress = progress.coerceIn(0, 99),
                currentFileName = fileName?.takeIf { it.isNotBlank() } ?: current.currentFileName,
                errorMessage = null,
                stage = stage,
            ),
        )
    }

    @Synchronized
    fun complete(taskId: Int, files: List<ReceivedFile>, partial: Boolean, receivedFilesToken: String? = null) {
        val current = _states.value[taskId] ?: return
        if (current.status.isTerminal()) return
        if (requests[taskId]?.let { it.decision != IncomingRequestDecision.ACCEPTED } == true) return
        publish(
            current.copy(
                status = if (partial) {
                    IncomingTransferUiStatus.PARTIAL
                } else {
                    IncomingTransferUiStatus.SUCCESS
                },
                progress = 100,
                receivedFiles = files,
                receivedFilesToken = receivedFilesToken,
                errorMessage = null,
                stage = LiveStage.COMPLETED,
            ),
        )
    }

    @Synchronized
    fun fail(taskId: Int, message: String, canceled: Boolean = false) {
        val current = _states.value[taskId] ?: return
        if (current.status.isTerminal()) return
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
        requests[taskId]?.takeIf { it.decision == null }?.result?.cancel(CancellationException(message))
    }

    fun get(taskId: Int): IncomingTransferUiState? = _states.value[taskId]

    fun getActive(): IncomingTransferUiState? = _states.value.values.lastOrNull { !it.status.isTerminal() }

    @Synchronized
    fun takeRequestAlert(taskId: Int, nowMillis: Long = SystemClock.elapsedRealtime()): Boolean {
        val request = requests[taskId] ?: return false
        val state = _states.value[taskId] ?: return false
        if (request.arrivalAlerted || state.status != IncomingTransferUiStatus.REQUESTED ||
            nowMillis >= request.expiresAtMillis) return false
        // Visibility changes must not replay the request's arrival alert.
        request.arrivalAlerted = true
        return state.monitorInBackground
    }

    @Synchronized
    fun cancelReceiving(taskId: Int, message: String, nowMillis: Long = SystemClock.elapsedRealtime()): Boolean {
        val current = _states.value[taskId] ?: return false
        if (current.status != IncomingTransferUiStatus.RECEIVING || current.cancelRequested ||
            nowMillis < current.cancelEnabledAtMillis) return false
        // Storage cleanup owns the result: a cancel can still leave fully saved files.
        publish(current.copy(cancelRequested = true, errorMessage = message))
        return true
    }

    @Synchronized
    fun hide(taskId: Int, owner: Any): Boolean {
        visibleSheets[taskId]?.let { owners ->
            owners.remove(owner)
            if (owners.isEmpty()) visibleSheets.remove(taskId)
        }
        return setBackgroundMonitoring(taskId, visibleSheets[taskId].isNullOrEmpty())
    }

    @Synchronized
    fun show(taskId: Int, owner: Any): Boolean {
        if (!_states.value.containsKey(taskId)) return false
        visibleSheets.getOrPut(taskId) { mutableSetOf() }.add(owner)
        return setBackgroundMonitoring(taskId, false)
    }

    private fun setBackgroundMonitoring(taskId: Int, enabled: Boolean): Boolean {
        val current = _states.value[taskId] ?: return false
        if (current.monitorInBackground != enabled) publish(current.copy(monitorInBackground = enabled))
        return true
    }

    @Synchronized
    fun releaseDecision(taskId: Int) {
        requests.remove(taskId)?.result?.cancel()
    }

    @Synchronized
    fun clear(taskId: Int) {
        releaseDecision(taskId)
        visibleSheets.remove(taskId)
        _states.value = _states.value - taskId
    }

    @Synchronized
    fun clearAll() {
        requests.values.forEach { it.result.cancel() }
        requests.clear()
        visibleSheets.clear()
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
