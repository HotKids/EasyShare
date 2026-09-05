package me.pipi.easyshare.services

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Binder
import android.util.Log
import androidx.annotation.DrawableRes
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.network.tls.certificates.buildKeyStore
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.application.serverConfig
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.sslConnector
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.origin
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import me.pipi.easyshare.AppSettings
import me.pipi.easyshare.BleSecurity
import me.pipi.easyshare.BuildConfig
import me.pipi.easyshare.MyApplication
import me.pipi.easyshare.R
import me.pipi.easyshare.SessionSecurity
import me.pipi.easyshare.exceptions.CancelledByUserException
import me.pipi.easyshare.exceptions.ExceptionWithMessage
import me.pipi.easyshare.models.DeviceInfo
import me.pipi.easyshare.models.P2pInfo
import me.pipi.easyshare.models.TaskInfo
import me.pipi.easyshare.models.TransferUiState
import me.pipi.easyshare.models.TransferUiStatus
import me.pipi.easyshare.models.WebSocketMessage
import me.pipi.easyshare.models.LiveUpdatePriority
import me.pipi.easyshare.models.LiveUpdateState
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
import me.pipi.easyshare.utils.requestConnectionInfo
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
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random

class P2pSenderService : BaseP2pService() {
    private val binder = LocalBinder()
    private val serviceJob = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Main + serviceJob)
    private var retainTransferNotification = false

    inner class LocalBinder : Binder() {
        fun getService(): P2pSenderService = this@P2pSenderService
    }

    override fun onBind(intent: Intent) = binder

    @Volatile
    private var currentDeviceId: String? = null

    private fun updateStage(
        taskId: Int,
        targetName: String,
        stage: LiveStage,
        progress: Int = 0,
        currentFile: String? = null,
        partial: Boolean = false,
    ) {
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
            TransferUiCoordinator.publish(
                TransferUiState(
                    taskId = taskId,
                    deviceId = deviceId,
                    status = uiStatus,
                    progress = when (stage) {
                        LiveStage.TRANSFERRING -> unconfirmedProgress
                        LiveStage.FINALIZING -> 99
                        LiveStage.COMPLETED -> 100
                        else -> 0
                    }
                )
            )
        }

        val cancelIntent = if (stage != LiveStage.COMPLETED) {
            PendingIntent.getBroadcast(
                this, taskId,
                Intent(ACTION_CANCEL_SENDING).apply {
                    putExtra("taskId", taskId)
                    setPackage(packageName)
                },
                PendingIntent.FLAG_IMMUTABLE
            )
        } else null

        val content = when (stage) {
            LiveStage.TRANSFERRING -> currentFile ?: getString(R.string.sending_files)
            LiveStage.INIT -> getString(R.string.preparing_send)
            LiveStage.PREPARING -> getString(R.string.preparing_send)
            LiveStage.REQUESTED -> getString(R.string.response_waiting)
            LiveStage.HANDSHAKE -> getString(R.string.noti_connecting)
            LiveStage.WAITING_AUTH -> getString(R.string.auth_waiting)
            LiveStage.FINALIZING -> getString(R.string.finishing_send)
            LiveStage.COMPLETED -> getString(
                if (partial) R.string.noti_send_partial_body else R.string.noti_send_complete_body,
            )
        }

        val displayProgress = if (stage == LiveStage.TRANSFERRING) {
            40 + (unconfirmedProgress * 0.5).toInt()
        } else {
            stage.progress
        }

        val shortText = when (stage) {
            LiveStage.TRANSFERRING -> "$unconfirmedProgress%"
            LiveStage.INIT, LiveStage.PREPARING -> getString(R.string.stage_prep)
            LiveStage.HANDSHAKE -> getString(R.string.stage_conn)
            LiveStage.REQUESTED, LiveStage.WAITING_AUTH -> getString(R.string.stage_wait)
            LiveStage.FINALIZING -> getString(R.string.stage_fin)
            LiveStage.COMPLETED -> getString(R.string.stage_done)
        }

        val state = LiveUpdateState(
            title = getString(R.string.sending),
            content = content,
            subText = getString(R.string.outgoing_transfer_to, targetName),
            progress = if (stage == LiveStage.TRANSFERRING || stage == LiveStage.FINALIZING) {
                displayProgress
            } else {
                -1
            },
            shortCriticalText = shortText,
            priority = LiveUpdatePriority.CRITICAL,
            ongoing = stage != LiveStage.COMPLETED,
            cancelIntent = cancelIntent,
            channelId = NotificationUtils.SENDER_CHAN_ID,
            smallIcon = R.drawable.ic_arrow_circle_up
        )

        LiveUpdateCoordinator.publishState("SENDER", state)
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
            Log.w(TAG, "Notification permission unavailable for send result", e)
            false
        }
    }

    private fun removeTransferNotification() {
        stopForeground(android.app.Service.STOP_FOREGROUND_REMOVE)
        notificationManager.cancel(NotificationUtils.ID_TRANSFER)
        retainTransferNotification = false
    }

    @Volatile
    private var groupInfoFuture = CompletableDeferred<WifiP2pGroup>()

    private suspend fun createP2pGroup(config: WifiP2pConfig) {
        var lastFailure: Throwable? = null

        repeat(MAX_P2P_CREATE_ATTEMPTS) { index ->
            val attempt = index + 1
            groupInfoFuture = CompletableDeferred()

            try {
                p2pManager.createGroupSuspend(p2pChannel, config)
                groupInfoFuture.awaitWithTimeout(
                    Duration.ofSeconds(5),
                    "Waiting for P2P group info",
                    R.string.error_p2p_failed,
                )
                return
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
                    return
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

    private lateinit var notificationManager: NotificationManagerCompat
    private var internalReceiverRegistered = false

    private val internalReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_CANCEL_SENDING -> {
                    cancel(intent.getIntExtra("taskId", -1))
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
        updateStage(task.id, task.device.name, LiveStage.PREPARING)
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
        val p2pGroupReady = AtomicBoolean(false)
        val p2pGroupOwnerAddress = AtomicReference<String?>(null)

        fun isAuthorized(candidate: String?): Boolean =
            SessionSecurity.isAuthorized(securePeerExpected.get(), sessionToken, candidate)

        // The session server listens on every interface, so the phone's regular Wi-Fi network can
        // reach it too. Only serve peers that connected through the Wi-Fi Direct group, i.e. whose
        // connection landed on the group owner address. Before the group exists nothing can be
        // legitimate; if the owner address cannot be resolved the historical behaviour is kept.
        fun arrivedThroughP2pGroup(call: ApplicationCall): Boolean {
            if (!p2pGroupReady.get()) return false
            val expected = p2pGroupOwnerAddress.get() ?: return true
            val accepted = SessionSecurity.isSameAddress(call.request.origin.localAddress, expected)
            if (!accepted) {
                Log.w(TAG, "Rejected a session request that did not arrive through the P2P group")
            }
            return accepted
        }

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
                    if (!arrivedThroughP2pGroup(call) ||
                        !isAuthorized(call.request.queryParameters["token"]) ||
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
                    updateStage(task.id, task.device.name, LiveStage.HANDSHAKE)
                    
                    val srMsg = WebSocketMessage("action", 1, "sendRequest", taskObj)
                    send(Frame.Text(srMsg.toText()))
                    handshakeCompleteFuture.complete(Unit)

                    wsCloseFuture.await()
                }

                get("/download") {
                    if (!arrivedThroughP2pGroup(call) ||
                        call.request.queryParameters["taskId"] != taskIdStr ||
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
                    updateStage(task.id, task.device.name, LiveStage.TRANSFERRING)

                    var processedSize = 0L
                    var currentFileName: String? = null
                    val progress = ProgressCounter(totalSize) { total, processed ->
                        val percent = if (total > 0L) {
                            (100.0 * processed / total).toInt().coerceIn(0, 100)
                        } else {
                            0
                        }
                        updateStage(
                            task.id,
                            task.device.name,
                            LiveStage.TRANSFERRING,
                            percent,
                            currentFileName,
                        )
                    }

                    try {
                        call.respondOutputStream(ContentType.Application.Zip, HttpStatusCode.OK) {
                            val cr = contentResolver
                            ZipOutputStream(this).use { zo ->
                                // Shared media is usually compressed already; deflating it again
                                // only burns CPU and caps the throughput of large transfers.
                                zo.setLevel(Deflater.NO_COMPRESSION)
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
                createP2pGroup(p2pConfig)

                val connectionInfo = try {
                    p2pManager.requestConnectionInfo(p2pChannel)
                } catch (error: Throwable) {
                    Log.w(TAG, "Failed to resolve the P2P group owner address", error)
                    null
                }
                p2pGroupOwnerAddress.set(
                    connectionInfo?.takeIf { it.groupFormed }?.groupOwnerAddress?.hostAddress,
                )
                p2pGroupReady.set(true)

                val p2pMac = ShizukuUtils.getMacAddress(this@P2pSenderService, "p2p0") ?: "02:00:00:00:00:00"
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
                        // A peer only counts as secure when it also published a session key;
                        // otherwise the credentials would go out in plain text under a modern label.
                        val securePeer = SessionSecurity.usesModernProtocol(rdInfo.cryptoVersion) &&
                            rdInfo.key != null
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
                        Duration.ofSeconds(WEBSOCKET_CONNECT_TIMEOUT_SECONDS),
                        "Waiting for WS connect",
                        R.string.error_send_timeout_ws
                    )

                    updateStage(task.id, task.device.name, LiveStage.HANDSHAKE)

                    handshakeCompleteFuture.awaitWithTimeout(
                        Duration.ofSeconds(5),
                        "Waiting for handshake",
                        R.string.error_send_timeout_handshake
                    )
                    transferStartFuture.awaitWithTimeout(
                        Duration.ofSeconds(TRANSFER_START_TIMEOUT_SECONDS),
                        "Waiting for start transfer",
                        R.string.error_send_timeout_handshake
                    )
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
                    updateStage(task.id, task.device.name, LiveStage.FINALIZING)
                    statusFuture.awaitWithTimeout(
                        Duration.ofSeconds(STATUS_CONFIRMATION_TIMEOUT_SECONDS),
                        "Waiting for receive confirmation",
                        R.string.error_send_timeout_confirmation,
                    )
                }
                val status = select {
                    statusFuture.onAwait { it }
                    transferJob.onAwait { it }
                }

                when (TransferStatusProtocol.classify(status.first, status.second)) {
                    RemoteTransferOutcome.REJECTED -> throw CancelledByUserException(true)
                    RemoteTransferOutcome.TIMED_OUT -> {
                        throw TimeoutException("Remote receive request timed out")
                    }
                    RemoteTransferOutcome.SUCCESS,
                    RemoteTransferOutcome.PARTIAL -> {
                        delay(1000)
                        if (transferJob.isActive) transferJob.cancel()
                        return@coroutineScope status.first == TransferStatusProtocol.TYPE_SUCCESS &&
                            !status.second.equals(
                                TransferStatusProtocol.REASON_PARTIAL,
                                ignoreCase = true,
                            )
                    }
                    RemoteTransferOutcome.FAILED -> Unit
                }
                throw RuntimeException("Transfer terminated with $status")
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
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        if (!MyApplication.getInstance().setBusy()) {
            Log.i(TAG, "Application is busy, skipping")
            NotificationUtils.showBusyToast(this)
            val hasActiveTask = synchronized(currentTaskLock) { currentJob?.isActive == true }
            if (!hasActiveTask) stopSelf(startId)
            return START_NOT_STICKY
        }

        @Suppress("DEPRECATION")
        val task = intent.getParcelableExtra<TaskInfo>("task") ?: run {
            MyApplication.getInstance().clearBusy()
            stopSelf(startId)
            return START_NOT_STICKY
        }

        currentDeviceId = task.device.id
        retainTransferNotification = false

        val job = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            try {
                updateStage(task.id, task.device.name, LiveStage.PREPARING)
                val completedFully = runTask(task)
                updateStage(
                    task.id,
                    task.device.name,
                    LiveStage.COMPLETED,
                    partial = !completedFully,
                )
                showTransferResult(
                    createCompletedNotification(
                        targetName = task.device.name,
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
                        status = if (e.isRemote) TransferUiStatus.REJECTED else TransferUiStatus.CANCELED
                    )
                )
                if (e.isRemote) {
                    showTransferResult(createFailedNotification(task.device.name, e))
                } else {
                    removeTransferNotification()
                }
            } catch (e: CancellationException) {
                Log.i(TAG, "Sending coroutine stopped", e)
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to process task", e)
                if (!retainTransferNotification) {
                    TransferUiCoordinator.publish(
                        TransferUiState(
                            taskId = task.id,
                            deviceId = task.device.id,
                            status = if (e is TimeoutException) TransferUiStatus.TIMEOUT else TransferUiStatus.FAILED
                        )
                    )
                    showTransferResult(createFailedNotification(task.device.name, e))
                }
            } finally {
                LiveUpdateCoordinator.clearState("SENDER")
                MyApplication.getInstance().clearBusy()

                if (!retainTransferNotification) {
                    removeTransferNotification()
                }

                synchronized(currentTaskLock) {
                    currentTaskId = null
                    currentJob = null
                    currentDeviceId = null
                }
                stopSelf()
            }
        }

        synchronized(currentTaskLock) {
            currentTaskId = task.id
            currentJob = job
        }
        job.start()

        return START_NOT_STICKY
    }

    fun cancel(taskId: Int) {
        synchronized(currentTaskLock) {
            if (currentTaskId == taskId) {
                currentJob?.cancel(CancelledByUserException(false))
            }
        }
    }

    private fun createNotificationBuilder(@DrawableRes icon: Int): NotificationCompat.Builder {
        return NotificationCompat.Builder(this, NotificationUtils.SENDER_CHAN_ID)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setSmallIcon(icon)
            .setPriority(NotificationCompat.PRIORITY_MAX)
    }

    private fun createFailedNotification(targetName: String, exception: Throwable?): Notification {
        return createNotificationBuilder(R.drawable.ic_warning)
            .setContentTitle(getString(R.string.send_fail))
            .setSubText(targetName)
            .setContentText(
                when (exception) {
                    is ExceptionWithMessage -> exception.getMessage(this)
                    is CancelledByUserException -> if (exception.isRemote) {
                        getString(R.string.cancelled_by_user_remote)
                    } else {
                        getString(R.string.cancelled_by_user_local)
                    }
                    else -> getString(R.string.noti_send_interrupted)
                }
            )
            .setAutoCancel(true)
            .build()
    }

    private fun createCompletedNotification(
        targetName: String,
        partial: Boolean,
        textShared: Boolean,
    ) =
        createNotificationBuilder(R.drawable.ic_arrow_circle_up)
            .setContentTitle(getString(if (partial) R.string.send_partial else R.string.send_ok))
            .setSubText(targetName)
            .setContentText(
                getString(
                    when {
                        partial -> R.string.noti_send_partial_body
                        textShared -> R.string.noti_send_text_complete_body
                        else -> R.string.noti_send_complete_body
                    },
                ),
            )
            .setAutoCancel(true)
            .build()

    override fun onDestroy() {
        if (internalReceiverRegistered) {
            try {
                unregisterReceiver(internalReceiver)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to unregister sender receiver", e)
            }
            internalReceiverRegistered = false
        }
        LiveUpdateCoordinator.clearState("SENDER")
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val MAX_P2P_CREATE_ATTEMPTS = 10
        private const val P2P_CREATE_RETRY_DELAY_MS = 1_000L
        private const val P2P_GROUP_REMOVAL_SETTLE_MS = 1_000L
        // The receiver may need up to 10 s to join the group, 5 s for the P2P network to come up
        // and 3 s to connect; give it headroom instead of tearing the group down underneath it.
        private const val WEBSOCKET_CONNECT_TIMEOUT_SECONDS = 20L
        // The receiver shows a 30 s accept prompt backed by a 31 s service timeout, both starting
        // only after sendRequest arrived, so the sender must wait strictly longer than that.
        private const val TRANSFER_START_TIMEOUT_SECONDS = 45L
        private const val TRANSFER_STALL_POLL_MS = 1_000L
        private const val TRANSFER_STALL_TIMEOUT_MS = 120_000L
        private const val TRANSFER_BUFFER_BYTES = 64 * 1024
        private const val STATUS_CONFIRMATION_TIMEOUT_SECONDS = 30L
        private const val MAX_WEBSOCKET_FRAME_BYTES = 3L * 1024 * 1024

        val TAG: String = P2pSenderService::class.java.simpleName
        private const val ACTION_VERSION_NEGOTIATION = "versionNegotiation"
        private val ACTION_CANCEL_SENDING = "${BuildConfig.APPLICATION_ID}.CANCEL_SENDING"

        fun getIntent(context: Context, task: TaskInfo): Intent {
            return Intent(context, P2pSenderService::class.java).apply {
                putExtra("task", task)
            }
        }

        fun startTaskChecked(context: Context, task: TaskInfo): Boolean {
            if (MyApplication.getInstance().getBusy()) {
                NotificationUtils.showBusyToast(context)
                return false
            }
            TransferUiCoordinator.clear()
            TransferUiCoordinator.publish(
                TransferUiState(
                    taskId = task.id,
                    deviceId = task.device.id,
                    status = TransferUiStatus.WAITING
                )
            )
            context.startService(getIntent(context, task))
            return true
        }

        fun cancelTask(context: Context, taskId: Int) {
            context.sendBroadcast(
                Intent(ACTION_CANCEL_SENDING).apply {
                    putExtra("taskId", taskId)
                    setPackage(context.packageName)
                },
                me.pipi.easyshare.utils.INTERNAL_BROADCAST_PERMISSION,
            )
        }
    }
}
