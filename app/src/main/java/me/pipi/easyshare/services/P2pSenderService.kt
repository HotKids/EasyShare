package me.pipi.easyshare.services

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.graphics.drawable.Icon
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.Uri
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Binder
import android.util.Log
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.network.tls.certificates.buildKeyStore
import io.ktor.server.application.install
import io.ktor.server.application.serverConfig
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.sslConnector
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.CloseReason
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.serialization.json.Json
import me.pipi.easyshare.AppSettings
import me.pipi.easyshare.BleSecurity
import me.pipi.easyshare.BuildConfig
import me.pipi.easyshare.MyApplication
import me.pipi.easyshare.R
import me.pipi.easyshare.ShareActivity
import me.pipi.easyshare.SessionSecurity
import me.pipi.easyshare.exceptions.CancelledByUserException
import me.pipi.easyshare.exceptions.ExceptionWithMessage
import me.pipi.easyshare.models.DeviceInfo
import me.pipi.easyshare.models.P2pInfo
import me.pipi.easyshare.models.TaskInfo
import me.pipi.easyshare.models.OutgoingTransferPresentation
import me.pipi.easyshare.models.TransferUiState
import me.pipi.easyshare.models.TransferUiStatus
import me.pipi.easyshare.models.WebSocketMessage
import me.pipi.easyshare.models.LiveUpdatePriority
import me.pipi.easyshare.models.LiveUpdateState
import me.pipi.easyshare.ui.transfer.TransferCopy
import me.pipi.easyshare.utils.BleUtils
import me.pipi.easyshare.utils.DeviceUtils
import me.pipi.easyshare.utils.JsonWithUnknownKeys
import me.pipi.easyshare.utils.LiveStage
import me.pipi.easyshare.utils.LiveUpdateCoordinator
import me.pipi.easyshare.utils.NotificationUtils
import me.pipi.easyshare.utils.ProgressCounter
import me.pipi.easyshare.utils.ShizukuUtils
import me.pipi.easyshare.utils.TAG
import me.pipi.easyshare.utils.TransferUiCoordinator
import me.pipi.easyshare.utils.TransferLimits
import me.pipi.easyshare.utils.RemoteTransferOutcome
import me.pipi.easyshare.utils.TransferStatusProtocol
import me.pipi.easyshare.utils.awaitWithTimeout
import me.pipi.easyshare.utils.createGroupSuspend
import me.pipi.easyshare.utils.registerInternalBroadcastReceiver
import me.pipi.easyshare.utils.removeGroupSuspend
import me.pipi.easyshare.utils.requestGroupInfo
import me.pipi.easyshare.utils.withTimeoutReason
import no.nordicsemi.android.kotlin.ble.client.main.callback.ClientBleGatt
import no.nordicsemi.android.kotlin.ble.core.RealServerDevice
import no.nordicsemi.android.kotlin.ble.core.data.util.DataByteArray
import org.json.JSONObject
import java.io.EOFException
import java.io.File
import java.security.cert.X509Certificate
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.io.IOException
import kotlin.random.Random

internal class UnconfirmedTransferException(cause: Throwable? = null) :
    Exception("No reception result after local output completed", cause)

internal suspend fun awaitOutgoingDownloadStart(
    inlineText: Boolean,
    transferStart: Deferred<Unit>,
): Boolean {
    // Inline text is already in sendRequest; accepting it never starts a ZIP download.
    if (inlineText) return false
    transferStart.awaitWithTimeout(
        Duration.ofSeconds(30),
        "Waiting for start transfer",
        R.string.error_send_timeout_handshake,
    )
    return true
}

internal suspend fun awaitOutgoingOutcome(
    localCompletion: Deferred<Unit>,
    remoteStatus: Deferred<Pair<Int, String>>,
    confirmationTimeoutMillis: Long = 30_000L,
): RemoteTransferOutcome {
    val status: Pair<Int, String> = try {
        select<Pair<Int, String>> {
            remoteStatus.onAwait { it }
            localCompletion.onAwait {
                withTimeoutOrNull(confirmationTimeoutMillis) { remoteStatus.await() }
                    ?: throw UnconfirmedTransferException()
            }
        }
    } catch (error: Exception) {
        if (localCompletion.isCompleted && !localCompletion.isCancelled &&
            (error is IOException || error is ClosedReceiveChannelException)) {
            throw UnconfirmedTransferException(error)
        }
        throw error
    }
    val outcome = TransferStatusProtocol.classify(status.first, status.second)
    // A peer's receipt cannot substitute for finishing our own output stream.
    if (outcome == RemoteTransferOutcome.SUCCESS) localCompletion.await()
    return outcome
}

internal fun outgoingFailureStatus(exception: Throwable?): TransferUiStatus = when {
    exception is UnconfirmedTransferException -> TransferUiStatus.UNCONFIRMED
    exception is CancelledByUserException ->
        if (exception.isRemote) TransferUiStatus.REJECTED else TransferUiStatus.CANCELED
    exception is TimeoutException ||
        (exception is ExceptionWithMessage && exception.cause is TimeoutCancellationException) -> TransferUiStatus.TIMEOUT
    else -> TransferUiStatus.FAILED
}

