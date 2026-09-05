package me.pipi.easyshare

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.ParcelUuid
import android.os.Parcelable
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
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.parcelize.Parcelize
import me.pipi.easyshare.models.DiscoveredDevice
import me.pipi.easyshare.models.FileInfo
import me.pipi.easyshare.models.TaskInfo
import me.pipi.easyshare.models.TransferUiState
import me.pipi.easyshare.models.TransferUiStatus
import me.pipi.easyshare.services.P2pSenderService
import me.pipi.easyshare.ui.PagAnimation
import me.pipi.easyshare.ui.theme.EasyShareTheme
import me.pipi.easyshare.ui.transfer.EasyShareSheetContainer
import me.pipi.easyshare.ui.transfer.EasyShareSheetActions
import me.pipi.easyshare.ui.transfer.TransferSheet
import me.pipi.easyshare.ui.transfer.TransferVisualState
import me.pipi.easyshare.ui.transfer.fileTypeLabel
import me.pipi.easyshare.utils.BleUtils
import me.pipi.easyshare.utils.DeviceUtils
import me.pipi.easyshare.utils.NotificationUtils
import me.pipi.easyshare.utils.ShizukuUtils
import me.pipi.easyshare.utils.TAG
import me.pipi.easyshare.utils.TransferUiCoordinator
import me.pipi.easyshare.utils.missingTransferPermissions
import java.nio.ByteBuffer
import kotlin.random.Random

class ShareActivity : ComponentActivity() {
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

        // Both managers are null on hardware without the radio; show the hint instead of crashing.
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            NotificationUtils.showBluetoothToast(this)
            finish()
            return
        }

        val wifiManager = getSystemService(WifiManager::class.java)
        if (wifiManager == null || !wifiManager.isWifiEnabled) {
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

        window.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
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
        val name = displayName?.trim()?.takeIf { it.isNotEmpty() }
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: "shared_file"
        val mimeType = cr.getType(uri)?.takeIf { it.isNotBlank() }
            ?: "application/octet-stream"

        return FileInfo(uri, name, mimeType, reportedSize, null)
    }
}

