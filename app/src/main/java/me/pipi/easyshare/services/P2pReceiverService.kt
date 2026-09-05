package me.pipi.easyshare.services

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
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
import io.ktor.http.isSuccess
import io.ktor.utils.io.jvm.javaio.toInputStream
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
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
import me.pipi.easyshare.utils.DeviceUtils
import me.pipi.easyshare.utils.BleUtils
import me.pipi.easyshare.utils.ArchiveEntryNames
import me.pipi.easyshare.utils.ArchiveReceiveRecovery
import me.pipi.easyshare.utils.LiveStage
import me.pipi.easyshare.utils.LiveUpdateCoordinator
import me.pipi.easyshare.utils.IncomingTransferUiCoordinator
import me.pipi.easyshare.utils.NotificationUtils
import me.pipi.easyshare.utils.ProgressCounter
import me.pipi.easyshare.utils.RemoteTransferOutcome
import me.pipi.easyshare.utils.TAG
import me.pipi.easyshare.utils.TransferLimitException
import me.pipi.easyshare.utils.TransferLimits
import me.pipi.easyshare.utils.TransferStatusProtocol
import me.pipi.easyshare.utils.ZipPathValidatorCallback
import me.pipi.easyshare.utils.awaitP2pNetwork
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
import javax.net.SocketFactory
import kotlin.math.min
import kotlin.random.Random

class P2pReceiverService : BaseP2pService() {
    private enum class IncomingRequestDecision {
        ACCEPTED,
        REJECTED,
        TIMED_OUT,
    }

