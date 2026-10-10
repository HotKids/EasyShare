package me.pipi.easyshare.services

internal class ReceiverReadiness {
    private var advertisingStarted = false
    private var serviceAdded = false
    var isStopped = false
        private set
    var isReady = false
        private set

    fun advertisingStarted(): Boolean {
        advertisingStarted = true
        return readyOnce()
    }

    fun serviceAdded(): Boolean {
        serviceAdded = true
        return readyOnce()
    }

    fun stop() {
        isStopped = true
        isReady = false
    }

    private fun readyOnce(): Boolean {
        if (isStopped || isReady || !advertisingStarted || !serviceAdded) return false
        isReady = true
        return true
    }
}
