package me.pipi.easyshare.utils

internal class ReceiverPolicy {
    enum class Action { NONE, START, STOP }
    private enum class State { STOPPED, STARTING, RUNNING, STOPPING }

    private var state = State.STOPPED
    private var restartPending = false

    // A stop must not close GATT between queuing the receiver and replying to its sender.
    @Synchronized
    fun withGattResponse(response: () -> Unit): Boolean {
        if (state != State.STARTING && state != State.RUNNING) return false
        response()
        return true
    }

    @Synchronized
    fun requestRestart() {
        restartPending = state != State.STOPPED
    }

    @Synchronized
    fun reconcile(
        visible: Boolean,
        backgroundEnabled: Boolean,
        busy: Boolean,
        available: Boolean,
    ): Action {
        val wanted = (visible || backgroundEnabled) && available
        return when {
            state == State.STOPPED && wanted && !busy -> {
                state = State.STARTING
                Action.START
            }
            (state == State.STARTING || state == State.RUNNING) &&
                (!wanted || restartPending) && !busy -> {
                state = State.STOPPING
                Action.STOP
            }
            else -> Action.NONE
        }
    }

    @Synchronized
    fun serviceStarted() {
        if (state != State.STOPPING) state = State.RUNNING
    }

    @Synchronized
    fun serviceStopped(): Boolean {
        val expectedStop = state == State.STOPPING
        state = State.STOPPED
        restartPending = false
        return expectedStop
    }
}
