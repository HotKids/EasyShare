package me.pipi.easyshare

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Intent
import android.content.Context
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.ParcelUuid
import android.provider.OpenableColumns
import android.text.format.Formatter
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.graphics.drawable.toDrawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import me.pipi.easyshare.models.DiscoveredDevice
import me.pipi.easyshare.models.FileInfo
import me.pipi.easyshare.models.TaskInfo
import me.pipi.easyshare.models.OutgoingTransferPresentation
import me.pipi.easyshare.models.TransferUiState
import me.pipi.easyshare.models.TransferUiStatus
import me.pipi.easyshare.services.P2pSenderService
import me.pipi.easyshare.ui.theme.EasyShareTheme
import me.pipi.easyshare.ui.transfer.EasyShareSheetContainer
import me.pipi.easyshare.ui.transfer.EasyShareSheetActions
import me.pipi.easyshare.ui.transfer.TransferSheetContent
import me.pipi.easyshare.ui.transfer.TransferSheetBody
import me.pipi.easyshare.ui.transfer.TransferVisualState
import me.pipi.easyshare.ui.transfer.NearbySearchAnimation
import me.pipi.easyshare.ui.transfer.attachmentKind
import me.pipi.easyshare.utils.BleUtils
import me.pipi.easyshare.utils.DeviceUtils
import me.pipi.easyshare.utils.NotificationUtils
import me.pipi.easyshare.utils.ShizukuUtils
import me.pipi.easyshare.utils.TAG
import me.pipi.easyshare.utils.TransferUiCoordinator
import me.pipi.easyshare.utils.LiveStage
import me.pipi.easyshare.utils.missingTransferPermissions
import java.nio.ByteBuffer
import kotlin.random.Random

class ShareActivity : ComponentActivity() {
    private lateinit var bluetoothManager: BluetoothManager
    private var shareInitialized = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (missingTransferPermissions(includeNotifications = false).isEmpty()) {
            initializeShare()
        } else {
            Toast.makeText(
                this,
                getString(R.string.permission_not_granted),
                Toast.LENGTH_LONG,
            ).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val missingPermissions = missingTransferPermissions(includeNotifications = false)
        if (missingPermissions.isNotEmpty()) {
            permissionLauncher.launch(missingPermissions.toTypedArray())
            return
        }

        initializeShare()
    }

    private fun initializeShare() {
        if (shareInitialized) return
        shareInitialized = true

        bluetoothManager = getSystemService(BluetoothManager::class.java)
        val adapter = bluetoothManager.adapter
        if (adapter == null || !adapter.isEnabled) {
            NotificationUtils.showBluetoothToast(this)
            finish()
            return
        }

        val wifiManager = getSystemService(WifiManager::class.java)
        if (!wifiManager.isWifiEnabled) {
            NotificationUtils.showWifiToast(this)
            finish()
            return
        }

        val fileInfos = try {
            if (intent.action == Intent.ACTION_SEND) {
                @Suppress("DEPRECATION") val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                if (uri != null) {
                    listOf(uri).mapNotNull { extractFileInfo(it) }
                } else {
                    val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }
                    if (text == null) emptyList() else listOf(
                        FileInfo(
                            Uri.EMPTY, "", "", 0, text
                        )
                    )
                }
            } else {
                @Suppress("DEPRECATION") val uris = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                uris?.mapNotNull { extractFileInfo(it) } ?: emptyList()
            }
        } catch (e: Throwable) {
            Log.e("ShareActivity", "Failed to extract file info", e)
            Toast.makeText(this, R.string.no_file_shared, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        if (fileInfos.isEmpty()) {
            Toast.makeText(this, R.string.no_file_shared, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        Log.i(TAG, "Shared ${fileInfos.size} files")

        ShizukuUtils.bindService()

        window.setBackgroundDrawable(android.graphics.Color.TRANSPARENT.toDrawable())
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.attributes = window.attributes.apply { dimAmount = 0.18f }
        window.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
        )
        enableEdgeToEdge()
        setContent {
            EasyShareTheme {
                ShareActivityContent(
                    files = fileInfos,
                    onDone = ::finish,
                )
            }
        }
    }

    private fun extractFileInfo(uri: Uri): FileInfo? {
        val cr = contentResolver
        var displayName: String? = null
        var reportedSize = -1L
        cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        .takeIf { it >= 0 && !cursor.isNull(it) }
                        ?.let { displayName = cursor.getString(it) }
                    cursor.getColumnIndex(OpenableColumns.SIZE)
                        .takeIf { it >= 0 && !cursor.isNull(it) }
                        ?.let { reportedSize = cursor.getLong(it) }
                }
            }

        if (reportedSize < 0L) {
            reportedSize = runCatching {
                cr.openFileDescriptor(uri, "r")?.use { it.statSize }
            }.getOrNull()?.takeIf { it >= 0L } ?: 0L
        }
        val suppliedName = displayName?.trim()?.takeIf { it.isNotEmpty() }
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
        val name = suppliedName ?: "shared_file"
        val mimeType = cr.getType(uri)?.takeIf { it.isNotBlank() }
            ?: "application/octet-stream"

        return FileInfo(uri, name, mimeType, reportedSize, null, nameIsFallback = suppliedName == null)
    }

