package me.pipi.easyshare.utils

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.annotation.RequiresPermission
import androidx.annotation.StringRes
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.serialization.Serializable
import me.pipi.easyshare.MainActivity
import me.pipi.easyshare.R
import me.pipi.easyshare.models.LiveUpdateState
import java.util.concurrent.ConcurrentHashMap

@Serializable
enum class LiveStage {
    INIT,
    PREPARING,
    REQUESTED,
    HANDSHAKE,
    WAITING_AUTH,
    TRANSFERRING,
    FINALIZING,
    COMPLETED;

    @StringRes
    fun titleResource(sending: Boolean): Int = when (this) {
        INIT, PREPARING -> if (sending) R.string.noti_connecting else R.string.preparing_receive
        HANDSHAKE -> R.string.noti_connecting
        REQUESTED, WAITING_AUTH -> if (sending) R.string.noti_connecting else R.string.auth_waiting
        TRANSFERRING -> if (sending) R.string.sending else R.string.receiving
        FINALIZING -> if (sending) R.string.finishing_send else R.string.finishing_receive
        COMPLETED -> if (sending) R.string.send_ok else R.string.recv_ok
    }

    fun usesSingleLineFileCopy(isText: Boolean): Boolean = !isText && when (this) {
        INIT, PREPARING, REQUESTED, HANDSHAKE, WAITING_AUTH, TRANSFERRING -> true
        FINALIZING, COMPLETED -> false
    }

    fun notificationContent(currentFile: String?, attachmentSummary: String, completionSummary: String): String =
        when (this) {
            TRANSFERRING -> currentFile ?: attachmentSummary
            COMPLETED -> completionSummary
            else -> attachmentSummary
        }

    fun notificationProgress(actualProgress: Int): Int =
        if (this == TRANSFERRING) actualProgress.coerceIn(0, 99) else -1

    fun hasIndeterminateProgress(userInitiated: Boolean): Boolean =
        userInitiated && when (this) {
            INIT, PREPARING, HANDSHAKE, FINALIZING -> true
            else -> false
        }

    fun requestsPromotion(userInitiated: Boolean): Boolean =
        this != COMPLETED && (userInitiated || this == WAITING_AUTH)
}

internal class LiveUpdatePromotionPolicy {
    private val dismissedTaskKeys = ConcurrentHashMap.newKeySet<String>()

    fun shouldPromote(state: LiveUpdateState): Boolean =
        state.ongoing && state.promoted && !state.taskKey.isNullOrBlank() &&
            state.taskKey !in dismissedTaskKeys

    fun dismiss(taskKey: String) {
        if (taskKey.isNotBlank()) dismissedTaskKeys += taskKey
    }

    fun release(taskKey: String) {
        dismissedTaskKeys -= taskKey
    }
}

class LiveUpdateDismissedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != NotificationUtils.ACTION_LIVE_UPDATE_DISMISSED) return
        intent.getStringExtra(NotificationUtils.EXTRA_TASK_KEY)?.let(NotificationUtils::dismissLiveUpdate)
    }
}

object NotificationUtils {
    const val RECEIVER_FG_CHAN_ID = "RECEIVER_READY_V4"
    private const val LEGACY_RECEIVER_FG_CHAN_ID = "RECEIVER_READY_V3"
    const val SENDER_CHAN_ID = "SENDER_LIVE"
    const val RECEIVER_CHAN_ID = "RECEIVER_LIVE"
    const val OTHER_CHAN_ID = "OTHER"

    const val ID_RECEIVER_READY = 1
    internal const val ACTION_LIVE_UPDATE_DISMISSED = "me.pipi.easyshare.action.LIVE_UPDATE_DISMISSED"
    internal const val EXTRA_TASK_KEY = "liveUpdateTaskKey"
    private val promotionPolicy = LiveUpdatePromotionPolicy()

    fun taskKey(direction: String, taskId: Int): String = "$direction:$taskId"

    internal fun availableTransferNotificationId(occupiedIds: Set<Int>): Int {
        var id = ID_RECEIVER_READY + 1
        while (id in occupiedIds) id++
        return id
    }

    fun newTransferNotificationId(context: Context): Int = availableTransferNotificationId(
        context.getSystemService(NotificationManager::class.java).activeNotifications.mapTo(mutableSetOf()) { it.id },
    )

    internal fun peerIconResource(state: LiveUpdateState): Int? = when (state.channelId) {
        SENDER_CHAN_ID, RECEIVER_CHAN_ID -> DeviceUtils.knownDeviceIconById(state.peerBrandId)
        else -> null
    }

    internal fun canPublishTransferNotification(taskId: Int, ownerTaskId: Int?, terminalStarted: Boolean): Boolean =
        taskId == ownerTaskId && !terminalStarted

    internal fun dismissLiveUpdate(taskKey: String) {
        // Dismissal changes this task's presentation, never its transfer or decision.
        promotionPolicy.dismiss(taskKey)
        Log.i("TransferNotification", "event=dismiss direction=" +
            if (taskKey.startsWith("receive:")) "receive" else "send")
    }

    // Release only after the terminal job and its notification effects have finished.
    fun releaseTask(direction: String, taskId: Int) {
        promotionPolicy.release(taskKey(direction, taskId))
    }