class P2pSenderService : BaseP2pService() {
    private val binder = LocalBinder()
    private val serviceJob = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Main + serviceJob)

    inner class LocalBinder : Binder() {
        fun getService(): P2pSenderService = this@P2pSenderService
    }

    override fun onBind(intent: Intent) = binder

    @Volatile
    private var currentDeviceId: String? = null
    private var transferNotificationId = 0
    private var terminalNotificationStarted = false
    private var retainTransferNotification = false

    private suspend fun updateStage(
        task: TaskInfo,
        stage: LiveStage,
        progress: Int = 0,
        currentFile: String? = null,
        partial: Boolean = false,
    ) {
        val taskId = task.id
        if (!TransferUiCoordinator.owns(taskId, task.device.id)) return
        val currentStatus = TransferUiCoordinator.states.value[task.device.id]?.status
        if (currentStatus != null && currentStatus != TransferUiStatus.WAITING &&
            currentStatus != TransferUiStatus.SENDING) return
        val unconfirmedProgress = progress.coerceIn(0, 99)
        currentDeviceId?.let { deviceId ->
            val uiStatus = when (stage) {
                LiveStage.INIT,
                LiveStage.PREPARING,
                LiveStage.REQUESTED,
                LiveStage.HANDSHAKE,
                LiveStage.WAITING_AUTH -> TransferUiStatus.WAITING

                LiveStage.TRANSFERRING,
                LiveStage.FINALIZING -> TransferUiStatus.SENDING

                LiveStage.COMPLETED -> if (partial) TransferUiStatus.PARTIAL else TransferUiStatus.SUCCESS
            }
            val published = TransferUiCoordinator.publish(
                TransferUiState(
                    taskId = taskId,
                    deviceId = deviceId,
                    status = uiStatus,
                    progress = when (stage) {
                        LiveStage.TRANSFERRING -> unconfirmedProgress
                        LiveStage.FINALIZING -> 99
                        LiveStage.COMPLETED -> 100
                        else -> 0
                    },
                    stage = stage,
                )
            )
            if (!published && stage == LiveStage.COMPLETED &&
                TransferUiCoordinator.isCancelRequested(taskId, deviceId)) {
                throw CancelledByUserException(false)
            }
            if (!published) return
        }
        if (stage == LiveStage.COMPLETED) return

        val cancelIntent = if (stage != LiveStage.COMPLETED) {
            PendingIntent.getBroadcast(
                this, taskId,
                Intent(ACTION_CANCEL_SENDING).apply {
                    data = cancelIdentity(taskId, task.device.id)
                    putExtra("taskId", taskId)
                    putExtra("deviceId", task.device.id)
                    setPackage(packageName)
                },
                PendingIntent.FLAG_IMMUTABLE
            )
        } else null

        val isText = task.files.singleOrNull()?.textContent != null
        val mainCopyOnly = stage.usesSingleLineFileCopy(isText)
        val title = getString(stage.titleResource(sending = true)).let {
            if (mainCopyOnly) TransferCopy.withSize(this, it, task.files.sumOf { file -> file.size }, isText)
            else it
        }
        val attachmentSummary = if (isText) getString(R.string.shared_text) else {
            resources.getQuantityString(R.plurals.incoming_transfer_multiple, task.files.size, task.files.size)
        }
        val content = stage.notificationContent(
            currentFile,
            attachmentSummary,
            getString(
                if (partial) R.string.noti_send_partial_body else R.string.noti_send_complete_body,
            ),
        )

        val shortText = when (stage) {
            LiveStage.TRANSFERRING -> "$unconfirmedProgress%"
            LiveStage.INIT, LiveStage.PREPARING -> getString(R.string.stage_prep)
            LiveStage.HANDSHAKE -> getString(R.string.stage_conn)
            LiveStage.REQUESTED, LiveStage.WAITING_AUTH -> getString(R.string.stage_wait)
            LiveStage.FINALIZING -> getString(R.string.stage_send_fin)
            LiveStage.COMPLETED -> getString(R.string.stage_done)
        }

        val state = LiveUpdateState(
            taskKey = NotificationUtils.taskKey("send", task.id),
            title = title,
            content = if (mainCopyOnly) "" else content,
            subText = if (mainCopyOnly) null else getString(R.string.outgoing_transfer_to, task.device.displayName),
            peerBrandId = task.device.brandId,
            stage = stage,
            isText = isText,
            progress = stage.notificationProgress(unconfirmedProgress),
            indeterminate = stage.hasIndeterminateProgress(userInitiated = true),
            shortCriticalText = shortText,
            priority = LiveUpdatePriority.CRITICAL,
            ongoing = stage != LiveStage.COMPLETED,
            promoted = stage.requestsPromotion(userInitiated = true),
            cancelIntent = cancelIntent,
            contentIntent = taskContentIntent(task),
            channelId = NotificationUtils.SENDER_CHAN_ID,
            smallIcon = R.drawable.ic_arrow_circle_up
        )

        withContext(Dispatchers.Main.immediate) {
            if (!NotificationUtils.canPublishTransferNotification(taskId, currentTaskId, terminalNotificationStarted) ||
                !TransferUiCoordinator.owns(taskId, task.device.id)) return@withContext
            val latest = TransferUiCoordinator.states.value[task.device.id]
            if (latest?.status != TransferUiStatus.WAITING && latest?.status != TransferUiStatus.SENDING) return@withContext
            LiveUpdateCoordinator.publishState("SENDER", state)
            updateForeground()
        }
    }

    private fun updateForeground() {
        startForeground(
            transferNotificationId,
            NotificationUtils.getCurrentLiveNotification(this),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    }

    private suspend fun showTransferResult(task: TaskInfo, notification: Notification) =
        withContext(Dispatchers.Main.immediate + NonCancellable) {
            if (currentTaskId != task.id || terminalNotificationStarted) return@withContext
            terminalNotificationStarted = true
            if (transferNotificationId == 0) return@withContext
            retainTransferNotification = try {
                // Progress and result must use the same AMS queue before detaching the FGS flag.
                startForeground(transferNotificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                stopForeground(android.app.Service.STOP_FOREGROUND_DETACH)
                true
            } catch (e: SecurityException) {
                Log.w(TAG, "Notification permission unavailable for send result", e)
                false
            } catch (e: Exception) {
                Log.w(TAG, "Unable to show send result notification", e)
                false
            }
        }

    private fun removeTransferNotification() {
        try {
            stopForeground(android.app.Service.STOP_FOREGROUND_REMOVE)
            if (transferNotificationId != 0) notificationManager.cancel(transferNotificationId)
        } catch (e: Exception) {
            Log.w(TAG, "Unable to remove live send notification", e)
        }
    }

    @Volatile
    private var groupInfoFuture = CompletableDeferred<WifiP2pGroup>()

    private suspend fun createP2pGroup(config: WifiP2pConfig): WifiP2pGroup {
        var lastFailure: Throwable? = null

        repeat(MAX_P2P_CREATE_ATTEMPTS) { index ->
            val attempt = index + 1
            groupInfoFuture = CompletableDeferred()

            try {
                p2pManager.createGroupSuspend(p2pChannel, config)
                return groupInfoFuture.awaitWithTimeout(
                    Duration.ofSeconds(5),
                    "Waiting for P2P group info",
                    R.string.error_p2p_failed,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                lastFailure = e
                Log.w(TAG, "P2P group creation attempt $attempt failed", e)

                // On Pixel, the first createGroup call can initialize the P2P interface but
                // fail before the interface is ready. If the group appeared meanwhile, use it;
                // otherwise allow the interface a short settling period before retrying.
                val activeGroup = try {
                    p2pManager.requestGroupInfo(p2pChannel)
                } catch (requestError: Throwable) {
                    Log.w(TAG, "Failed to query P2P group after attempt $attempt", requestError)
                    null
                }
                if (activeGroup != null) {
                    Log.i(TAG, "P2P group became available after attempt $attempt")
                    return activeGroup
                }

                if (attempt < MAX_P2P_CREATE_ATTEMPTS) {
                    delay(P2P_CREATE_RETRY_DELAY_MS)
                }
            }
        }

        throw checkNotNull(lastFailure)
    }

    private val currentTaskLock = Any()
    private var currentJob: Job? = null
    private var currentTaskId: Int? = null
    private var currentStartId: Int? = null

    private lateinit var notificationManager: NotificationManagerCompat
    private var internalReceiverRegistered = false

    private val internalReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_CANCEL_SENDING -> {
                    intent.getStringExtra("deviceId")?.let { deviceId ->
                        cancel(intent.getIntExtra("taskId", -1), deviceId)
                    }
                }
            }
        }
    }


    override fun onCreate() {
        super.onCreate()
        notificationManager = NotificationManagerCompat.from(this)

        registerInternalBroadcastReceiver(internalReceiver, IntentFilter(ACTION_CANCEL_SENDING))
        internalReceiverRegistered = true
    }

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

                if (group != null) {
                    groupInfoFuture.complete(group)
                }

                if (BuildConfig.DEBUG) Log.d(TAG, "P2P connection state changed")
            }

            WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                val peers =
                    intent.getParcelableExtra<WifiP2pDeviceList>(WifiP2pManager.EXTRA_P2P_DEVICE_LIST)!!
                if (BuildConfig.DEBUG) Log.d(TAG, "P2P peer list changed")
            }

            WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                val device =
                    intent.getParcelableExtra<WifiP2pDevice>(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE)!!
                if (BuildConfig.DEBUG) Log.d(TAG, "Local P2P device state changed")
            }
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun runTask(task: TaskInfo): Boolean = coroutineScope {
        require(task.files.isNotEmpty()) { "No files to send" }
        val taskIdStr = task.id.toString()
        updateStage(task, LiveStage.PREPARING)
        var totalSize = 0L
        var fileCount = 0
        var mimeType: String? = null

        for (fi in task.files) {
            require(fi.size >= 0L) { "Invalid shared file size" }
            require(totalSize <= Long.MAX_VALUE - fi.size) { "Shared file size overflow" }
            totalSize += fi.size
            fileCount += 1

            if (mimeType == null) {
                mimeType = fi.mimeType
            } else if (mimeType != fi.mimeType) {
                mimeType = "*/*"
            }
        }

        val settings = AppSettings(this@P2pSenderService)

        val brandId = DeviceUtils.getLocalBrandId()
        val taskObj =
            JSONObject()
                .put("taskId", taskIdStr)
                .put("id", taskIdStr)
                .put("senderId", BleUtils.getSenderId())
                .put("senderName", settings.deviceName)
                .put("senderBrand", DeviceUtils.deviceNameById(brandId))
                .put("senderBrandId", brandId)
                .put("fileName", task.files.first().name)
                .put("mimeType", mimeType)
                .put("fileCount", fileCount)
                .put("totalSize", totalSize)

        val sharedTextContent = if (task.files.size == 1 && task.files[0].textContent != null) {
            val tc = task.files[0].textContent
            // Keep the established on-wire key for alliance protocol compatibility.
            taskObj.put("catShareText", tc)
            tc
        } else {
            null
        }
        TransferLimits.validateMetadata(
            fileCount = fileCount,
            totalSize = totalSize,
            textSize = sharedTextContent?.toByteArray(Charsets.UTF_8)?.size?.toLong(),
        )

        val websocketConnectFuture = CompletableDeferred<Unit>()
        val handshakeCompleteFuture = CompletableDeferred<Unit>()
        val transferStartFuture = CompletableDeferred<Unit>()
        val statusFuture = CompletableDeferred<Pair<Int, String>>()
        val transferCompleteFuture = CompletableDeferred<Unit>()
        val wsCloseFuture = CompletableDeferred<Unit>()
        val sessionToken = SessionSecurity.generateToken()
        val securePeerExpected = AtomicBoolean(true)
        val websocketClaimed = AtomicBoolean(false)
        val downloadClaimed = AtomicBoolean(false)
        val transferActivityNanos = AtomicLong(System.nanoTime())

        fun isAuthorized(candidate: String?): Boolean =
            SessionSecurity.isAuthorized(securePeerExpected.get(), sessionToken, candidate)

        val keyAlias = "easyShareSession"
        val keyStorePassword = SessionSecurity.generateToken().take(32)
        val privateKeyPassword = SessionSecurity.generateToken().take(32)
        val keyStore = buildKeyStore {
            certificate(keyAlias) {
                password = privateKeyPassword
                domains = listOf("127.0.0.1", "0.0.0.0", "localhost")
            }
        }
        val certificateSha256 = SessionSecurity.certificateSha256(
            keyStore.getCertificate(keyAlias) as X509Certificate,
        )

        val httpServerConfig = serverConfig {
            // This transient production server never supports hot reload. Ktor's
            // default working-directory watch path creates and then double-closes
            // a WatchService on Android, producing a finalizer exception.
            developmentMode = false
            watchPaths = emptyList()
            module {
                install(WebSockets) {
                    maxFrameSize = MAX_WEBSOCKET_FRAME_BYTES
                }

                routing {
                webSocket("/websocket") {
                    if (!isAuthorized(call.request.queryParameters["token"]) ||
                        !websocketClaimed.compareAndSet(false, true)
                    ) {
                        close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Unauthorized session"))
                        return@webSocket
                    }
                    if (BuildConfig.DEBUG) Log.d(TAG, "Authorized WebSocket connected")
                    websocketConnectFuture.complete(Unit)

                    val versionNegotiationFuture = CompletableDeferred<Unit>()

                    launch {
                        try {
                            while (true) {
                                val receiveResult = incoming.receiveCatching()
                                val rawFrame = receiveResult.getOrNull()
                                if (rawFrame == null) {
                                    receiveResult.exceptionOrNull()?.let { throw it }
                                    break
                                }
                                val rawMessage = rawFrame as? Frame.Text
                                    ?: throw IllegalArgumentException("Invalid frame type")
                                val message = WebSocketMessage.fromText(rawMessage.readText())
                                    ?: throw IllegalArgumentException("Failed to parse message")
                                if (BuildConfig.DEBUG) {
                                    Log.d(TAG, "Incoming protocol frame: ${message.type}/${message.name}")
                                }

                                when (message.type) {
                                    "action" -> {
                                        if (message.name.contentEquals("status")) {
                                            val payload = message.payload ?: continue
                                            statusFuture.complete(
                                                Pair(
                                                    payload.optInt("type"),
                                                    payload.optString("reason")
                                                )
                                            )
                                        }

                                        val ackMsg = WebSocketMessage(
                                            "ack", message.id, message.name, null
                                        )
                                        send(Frame.Text(ackMsg.toText()))
                                    }

                                    "ack" -> {
                                        val isVn = message.name.contentEquals(
                                            ACTION_VERSION_NEGOTIATION, true
                                        )
                                        if (isVn) {
                                            versionNegotiationFuture.complete(Unit)
                                        }
                                    }
                                }
                            }
                        } catch (e: Throwable) {
                            statusFuture.completeExceptionally(e)
                            Log.e(TAG, "WebSocket failed", e)
                            throw e
                        } finally {
                            if (!statusFuture.isCompleted && !wsCloseFuture.isCompleted) {
                                statusFuture.completeExceptionally(
                                    EOFException("WebSocket closed before transfer status"),
                                )
                            }
                            outgoing.close()
                        }
                    }

                    val vnMsg = WebSocketMessage(
                        "action",
                        0,
                        "versionNegotiation",
                        JSONObject()
                            .put("version", 1)
                            .put("versions", listOf(1))
                    )
                    send(Frame.Text(vnMsg.toText()))
                    versionNegotiationFuture.await()
                    val srMsg = WebSocketMessage("action", 1, "sendRequest", taskObj)
                    updateStage(task, LiveStage.WAITING_AUTH)
                    send(Frame.Text(srMsg.toText()))
                    handshakeCompleteFuture.complete(Unit)

                    wsCloseFuture.await()
                }

                get("/download") {
                    if (call.request.queryParameters["taskId"] != taskIdStr ||
                        !isAuthorized(call.request.queryParameters["token"]) ||
                        !downloadClaimed.compareAndSet(false, true)
                    ) {
                        call.respondText(
                            "Task ID not found",
                            ContentType.Text.Plain,
                            HttpStatusCode.NotFound
                        )
                        return@get
                    }
                    if (BuildConfig.DEBUG) Log.d(TAG, "Authorized download connected")
                    transferActivityNanos.set(System.nanoTime())
                    transferStartFuture.complete(Unit)
                    updateStage(task, LiveStage.TRANSFERRING)

                    var processedSize = 0L
                    var currentFileName: String? = null
                    val progress = ProgressCounter(totalSize) { total, processed ->
                        val percent = if (total > 0L) {
                            (100.0 * processed / total).toInt().coerceIn(0, 100)
                        } else {
                            0
                        }
                        updateStage(
                            task,
                            LiveStage.TRANSFERRING,
                            percent,
                            currentFileName,
                        )
                    }

                    try {
                        call.respondOutputStream(ContentType.Application.Zip, HttpStatusCode.OK) {
                            val cr = contentResolver
                            ZipOutputStream(this).use { zo ->
                                if (sharedTextContent != null) {
                                    currentFileName = "sharedText.txt"
                                    val textBytes = sharedTextContent.toByteArray(Charsets.UTF_8)
                                    zo.putNextEntry(ZipEntry("0/sharedText.txt"))
                                    zo.write(textBytes)
                                    processedSize += textBytes.size
                                    transferActivityNanos.set(System.nanoTime())
                                    progress.complete(processedSize)
                                    zo.closeEntry()
                                    return@use
                                }

                                for ((i, rf) in task.files.withIndex()) {
                                    val safeName = File(rf.name).name.takeIf { it.isNotBlank() }
                                        ?: "shared_file_$i"
                                    currentFileName = safeName
                                    val input = cr.openInputStream(rf.uri)
                                        ?: throw IllegalArgumentException("Shared content is no longer readable")
                                    input.use { ist ->
                                        zo.putNextEntry(ZipEntry("$i/$safeName"))

                                        val buffer = ByteArray(TRANSFER_BUFFER_BYTES)
                                        while (true) {
                                            val readLen = ist.read(buffer)
                                            if (readLen == -1) break
                                            if (readLen == 0) continue
                                            transferActivityNanos.set(System.nanoTime())
                                            zo.write(buffer, 0, readLen)
                                            processedSize += readLen.toLong()
                                            transferActivityNanos.set(System.nanoTime())
                                            progress.update(processedSize)
                                        }

                                        zo.closeEntry()
                                    }
                                }
                                progress.complete(processedSize)
                            }
                        }
                        transferCompleteFuture.complete(Unit)
                    } catch (error: Throwable) {
                        transferCompleteFuture.completeExceptionally(error)
                        throw error
                    }
                }
            }
        }
        }

        val httpServer = embeddedServer(Netty, httpServerConfig, configure = {
            sslConnector(
                keyStore = keyStore,
                keyAlias = keyAlias,
                keyStorePassword = { keyStorePassword.toCharArray() },
                privateKeyPassword = { privateKeyPassword.toCharArray() },
            ) {
                port = 0
            }
            enableHttp2 = false
        })

        try {
            httpServer.start()
            val serverPort = httpServer.engine.resolvedConnectors().first().port
            if (BuildConfig.DEBUG) Log.d(TAG, "Session server started")

            val existingGroup = p2pManager.requestGroupInfo(p2pChannel)
            if (existingGroup != null) {
                if (BuildConfig.DEBUG) Log.d(TAG, "Removing existing P2P group")
                p2pManager.removeGroupSuspend(p2pChannel)
                // The framework reports removal success before the P2P interface and
                // tethering state have finished tearing down on Pixel.
                delay(P2P_GROUP_REMOVAL_SETTLE_MS)
            }

            val ssid = "DIRECT-${DeviceUtils.getRandomChars(8)}"
            val psk = DeviceUtils.getRandomChars(8)

            val compatibilityBand = DeviceUtils.requiresTwoGhzP2pCompatibility(
                task.device.brandId,
            )
            val operatingBand = if (task.device.supports5Ghz && !compatibilityBand) {
                WifiP2pConfig.GROUP_OWNER_BAND_AUTO
            } else {
                WifiP2pConfig.GROUP_OWNER_BAND_2GHZ
            }
            Log.i(
                TAG,
                "Creating P2P group with ${if (operatingBand == WifiP2pConfig.GROUP_OWNER_BAND_2GHZ) "2.4 GHz" else "automatic"} band",
            )
            val p2pConfig = WifiP2pConfig.Builder()
                .setGroupOperatingBand(operatingBand)
                .setNetworkName(ssid)
                .setPassphrase(psk)
                .enablePersistentMode(false)
                .build()

            try {
                val group = createP2pGroup(p2pConfig)

                // Native peers validate the P2P device identity, not the group's interface
                // MAC/BSSID. Android redacts our identity without LOCAL_MAC_ADDRESS.
                val p2pMac = DeviceUtils.usableP2pDeviceAddress(group.owner?.deviceAddress)
                    ?: DeviceUtils.usableP2pDeviceAddress(
                        ShizukuUtils.getP2pDeviceAddress(this@P2pSenderService),
                    )
                    ?: throw ExceptionWithMessage(
                        "Missing P2P device address", IllegalStateException(), R.string.error_p2p_failed,
                    )
                if (BuildConfig.DEBUG) Log.d(TAG, "Resolved local P2P interface metadata")

                withTimeoutReason(
                    Duration.ofSeconds(10),
                    "BLE operations",
                    R.string.error_bt_failed,
                ) {
                    var gBleClient: ClientBleGatt? = null
                    try {
                        val bleClient = ClientBleGatt.connect(
                            this@P2pSenderService,
                            RealServerDevice(task.device.device),
                            this@withTimeoutReason,
                        )
                        gBleClient = bleClient

                        bleClient.requestMtu(512)
                        val services = bleClient.discoverServices()
                        val p2pService = services.findService(BleUtils.SERVICE_UUID)
                            ?: throw IllegalStateException("BLE service not found")
                        val deviceInfoChar =
                            p2pService.findCharacteristic(BleUtils.CHAR_STATUS_UUID)
                                ?: throw IllegalStateException("BLE device info char not found")
                        val p2pInfoChar = p2pService.findCharacteristic(BleUtils.CHAR_P2P_UUID)
                            ?: throw IllegalStateException("BLE P2P info char not found")
                        val rdInfo: DeviceInfo =
                            JsonWithUnknownKeys.decodeFromString(deviceInfoChar.read().value.decodeToString())
                        val securePeer = rdInfo.cryptoVersion != null &&
                            rdInfo.cryptoVersion >= BleSecurity.MODERN_CRYPTO_VERSION
                        securePeerExpected.set(securePeer)
                        if (BuildConfig.DEBUG) {
                            Log.d(TAG, "Remote protocol metadata received; secure=$securePeer")
                        }

                        val cipher = rdInfo.key?.let {
                            BleSecurity.deriveSessionKey(it, rdInfo.cryptoVersion)
                        }

                        val newP2pInfo = P2pInfo(
                            id = BleUtils.getSenderId(),
                            ssid = cipher?.encrypt("ssid", ssid) ?: ssid,
                            psk = cipher?.encrypt("psk", psk) ?: psk,
                            mac = cipher?.encrypt("mac", p2pMac) ?: p2pMac,
                            key = if (cipher != null) {
                                BleSecurity.getEncodedPublicKey()
                            } else {
                                null
                            },
                            port = serverPort,
                            easyShare = BuildConfig.VERSION_CODE,
                            cryptoVersion = BleSecurity.MODERN_CRYPTO_VERSION.takeIf { securePeer },
                            authToken = sessionToken.takeIf { securePeer },
                            certificateSha256 = certificateSha256.takeIf { securePeer },
                        )

                        val p2pInfoPayload = Json.encodeToString(newP2pInfo).toByteArray()
                        val p2pInfoWrites = if (
                            securePeer && p2pInfoPayload.size > BleUtils.MAX_P2P_GATT_FRAME_BYTES
                        ) {
                            BleUtils.frameP2pPayload(p2pInfoPayload)
                        } else {
                            listOf(p2pInfoPayload)
                        }
                        p2pInfoWrites.forEach { value ->
                            p2pInfoChar.write(DataByteArray(value))
                        }
                    } finally {
                        gBleClient?.close()
                    }
                }

                val transferJob = async {
                    websocketConnectFuture.awaitWithTimeout(
                        Duration.ofSeconds(10),
                        "Waiting for WS connect",
                        R.string.error_send_timeout_ws
                    )

                    handshakeCompleteFuture.awaitWithTimeout(
                        Duration.ofSeconds(5),
                        "Waiting for handshake",
                        R.string.error_send_timeout_handshake
                    )
                    if (!awaitOutgoingDownloadStart(sharedTextContent != null, transferStartFuture)) return@async
                    val stallWatchdog = launch {
                        while (!transferCompleteFuture.isCompleted) {
                            delay(TRANSFER_STALL_POLL_MS)
                            val idleMs = TimeUnit.NANOSECONDS.toMillis(
                                System.nanoTime() - transferActivityNanos.get(),
                            )
                            if (idleMs >= TRANSFER_STALL_TIMEOUT_MS) {
                                transferCompleteFuture.completeExceptionally(
                                    TimeoutException("Transfer stalled for $idleMs ms"),
                                )
                                break
                            }
                        }
                    }
                    try {
                        transferCompleteFuture.await()
                    } finally {
                        stallWatchdog.cancel()
                    }
                    updateStage(task, LiveStage.FINALIZING)
                }
                when (awaitOutgoingOutcome(transferJob, statusFuture)) {
                    RemoteTransferOutcome.REJECTED -> throw CancelledByUserException(true)
                    RemoteTransferOutcome.TIMED_OUT -> {
                        throw TimeoutException("Remote receive request timed out")
                    }
                    RemoteTransferOutcome.SUCCESS -> return@coroutineScope true
                    RemoteTransferOutcome.PARTIAL -> {
                        if (transferJob.isActive) transferJob.cancel()
                        return@coroutineScope false
                    }
                    RemoteTransferOutcome.FAILED -> Unit
                }
                throw RuntimeException("Transfer terminated by the receiver")
            } finally {
                withContext(NonCancellable) {
                    try {
                        val activeGroup = p2pManager.requestGroupInfo(p2pChannel)
                        if (activeGroup != null) {
                            p2pManager.removeGroupSuspend(p2pChannel)
                        }
                    } catch (e: Throwable) {
                        Log.w(TAG, "Failed to remove P2P group", e)
                    }
                }
            }
        } finally {
            wsCloseFuture.complete(Unit)
            httpServer.stop(1000, 1000)
        }
    }

    @SuppressLint("MissingPermission")
    @OptIn(DelicateCoroutinesApi::class)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val alreadyRunning = synchronized(currentTaskLock) {
            if (currentJob == null) false else {
                // An ignored duplicate still advances the Service's last start ID.
                currentStartId = startId
                true
            }
        }
        if (alreadyRunning) return START_NOT_STICKY
        if (intent == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        @Suppress("DEPRECATION")
        val task = intent.getParcelableExtra<TaskInfo>("task") ?: run {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (!TransferUiCoordinator.owns(task.id, task.device.id)) {
            if (synchronized(currentTaskLock) { currentJob == null }) stopSelf(startId)
            return START_NOT_STICKY
        }

        currentDeviceId = task.device.id
        transferNotificationId = 0
        terminalNotificationStarted = false
        retainTransferNotification = false

        // Every admitted task must enter cleanup, even if canceled before IO dispatch.
        val job = scope.launch(Dispatchers.IO, start = CoroutineStart.ATOMIC) {
            try {
                withContext(Dispatchers.Main) {
                    transferNotificationId = NotificationUtils.newTransferNotificationId(this@P2pSenderService)
                }
                if (TransferUiCoordinator.isCancelRequested(task.id, task.device.id)) {
                    throw CancelledByUserException(false)
                }
                currentCoroutineContext().ensureActive()
                updateStage(task, LiveStage.PREPARING)
                val completedFully = runTask(task)
                updateStage(
                    task,
                    LiveStage.COMPLETED,
                    partial = !completedFully,
                )
                showTransferResult(
                    task,
                    createCompletedNotification(
                        task = task,
                        partial = !completedFully,
                        textShared = task.files.size == 1 && task.files.first().textContent != null,
                    ),
                )
            } catch (e: CancelledByUserException) {
                Log.i(TAG, "Cancelled by user")
                TransferUiCoordinator.publish(
                    TransferUiState(
                        taskId = task.id,
                        deviceId = task.device.id,
                        status = outgoingFailureStatus(e)
                    )
                )
                showTransferResult(task, createFailedNotification(task, e))
            } catch (e: CancellationException) {
                Log.i(TAG, "Sending coroutine stopped", e)
                TransferUiCoordinator.publish(TransferUiState(task.id, task.device.id,
                    TransferUiStatus.FAILED, errorMessage = getString(R.string.noti_send_interrupted)))
                showTransferResult(task, createFailedNotification(task, e))
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to process task", e)
                val currentStatus = TransferUiCoordinator.states.value[task.device.id]?.status
                if (currentStatus == TransferUiStatus.WAITING || currentStatus == TransferUiStatus.SENDING) {
                    TransferUiCoordinator.publish(
                        TransferUiState(
                            taskId = task.id,
                            deviceId = task.device.id,
                            status = outgoingFailureStatus(e),
                            errorMessage = if (e is ExceptionWithMessage) e.getMessage(this@P2pSenderService)
                                else if (e is UnconfirmedTransferException) getString(R.string.error_send_timeout_confirmation)
                                else if (outgoingFailureStatus(e) == TransferUiStatus.TIMEOUT) getString(R.string.device_status_timeout)
                                else getString(R.string.noti_send_interrupted),
                        )
                    )
                    showTransferResult(task, createFailedNotification(task, e))
                }
            } finally {
                withContext(NonCancellable + Dispatchers.Main) {
                    synchronized(currentTaskLock) {
                        if (currentTaskId == task.id && currentDeviceId == task.device.id) {
                            val endingStartId = currentStartId ?: startId
                            try {
                                LiveUpdateCoordinator.clearState("SENDER")
                                if (!retainTransferNotification) removeTransferNotification()
                                NotificationUtils.releaseTask("send", task.id)
                            } finally {
                                currentTaskId = null
                                currentStartId = null
                                currentJob = null
                                currentDeviceId = null
                                TransferUiCoordinator.finish(task.id, task.device.id)
                                stopSelf(endingStartId)
                                MyApplication.getInstance().clearBusy()
                            }
                        }
                    }
                }
            }
        }

        synchronized(currentTaskLock) {
            currentTaskId = task.id
            currentStartId = startId
            currentJob = job
        }
        return START_NOT_STICKY
    }

    fun cancel(taskId: Int, deviceId: String) {
        synchronized(currentTaskLock) {
            if (currentTaskId == taskId && currentDeviceId == deviceId &&
                TransferUiCoordinator.requestCancel(taskId, deviceId)) {
                currentJob?.cancel(CancelledByUserException(false))
            }
        }
    }

    private fun taskContentIntent(task: TaskInfo): PendingIntent = PendingIntent.getActivity(
        this,
        task.id,
        ShareActivity.createTransferIntent(this, task),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun createNotificationBuilder(task: TaskInfo, @DrawableRes icon: Int): NotificationCompat.Builder {
        return NotificationCompat.Builder(this, NotificationUtils.SENDER_CHAN_ID)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setSmallIcon(icon)
            .apply {
                DeviceUtils.knownDeviceIconById(task.device.brandId)?.let {
                    setLargeIcon(Icon.createWithResource(this@P2pSenderService, it))
                }
            }
            .setContentIntent(taskContentIntent(task))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setOngoing(false)
            .setRequestPromotedOngoing(false)
    }

    private fun createFailedNotification(task: TaskInfo, exception: Throwable?): Notification {
        val content = when (exception) {
            is UnconfirmedTransferException -> getString(R.string.error_send_timeout_confirmation)
            is ExceptionWithMessage -> exception.getMessage(this)
            is CancelledByUserException -> if (exception.isRemote) {
                getString(R.string.device_status_rejected)
            } else {
                getString(R.string.cancelled_by_user_local)
            }
            else -> getString(if (outgoingFailureStatus(exception) == TransferUiStatus.TIMEOUT)
                R.string.device_status_timeout else R.string.noti_send_interrupted)
        }
        return createNotificationBuilder(task, R.drawable.ic_warning)
            .let {
                NotificationUtils.setTransferCopy(this, it, getString(me.pipi.easyshare.outgoingTransferTitle(
                    TransferUiState(task.id, task.device.id, outgoingFailureStatus(exception)),
                )), content)
            }
            .setSubText(task.device.displayName)
            .setAutoCancel(true)
            .build()
    }

    private fun createCompletedNotification(
        task: TaskInfo,
        partial: Boolean,
        textShared: Boolean,
    ): Notification {
        val mainCopyOnly = !partial && !textShared
        val title = getString(if (partial) R.string.send_partial else R.string.send_ok).let {
            if (mainCopyOnly) TransferCopy.withSize(this, it, task.files.sumOf { file -> file.size }, isText = false)
            else it
        }
        return createNotificationBuilder(task, R.drawable.ic_arrow_circle_up)
            .let {
                NotificationUtils.setTransferCopy(this, it, title, if (mainCopyOnly) null else getString(
                    when {
                        partial -> R.string.noti_send_partial_body
                        textShared -> R.string.noti_send_text_complete_body
                        else -> R.string.noti_send_complete_body
                    },
                ))
            }
            .setSubText(if (mainCopyOnly) null else task.device.displayName)
            .setAutoCancel(true)
            .build()
    }

    override fun onDestroy() {
        if (internalReceiverRegistered) {
            try {
                unregisterReceiver(internalReceiver)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to unregister sender receiver", e)
            }
            internalReceiverRegistered = false
        }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val MAX_P2P_CREATE_ATTEMPTS = 10
        private const val P2P_CREATE_RETRY_DELAY_MS = 1_000L
        private const val P2P_GROUP_REMOVAL_SETTLE_MS = 1_000L
        private const val TRANSFER_STALL_POLL_MS = 1_000L
        private const val TRANSFER_STALL_TIMEOUT_MS = 120_000L
        private const val TRANSFER_BUFFER_BYTES = 64 * 1024
        private const val MAX_WEBSOCKET_FRAME_BYTES = 3L * 1024 * 1024

        val TAG: String = P2pSenderService::class.java.simpleName
        private const val ACTION_VERSION_NEGOTIATION = "versionNegotiation"
        private val ACTION_CANCEL_SENDING = "${BuildConfig.APPLICATION_ID}.CANCEL_SENDING"

        fun getIntent(context: Context, task: TaskInfo): Intent {
            return Intent(context, P2pSenderService::class.java).apply {
                putExtra("task", task)
                val uris = task.files.filter { it.textContent == null && it.uri != Uri.EMPTY }
                    .map { it.uri }.distinct()
                if (uris.isNotEmpty()) {
                    clipData = ClipData.newRawUri("shared files", uris.first()).apply {
                        uris.drop(1).forEach { addItem(ClipData.Item(it)) }
                    }
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
        }

        fun startTaskChecked(context: Context, task: TaskInfo): Boolean {
            val presentation = OutgoingTransferPresentation.from(task)
            if (!MyApplication.getInstance().setBusy()) {
                NotificationUtils.showBusyToast(context)
                return false
            }
            if (!TransferUiCoordinator.begin(presentation)) {
                MyApplication.getInstance().clearBusy()
                NotificationUtils.showBusyToast(context)
                return false
            }
            return try {
                checkNotNull(context.startService(getIntent(context, task)))
                true
            } catch (error: Exception) {
                Log.e(TAG, "Failed to start sending service", error)
                TransferUiCoordinator.publish(TransferUiState(task.id, task.device.id,
                    TransferUiStatus.FAILED, errorMessage = context.getString(R.string.noti_send_interrupted)))
                TransferUiCoordinator.finish(task.id, task.device.id)
                MyApplication.getInstance().clearBusy()
                Toast.makeText(context, R.string.noti_send_interrupted, Toast.LENGTH_SHORT).show()
                false
            }
        }

        private fun cancelIdentity(taskId: Int, deviceId: String): Uri =
            Uri.Builder().scheme("easyshare").authority("cancel-sending")
                .appendPath(deviceId).appendPath(taskId.toString()).build()

        fun cancelTask(context: Context, taskId: Int, deviceId: String) {
            if (!TransferUiCoordinator.requestCancel(taskId, deviceId)) return
            context.sendBroadcast(
                Intent(ACTION_CANCEL_SENDING).apply {
                    data = cancelIdentity(taskId, deviceId)
                    putExtra("taskId", taskId)
                    putExtra("deviceId", deviceId)
                    setPackage(context.packageName)
                },
                me.pipi.easyshare.utils.INTERNAL_BROADCAST_PERMISSION,
            )
        }
    }
}
