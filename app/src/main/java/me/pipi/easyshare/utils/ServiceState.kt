package me.pipi.easyshare.utils

import android.content.Intent
import me.pipi.easyshare.BuildConfig

object ServiceState {
    const val ACTION_QUERY_RECEIVER_STATE = "${BuildConfig.APPLICATION_ID}.QUERY_RECEIVER_STATE"
    const val ACTION_UPDATE_RECEIVER_STATE = "${BuildConfig.APPLICATION_ID}.UPDATE_RECEIVER_STATE"
    const val ACTION_STOP_SERVICE = "${BuildConfig.APPLICATION_ID}.STOP_SERVICE"
    const val ACTION_BUSY_CHANGED = "${BuildConfig.APPLICATION_ID}.BUSY_CHANGED"

    fun getQueryIntent() = Intent(ACTION_QUERY_RECEIVER_STATE).setPackage(BuildConfig.APPLICATION_ID)

    fun getUpdateIntent(
        isRunning: Boolean,
        progress: Float = 0f,
        progressText: String = "",
        isFinishing: Boolean = false
    ) = Intent(ACTION_UPDATE_RECEIVER_STATE).apply {
        setPackage(BuildConfig.APPLICATION_ID)
        putExtra("isRunning", isRunning)
        putExtra("progress", progress)
        putExtra("progressText", progressText)
        putExtra("isFinishing", isFinishing)
    }

    fun getStopIntent() = Intent(ACTION_STOP_SERVICE).setPackage(BuildConfig.APPLICATION_ID)

    fun getBusyChangedIntent(busy: Boolean) = Intent(ACTION_BUSY_CHANGED).apply {
        setPackage(BuildConfig.APPLICATION_ID)
        putExtra("busy", busy)
    }
}