    fun createChannels(context: Context) {
        val manager = NotificationManagerCompat.from(context)

        val channels = mutableListOf(
            NotificationChannelCompat.Builder(
                SENDER_CHAN_ID,
                NotificationManagerCompat.IMPORTANCE_HIGH
            ).setName(context.getString(R.string.noti_chan_sending)).build(),
            NotificationChannelCompat.Builder(
                RECEIVER_CHAN_ID,
                NotificationManagerCompat.IMPORTANCE_HIGH
            ).setName(context.getString(R.string.noti_chan_receiving)).build(),
            NotificationChannelCompat.Builder(
                OTHER_CHAN_ID,
                NotificationManagerCompat.IMPORTANCE_DEFAULT
            ).setName(context.getString(R.string.noti_chan_other)).build(),
        )

        if (readyChannelId(context) == RECEIVER_FG_CHAN_ID) {
            channels += NotificationChannelCompat.Builder(
                RECEIVER_FG_CHAN_ID,
                NotificationManagerCompat.IMPORTANCE_LOW,
            ).setName(context.getString(R.string.noti_chan_receiver_persistent)).build()
        }

        manager.createNotificationChannelsCompat(channels)
    }

    fun readyChannelId(context: Context): String = readyChannelId(
        NotificationManagerCompat.from(context).getNotificationChannel(LEGACY_RECEIVER_FG_CHAN_ID) != null,
    )

    // A new channel must not bypass an existing user's disabled or customized channel.
    internal fun readyChannelId(hasLegacyChannel: Boolean): String =
        if (hasLegacyChannel) LEGACY_RECEIVER_FG_CHAN_ID else RECEIVER_FG_CHAN_ID

    fun buildNotificationFromState(context: Context, state: LiveUpdateState): Notification {
        val channelId = state.channelId ?: readyChannelId(context)
        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(state.smallIcon ?: R.drawable.ic_sync_alt)
            .setContentTitle(state.title)
            .setContentText(state.content)
            .setSubText(state.subText)
            .setContentIntent(state.contentIntent ?: if (channelId == RECEIVER_CHAN_ID) null else mainContentIntent(context))
            .setPriority(if (state.silent) NotificationCompat.PRIORITY_DEFAULT else NotificationCompat.PRIORITY_MAX)
            .setOnlyAlertOnce(state.alertOnlyOnce)
            .setSilent(state.silent)
            .setOngoing(state.ongoing)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        if (channelId == SENDER_CHAN_ID || channelId == RECEIVER_CHAN_ID) {
            setTransferCopy(context, builder, state.title, state.content)
        }

        peerIconResource(state)?.let { builder.setLargeIcon(Icon.createWithResource(context, it)) }

        if (promotionPolicy.shouldPromote(state)) {
            val taskKey = requireNotNull(state.taskKey)
            builder.setRequestPromotedOngoing(true)
            builder.setLocalOnly(true)
            builder.setDeleteIntent(
                PendingIntent.getBroadcast(
                    context,
                    0,
                    Intent(context, LiveUpdateDismissedReceiver::class.java).apply {
                        action = ACTION_LIVE_UPDATE_DISMISSED
                        data = Uri.Builder().scheme("easyshare").authority("live-dismissal")
                            .appendPath(taskKey).build()
                        putExtra(EXTRA_TASK_KEY, taskKey)
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        }

        if (state.progress >= 0) {
            builder.setProgress(100, state.progress.coerceIn(0, 99), false)
            builder.setCategory(NotificationCompat.CATEGORY_PROGRESS)
        } else if (state.indeterminate) {
            builder.setProgress(100, 0, true)
            builder.setCategory(NotificationCompat.CATEGORY_PROGRESS)
        }

        state.shortCriticalText?.let {
            builder.setShortCriticalText(it)
        }

        if (state.usesChronometer) {
            builder.setWhen(state.whenTime)
            builder.setUsesChronometer(true)
            builder.setChronometerCountDown(state.chronometerCountDown)
        }

        state.cancelIntent?.let {
            builder.addAction(R.drawable.ic_close, context.getString(R.string.cancel), it)
        }

        state.rejectIntent?.let {
            builder.addAction(R.drawable.ic_close, context.getString(R.string.reject), it)
        }

        state.acceptIntent?.let {
            builder.addAction(R.drawable.ic_done, context.getString(R.string.accept), it)
        }

        return builder.build()
    }

    fun setTransferCopy(
        context: Context,
        builder: NotificationCompat.Builder,
        title: String,
        content: String? = null,
    ): NotificationCompat.Builder {
        val body = if (content.isNullOrBlank()) title else "$title\n$content"
        // System templates discard size spans; transfer details use the native body style.
        return builder.setContentTitle(context.getString(R.string.app_name))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
    }

    fun mainContentIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
        ),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun getCurrentLiveNotification(context: Context): Notification {
        return buildNotificationFromState(context, LiveUpdateCoordinator.state.value)
    }

    fun showBusyToast(context: Context) {
        Toast.makeText(context, R.string.app_busy_toast, Toast.LENGTH_LONG).show()
    }

    fun showBluetoothToast(context: Context) {
        Toast.makeText(context, R.string.bluetooth_disabled, Toast.LENGTH_LONG).show()
    }

    fun showWifiToast(context: Context) {
        Toast.makeText(context, R.string.wifi_disabled, Toast.LENGTH_LONG).show()
    }
}
