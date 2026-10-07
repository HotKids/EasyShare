package me.pipi.easyshare.services

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.net.Uri
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.StatFs
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.text.format.Formatter
import android.util.Log
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.documentfile.provider.DocumentFile
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.jvm.javaio.toInputStream
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import me.pipi.easyshare.AppSettings
import me.pipi.easyshare.BuildConfig
import me.pipi.easyshare.IncomingTransferActivity
import me.pipi.easyshare.MyApplication
import me.pipi.easyshare.R
import me.pipi.easyshare.SessionSecurity
import me.pipi.easyshare.SessionTrustManager
import me.pipi.easyshare.exceptions.CancelledByUserException
import me.pipi.easyshare.exceptions.ExceptionWithMessage
import me.pipi.easyshare.models.LiveUpdatePriority
import me.pipi.easyshare.models.LiveUpdateState
import me.pipi.easyshare.models.IncomingTransferUiState
import me.pipi.easyshare.models.IncomingTransferUiStatus
import me.pipi.easyshare.models.P2pInfo
import me.pipi.easyshare.models.ReceivedFile
import me.pipi.easyshare.models.WebSocketMessage
import me.pipi.easyshare.ui.transfer.TransferCopy
import me.pipi.easyshare.utils.DeviceUtils
import me.pipi.easyshare.utils.BleUtils
import me.pipi.easyshare.utils.ArchiveEntryNames
import me.pipi.easyshare.utils.ArchiveReceiveRecovery
import me.pipi.easyshare.utils.LiveStage
import me.pipi.easyshare.utils.IncomingPeerIdentity
import me.pipi.easyshare.utils.LiveUpdateCoordinator
import me.pipi.easyshare.utils.IncomingTransferUiCoordinator
import me.pipi.easyshare.utils.IncomingRequestDecision
import me.pipi.easyshare.utils.NotificationUtils
import me.pipi.easyshare.utils.ProgressCounter
import me.pipi.easyshare.utils.TAG
import me.pipi.easyshare.utils.TransferLimitException
import me.pipi.easyshare.utils.TransferLimits
import me.pipi.easyshare.utils.ReceivedFilesSnapshot
import me.pipi.easyshare.utils.TransferStatusProtocol
import me.pipi.easyshare.utils.ZipPathValidatorCallback
import me.pipi.easyshare.utils.awaitWithTimeout
import me.pipi.easyshare.utils.checkP2pPermissions
import me.pipi.easyshare.utils.connectSuspend
import me.pipi.easyshare.utils.registerInternalBroadcastReceiver
import me.pipi.easyshare.utils.removeGroupSuspend
import me.pipi.easyshare.utils.requestGroupInfo
import me.pipi.easyshare.utils.sendStatusIgnoreException
import okhttp3.ConnectionPool
import org.json.JSONObject
import java.io.EOFException
import java.io.File
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import javax.net.ssl.SSLContext
import kotlin.math.min
import kotlin.random.Random

