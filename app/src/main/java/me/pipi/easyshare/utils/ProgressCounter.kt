package me.pipi.easyshare.utils

class ProgressCounter(
    private val totalSize: Long,
    private val nowNanos: () -> Long = System::nanoTime,
    private val callback: (Long, Long) -> Unit,
) {
    private var lastProgressUpdate: Long? = null
    private var lastReportedSize = Long.MIN_VALUE

    fun update(processedSize: Long) {
        report(processedSize, force = false)
    }

    fun complete(processedSize: Long) {
        report(processedSize, force = true)
    }

    private fun report(processedSize: Long, force: Boolean) {
        val now = nowNanos()
        val elapsed = lastProgressUpdate?.let(now::minus)
        if (!force && elapsed != null && elapsed < UPDATE_INTERVAL_NANOS) return
        if (processedSize == lastReportedSize) return

        callback(totalSize, processedSize)
        lastProgressUpdate = now
        lastReportedSize = processedSize
    }

    private companion object {
        const val UPDATE_INTERVAL_NANOS = 1_000_000_000L
    }
}