    private lateinit var notificationManager: NotificationManagerCompat
    private val serviceJob = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Main + serviceJob)
    private var retainTransferNotification = false

    private fun updateStage(
        taskId: Int,
        senderName: String,
        stage: LiveStage,
        progress: Int = 0,
        currentFile: String? = null,
        contentOverride: String? = null,
        contentIntent: PendingIntent? = null,
    ) {
        val cancelIntent = if (stage != LiveStage.COMPLETED) {
            PendingIntent.getBroadcast(
                this, taskId,
                Intent(ACTION_CANCEL_RECEIVING).apply {
                    putExtra("taskId", taskId)
                    setPackage(packageName)
                },
                PendingIntent.FLAG_IMMUTABLE
            )
        } else null

        val title = when (stage) {
            LiveStage.COMPLETED -> getString(R.string.recv_ok)
            else -> getString(R.string.receiving)
        }

        val content = contentOverride ?: when (stage) {
            LiveStage.TRANSFERRING -> currentFile ?: getString(R.string.receiving_files)
            LiveStage.INIT -> getString(R.string.preparing_receive)
            LiveStage.PREPARING -> getString(R.string.preparing_receive)
            LiveStage.REQUESTED -> getString(R.string.response_waiting)
            LiveStage.HANDSHAKE -> getString(R.string.noti_connecting)
            LiveStage.WAITING_AUTH -> getString(R.string.auth_waiting)
            LiveStage.FINALIZING -> getString(R.string.finishing_receive)
            LiveStage.COMPLETED -> getString(R.string.noti_receive_complete_body)
        }

        val displayProgress = if (stage == LiveStage.TRANSFERRING) {
            40 + (progress * 0.5).toInt()
        } else {
            stage.progress
        }

        val shortText = when (stage) {
            LiveStage.TRANSFERRING -> "$progress%"
            LiveStage.INIT, LiveStage.PREPARING -> getString(R.string.stage_prep)
            LiveStage.HANDSHAKE -> getString(R.string.stage_conn)
            LiveStage.REQUESTED, LiveStage.WAITING_AUTH -> getString(R.string.stage_wait)
            LiveStage.FINALIZING -> getString(R.string.stage_fin)
            LiveStage.COMPLETED -> getString(R.string.stage_done)
        }

        val state = LiveUpdateState(
            title = title,
            content = content,
            subText = getString(R.string.incoming_transfer_from, senderName),
            progress = if (stage != LiveStage.COMPLETED) displayProgress else -1,
            shortCriticalText = shortText,
            priority = LiveUpdatePriority.CRITICAL,
            ongoing = stage != LiveStage.COMPLETED,
            cancelIntent = cancelIntent,
            cancelLabel = if (stage == LiveStage.WAITING_AUTH) getString(R.string.ignore) else null,
            acceptIntent = if (stage == LiveStage.WAITING_AUTH) {
                PendingIntent.getBroadcast(
                    this, taskId,
                    Intent(ACTION_ACCEPTED).apply {
                        putExtra("taskId", taskId)
                        setPackage(packageName)
                    },
                    PendingIntent.FLAG_IMMUTABLE
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
            contentIntent = contentIntent,
            channelId = NotificationUtils.RECEIVER_CHAN_ID,
            smallIcon = R.drawable.ic_arrow_circle_down,
            silent = stage != LiveStage.WAITING_AUTH,
            alertOnlyOnce = stage != LiveStage.WAITING_AUTH,
        )

        LiveUpdateCoordinator.publishState("RECEIVER", state)
        updateForeground()
    }

    private fun updateForeground() {
        startForeground(
            NotificationUtils.ID_TRANSFER,
            NotificationUtils.getCurrentLiveNotification(this),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    }

    private fun showTransferResult(notification: Notification) {
        // Replacing a promoted foreground notification in place can retain its
        // stale ongoing flags on Android 17. Remove the live notification first.
        stopForeground(android.app.Service.STOP_FOREGROUND_REMOVE)
        notificationManager.cancel(NotificationUtils.ID_TRANSFER)
        retainTransferNotification = try {
            notificationManager.notify(NotificationUtils.ID_TRANSFER, notification)
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "Notification permission unavailable for receive result", e)
            false
        }
    }

    private fun removeTransferNotification() {
        stopForeground(android.app.Service.STOP_FOREGROUND_REMOVE)
        notificationManager.cancel(NotificationUtils.ID_TRANSFER)
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

        registerInternalBroadcastReceiver(internalReceiver, IntentFilter(ACTION_CANCEL_RECEIVING))
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
    @Volatile
    private var latestStartId = -1

    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        if (!checkP2pPermissions()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        // Acquire the busy flag under the task lock: the finishing task releases it and stops
        // the service under the same lock, so a start that slips in between is never killed.
        val busyAcquired = synchronized(currentTaskLock) {
            latestStartId = startId
            MyApplication.getInstance().setBusy()
        }
        if (!busyAcquired) {
            Log.i(TAG, "Application is busy, skipping")
            NotificationUtils.showBusyToast(this)
            val hasActiveTask = synchronized(currentTaskLock) { currentJob?.isActive == true }
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
        IncomingTransferUiCoordinator.clearAll()
        val job = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            try {
                updateStage(localTaskId, getString(R.string.device), LiveStage.INIT)
                runReceive(info, localTaskId)
            } catch (e: CancelledByUserException) {
                Log.i(TAG, "Cancelled by user")
                if (e.isRemote) {
                    IncomingTransferUiCoordinator.fail(
                        localTaskId,
                        getString(R.string.cancelled_by_user_remote),
                        canceled = true,
                    )
                    showTransferResult(createFailedNotification(e))
                } else {
                    // Decided locally (notification action or sheet): drop the pending sheet
                    // state so an open IncomingTransferActivity closes instead of waiting.
                    IncomingTransferUiCoordinator.clear(localTaskId)
                    removeTransferNotification()
                }
            } catch (e: CancellationException) {
                Log.i(TAG, "Receiving coroutine stopped", e)
                IncomingTransferUiCoordinator.fail(
                    localTaskId,
                    getString(R.string.noti_recv_interrupted),
                )
            } catch (e: Throwable) {
                if (isExpectedCompletedSessionClose(e)) {
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
                        showTransferResult(createFailedNotification(e))
                    }
                }
            } finally {
                LiveUpdateCoordinator.clearState("RECEIVER")

                if (!retainTransferNotification) {
                    removeTransferNotification()
                }

                synchronized(currentTaskLock) {
                    MyApplication.getInstance().clearBusy()
                    if (currentJob === coroutineContext[Job]) {
                        currentTaskId = null
                        currentJob = null
                        stopSelf(latestStartId)
                    }
                }
            }
        }

        synchronized(currentTaskLock) {
            currentTaskId = localTaskId
            currentJob = job
        }
        job.start()


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
                MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Easy Share"
            )
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
    }

    private fun ensureReceiveStorage(declaredSize: Long) {
        if (getCustomDownloadDir() != null) return
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val available = StatFs(downloads.absolutePath).availableBytes
        if (available < TransferLimits.requiredAvailableBytes(declaredSize)) {
            throw TransferLimitException("Not enough free storage")
        }
    }

    private fun createNotificationBuilder(@DrawableRes icon: Int): NotificationCompat.Builder {
        return NotificationCompat.Builder(this, NotificationUtils.RECEIVER_CHAN_ID)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setSmallIcon(icon).setPriority(NotificationCompat.PRIORITY_MAX)
    }

    private fun createCompletedNotification(
        senderName: String, receivedFiles: List<ReceivedFile>, isPartial: Boolean
    ): Notification {
        val builder =
            createNotificationBuilder(R.drawable.ic_arrow_circle_down)
                .setContentTitle(getString(if (isPartial) R.string.recv_partial else R.string.recv_ok))
                .setSubText(senderName).setAutoCancel(true).setContentText(
                    if (receivedFiles.isEmpty()) {
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
                )

        if (receivedFiles.isEmpty()) {
            return builder.build()
        }

        builder.setStyle(
            NotificationCompat.BigTextStyle()
                .bigText(receivedFiles.take(5).joinToString("\n") { it.name })
        )

        val intent = if (receivedFiles.size == 1) {
            val rf = receivedFiles.first()
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(rf.uri, rf.mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } else {
            Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)
        }
        builder.setContentIntent(
            PendingIntent.getActivity(
                this, 0, intent, PendingIntent.FLAG_IMMUTABLE
            )
        )
        return builder.build()
    }

    private fun createFailedNotification(exception: Throwable?): Notification {
        return createNotificationBuilder(R.drawable.ic_warning)
            .setContentTitle(getString(R.string.recv_fail))
            .setContentText(
                if (exception != null && exception is ExceptionWithMessage) {
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
            )
            .setAutoCancel(true).build()
    }

    @SuppressLint("MissingPermission")
    private suspend fun runReceive(
        p2pInfo: P2pInfo,
        localTaskId: Int,
    ) = coroutineScope {
        updateStage(localTaskId, getString(R.string.device), LiveStage.PREPARING)
        val secureSession = SessionSecurity.usesModernProtocol(p2pInfo.cryptoVersion)
        if (secureSession) {
            require(!p2pInfo.authToken.isNullOrBlank())
            require(p2pInfo.certificateSha256?.matches(Regex("[0-9a-f]{64}")) == true)
        }
        val expectedCertificate = p2pInfo.certificateSha256.takeIf { secureSession }
        fun createClient(p2pSocketFactory: SocketFactory?) = HttpClient(OkHttp) {
            install(WebSockets)
            engine {
                config {
                    val sslContext = SSLContext.getInstance("TLSv1.2")
                    val tm = SessionTrustManager(expectedCertificate)
                    sslContext.init(null, arrayOf(tm), SecureRandom())

                    connectTimeout(3, TimeUnit.SECONDS)
                    // The sender tolerates long stalls while it opens slow sources; OkHttp's
                    // default 10 s read timeout would abort such downloads first.
                    readTimeout(DOWNLOAD_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    connectionPool(
                        ConnectionPool(5, 10, TimeUnit.SECONDS)
                    )
                    if (p2pSocketFactory != null) {
                        socketFactory(p2pSocketFactory)
                    }
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
            val (wifiP2pInfo, p2pGroup) = p2pFuture.awaitWithTimeout(
                Duration.ofSeconds(10), "Waiting for P2P connect", R.string.error_p2p_failed
            )
            val p2pSocketFactory = if (Build.VERSION.SDK_INT >= EXPLICIT_P2P_NETWORK_API) {
                awaitP2pNetwork(p2pGroup.`interface`)?.socketFactory
                    ?: throw ExceptionWithMessage(
                        "P2P network route was not ready",
                        IllegalStateException("No network for P2P interface"),
                        R.string.error_p2p_failed,
                    )
            } else {
                // One UI on Android 16 installs the Wi-Fi Direct route without exposing
                // the P2P interface as a ConnectivityManager Network. Give that route a
                // moment to settle, then let the system route the socket normally.
                delay(P2P_ROUTE_SETTLE_MS)
                null
            }

            createClient(p2pSocketFactory).use { client ->
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
                    val senderName = BleUtils.normalizeDeviceName(
                        sendRequestPayload.getString("senderName"),
                    )
                    val senderBrandId = sendRequestPayload.optInt("senderBrandId", -1)
                        .takeIf { it >= 0 }
                    val rawSenderBrand = if (sendRequestPayload.has("senderBrand")) {
                        sendRequestPayload.getString("senderBrand")
                    } else {
                        senderBrandId?.let(DeviceUtils::knownDeviceNameById)
                    }
                    val senderBrand = rawSenderBrand
                        ?.takeUnless { it.equals("Unknown", ignoreCase = true) }
                        ?: getString(R.string.unknown)
                    val senderDisplayName = if (senderBrand == getString(R.string.unknown)) {
                        senderName
                    } else {
                        "$senderName ($senderBrand)"
                    }
                    if (BuildConfig.DEBUG) Log.d(TAG, "Sender metadata received")

                    updateStage(localTaskId, senderDisplayName, LiveStage.HANDSHAKE)

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
                    if (textContent == null) ensureReceiveStorage(totalSize)

                    run {
                        val requestedFileName = sendRequestPayload.optString("fileName")
                        IncomingTransferUiCoordinator.publish(
                            IncomingTransferUiState(
                                taskId = localTaskId,
                                senderName = senderName,
                                brandId = senderBrandId,
                                fileName = requestedFileName,
                                fileCount = fileCount,
                                totalSize = totalSize,
                                status = IncomingTransferUiStatus.REQUESTED,
                                isText = textContent != null,
                            ),
                        )
                        val incomingIntent = IncomingTransferActivity.createIntent(
                            context = this@P2pReceiverService,
                            taskId = localTaskId,
                            senderName = senderName,
                            fileName = requestedFileName,
                            fileCount = fileCount,
                            totalSize = totalSize,
                            brandId = senderBrandId,
                        )
                        val requestSummary = if (textContent != null) {
                            getString(R.string.noti_request_desc_text)
                        } else {
                            resources.getQuantityString(
                                R.plurals.noti_request_desc,
                                fileCount,
                                fileCount,
                                Formatter.formatFileSize(this@P2pReceiverService, totalSize),
                            )
                        }
                        val requestPendingIntent = PendingIntent.getActivity(
                            this@P2pReceiverService,
                            localTaskId,
                            incomingIntent,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                        )
                        updateStage(
                            localTaskId,
                            senderDisplayName,
                            LiveStage.WAITING_AUTH,
                            contentOverride = requestSummary,
                            contentIntent = requestPendingIntent,
                        )

                        if (MyApplication.getInstance().hasVisibleActivity()) {
                            startActivity(
                                incomingIntent.addFlags(
                                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP,
                                ),
                            )
                        }

                        val userResponse = withTimeoutOrNull(INCOMING_REQUEST_TIMEOUT_MS) {
                            waitForAction(localTaskId)
                        }

                        when (userResponse) {
                            IncomingRequestDecision.ACCEPTED -> Unit
                            IncomingRequestDecision.REJECTED -> {
                                wsSession.sendStatusIgnoreException(
                                    99,
                                    taskId,
                                    3,
                                    TransferStatusProtocol.REASON_USER_REFUSED,
                                )
                                throw CancelledByUserException(false)
                            }
                            IncomingRequestDecision.TIMED_OUT,
                            null -> {
                                IncomingTransferUiCoordinator.fail(
                                    localTaskId,
                                    getString(R.string.incoming_transfer_timeout),
                                )
                                wsSession.sendStatusIgnoreException(
                                    99,
                                    taskId,
                                    3,
                                    TransferStatusProtocol.REASON_TIMEOUT,
                                )
                                throw CancellationException("Incoming request timed out")
                            }
                        }
                        IncomingTransferUiCoordinator.markReceiving(localTaskId)
                    }
                    if (textContent != null) {
                        val cm = getSystemService(ClipboardManager::class.java)
                        cm.setPrimaryClip(ClipData.newPlainText(getString(R.string.shared_text), textContent))

                        showTextCopiedToast()

                        wsSession.sendStatusIgnoreException(99, taskId, 1, "ok")
                        IncomingTransferUiCoordinator.complete(
                            localTaskId,
                            files = emptyList(),
                            partial = false,
                        )
                        showTransferResult(
                            createCompletedNotification(senderName, emptyList(), isPartial = false),
                        )
                        delay(1000)
                        return@async
                    }

                    val downloadUrl = buildString {
                        append("https://$hostPort/download?taskId=$taskId")
                        p2pInfo.authToken?.let { append("&token=$it") }
                    }

                    val files = client.prepareGet(downloadUrl).execute { downloadRes ->
                        if (!downloadRes.status.isSuccess()) {
                            throw ExceptionWithMessage(
                                "Download rejected with ${downloadRes.status}",
                                IllegalStateException("HTTP ${downloadRes.status.value}"),
                                R.string.noti_recv_interrupted,
                            )
                        }
                        val ist = downloadRes.bodyAsChannel().toInputStream()

                        val progress = ProgressCounter(totalSize) { total, processed ->
                            val percent = if (total > 0L) {
                                (100.0 * processed / total).toInt().coerceIn(0, 100)
                            } else {
                                0
                            }
                            updateStage(localTaskId, senderDisplayName, LiveStage.TRANSFERRING, percent, currentFileName)
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
                            ) { name ->
                                currentFileName = name
                                updateStage(localTaskId, senderDisplayName, LiveStage.TRANSFERRING, 0, name)
                            }
                        }
                    }
                    updateStage(localTaskId, senderDisplayName, LiveStage.FINALIZING)

                    if (files.isNotEmpty()) {
                        val isPartial = files.size != fileCount
                        showTransferResult(
                            createCompletedNotification(
                                senderName,
                                files,
                                isPartial,
                            ),
                        )
                        IncomingTransferUiCoordinator.complete(
                            localTaskId,
                            files = files,
                            partial = isPartial,
                        )
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
                            when (TransferStatusProtocol.classify(status.first, status.second)) {
                                RemoteTransferOutcome.REJECTED -> throw CancelledByUserException(true)
                                RemoteTransferOutcome.SUCCESS,
                                RemoteTransferOutcome.PARTIAL -> {
                                    downloadJob.await()
                                    false
                                }
                                RemoteTransferOutcome.TIMED_OUT,
                                RemoteTransferOutcome.FAILED -> {
                                    throw RuntimeException("Transfer terminated with $status")
                                }
                            }
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

    private fun getCustomDownloadDir(): DocumentFile? {
        val settings = AppSettings(this)
        val uriStr = settings.downloadUri ?: return null
        val uri = Uri.parse(uriStr)

        val hasPermission = contentResolver.persistedUriPermissions.any {
            it.uri.toString() == uri.toString() && it.isWritePermission
        }
        if (!hasPermission) {
            Log.w(TAG, "No persisted permission for configured download directory")
            return null
        }

        return try {
            val df = DocumentFile.fromTreeUri(this, uri)
            if (df?.exists() == true && df.isDirectory) df else null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resolve custom download dir", e)
            null
        }
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

    private fun saveArchive(
        zipStream: ZipInputStream,
        progress: ProgressCounter,
        expectedFileCount: Int,
        expectedTotalSize: Long,
        onFileStart: (String) -> Unit
    ): List<ReceivedFile> {
        val receivedFiles = mutableListOf<ReceivedFile>()
        var processedSize = 0L
        val maxActualBytes = TransferLimits.maxActualBytes(expectedTotalSize)
        val platformValidatorInstalled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

        if (platformValidatorInstalled) {
            dalvik.system.ZipPathValidator.setCallback(ZipPathValidatorCallback)
        }

        try {
            val customDir = getCustomDownloadDir()

            while (true) {
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

            return receivedFiles
        } catch (error: Throwable) {
            if (ArchiveReceiveRecovery.canKeepCompletedFiles(error, receivedFiles.size)) {
                Log.w(
                    TAG,
                    "Transfer interrupted after ${receivedFiles.size} completed files; keeping them",
                    error,
                )
                progress.complete(processedSize)
                return receivedFiles.toList()
            }
            receivedFiles.forEach(::deleteReceivedFile)
            throw error
        } finally {
            if (platformValidatorInstalled) {
                dalvik.system.ZipPathValidator.clearCallback()
            }
        }
    }

    private suspend fun waitForAction(taskId: Int) = suspendCancellableCoroutine { continuation ->
        var registered = true
        lateinit var receiver: BroadcastReceiver

        fun unregister() {
            if (!registered) return
            registered = false
            try {
                unregisterReceiver(receiver)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to unregister waitForAction receiver", e)
            }
        }

        receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.getIntExtra("taskId", -1) != taskId) {
                    return
                }

                when (intent.action) {
                    ACTION_ACCEPTED -> {
                        unregister()
                        if (continuation.isActive) {
                            continuation.resume(IncomingRequestDecision.ACCEPTED) { _, _, _ -> }
                        }
                    }
                    ACTION_DISMISSED -> {
                        unregister()
                        if (continuation.isActive) {
                            continuation.resume(IncomingRequestDecision.REJECTED) { _, _, _ -> }
                        }
                    }
                    ACTION_TIMED_OUT -> {
                        unregister()
                        if (continuation.isActive) {
                            continuation.resume(IncomingRequestDecision.TIMED_OUT) { _, _, _ -> }
                        }
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(ACTION_ACCEPTED)
            addAction(ACTION_DISMISSED)
            addAction(ACTION_TIMED_OUT)
        }
        registerInternalBroadcastReceiver(receiver, filter)

        continuation.invokeOnCancellation { unregister() }
    }

    fun cancel(taskId: Int) {
        synchronized(currentTaskLock) {
            if (currentTaskId == taskId) {
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
        private const val INCOMING_REQUEST_TIMEOUT_MS = 31_000L
        // Keep in step with the sender's TRANSFER_STALL_TIMEOUT_MS.
        private const val DOWNLOAD_READ_TIMEOUT_MS = 120_000L
        private const val EXPLICIT_P2P_NETWORK_API = 37
        private const val P2P_ROUTE_SETTLE_MS = 500L
        private const val STATUS_REASON_OK = TransferStatusProtocol.REASON_OK
        private const val STATUS_REASON_PARTIAL = TransferStatusProtocol.REASON_PARTIAL
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
