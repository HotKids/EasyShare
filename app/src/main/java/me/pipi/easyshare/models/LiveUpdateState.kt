package me.pipi.easyshare.models

import android.app.PendingIntent

enum class LiveUpdatePriority(val value: Int) {
    IDLE(0),
    STANDBY(5),
    CRITICAL(10)
}

data class LiveUpdateState(
    val title: String = "",
    val content: String = "",
    val subText: String? = null,
    val progress: Int = -1, // -1 for no progress bar
    val indeterminate: Boolean = false,
    val shortCriticalText: String? = null,
    val priority: LiveUpdatePriority = LiveUpdatePriority.IDLE,
    val cancelIntent: PendingIntent? = null,
    val cancelLabel: String? = null,
    val acceptIntent: PendingIntent? = null,
    val rejectIntent: PendingIntent? = null,
    val contentIntent: PendingIntent? = null,
    val channelId: String? = null,
    val smallIcon: Int? = null,
    val ongoing: Boolean = true,
    val silent: Boolean = true,
    val alertOnlyOnce: Boolean = true,
    val whenTime: Long = 0,
    val usesChronometer: Boolean = false,
    val chronometerCountDown: Boolean = false
) {
    companion object {
        val IDLE = LiveUpdateState()
    }
}