class P2pReceiverService : BaseP2pService() {
    private lateinit var notificationManager: NotificationManagerCompat
    private val serviceJob = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Main + serviceJob)
    private var retainTransferNotification = false
    private var transferNotificationId = 0
    private var terminalNotificationStarted = false
    private var taskNotificationJob: Job? = null
    private var lastNotificationStage: LiveStage? = null

    private suspend fun updateStage(
        taskId: Int,
        senderName: String,
        stage: LiveStage,
        progress: Int = 0,
        currentFile: String? = null,
        contentIntent: PendingIntent?,
        requestAccepted: Boolean = false,
        alertUser: Boolean = false,
        peerBrandId: Int? = null,
    ) {
        val presentation = IncomingTransferUiCoordinator.get(taskId)
        val cancelIntent = if (presentation?.cancelRequested != true &&
            stage != LiveStage.COMPLETED && stage != LiveStage.WAITING_AUTH &&
            SystemClock.elapsedRealtime() >= (presentation?.cancelEnabledAtMillis ?: 0L)) {
            PendingIntent.getBroadcast(
                this, taskId,
                Intent(ACTION_CANCEL_RECEIVING).apply {
                    putExtra("taskId", taskId)
                    setPackage(packageName)
                },
                PendingIntent.FLAG_IMMUTABLE
            )
        } else null

        val isText = presentation?.isText == true
        val mainCopyOnly = stage.usesSingleLineFileCopy(isText)
        val attachmentSummary = presentation?.let {
            TransferCopy.itemLabel(this, it.fileName, it.mimeType, it.isText, it.fileCount)
        }.orEmpty()
        val title = when {
            mainCopyOnly && presentation != null && (stage == LiveStage.REQUESTED || stage == LiveStage.WAITING_AUTH) ->
                TransferCopy.request(this, senderName, attachmentSummary)
            mainCopyOnly && presentation != null && stage == LiveStage.TRANSFERRING ->
                TransferCopy.receiving(this, attachmentSummary)
            else -> getString(stage.titleResource(sending = false))
        }.let {
            if (mainCopyOnly && presentation != null) TransferCopy.withSize(this, it, presentation.totalSize, isText)
            else it
        }
        val content = when {
            mainCopyOnly -> ""
            isText && stage == LiveStage.WAITING_AUTH -> getString(R.string.noti_request_desc_text)
            else -> stage.notificationContent(
                currentFile,
                attachmentSummary,
                getString(R.string.noti_receive_complete_body),
            )
        }

        val unconfirmedProgress = progress.coerceIn(0, 99)
        val shortText = when (stage) {
            LiveStage.TRANSFERRING -> "$unconfirmedProgress%"
            LiveStage.INIT, LiveStage.PREPARING -> getString(R.string.stage_prep)
            LiveStage.HANDSHAKE -> getString(R.string.stage_conn)
            LiveStage.REQUESTED, LiveStage.WAITING_AUTH -> getString(R.string.stage_wait)
            LiveStage.FINALIZING -> getString(R.string.stage_fin)
            LiveStage.COMPLETED -> getString(R.string.stage_done)
        }

        val state = LiveUpdateState(
            title = title,
            content = content,
            subText = if (mainCopyOnly) null else getString(R.string.incoming_transfer_from, senderName),
            peerBrandId = presentation?.brandId ?: peerBrandId,
            stage = stage,
            isText = isText,
            progress = if (requestAccepted) stage.notificationProgress(unconfirmedProgress) else -1,
            indeterminate = stage.hasIndeterminateProgress(userInitiated = requestAccepted),
            shortCriticalText = shortText,
            priority = LiveUpdatePriority.CRITICAL,
            ongoing = stage != LiveStage.COMPLETED,
            promoted = stage.requestsPromotion(userInitiated = requestAccepted),
            taskKey = NotificationUtils.taskKey("receive", taskId),
            cancelIntent = cancelIntent,
            acceptIntent = if (stage == LiveStage.WAITING_AUTH) {
                PendingIntent.getBroadcast(
                    this, taskId,
                    getResponseIntent(this, taskId, accepted = true),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            } else null,
            rejectIntent = if (stage == LiveStage.WAITING_AUTH) {
                PendingIntent.getBroadcast(
                    this, taskId,
                    Intent(ACTION_DISMISSED).apply {
                        putExtra("taskId", taskId)
                        setPackage(packageName)
                    },
                    PendingIntent.FLAG_IMMUTABLE
                )
            } else null,
            contentIntent = if (presentation?.isText == true) viewerPendingIntent(presentation) else contentIntent,
            channelId = NotificationUtils.RECEIVER_CHAN_ID,
            smallIcon = R.drawable.ic_arrow_circle_down,
            silent = !alertUser,
            alertOnlyOnce = !alertUser,
        )
        withContext(Dispatchers.Main.immediate) {
            if (!NotificationUtils.canPublishTransferNotification(taskId, currentTaskId, terminalNotificationStarted)) return@withContext
            val latest = IncomingTransferUiCoordinator.get(taskId)
            if (latest != null && latest.status != IncomingTransferUiStatus.REQUESTED &&
                latest.status != IncomingTransferUiStatus.RECEIVING) return@withContext
            LiveUpdateCoordinator.publishState("RECEIVER", state)
            updateForeground()
        }
    }

    private fun updateForeground() {
        val state = LiveUpdateCoordinator.state.value
        val notification = NotificationUtils.buildNotificationFromState(this, state)
        if (Build.VERSION.SDK_INT >= 37 && state.stage != null && state.stage != lastNotificationStage) {
            lastNotificationStage = state.stage
            // Keep promotion diagnostics free of peer names, file names, and task payloads.
            Log.i("TransferNotification", "direction=receive id=$transferNotificationId stage=${state.stage}" +
                " ongoing=${notification.flags and Notification.FLAG_ONGOING_EVENT != 0}" +
                " requested=${notification.isRequestPromotedOngoing}" +
                " eligible=${notification.hasPromotableCharacteristics()}" +
                " allowed=${getSystemService(android.app.NotificationManager::class.java).canPostPromotedNotifications()}" +
                " silent=${state.silent} largeIcon=${notification.getLargeIcon() != null}")
        }
        startForeground(
            transferNotificationId,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    }

    private fun viewerPendingIntent(state: IncomingTransferUiState, manualResult: Boolean = false): PendingIntent =
        PendingIntent.getActivity(
            this, state.taskId,
            IncomingTransferActivity.createIntent(this, state, manualResult = manualResult),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun receiveDirectoryPendingIntent(taskId: Int, customDir: DocumentFile?): PendingIntent {
        return PendingIntent.getActivity(
            this, taskId, receivedDirectoryIntent(this, customDir),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private suspend fun observeRequest(taskId: Int, contentIntent: PendingIntent?) {
        taskNotificationJob?.cancel()
        taskNotificationJob = CoroutineScope(currentCoroutineContext()).launch(Dispatchers.Main.immediate) {
            var guardJob: Job? = null
            suspend fun display(state: IncomingTransferUiState) {
                when (state.status) {
                    IncomingTransferUiStatus.REQUESTED -> updateStage(
                        taskId, state.senderName, LiveStage.WAITING_AUTH,
                        contentIntent = contentIntent,
                        alertUser = IncomingTransferUiCoordinator.takeRequestAlert(taskId),
                    )
                    IncomingTransferUiStatus.RECEIVING -> updateStage(
                        taskId, state.senderName, state.stage, state.progress, state.currentFileName,
                        contentIntent = contentIntent, requestAccepted = true,
                    )
                    else -> Unit
                }
            }
            IncomingTransferUiCoordinator.states.map { it[taskId] }.distinctUntilChanged().collect { state ->
                guardJob?.cancel()
                if (state == null) return@collect
                display(state)
                val guardRemaining = state.cancelEnabledAtMillis - SystemClock.elapsedRealtime()
                if (state.status == IncomingTransferUiStatus.RECEIVING && guardRemaining > 0L) {
                    guardJob = launch {
                        delay(guardRemaining)
                        IncomingTransferUiCoordinator.get(taskId)?.let { display(it) }
                    }
                }
            }
        }
    }

    private suspend fun showTransferResult(taskId: Int, notification: Notification) = withContext(Dispatchers.Main + NonCancellable) {
        if (currentTaskId != taskId || terminalNotificationStarted) return@withContext
        terminalNotificationStarted = true
        taskNotificationJob?.cancel()
        if (transferNotificationId == 0) return@withContext
        retainTransferNotification = try {
            // Progress and result must use the same AMS queue before detaching the FGS flag.
            startForeground(transferNotificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            stopForeground(android.app.Service.STOP_FOREGROUND_DETACH)
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "Notification permission unavailable for receive result", e)
            false
        } catch (e: Exception) {
            Log.w(TAG, "Unable to show receive result notification", e)
            false
        }
    }

    private fun removeTransferNotification() {
        stopForeground(android.app.Service.STOP_FOREGROUND_REMOVE)
        if (transferNotificationId != 0) notificationManager.cancel(transferNotificationId)
        retainTransferNotification = false
    }

    private fun isExpectedCompletedSessionClose(error: Throwable): Boolean {
        if (!retainTransferNotification) return false
        return generateSequence(error) { it.cause }.any {
            it is EOFException || it is ClosedReceiveChannelException
        }
    }

    private val internalReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_CANCEL_RECEIVING -> {
                    cancel(intent.getIntExtra("taskId", -1))
                }
                ACTION_ACCEPTED -> IncomingTransferUiCoordinator.decide(
                    intent.getIntExtra("taskId", -1), IncomingRequestDecision.ACCEPTED,
                )
                ACTION_DISMISSED -> IncomingTransferUiCoordinator.decide(
                    intent.getIntExtra("taskId", -1), IncomingRequestDecision.REJECTED,
                )
                ACTION_TIMED_OUT -> IncomingTransferUiCoordinator.decide(
                    intent.getIntExtra("taskId", -1), IncomingRequestDecision.TIMED_OUT,
                )
            }
        }
    }
    private var internalReceiverRegistered = false

    override fun onCreate() {
        super.onCreate()

        Log.d(TAG, "onCreate")
        notificationManager = NotificationManagerCompat.from(this)

        if (!checkP2pPermissions()) {
            stopSelf()
            return
        }

        registerInternalBroadcastReceiver(internalReceiver, IntentFilter().apply {
            addAction(ACTION_CANCEL_RECEIVING)
            addAction(ACTION_ACCEPTED)
            addAction(ACTION_DISMISSED)
            addAction(ACTION_TIMED_OUT)
        })
        internalReceiverRegistered = true
    }

    @Volatile
    private var p2pFuture = CompletableDeferred<Pair<WifiP2pInfo, WifiP2pGroup>>()

    @Suppress("DEPRECATION")
    override fun onP2pBroadcast(intent: Intent) {
        when (intent.action) {
            WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                val connInfo = intent.getParcelableExtra<WifiP2pInfo>(
                    WifiP2pManager.EXTRA_WIFI_P2P_INFO
                )!!
                val group = intent.getParcelableExtra<WifiP2pGroup>(
                    WifiP2pManager.EXTRA_WIFI_P2P_GROUP
                )
                if (BuildConfig.DEBUG) Log.d(TAG, "P2P connection state changed")

                if (connInfo.groupFormed && !connInfo.isGroupOwner && group != null) {
                    p2pFuture.complete(Pair(connInfo, group))
                }
            }
        }
    }


    private val currentTaskLock = Any()
    private var currentJob: Job? = null
    private var currentTaskId: Int? = null
    private var currentStartId: Int = 0

    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    @OptIn(DelicateCoroutinesApi::class)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        if (!checkP2pPermissions()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        if (!MyApplication.getInstance().setBusy()) {
            Log.i(TAG, "Application is busy, skipping")
            NotificationUtils.showBusyToast(this)
            val hasActiveTask = synchronized(currentTaskLock) {
                (currentTaskId != null).also { if (it) currentStartId = startId }
            }
            if (!hasActiveTask) stopSelf(startId)
            return START_NOT_STICKY
        }

        val info = intent.getParcelableExtra<P2pInfo>("p2p_info") ?: run {
            MyApplication.getInstance().clearBusy()
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (!SessionSecurity.isPeerAllowed(
                AppSettings(this).secureReceiveOnly,
                info.cryptoVersion,
            )
        ) {
            Log.i(TAG, "Rejected peer without secure protocol support")
            MyApplication.getInstance().clearBusy()
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val localTaskId = Random.nextInt()
        retainTransferNotification = false
        transferNotificationId = 0
        terminalNotificationStarted = false
        val peerAddress = intent.getStringExtra(IncomingPeerIdentity.EXTRA_GATT_PEER_ADDRESS)
        // An admitted task must enter its cleanup even if canceled before IO dispatch.
        val job = scope.launch(Dispatchers.IO, start = CoroutineStart.ATOMIC) {
            var directoryContentIntent: PendingIntent? = null
            var peerBrandId: Int? = null
            try {
                withContext(Dispatchers.Main) {
                    transferNotificationId = NotificationUtils.newTransferNotificationId(this@P2pReceiverService)
                }
                updateStage(localTaskId, getString(R.string.device), LiveStage.INIT, contentIntent = null)
                val customDir = getCustomDownloadDir()
                directoryContentIntent = receiveDirectoryPendingIntent(localTaskId, customDir)
                runReceive(info, localTaskId, customDir, directoryContentIntent, peerAddress) {
                    peerBrandId = it
                }
            } catch (e: CancelledByUserException) {
                Log.i(TAG, "Cancelled by user")
                if (!hasCompletedResult(localTaskId)) {
                    IncomingTransferUiCoordinator.fail(
                        localTaskId,
                        getString(if (e.isRemote) R.string.cancelled_by_user_remote else R.string.cancelled_by_user_local),
                        canceled = true,
                    )
                    withContext(NonCancellable) {
                        showTransferResult(localTaskId, createFailedNotification(localTaskId, e, directoryContentIntent, peerBrandId))
                    }
                }
            } catch (e: CancellationException) {
                Log.i(TAG, "Receiving coroutine stopped", e)
                IncomingTransferUiCoordinator.fail(
                    localTaskId,
                    getString(R.string.noti_recv_interrupted),
                )
                if (IncomingTransferUiCoordinator.get(localTaskId)?.status == IncomingTransferUiStatus.FAILED) {
                    withContext(NonCancellable) {
                        showTransferResult(localTaskId, createFailedNotification(localTaskId, e, directoryContentIntent, peerBrandId))
                    }
                }
            } catch (e: Throwable) {
                if (hasCompletedResult(localTaskId) || isExpectedCompletedSessionClose(e)) {
                    Log.i(TAG, "Peer closed session after receive completed")
                } else {
                    Log.e(TAG, "Failed to process task", e)
                    if (!retainTransferNotification) {
                        IncomingTransferUiCoordinator.fail(
                            localTaskId,
                            if (e is ExceptionWithMessage) {
                                e.getMessage(this@P2pReceiverService)
                            } else {
                                getString(R.string.noti_recv_interrupted)
                            },
                        )
                        showTransferResult(localTaskId, createFailedNotification(localTaskId, e, directoryContentIntent, peerBrandId))
                    }
                }
            } finally {
                withContext(Dispatchers.Main + NonCancellable) {
                    taskNotificationJob?.cancel()
                    taskNotificationJob = null
                    IncomingTransferUiCoordinator.releaseDecision(localTaskId)
                    NotificationUtils.releaseTask("receive", localTaskId)
                    synchronized(currentTaskLock) {
                        if (currentTaskId == localTaskId) {
                            try {
                                LiveUpdateCoordinator.clearState("RECEIVER")
                                if (!retainTransferNotification) removeTransferNotification()
                            } finally {
                                currentTaskId = null
                                currentJob = null
                                MyApplication.getInstance().clearBusy()
                                stopSelf(currentStartId)
                            }
                        }
                    }
                }
            }
        }

        synchronized(currentTaskLock) {
            currentTaskId = localTaskId
            currentJob = job
            currentStartId = startId
        }


        return START_NOT_STICKY
    }

    private fun createContentValues(file: File): ContentValues {
        val extension = file.extension
        val mimeType = if (extension.isNotEmpty()) {
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
        } else null

        return ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, file.name)
            put(MediaStore.Downloads.MIME_TYPE, mimeType ?: "application/octet-stream")
            put(
                MediaStore.Downloads.RELATIVE_PATH, DEFAULT_DOWNLOAD_RELATIVE_PATH
            )
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
    }

    private fun ensureReceiveStorage(declaredSize: Long, customDir: DocumentFile?) {
        if (customDir != null) return
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val available = StatFs(downloads.absolutePath).availableBytes
        if (available < TransferLimits.requiredAvailableBytes(declaredSize)) {
            throw TransferLimitException("Not enough free storage")
        }
    }

    private fun createNotificationBuilder(
        @DrawableRes icon: Int, contentIntent: PendingIntent?, peerBrandId: Int?,
    ): NotificationCompat.Builder {
        return NotificationCompat.Builder(this, NotificationUtils.RECEIVER_CHAN_ID)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setSmallIcon(icon)
            .apply {
                DeviceUtils.knownDeviceIconById(peerBrandId)?.let {
                    setLargeIcon(Icon.createWithResource(this@P2pReceiverService, it))
                }
            }
            .setContentIntent(contentIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setOngoing(false)
            .setRequestPromotedOngoing(false)
    }

    private fun createCompletedNotification(
        taskId: Int, senderName: String, receivedFiles: List<ReceivedFile>, isPartial: Boolean,
        contentIntent: PendingIntent?,
    ): Notification {
        val presentation = IncomingTransferUiCoordinator.get(taskId)
        val mainCopyOnly = !isPartial && receivedFiles.isNotEmpty() && presentation?.isText != true
        val title = if (mainCopyOnly) {
            val item = TransferCopy.itemLabel(this, receivedFiles.first().name,
                receivedFiles.map { it.mimeType }.distinct().singleOrNull(), isText = false, count = receivedFiles.size)
            TransferCopy.withSize(this, TransferCopy.received(this, senderName, item),
                presentation?.totalSize ?: 0L, isText = false)
        } else getString(if (isPartial) R.string.recv_partial else R.string.recv_ok)
        val content = if (receivedFiles.isEmpty()) {
            getString(R.string.msg_copied_to_clipboard)
        } else if (isPartial) {
            resources.getQuantityString(
                R.plurals.noti_complete_partial, receivedFiles.size, receivedFiles.size
            )
        } else {
            resources.getQuantityString(
                R.plurals.noti_complete, receivedFiles.size, receivedFiles.size
            )
        }
        val builder =
            createNotificationBuilder(R.drawable.ic_arrow_circle_down, contentIntent,
                presentation?.brandId)
                .let {
                    NotificationUtils.setTransferCopy(this, it, title, if (mainCopyOnly) null else content)
                }
                .setSubText(if (mainCopyOnly) null else senderName).setAutoCancel(true)

        presentation?.takeIf { it.isText }?.let {
            builder.setContentIntent(viewerPendingIntent(it, manualResult = true))
        }

        if (receivedFiles.isEmpty()) {
            return builder.build()
        }

        if (!mainCopyOnly) builder.setStyle(
            if (receivedFiles.size == 1) {
                NotificationCompat.BigTextStyle().bigText("$title\n$content\n${receivedFiles.first().name}")
            } else {
                val inbox = NotificationCompat.InboxStyle().setSummaryText(content)
                receivedFiles.take(5).forEach { inbox.addLine(it.name) }
                inbox
            }
        )

        val openPendingIntent = if (receivedFiles.size == 1) {
            val rf = receivedFiles.first()
            val openIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(rf.uri, rf.mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            PendingIntent.getActivity(
                this, taskId, openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        } else {
            contentIntent
        }
        openPendingIntent?.let {
            builder.setContentIntent(it)
            builder.addAction(0, getString(R.string.open), it)
        }
        return builder.build()
    }

    private fun createFailedNotification(
        taskId: Int, exception: Throwable?, contentIntent: PendingIntent?, peerBrandId: Int?,
    ): Notification {
        val state = IncomingTransferUiCoordinator.get(taskId) ?: IncomingTransferUiState(
            taskId, getString(R.string.device), null, "", 1, 0L, IncomingTransferUiStatus.FAILED,
            errorMessage = getString(R.string.noti_recv_interrupted),
        )
        val content = state.errorMessage ?: if (exception != null && exception is ExceptionWithMessage) {
            exception.getMessage(this)
        } else if (exception != null && exception is CancelledByUserException) {
            if (exception.isRemote) {
                getString(R.string.cancelled_by_user_remote)
            } else {
                getString(R.string.cancelled_by_user_local)
            }
        } else {
            getString(R.string.noti_recv_interrupted)
        }
        return createNotificationBuilder(
            R.drawable.ic_warning,
            if (state.isText) viewerPendingIntent(state, manualResult = true) else contentIntent,
            state.brandId ?: peerBrandId,
        )
            .let {
                NotificationUtils.setTransferCopy(this, it,
                    getString(me.pipi.easyshare.incomingTransferTitle(state.status, state.stage)), content)
            }
            .setAutoCancel(true).build()
    }

    @SuppressLint("MissingPermission")
    private suspend fun runReceive(
        p2pInfo: P2pInfo,
        localTaskId: Int,
        customDir: DocumentFile?,
        contentIntent: PendingIntent?,
        peerAddress: String?,
        onPeerIdentified: (Int?) -> Unit,
    ) = coroutineScope {
        updateStage(localTaskId, getString(R.string.device), LiveStage.PREPARING, contentIntent = contentIntent)
        val secureSession = SessionSecurity.usesModernProtocol(p2pInfo.cryptoVersion)
        if (secureSession) {
            require(!p2pInfo.authToken.isNullOrBlank())
            require(p2pInfo.certificateSha256?.matches(Regex("[0-9a-f]{64}")) == true)
        }
        val expectedCertificate = p2pInfo.certificateSha256.takeIf { secureSession }
        fun createClient() = HttpClient(OkHttp) {
            install(WebSockets)
            engine {
                config {
                    val sslContext = SSLContext.getInstance("TLSv1.2")
                    val tm = SessionTrustManager(expectedCertificate)
                    sslContext.init(null, arrayOf(tm), SecureRandom())

                    connectTimeout(3, TimeUnit.SECONDS)
                    connectionPool(
                        ConnectionPool(5, 10, TimeUnit.SECONDS)
                    )
                    sslSocketFactory(sslContext.socketFactory, tm)
                    hostnameVerifier { _, session ->
                        if (expectedCertificate == null) {
                            true
                        } else {
                            val peer = runCatching {
                                session.peerCertificates.firstOrNull() as? X509Certificate
                            }.getOrNull()
                            peer != null && SessionSecurity.constantTimeEquals(
                                expectedCertificate,
                                SessionSecurity.certificateSha256(peer),
                            )
                        }
                    }
                }
            }
        }

        val p2pConfig = WifiP2pConfig.Builder()
            .setNetworkName(p2pInfo.ssid)
            .setPassphrase(p2pInfo.psk)
            .build()

        p2pFuture = CompletableDeferred()
        val groupInfo = p2pManager.requestGroupInfo(p2pChannel)
        if (groupInfo != null) {
            Log.i(TAG, "A P2P group already exists, trying to remove")
            p2pManager.removeGroupSuspend(p2pChannel)
        }
        p2pManager.connectSuspend(p2pChannel, p2pConfig)
        try {
            val (wifiP2pInfo, joinedGroup) = p2pFuture.awaitWithTimeout(
                Duration.ofSeconds(10), "Waiting for P2P connect", R.string.error_p2p_failed
            )
            val joinedOwnerName = IncomingPeerIdentity.verifiedJoinedOwnerName(
                joinedGroup.networkName, p2pInfo.ssid, joinedGroup.isGroupOwner, joinedGroup.owner?.deviceName,
            )
            // Wi-Fi Direct can install a route without exposing a ConnectivityManager
            // Network. Let the route settle, then use the group owner's system route.
            delay(P2P_ROUTE_SETTLE_MS)

            createClient().use { client ->
                val hostPort = "${wifiP2pInfo.groupOwnerAddress.hostAddress}:${p2pInfo.port}"

                val sendRequestFuture = CompletableDeferred<JSONObject>()
                val statusFuture = CompletableDeferred<Pair<Int, String>>()

                var currentFileName: String? = null

                val tokenQuery = p2pInfo.authToken?.let { "?token=$it" }.orEmpty()
                val wsSession = client.webSocketSession("wss://${hostPort}/websocket$tokenQuery")

                val downloadJob = async {
                    val sendRequestPayload = sendRequestFuture.awaitWithTimeout(
                        Duration.ofSeconds(5), "Waiting for send request",
                        R.string.err_recv_req_timeout
                    )

                    val taskId = sendRequestPayload.optString("taskId", sendRequestPayload.optString("id"))
                    val requestedSenderName = BleUtils.normalizeDeviceName(
                        sendRequestPayload.getString("senderName"),
                    )
                    val senderBrandId = sendRequestPayload.optInt("senderBrandId", -1)
                        .takeIf { it >= 0 }
                    val rawSenderBrand = if (!sendRequestPayload.isNull("senderBrand")) {
                        sendRequestPayload.optString("senderBrand").trim().takeIf { it.isNotEmpty() }
                    } else {
                        null
                    }
                    val senderIdentity = IncomingPeerIdentity.resolve(
                        requestedSenderName, senderBrandId, rawSenderBrand, peerAddress,
                        sendRequestPayload.optString("senderId"), SystemClock.elapsedRealtime(),
                        joinedOwnerName = joinedOwnerName,
                    )
                    val senderName = senderIdentity.name
                    val resolvedSenderBrandId = senderIdentity.brandId
                    onPeerIdentified(resolvedSenderBrandId)
                    Log.i(TAG, "Incoming peer identity source=${senderIdentity.source}, " +
                        "brandKnown=${resolvedSenderBrandId != null}, nameFromRequest=${senderName == requestedSenderName}, " +
                        "joinedOwnerNameAvailable=${!joinedOwnerName.isNullOrBlank()}")
                    val senderBrand = (rawSenderBrand ?: resolvedSenderBrandId?.let(DeviceUtils::knownDeviceNameById))
                        ?.takeUnless { it.equals("Unknown", ignoreCase = true) }
                        ?: getString(R.string.unknown)
                    val senderDisplayName = if (senderBrand == getString(R.string.unknown)) {
                        senderName
                    } else {
                        getString(R.string.sender_identity_brand, senderName, senderBrand)
                    }
                    if (BuildConfig.DEBUG) Log.d(TAG, "Sender metadata received")

                    updateStage(localTaskId, senderDisplayName, LiveStage.HANDSHAKE,
                        contentIntent = contentIntent, peerBrandId = resolvedSenderBrandId)

                    val totalSize = sendRequestPayload.getLong("totalSize")
                    val fileCount = sendRequestPayload.getInt("fileCount")
                    val textContent = when {
                        sendRequestPayload.has("catShareText") -> {
                            sendRequestPayload.getString("catShareText")
                        }
                        sendRequestPayload.has("easyShareText") -> {
                            sendRequestPayload.getString("easyShareText")
                        }
                        else -> null
                    }
                    TransferLimits.validateMetadata(
                        fileCount = fileCount,
                        totalSize = totalSize,
                        textSize = textContent?.toByteArray(Charsets.UTF_8)?.size?.toLong(),
                    )
                    if (textContent == null) ensureReceiveStorage(totalSize, customDir)

                    run {
                        val requestedFileName = sendRequestPayload.optString("fileName")
                        val requestState = IncomingTransferUiState(
                                taskId = localTaskId,
                                senderName = senderName,
                                brandId = resolvedSenderBrandId,
                                fileName = requestedFileName,
                                fileCount = fileCount,
                                totalSize = totalSize,
                                status = IncomingTransferUiStatus.REQUESTED,
                                isText = textContent != null,
                                mimeType = sendRequestPayload.optString("mimeType").takeIf { it.isNotBlank() },
                                receiveDirectoryUri = receiveDirectoryUri(customDir).toString(),
                            )
                        check(IncomingTransferUiCoordinator.beginRequest(
                            requestState,
                            SystemClock.elapsedRealtime() + INCOMING_REQUEST_TIMEOUT_MS,
                            getString(R.string.incoming_transfer_timeout),
                            getString(R.string.cancelled_by_user_local),
                        ))
                        val incomingIntent = IncomingTransferActivity.createIntent(
                            context = this@P2pReceiverService,
                            state = requestState,
                        )
                        observeRequest(localTaskId, contentIntent)

                        withContext(Dispatchers.Main) {
                            if (MyApplication.getInstance().hasVisibleActivity()) {
                                try {
                                    startActivity(incomingIntent)
                                } catch (error: SecurityException) {
                                    Log.w(TAG, "Incoming sheet launch denied; keeping notification controls", error)
                                } catch (error: android.content.ActivityNotFoundException) {
                                    Log.w(TAG, "Incoming sheet unavailable; keeping notification controls", error)
                                }
                            }
                        }

                        val userResponse = IncomingTransferUiCoordinator.awaitDecision(localTaskId)

                        when (userResponse) {
                            IncomingRequestDecision.ACCEPTED -> Unit
                            IncomingRequestDecision.REJECTED -> {
                                showTransferResult(localTaskId, createFailedNotification(
                                    localTaskId, CancelledByUserException(false), contentIntent, resolvedSenderBrandId,
                                ))
                                wsSession.sendStatusIgnoreException(
                                    99,
                                    taskId,
                                    3,
                                    TransferStatusProtocol.REASON_USER_REFUSED,
                                )
                                throw CancelledByUserException(false)
                            }
                            IncomingRequestDecision.TIMED_OUT -> {
                                showTransferResult(localTaskId, createFailedNotification(
                                    localTaskId, null, contentIntent, resolvedSenderBrandId,
                                ))
                                wsSession.sendStatusIgnoreException(
                                    99,
                                    taskId,
                                    3,
                                    TransferStatusProtocol.REASON_TIMEOUT,
                                )
                                throw CancellationException("Incoming request timed out")
                            }
                        }
                        IncomingTransferUiCoordinator.markReceiving(localTaskId, stage = LiveStage.PREPARING)
                    }
                    if (textContent != null) {
                        val cm = getSystemService(ClipboardManager::class.java)
                        cm.setPrimaryClip(ClipData.newPlainText(getString(R.string.shared_text), textContent))

                        showTextCopiedToast()

                        IncomingTransferUiCoordinator.complete(
                            localTaskId,
                            files = emptyList(),
                            partial = false,
                        )
                        ensureReceiveCompleted(localTaskId)
                        showTransferResult(
                            localTaskId,
                            createCompletedNotification(
                                localTaskId, senderName, emptyList(), isPartial = false, contentIntent = contentIntent,
                            ),
                        )
                        wsSession.sendStatusIgnoreException(99, taskId, 1, "ok")
                        delay(1000)
                        return@async
                    }

                    val downloadUrl = buildString {
                        append("https://$hostPort/download?taskId=$taskId")
                        p2pInfo.authToken?.let { append("&token=$it") }
                    }

                    val files = client.prepareGet(downloadUrl).execute { downloadRes ->
                        IncomingTransferUiCoordinator.markReceiving(localTaskId)
                        val ist = downloadRes.bodyAsChannel().toInputStream()

                        var currentProgress = 0
                        val progress = ProgressCounter(totalSize) { total, processed ->
                            val percent = if (total > 0L) {
                                (100.0 * processed / total).toInt().coerceIn(0, 100)
                            } else {
                                0
                            }
                            currentProgress = percent.coerceIn(0, 99)
                            IncomingTransferUiCoordinator.markReceiving(
                                localTaskId,
                                progress = percent,
                                fileName = currentFileName,
                            )
                        }

                        ZipInputStream(ist).use { zipStream ->
                            saveArchive(
                                zipStream = zipStream,
                                progress = progress,
                                expectedFileCount = fileCount,
                                expectedTotalSize = totalSize,
                                customDir = customDir,
                                onFilesSaved = { savedFiles ->
                                    completeReceivedFiles(localTaskId, savedFiles, fileCount, contentIntent)
                                },
                            ) { name ->
                                currentFileName = name
                                IncomingTransferUiCoordinator.markReceiving(
                                    localTaskId,
                                    progress = currentProgress, fileName = name,
                                )
                            }
                        }
                    }
                    if (files.isNotEmpty()) {
                        val isPartial = files.size != fileCount
                        ensureReceiveCompleted(localTaskId)
                        wsSession.sendStatusIgnoreException(
                            99,
                            taskId,
                            1,
                            if (isPartial) STATUS_REASON_PARTIAL else STATUS_REASON_OK,
                        )
                        delay(1000)
                    } else {
                        throw IllegalStateException("Failed to receive any file")
                    }
                }

                while (true) {
                    val run = select {
                        wsSession.incoming.onReceive { frame ->
                            val text = (frame as? Frame.Text)?.readText()
                                ?: throw IllegalArgumentException("Got non-text frame")
                            val message = WebSocketMessage.fromText(text)
                                ?: throw IllegalArgumentException("Failed to parse message")

                            if (BuildConfig.DEBUG) {
                                Log.d(TAG, "Incoming protocol frame: ${message.type}/${message.name}")
                            }

                            if (message.type != "action") {
                                return@onReceive true
                            }

                            val payload = message.payload ?: return@onReceive true

                            val r = when (message.name.lowercase()) {
                                "versionnegotiation" -> {
                                    val inVersion = payload.optInt("version", 1)
                                    val currentVersion = min(inVersion, 1)

                                    JSONObject()
                                        .put("version", currentVersion)
                                        .put("threadLimit", 5)
                                }

                                "sendrequest" -> {
                                    sendRequestFuture.complete(payload)
                                    null
                                }

                                "status" -> {
                                    statusFuture.complete(
                                        Pair(
                                            payload.optInt("type"), payload.optString("reason")
                                        )
                                    )
                                    null
                                }

                                else -> {
                                    null
                                }
                            }

                            val ack = WebSocketMessage("ack", message.id, message.name, r)
                            wsSession.send(Frame.Text(ack.toText()))
                            true
                        }
                        downloadJob.onAwait {
                            false
                        }
                        statusFuture.onAwait { status ->
                            if (status.first == 3 && status.second == "user refuse") {
                                throw CancelledByUserException(true)
                            }
                            if (status.first == 1) {
                                downloadJob.await()
                                return@onAwait false
                            }
                            throw RuntimeException("Transfer terminated with $status")
                        }
                    }

                    if (!run) {
                        break
                    }
                }
            }
        } finally {
            p2pManager.removeGroup(p2pChannel, null)
            p2pManager.cancelConnect(p2pChannel, null)
        }
    }

    private fun getCustomDownloadDir(): DocumentFile? = getCustomDownloadDir(this)

    private fun ensureReceiveCompleted(taskId: Int) {
        if (!hasCompletedResult(taskId)) {
            throw CancelledByUserException(false)
        }
    }

    private fun hasCompletedResult(taskId: Int): Boolean =
        IncomingTransferUiCoordinator.get(taskId)?.status.let {
            it == IncomingTransferUiStatus.SUCCESS || it == IncomingTransferUiStatus.PARTIAL
        }

    private suspend fun completeReceivedFiles(
        taskId: Int, files: List<ReceivedFile>, expectedFileCount: Int, contentIntent: PendingIntent?,
    ) = withContext(NonCancellable) {
        val state = IncomingTransferUiCoordinator.get(taskId) ?: return@withContext
        val partial = files.size != expectedFileCount
        IncomingTransferUiCoordinator.markReceiving(taskId, progress = 99, stage = LiveStage.FINALIZING)
        val token = try {
            ReceivedFilesSnapshot.save(this@P2pReceiverService, files)
        } catch (error: Exception) {
            // Files are already published; optional snapshot failure must not revoke them.
            Log.w(TAG, "Failed to save received-file navigation", error)
            null
        }
        IncomingTransferUiCoordinator.complete(taskId, files, partial, token)
        ensureReceiveCompleted(taskId)
        showTransferResult(
            taskId, createCompletedNotification(taskId, state.senderName, files, partial, contentIntent),
        )
    }

    private fun deleteReceivedFile(receivedFile: ReceivedFile) {
        runCatching {
            if (DocumentsContract.isDocumentUri(this, receivedFile.uri)) {
                DocumentFile.fromSingleUri(this, receivedFile.uri)?.delete() == true
            } else {
                contentResolver.delete(receivedFile.uri, null, null) > 0
            }
        }.onSuccess { deleted ->
            if (!deleted) {
                Log.w(TAG, "Could not remove a received file during rollback")
            }
        }.onFailure { cleanupError ->
            Log.w(TAG, "Failed to remove a received file during rollback", cleanupError)
        }
    }

    private suspend fun saveArchive(
        zipStream: ZipInputStream,
        progress: ProgressCounter,
        expectedFileCount: Int,
        expectedTotalSize: Long,
        customDir: DocumentFile?,
        onFilesSaved: suspend (List<ReceivedFile>) -> Unit,
        onFileStart: (String) -> Unit
    ): List<ReceivedFile> {
        val receivedFiles = mutableListOf<ReceivedFile>()
        var processedSize = 0L
        val maxActualBytes = TransferLimits.maxActualBytes(expectedTotalSize)
        val platformValidatorInstalled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

        if (platformValidatorInstalled) {
            dalvik.system.ZipPathValidator.setCallback(ZipPathValidatorCallback)
        }

        val savedFiles = try {
            while (true) {
                currentCoroutineContext().ensureActive()
                val entry = try {
                    zipStream.nextEntry
                } catch (error: ZipException) {
                    throw ExceptionWithMessage(
                        "Archive contains an invalid entry",
                        error,
                        R.string.error_receive_invalid_file_name,
                    )
                } ?: break
                if (entry.isDirectory) {
                    zipStream.closeEntry()
                    continue
                }
                if (receivedFiles.size >= expectedFileCount ||
                    receivedFiles.size >= TransferLimits.MAX_FILE_COUNT
                ) {
                    throw TransferLimitException("Archive contains too many files")
                }

                val safeName = try {
                    ArchiveEntryNames.safeFileName(entry.name)
                } catch (error: IllegalArgumentException) {
                    throw ExceptionWithMessage(
                        "Archive contains an invalid file name",
                        error,
                        R.string.error_receive_invalid_file_name,
                    )
                }
                if (BuildConfig.DEBUG) Log.d(TAG, "Receiving archive entry")
                onFileStart(safeName)

                val entryFile = File(safeName)
                var customDocument: DocumentFile? = null
                var mediaStoreUri: Uri? = null
                try {
                    val (uri, mimeType) = if (customDir != null) {
                        val extension = entryFile.extension
                        val mime = if (extension.isNotEmpty()) {
                            MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
                                ?: "application/octet-stream"
                        } else {
                            "application/octet-stream"
                        }

                        val doc = customDir.createFile(mime, entryFile.name)
                            ?: throw RuntimeException(
                                "Failed to create file ${entryFile.name} in custom dir",
                            )
                        customDocument = doc
                        Pair(doc.uri, mime)
                    } else {
                        val values = createContentValues(entryFile)
                        val insertedUri = contentResolver.insert(
                            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                            values,
                        ) ?: throw RuntimeException(
                            "Failed to write ${entryFile.name} to media store",
                        )
                        mediaStoreUri = insertedUri
                        Pair(insertedUri, values.getAsString(MediaStore.Downloads.MIME_TYPE))
                    }

                    val os = contentResolver.openOutputStream(uri)
                        ?: throw RuntimeException("Failed to open ${entryFile.name}")
                    val buffer = ByteArray(1024 * 1024)
                    var entrySize = 0L
                    var bytesSinceStorageCheck = 0L

                    os.use {
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val readLen = zipStream.read(buffer)
                            if (readLen == -1) {
                                break
                            }
                            if (readLen == 0) continue
                            entrySize += readLen.toLong()
                            processedSize += readLen.toLong()
                            if (entrySize > TransferLimits.MAX_ENTRY_BYTES ||
                                processedSize > maxActualBytes
                            ) {
                                throw TransferLimitException(
                                    "Archive exceeds the accepted transfer size",
                                )
                            }
                            os.write(buffer, 0, readLen)

                            bytesSinceStorageCheck += readLen.toLong()
                            if (customDir == null && bytesSinceStorageCheck >= 16L * 1024 * 1024) {
                                val downloads = Environment.getExternalStoragePublicDirectory(
                                    Environment.DIRECTORY_DOWNLOADS,
                                )
                                if (StatFs(downloads.absolutePath).availableBytes <
                                    TransferLimits.STORAGE_RESERVE_BYTES
                                ) {
                                    throw TransferLimitException("Free storage reserve reached")
                                }
                                bytesSinceStorageCheck = 0L
                            }
                            progress.update(processedSize)
                        }
                    }

                    mediaStoreUri?.let { pendingUri ->
                        val values = ContentValues().apply {
                            put(MediaStore.Downloads.IS_PENDING, 0)
                        }
                        if (contentResolver.update(pendingUri, values, null, null) <= 0) {
                            throw RuntimeException("Failed to publish received file")
                        }
                    }

                    receivedFiles.add(
                        ReceivedFile(
                            entryFile.name,
                            uri,
                            mimeType
                        )
                    )
                } catch (e: Throwable) {
                    customDocument?.delete()
                    mediaStoreUri?.let { contentResolver.delete(it, null, null) }
                    throw e
                }
                zipStream.closeEntry()
            }

            progress.complete(processedSize)
            if (receivedFiles.isEmpty() && expectedFileCount > 0) {
                throw EOFException(
                    "Archive ended before the first of $expectedFileCount files",
                )
            }
            if (receivedFiles.size != expectedFileCount) {
                Log.w(
                    TAG,
                    "Archive ended after ${receivedFiles.size} of $expectedFileCount files",
                )
            }
            if (BuildConfig.DEBUG) Log.d(TAG, "Received ${receivedFiles.size} files")
            receivedFiles.toList()
        } catch (error: Throwable) {
            if (ArchiveReceiveRecovery.canKeepCompletedFiles(error, receivedFiles.size)) {
                Log.w(
                    TAG,
                    "Transfer interrupted after ${receivedFiles.size} completed files; keeping them",
                    error,
                )
                receivedFiles.toList()
            } else {
                receivedFiles.forEach(::deleteReceivedFile)
                throw error
            }
        } finally {
            if (platformValidatorInstalled) {
                dalvik.system.ZipPathValidator.clearCallback()
            }
        }
        // Storage is committed; presentation failures must never enter archive rollback.
        // A canceled HTTP parent cannot deliver its result, so finalize before unwinding.
        withContext(NonCancellable) { onFilesSaved(savedFiles) }
        return savedFiles
    }

    fun cancel(taskId: Int) {
        synchronized(currentTaskLock) {
            if (currentTaskId == taskId) {
                val state = IncomingTransferUiCoordinator.get(taskId)
                if (state != null && !IncomingTransferUiCoordinator.cancelReceiving(
                    taskId, getString(R.string.cancelled_by_user_local),
                )) return
                currentJob?.cancel(CancelledByUserException(false))
            }
        }
    }

    override fun onDestroy() {
        LiveUpdateCoordinator.clearState("RECEIVER")
        scope.cancel()

        if (internalReceiverRegistered) {
            unregisterReceiver(internalReceiver)
        }
        super.onDestroy()
    }

    private fun showTextCopiedToast() {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(
                this@P2pReceiverService,
                R.string.msg_copied_to_clipboard,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    companion object {
        val TAG: String = P2pReceiverService::class.java.simpleName
        private const val INCOMING_REQUEST_TIMEOUT_MS = 30_000L
        private const val P2P_ROUTE_SETTLE_MS = 500L
        private val DEFAULT_DOWNLOAD_RELATIVE_PATH = "${Environment.DIRECTORY_DOWNLOADS}/Easy Share"
        private const val STATUS_REASON_OK = TransferStatusProtocol.REASON_OK
        private const val STATUS_REASON_PARTIAL = TransferStatusProtocol.REASON_PARTIAL

        private fun receiveDirectoryUri(customDir: DocumentFile?): Uri =
            customDir?.uri ?: DocumentsContract.buildDocumentUri(
                "com.android.externalstorage.documents", "primary:$DEFAULT_DOWNLOAD_RELATIVE_PATH",
            )

        private fun receivedDirectoryIntent(context: Context, customDir: DocumentFile?): Intent =
            receivedDirectoryIntent(context, receiveDirectoryUri(customDir))

        fun receivedDirectoryIntent(context: Context, directoryUri: Uri): Intent {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(directoryUri, DocumentsContract.Document.MIME_TYPE_DIR)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                // MediaStore access cannot grant a SAF directory; only forward an existing custom tree grant.
                if (DocumentsContract.isTreeUri(directoryUri) && context.checkUriPermission(
                        directoryUri, android.os.Process.myPid(), android.os.Process.myUid(),
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    clipData = ClipData.newRawUri("", DocumentsContract.buildTreeDocumentUri(
                        requireNotNull(directoryUri.authority), DocumentsContract.getTreeDocumentId(directoryUri),
                    ))
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
                }
            }
            val systemActivity = context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
                .firstOrNull {
                    it.activityInfo.applicationInfo.flags and
                        (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                }?.activityInfo
            if (systemActivity != null) intent.component = ComponentName(systemActivity.packageName, systemActivity.name)
            return intent
        }

        private fun getCustomDownloadDir(context: Context): DocumentFile? {
            val uri = AppSettings(context).downloadUri?.let(Uri::parse) ?: return null
            if (context.contentResolver.persistedUriPermissions.none { it.uri == uri && it.isWritePermission }) {
                Log.w(TAG, "No persisted permission for configured download directory")
                return null
            }
            return try {
                DocumentFile.fromTreeUri(context, uri)?.takeIf { it.exists() && it.isDirectory }
            } catch (error: Exception) {
                Log.w(TAG, "Failed to resolve custom download dir", error)
                null
            }
        }
        fun getIntent(context: Context, p2pInfo: P2pInfo): Intent {
            return Intent(context, P2pReceiverService::class.java).apply {
                putExtra("p2p_info", p2pInfo)
            }
        }

        fun getResponseIntent(
            context: Context,
            taskId: Int,
            accepted: Boolean,
            timedOut: Boolean = false,
        ): Intent {
            val action = when {
                accepted -> ACTION_ACCEPTED
                timedOut -> ACTION_TIMED_OUT
                else -> ACTION_DISMISSED
            }
            return Intent(action).apply {
                setPackage(context.packageName)
                putExtra("taskId", taskId)
            }
        }

        fun cancelTask(context: Context, taskId: Int) {
            context.sendBroadcast(
                Intent(ACTION_CANCEL_RECEIVING).apply {
                    setPackage(context.packageName)
                    putExtra("taskId", taskId)
                },
                me.pipi.easyshare.utils.INTERNAL_BROADCAST_PERMISSION,
            )
        }

        private val ACTION_DISMISSED = "${BuildConfig.APPLICATION_ID}.NOTIFICATION_DISMISSED"
        private val ACTION_ACCEPTED = "${BuildConfig.APPLICATION_ID}.NOTIFICATION_ACCEPTED"
        private val ACTION_TIMED_OUT = "${BuildConfig.APPLICATION_ID}.NOTIFICATION_TIMED_OUT"
        private val ACTION_CANCEL_RECEIVING = "${BuildConfig.APPLICATION_ID}.CANCEL_RECEIVING"
    }
}