    companion object {
        fun createTransferIntent(context: Context, task: TaskInfo): Intent =
            createTransferIntent(context, OutgoingTransferPresentation.from(task))

        fun createTransferIntent(context: Context, presentation: OutgoingTransferPresentation): Intent =
            Intent(context, OutgoingTransferActivity::class.java).apply {
                data = Uri.Builder().scheme("easyshare").authority("sending")
                    .appendPath(presentation.deviceId).appendPath(presentation.taskId.toString()).build()
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(OutgoingTransferActivity.EXTRA_PRESENTATION, presentation)
                TransferUiCoordinator.states.value[presentation.deviceId]
                    ?.takeIf { it.taskId == presentation.taskId }
                    ?.let { putExtra(OutgoingTransferActivity.EXTRA_STATE, it) }
            }
    }
}

@Composable
fun ShareActivityContent(
    files: List<FileInfo>,
    onDone: () -> Unit,
    initialTransfer: OutgoingTransferPresentation? = null,
    fallbackState: TransferUiState? = null,
) {
    val context = LocalContext.current
    var selectedTransfer by rememberSaveable { mutableStateOf(initialTransfer) }
    var scanAttempt by remember { mutableIntStateOf(0) }
    val discovery = if (selectedTransfer == null) deviceScanner(scanAttempt) else NearbyDeviceScan()
    val discoveredDevices = discovery.devices
    val transferStates by TransferUiCoordinator.states.collectAsState()
    var savedResult by rememberSaveable { mutableStateOf(fallbackState) }
    val selectedState = selectedTransfer?.let { transfer ->
        restoredTransferState(
            transfer,
            transferStates[transfer.deviceId],
            savedResult,
            stringResource(R.string.noti_send_interrupted),
        )
    }
    androidx.compose.runtime.SideEffect {
        if (selectedState != null && selectedState.status != TransferUiStatus.WAITING &&
            selectedState.status != TransferUiStatus.SENDING) {
            savedResult = selectedState
        }
    }
    val outsideInteraction = remember { MutableInteractionSource() }

    BackHandler(onBack = onDone)

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = outsideInteraction,
                    indication = null,
                    onClick = onDone,
                )
                .clearAndSetSemantics {},
        )

        Box(modifier = Modifier.align(Alignment.BottomCenter)) {
            EasyShareSheetContainer(
                title = stringResource(R.string.app_name),
                centerTitle = true,
                animateSize = true,
                onDismiss = onDone,
            ) {
                if (selectedTransfer == null) {
                    if (discovery.failed) {
                        TransferSheetBody(partyText = stringResource(R.string.nearby_scan_failed)) {
                            Icon(painterResource(R.drawable.ic_warning), null,
                                tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(40.dp))
                        }
                    } else if (discoveredDevices.isEmpty()) {
                        EmptyDeviceState()
                    } else {
                        BoxWithConstraints(
                            modifier = Modifier.weight(1f, fill = false)
                                .heightIn(min = 300.dp).padding(top = 12.dp),
                        ) {
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(nearbyDeviceColumns(maxWidth.value, LocalDensity.current.fontScale)),
                                modifier = Modifier.heightIn(max = 288.dp).fillMaxSize(),
                                horizontalArrangement = Arrangement.SpaceEvenly,
                                verticalArrangement = Arrangement.spacedBy(
                                    if (maxWidth >= 600.dp) 22.dp else 12.dp,
                                ),
                                contentPadding = PaddingValues(
                                    start = if (maxWidth >= 600.dp) 12.dp else 20.dp,
                                    top = 8.dp,
                                    end = if (maxWidth >= 600.dp) 12.dp else 20.dp,
                                    bottom = 16.dp,
                                ),
                            ) {
                                items(discoveredDevices, key = { it.id }) { device ->
                                    DeviceGridItem(
                                        device = device,
                                        enabled = true,
                                        onClick = {
                                            val taskId = Random.nextInt()
                                            val task = TaskInfo(taskId, device, files)
                                            if (
                                                P2pSenderService.startTaskChecked(
                                                    context,
                                                    task,
                                                )
                                            ) {
                                                selectedTransfer = OutgoingTransferPresentation.from(task)
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                    EasyShareSheetActions(
                        secondaryActionLabel = if (discovery.failed) stringResource(R.string.cancel) else null,
                        onSecondaryAction = if (discovery.failed) onDone else null,
                        primaryActionLabel = stringResource(if (discovery.failed) R.string.retry else R.string.cancel),
                        onPrimaryAction = { if (discovery.failed) scanAttempt++ else onDone() },
                        emphasizePrimary = discovery.failed,
                    )
                } else {
                    val transfer = checkNotNull(selectedTransfer)
                    OutgoingTransferSheet(
                        transfer = transfer,
                        state = selectedState,
                        onCancel = {
                            P2pSenderService.cancelTask(context, transfer.taskId, transfer.deviceId)
                        },
                        onDone = onDone,
                    )
                }
            }
        }
    }
}

internal fun restoredTransferState(
    transfer: OutgoingTransferPresentation,
    current: TransferUiState?,
    snapshot: TransferUiState?,
    interruptedMessage: String,
): TransferUiState {
    fun TransferUiState.matches() = taskId == transfer.taskId && deviceId == transfer.deviceId
    current?.takeIf { it.matches() }?.let { return it }
    snapshot?.takeIf {
        it.matches() && it.status != TransferUiStatus.WAITING && it.status != TransferUiStatus.SENDING
    }?.let { return it }
    // An active service cannot survive process death; restoring its old percentage would invent progress.
    return TransferUiState(transfer.taskId, transfer.deviceId, TransferUiStatus.FAILED,
        errorMessage = interruptedMessage)
}

internal fun nearbyDeviceColumns(widthDp: Float, fontScale: Float = 1f): Int {
    val horizontalPadding = if (widthDp >= 600f) 24f else 40f
    val cellWidth = 118f * fontScale.coerceAtLeast(1f)
    return ((widthDp - horizontalPadding) / cellWidth).toInt().coerceAtLeast(1)
}

@Composable
private fun ColumnScope.OutgoingTransferSheet(
    transfer: OutgoingTransferPresentation,
    state: TransferUiState?,
    onCancel: () -> Unit,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val status = state?.status ?: TransferUiStatus.WAITING
    val inProgress = status == TransferUiStatus.WAITING || status == TransferUiStatus.SENDING
    val statusLabel = stringResource(outgoingTransferTitle(state))
    val visualState = outgoingTransferVisual(state)
    val message = when (status) {
        TransferUiStatus.PARTIAL -> stringResource(R.string.noti_send_partial_body)
        TransferUiStatus.FAILED, TransferUiStatus.TIMEOUT, TransferUiStatus.UNCONFIRMED -> state?.errorMessage
        else -> null
    }

    TransferSheetContent(
        partyText = statusLabel,
        partyIconRes = DeviceUtils.knownDeviceIconById(transfer.brandId),
        attachmentKind = attachmentKind(transfer.fileName, transfer.mimeType, transfer.isText, transfer.fileCount),
        visualState = visualState,
        progress = state?.progress.takeIf { visualState == TransferVisualState.PROGRESS },
        fileSize = transfer.totalSize.takeIf { it > 0L && !transfer.isText }?.let { Formatter.formatFileSize(context, it) },
        secondaryActionLabel = null,
        onSecondaryAction = null,
        primaryActionLabel = stringResource(if (inProgress) R.string.cancel else R.string.close),
        onPrimaryAction = if (inProgress) onCancel else onDone,
        primaryActionEnabled = !inProgress || state?.cancelRequested != true,
        message = message,
        emphasizePrimary = false,
    )
}

internal fun outgoingTransferTitle(state: TransferUiState?): Int = if (state?.cancelRequested == true) {
    R.string.transfer_canceling
} else when (state?.status ?: TransferUiStatus.WAITING) {
    TransferUiStatus.WAITING -> (state?.stage ?: LiveStage.INIT).titleResource(sending = true)
    TransferUiStatus.SENDING -> (state?.stage ?: LiveStage.TRANSFERRING).titleResource(sending = true)
    TransferUiStatus.SUCCESS -> R.string.send_ok
    TransferUiStatus.PARTIAL -> R.string.send_partial
    TransferUiStatus.FAILED -> R.string.send_fail
    TransferUiStatus.CANCELED -> R.string.device_status_canceled
    TransferUiStatus.REJECTED -> R.string.device_status_rejected
    TransferUiStatus.TIMEOUT -> R.string.device_status_timeout
    TransferUiStatus.UNCONFIRMED -> R.string.device_status_unconfirmed
}

internal fun outgoingTransferVisual(state: TransferUiState?): TransferVisualState = if (state?.cancelRequested == true) {
    TransferVisualState.FINALIZING
} else when (state?.status ?: TransferUiStatus.WAITING) {
    TransferUiStatus.WAITING -> TransferVisualState.FILE
    TransferUiStatus.SENDING -> if (state?.stage == LiveStage.FINALIZING) TransferVisualState.FINALIZING else TransferVisualState.PROGRESS
    TransferUiStatus.SUCCESS -> TransferVisualState.SUCCESS
    TransferUiStatus.PARTIAL -> TransferVisualState.PARTIAL
    TransferUiStatus.CANCELED, TransferUiStatus.REJECTED, TransferUiStatus.TIMEOUT -> TransferVisualState.CANCELED
    TransferUiStatus.FAILED -> TransferVisualState.FAILURE
    TransferUiStatus.UNCONFIRMED -> TransferVisualState.FAILURE
}

@Composable
private fun ColumnScope.EmptyDeviceState() {
    TransferSheetBody(partyText = stringResource(R.string.no_nearby_devices)) {
        NearbySearchAnimation(Modifier.size(84.dp))
    }
}

@Composable
private fun DeviceGridItem(
    device: DiscoveredDevice,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val sendLabel = stringResource(R.string.send)
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        color = Color.Transparent,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 92.dp)
            .semantics { role = Role.Button; onClick(label = sendLabel, action = null) }
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier.size(48.dp),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(DeviceUtils.deviceIconById(device.brandId)),
                    contentDescription = device.brand,
                    modifier = Modifier.size(40.dp)
                )
            }
            Text(
                text = device.displayName,
                style = MaterialTheme.typography.titleSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
            )
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
private fun deviceScanner(attempt: Int): NearbyDeviceScan {
    val context = LocalContext.current
    var discovery by remember(attempt) { mutableStateOf(NearbyDeviceScan()) }

    LifecycleResumeEffect(context, attempt) {
        val manager = context.getSystemService(BluetoothManager::class.java)
        val adapter = manager.adapter
        val devicesLock = Any()
        var active = true
        discovery = discovery.copy(failed = false)

        fun failScan() = synchronized(devicesLock) {
            if (active) discovery = discovery.copy(failed = true)
        }

        val callback = object : ScanCallback() {
            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "BLE scan failed: $errorCode")
                failScan()
            }

            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val record = result.scanRecord ?: return
                var supports5Ghz = false
                var deviceName: String? = null
                var deviceDisplayName: String? = null
                var brandId: Int? = null
                var senderId: String? = null

                for ((uuid, data) in record.serviceData.entries) {
                    when (data.size) {
                        6 -> {
                            // UUID contains brand and 5GHz flag
                            val buf = ByteBuffer.allocate(16)
                            buf.putLong(uuid.uuid.mostSignificantBits)
                            buf.putLong(uuid.uuid.leastSignificantBits)
                            val arr = buf.array()
                            supports5Ghz = arr[2].toInt() == 1
                            brandId = DeviceUtils.bleByteToBrandId(arr[3])
                        }

                        27 -> {
                            // Data contains device name and ID
                            senderId = BleUtils.senderIdFromAdvertisement(data)
                            deviceName = BleUtils.deviceNameFromAdvertisement(data)
                            deviceDisplayName = BleUtils.deviceDisplayNameFromAdvertisement(data)
                        }
                    }
                }

                if (deviceName == null || senderId == null) {
                    return
                }

                val brand = brandId?.let {
                    DeviceUtils.knownDeviceNameById(it)
                }

                val newDevice = DiscoveredDevice(
                    device = result.device,
                    id = senderId,
                    name = deviceName,
                    brandId = brandId,
                    brand = brand,
                    supports5Ghz = supports5Ghz,
                    displayName = deviceDisplayName ?: deviceName,
                )
                var replaced = false
                synchronized(devicesLock) {
                    if (!active || discovery.failed) return
                    val newList = discovery.devices.map {
                        if (it.id == senderId) {
                            replaced = true
                            newDevice
                        } else {
                            it
                        }
                    }.toMutableList()
                    if (!replaced) {
                        newList.add(newDevice)
                    }
                    discovery = discovery.copy(devices = newList)
                }
            }
        }

        var startedScanner: BluetoothLeScanner? = null

        try {
            val scanner = adapter?.bluetoothLeScanner
            val filters = listOf(
                ScanFilter.Builder().setServiceUuid(ParcelUuid(BleUtils.ADV_SERVICE_UUID)).build()
            )
            val settings =
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()

            if (scanner == null) {
                failScan()
            } else {
                scanner.startScan(filters, settings, callback)
                startedScanner = scanner
                Log.d(TAG, "Started scanning")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start scan", e)
            failScan()
        }

        onPauseOrDispose {
            synchronized(devicesLock) { active = false }
            try {
                startedScanner?.stopScan(callback)
                Log.d(TAG, "Stopped scanning")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop scan", e)
            }
        }
    }

    return discovery
}

private data class NearbyDeviceScan(
    val devices: List<DiscoveredDevice> = emptyList(),
    val failed: Boolean = false,
)