@Composable
fun ShareActivityContent(
    files: List<FileInfo>,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val discoveredDevices = deviceScanner()
    val transferStates by TransferUiCoordinator.states.collectAsState()
    // Survives configuration changes so a rotation mid-send keeps showing the transfer sheet.
    var selectedTransfer by rememberSaveable { mutableStateOf<SelectedTransfer?>(null) }
    val selectedState = selectedTransfer?.let { transfer ->
        transferStates[transfer.device.id]?.takeIf { it.taskId == transfer.taskId }
    }
    val outsideInteraction = remember { MutableInteractionSource() }

    BackHandler {
        val transfer = selectedTransfer
        val status = selectedState?.status
        if (
            transfer != null &&
            (status == null || status == TransferUiStatus.WAITING || status == TransferUiStatus.SENDING)
        ) {
            P2pSenderService.cancelTask(context, transfer.taskId)
            onDone()
        } else {
            onDone()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .fillMaxHeight(0.48f)
                .clickable(
                    interactionSource = outsideInteraction,
                    indication = null,
                    enabled = selectedTransfer == null,
                    onClick = onDone,
                ),
        )

        if (selectedTransfer == null) {
            Box(modifier = Modifier.align(Alignment.BottomCenter)) {
                EasyShareSheetContainer(
                    title = stringResource(R.string.app_name),
                    centerTitle = true,
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        if (discoveredDevices.isEmpty()) {
                            EmptyDeviceState(modifier = Modifier.fillMaxSize())
                        } else {
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(3),
                                modifier = Modifier.fillMaxSize(),
                                horizontalArrangement = Arrangement.SpaceEvenly,
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                contentPadding = PaddingValues(
                                    start = 20.dp,
                                    top = 8.dp,
                                    end = 20.dp,
                                    bottom = 16.dp,
                                ),
                            ) {
                                items(discoveredDevices, key = { it.id }) { device ->
                                    DeviceGridItem(
                                        device = device,
                                        state = null,
                                        enabled = true,
                                        onClick = {
                                            val taskId = Random.nextInt()
                                            if (
                                                P2pSenderService.startTaskChecked(
                                                    context,
                                                    TaskInfo(
                                                        id = taskId,
                                                        device = device,
                                                        files = files,
                                                    ),
                                                )
                                            ) {
                                                selectedTransfer = SelectedTransfer(device, taskId)
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                    EasyShareSheetActions(
                        secondaryActionLabel = null,
                        onSecondaryAction = null,
                        primaryActionLabel = stringResource(R.string.cancel),
                        onPrimaryAction = onDone,
                    )
                }
            }
        } else {
            val transfer = checkNotNull(selectedTransfer)
            Box(modifier = Modifier.align(Alignment.BottomCenter)) {
                OutgoingTransferSheet(
                    files = files,
                    transfer = transfer,
                    state = selectedState,
                    onCancel = {
                        P2pSenderService.cancelTask(context, transfer.taskId)
                        onDone()
                    },
                    onDone = onDone,
                )
            }
        }
    }
}

@Parcelize
private data class SelectedTransfer(
    val device: DiscoveredDevice,
    val taskId: Int,
) : Parcelable

@Composable
private fun OutgoingTransferSheet(
    files: List<FileInfo>,
    transfer: SelectedTransfer,
    state: TransferUiState?,
    onCancel: () -> Unit,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val status = state?.status ?: TransferUiStatus.WAITING
    val inProgress = status == TransferUiStatus.WAITING || status == TransferUiStatus.SENDING
    val statusText = when (status) {
        TransferUiStatus.WAITING -> stringResource(R.string.response_waiting)
        TransferUiStatus.SENDING -> stringResource(R.string.device_status_sending)
        TransferUiStatus.SUCCESS -> stringResource(R.string.send_ok)
        TransferUiStatus.PARTIAL -> stringResource(R.string.send_partial)
        TransferUiStatus.FAILED -> stringResource(R.string.send_fail)
        TransferUiStatus.CANCELED -> stringResource(R.string.device_status_canceled)
        TransferUiStatus.REJECTED -> stringResource(R.string.device_status_rejected)
        TransferUiStatus.TIMEOUT -> stringResource(R.string.device_status_timeout)
    }
    val firstFile = files.first()
    val totalSize = files.sumOf { it.size }
    val sizeLabel = totalSize.takeIf { it > 0L }?.let { Formatter.formatFileSize(context, it) }
    val isText = files.size == 1 && firstFile.textContent != null
    val fileName = firstFile.name.takeIf { files.size == 1 && it.isNotBlank() }
    val headline = when {
        isText -> stringResource(R.string.shared_text)
        fileName != null -> fileName
        else -> pluralStringResource(
            R.plurals.incoming_transfer_multiple,
            files.size,
            files.size,
        )
    }
    val supporting = when (status) {
        TransferUiStatus.WAITING -> statusText
        TransferUiStatus.SENDING -> null

        else -> listOfNotNull(sizeLabel, statusText).joinToString(" · ")
    }
    val visualState = when (status) {
        TransferUiStatus.WAITING -> TransferVisualState.FILE
        TransferUiStatus.SENDING -> TransferVisualState.PROGRESS

        TransferUiStatus.SUCCESS,
        TransferUiStatus.PARTIAL -> TransferVisualState.SUCCESS

        TransferUiStatus.FAILED,
        TransferUiStatus.CANCELED,
        TransferUiStatus.REJECTED,
        TransferUiStatus.TIMEOUT -> TransferVisualState.FAILURE
    }

    TransferSheet(
        title = stringResource(R.string.app_name),
        partyText = stringResource(R.string.outgoing_transfer_to, transfer.device.name),
        partyIconRes = DeviceUtils.deviceIconById(transfer.device.brandId),
        headlineText = headline,
        supportingText = supporting,
        fileTypeLabel = fileTypeLabel(
            firstFile.name,
            isText = isText,
            textLabel = stringResource(R.string.text_file_type),
            fallbackLabel = stringResource(R.string.generic_file_type),
        ),
        visualState = visualState,
        progress = when (status) {
            TransferUiStatus.SENDING -> state?.progress ?: 0
            else -> null
        },
        secondaryActionLabel = null,
        onSecondaryAction = null,
        primaryActionLabel = stringResource(
            when {
                inProgress -> R.string.cancel
                status == TransferUiStatus.SUCCESS || status == TransferUiStatus.PARTIAL -> R.string.done
                else -> R.string.close
            },
        ),
        onPrimaryAction = if (inProgress) onCancel else onDone,
    )
}

@Composable
private fun EmptyDeviceState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .navigationBarsPadding()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top,
    ) {
        PagAnimation(
            lightAsset = "pag/sending_bg.pag",
            darkAsset = "pag/sending_bg_dark.pag",
            modifier = Modifier
                .padding(top = 20.dp)
                .size(width = 268.dp, height = 93.dp),
        )
        Text(
            text = stringResource(R.string.no_nearby_devices),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 20.dp)
        )
        Text(
            text = stringResource(R.string.scanning_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

@Composable
private fun DeviceGridItem(
    device: DiscoveredDevice,
    state: TransferUiState?,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val isError = when (state?.status) {
        TransferUiStatus.FAILED,
        TransferUiStatus.CANCELED,
        TransferUiStatus.REJECTED,
        TransferUiStatus.TIMEOUT -> true

        else -> false
    }
    val resultIcon = when (state?.status) {
        TransferUiStatus.SUCCESS,
        TransferUiStatus.PARTIAL -> R.drawable.ic_check_circle
        TransferUiStatus.FAILED,
        TransferUiStatus.CANCELED,
        TransferUiStatus.REJECTED,
        TransferUiStatus.TIMEOUT -> R.drawable.ic_error

        else -> null
    }
    val statusText = when (state?.status) {
        TransferUiStatus.WAITING -> stringResource(R.string.device_status_waiting)
        TransferUiStatus.SENDING -> stringResource(R.string.device_status_sending)
        TransferUiStatus.SUCCESS -> stringResource(R.string.device_status_success)
        TransferUiStatus.PARTIAL -> stringResource(R.string.device_status_partial)
        TransferUiStatus.FAILED -> stringResource(R.string.device_status_failed)
        TransferUiStatus.CANCELED -> stringResource(R.string.device_status_canceled)
        TransferUiStatus.REJECTED -> stringResource(R.string.device_status_rejected)
        TransferUiStatus.TIMEOUT -> stringResource(R.string.device_status_timeout)
        null -> device.brand ?: stringResource(R.string.unknown)
    }

    Surface(
        color = Color.Transparent,
        modifier = Modifier
            .fillMaxWidth()
            .height(deviceItemHeight(state))
            .clickable(enabled = enabled, onClick = onClick)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier.size(62.dp),
                contentAlignment = Alignment.Center
            ) {
                if (state != null) {
                    if (state.status == TransferUiStatus.WAITING) {
                        CircularProgressIndicator(
                            modifier = Modifier.fillMaxSize(),
                            strokeWidth = 2.dp
                        )
                    } else {
                        CircularProgressIndicator(
                            progress = { state.progress.coerceIn(0, 100) / 100f },
                            modifier = Modifier.fillMaxSize(),
                            color = if (isError) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                            trackColor = MaterialTheme.colorScheme.surfaceVariant,
                            strokeWidth = 2.dp
                        )
                    }
                }
                Image(
                    painter = painterResource(DeviceUtils.deviceIconById(device.brandId)),
                    contentDescription = device.brand,
                    modifier = Modifier.size(54.dp)
                )
                resultIcon?.let {
                    Icon(
                        painter = painterResource(it),
                        contentDescription = statusText,
                        tint = if (isError) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .size(22.dp)
                    )
                }
            }
            Text(
                text = device.name,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
            )
            if (state != null) {
                Text(
                    text = statusText,
                    fontSize = 9.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

private fun deviceItemHeight(state: TransferUiState?): Dp = if (state == null) 92.dp else 116.dp

@SuppressLint("MissingPermission")
@Composable
fun deviceScanner(): List<DiscoveredDevice> {
    val context = LocalContext.current
    var discoveredDevices by remember { mutableStateOf(emptyList<DiscoveredDevice>()) }

    LifecycleResumeEffect(context) {
        val manager = context.getSystemService(BluetoothManager::class.java)
        val adapter = manager.adapter
        val devicesLock = Any()

        val callback = object : ScanCallback() {
            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "BLE scan failed: $errorCode")
            }

            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val record = result.scanRecord ?: return
                var supports5Ghz = false
                var deviceName: String? = null
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
                    supports5Ghz = supports5Ghz
                )
                var replaced = false
                synchronized(devicesLock) {
                    val newList = discoveredDevices.map {
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
                    discoveredDevices = newList
                }
            }
        }

        var startedScanner: BluetoothLeScanner? = null

        if (adapter != null) {
            val scanner = adapter.bluetoothLeScanner
            val filters = listOf(
                ScanFilter.Builder().setServiceUuid(ParcelUuid(BleUtils.ADV_SERVICE_UUID)).build()
            )
            val settings =
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()

            try {
                scanner?.startScan(filters, settings, callback)
                startedScanner = scanner
                Log.d(TAG, "Started scanning")
            } catch (e: SecurityException) {
                Log.e(TAG, "Failed to start scan", e)
            }
        }

        onPauseOrDispose {
            try {
                startedScanner?.stopScan(callback)
                Log.d(TAG, "Stopped scanning")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop scan", e)
            }
        }
    }

    return discoveredDevices
}
