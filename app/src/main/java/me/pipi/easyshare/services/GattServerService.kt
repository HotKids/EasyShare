package me.pipi.easyshare.services


import android.annotation.SuppressLint
import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.pipi.easyshare.AppSettings
import me.pipi.easyshare.BleSecurity
import me.pipi.easyshare.BuildConfig
import me.pipi.easyshare.R
import me.pipi.easyshare.SessionSecurity
import me.pipi.easyshare.models.DeviceInfo
import me.pipi.easyshare.models.P2pInfo
import me.pipi.easyshare.utils.BleUtils
import me.pipi.easyshare.utils.DeviceUtils
import me.pipi.easyshare.utils.JsonWithUnknownKeys
import me.pipi.easyshare.utils.NotificationUtils
import me.pipi.easyshare.utils.ServiceState
import me.pipi.easyshare.utils.ShizukuUtils
import me.pipi.easyshare.utils.TAG
import me.pipi.easyshare.utils.checkBluetoothPermissions
import me.pipi.easyshare.utils.checkNotificationPermission
import me.pipi.easyshare.utils.getReceiverFlags
import me.pipi.easyshare.utils.registerInternalBroadcastReceiver
import java.util.Arrays
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

class GattServerService : Service() {
    private data class PendingGattWrite(
        val bytes: ByteArray = ByteArray(MAX_GATT_PAYLOAD_BYTES),
        var length: Int = 0,
        val expectedLength: Int? = null,
        val createdAtMs: Long = SystemClock.elapsedRealtime(),
    )

    private lateinit var btManager: BluetoothManager
    private var btAdvertiser: BluetoothLeAdvertiser? = null

    private var advertisingSet: AdvertisingSet? = null
    @Volatile
    private var destroyed = false

    private val localDeviceInfoLock = Any()
    private var sessionKeyPair = BleSecurity.SessionKeyPair.generate()
    private var localDeviceInfo = DeviceInfo(
        0,
        sessionKeyPair.encodedPublicKey,
        "02:00:00:00:00:00",
        BuildConfig.VERSION_CODE,
        BleSecurity.PROTECTED_SESSION_CRYPTO_VERSION,
    )
    private var localDeviceStatusBytes = Json.encodeToString(localDeviceInfo).toByteArray()

    private val internalReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ServiceState.ACTION_QUERY_RECEIVER_STATE -> {
                    context.sendBroadcast(ServiceState.getUpdateIntent(true))
                }

                ServiceState.ACTION_STOP_SERVICE -> {
                    Log.i(GattServerService.TAG, "Received ACTION_STOP_SERVICE")
                    stopSelf()
                }
            }
        }
    }
    private var internalReceiverRegistered = false

    private val advSetCallback = object : AdvertisingSetCallback() {
        @SuppressLint("MissingPermission")
        override fun onAdvertisingSetStarted(
            advertisingSet: AdvertisingSet?, txPower: Int, status: Int
        ) {
            if (status == ADVERTISE_SUCCESS) {
                if (destroyed) {
                    runCatching { btAdvertiser?.stopAdvertisingSet(this) }
                        .onFailure { Log.w(TAG, "Failed to stop late BLE advertiser", it) }
                } else {
                    this@GattServerService.advertisingSet = advertisingSet
                }
            } else {
                Log.e(TAG, "Advertising failed: $status")
            }
        }
    }

    private var gattServer: BluetoothGattServer? = null
    // Buffers for ATT prepared (long) writes, keyed by the writing central.
    private val pendingGattWrites = ConcurrentHashMap<BluetoothDevice, PendingGattWrite>()
    // Reassembly buffers for framed Easy Share payloads, kept apart from the long-write buffers
    // so a frame that itself arrives as a long write does not clobber the assembly in progress.
    private val pendingChunkAssemblies = ConcurrentHashMap<BluetoothDevice, PendingGattWrite>()

    @SuppressLint("MissingPermission")
    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (characteristic.uuid != BleUtils.CHAR_STATUS_UUID) {
                gattServer?.sendResponse(device, requestId, 257, 0, null)
                return
            }

            if (offset < 0) {
                gattServer?.sendResponse(device, requestId, GATT_INVALID_OFFSET, 0, null)
                return
            }
            val data = synchronized(localDeviceInfoLock) {
                if (offset <= localDeviceStatusBytes.size) {
                    localDeviceStatusBytes.copyOfRange(offset, localDeviceStatusBytes.size)
                } else {
                    null
                }
            }
            gattServer?.sendResponse(
                device,
                requestId,
                if (data == null) GATT_INVALID_OFFSET else BluetoothGatt.GATT_SUCCESS,
                offset,
                data,
            )
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            if (characteristic.uuid != BleUtils.CHAR_P2P_UUID) {
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, 257, 0, null)
                }
                return
            }

            if (offset < 0 || value.size > MAX_GATT_PAYLOAD_BYTES - offset) {
                if (responseNeeded) {
                    gattServer?.sendResponse(
                        device,
                        requestId,
                        if (offset < 0 || offset > MAX_GATT_PAYLOAD_BYTES) {
                            GATT_INVALID_OFFSET
                        } else {
                            GATT_INVALID_ATTRIBUTE_LENGTH
                        },
                        offset.coerceAtLeast(0),
                        null,
                    )
                }
                pendingGattWrites.remove(device)
                return
            }

            if (preparedWrite) {
                val existing = pendingGattWrites[device]
                val write = if (
                    existing == null ||
                    existing.expectedLength != null ||
                    SystemClock.elapsedRealtime() - existing.createdAtMs > GATT_WRITE_TIMEOUT_MS
                ) {
                    if (existing == null && pendingGattWrites.size >= MAX_PENDING_GATT_DEVICES) {
                        if (responseNeeded) {
                            gattServer?.sendResponse(
                                device, requestId, BluetoothGatt.GATT_FAILURE, offset, null,
                            )
                        }
                        return
                    }
                    PendingGattWrite().also { pendingGattWrites[device] = it }
                } else {
                    existing
                }
                value.copyInto(write.bytes, destinationOffset = offset)
                write.length = maxOf(write.length, offset + value.size)
                if (responseNeeded) {
                    gattServer?.sendResponse(
                        device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value,
                    )
                }
                return
            }

            if (offset != 0) {
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, GATT_INVALID_OFFSET, offset, null)
                }
                return
            }
            val success = handleP2pWrite(device, value)
            if (responseNeeded) {
                gattServer?.sendResponse(
                    device,
                    requestId,
                    if (success) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE,
                    0,
                    null,
                )
            }
        }

        override fun onExecuteWrite(device: BluetoothDevice, requestId: Int, execute: Boolean) {
            val pending = pendingGattWrites.remove(device)
            val success = if (!execute) {
                true
            } else if (pending == null || pending.length == 0 || pending.expectedLength != null) {
                false
            } else {
                // A long write carries either one framed chunk (small MTU) or a whole legacy
                // payload; handleP2pWrite tells the two apart.
                handleP2pWrite(device, pending.bytes.copyOf(pending.length))
            }
            gattServer?.sendResponse(
                device,
                requestId,
                if (success) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE,
                0,
                null,
            )
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState != BluetoothProfile.STATE_CONNECTED) {
                pendingGattWrites.remove(device)
                pendingChunkAssemblies.remove(device)
            }
        }

        private fun handleP2pWrite(device: BluetoothDevice, value: ByteArray): Boolean {
            return try {
                val chunk = BleUtils.parseP2pPayloadChunk(value)
                if (chunk == null) {
                    pendingChunkAssemblies.remove(device)
                    handleP2pPayload(value)
                } else {
                    handleP2pPayloadChunk(device, chunk)
                }
            } catch (error: IllegalArgumentException) {
                pendingChunkAssemblies.remove(device)
                Log.w(TAG, "Rejected malformed chunked GATT metadata")
                false
            }
        }

        private fun handleP2pPayloadChunk(
            device: BluetoothDevice,
            chunk: BleUtils.P2pPayloadChunk,
        ): Boolean {
            val now = SystemClock.elapsedRealtime()
            val existing = pendingChunkAssemblies[device]
            val pending = if (chunk.offset == 0) {
                if (existing == null && pendingChunkAssemblies.size >= MAX_PENDING_GATT_DEVICES) {
                    return false
                }
                PendingGattWrite(expectedLength = chunk.totalSize).also {
                    pendingChunkAssemblies[device] = it
                }
            } else {
                if (existing == null || now - existing.createdAtMs > GATT_WRITE_TIMEOUT_MS) {
                    pendingChunkAssemblies.remove(device)
                    return false
                }
                existing
            }

            if (pending.expectedLength != chunk.totalSize || pending.length != chunk.offset) {
                pendingChunkAssemblies.remove(device)
                return false
            }

            chunk.payload.copyInto(pending.bytes, destinationOffset = pending.length)
            pending.length += chunk.payload.size
            if (pending.length < chunk.totalSize) return true

            pendingChunkAssemblies.remove(device)
            return handleP2pPayload(pending.bytes.copyOf(pending.length))
        }

        private fun handleP2pPayload(data: ByteArray): Boolean {
            return try {
                require(data.isNotEmpty() && data.size <= MAX_GATT_PAYLOAD_BYTES)
                var p2pInfo: P2pInfo = JsonWithUnknownKeys.decodeFromString(
                    data.decodeToString(throwOnInvalidSequence = true),
                )
                if (!SessionSecurity.isPeerAllowed(
                        AppSettings(this@GattServerService).secureReceiveOnly,
                        p2pInfo.cryptoVersion,
                    )
                ) {
                    Log.i(TAG, "Rejected peer without secure protocol support")
                    return false
                }
                val ecKey = p2pInfo.key
                if (SessionSecurity.usesModernProtocol(p2pInfo.cryptoVersion)) {
                    // A modern peer must publish its session key; otherwise the credentials
                    // would be accepted in plain text under a secure label.
                    require(ecKey != null) { "Modern peer omitted its session key" }
                }
                if (ecKey != null) {
                    val keyPair = synchronized(localDeviceInfoLock) { sessionKeyPair }
                    val cipher = keyPair.deriveSessionKey(ecKey, p2pInfo.cryptoVersion)
                    val protectedMetadata =
                        SessionSecurity.protectsSessionMetadata(p2pInfo.cryptoVersion)
                    p2pInfo = P2pInfo(
                        id = BleUtils.getSenderId(),
                        ssid = cipher.decrypt("ssid", p2pInfo.ssid),
                        psk = cipher.decrypt("psk", p2pInfo.psk),
                        mac = cipher.decrypt("mac", p2pInfo.mac),
                        port = p2pInfo.port,
                        key = null,
                        easyShare = p2pInfo.easyShare,
                        cryptoVersion = p2pInfo.cryptoVersion,
                        authToken = p2pInfo.authToken?.let {
                            if (protectedMetadata) cipher.decrypt("token", it) else it
                        },
                        certificateSha256 = p2pInfo.certificateSha256?.let {
                            if (protectedMetadata) cipher.decrypt("cert", it) else it
                        },
                    )
                }
                require(p2pInfo.ssid.toByteArray().size in 1..MAX_SSID_BYTES)
                require(p2pInfo.psk.toByteArray().size in MIN_PSK_BYTES..MAX_PSK_BYTES)
                // Mirror WifiP2pConfig.Builder so junk is rejected here instead of after the
                // receiver service has already started and marked the app busy.
                require(P2P_NETWORK_NAME_PATTERN.matches(p2pInfo.ssid))
                require(p2pInfo.psk.length in MIN_PSK_CHARS..MAX_PSK_CHARS)
                require(p2pInfo.mac.toByteArray().size <= MAX_MAC_BYTES)
                require(p2pInfo.port in 1..65535)
                if (SessionSecurity.usesModernProtocol(p2pInfo.cryptoVersion)) {
                    require(p2pInfo.authToken?.length in 32..128)
                    require(p2pInfo.certificateSha256?.matches(Regex("[0-9a-f]{64}")) == true)
                }
                startService(P2pReceiverService.getIntent(this@GattServerService, p2pInfo))
                // The accepted request consumed this key pair; advertise a fresh one.
                rotateSessionKey()
                true
            } catch (error: Throwable) {
                Log.w(TAG, "Rejected malformed GATT transfer metadata", error)
                false
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        destroyed = false

        if (!checkBluetoothPermissions() || !checkNotificationPermission()) {
            Toast.makeText(this, R.string.permission_not_granted, Toast.LENGTH_LONG).show()
            stopSelf()
            return
        }

        try {
            btManager = getSystemService(BluetoothManager::class.java)
            val btAdapter = btManager.adapter
            if (btAdapter == null || !btAdapter.isEnabled) {
                throw IllegalStateException("Bluetooth not enabled")
            }
            btAdvertiser = btAdapter.bluetoothLeAdvertiser
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize BT", e)
            NotificationUtils.showBluetoothToast(this)
            stopSelf()
            return
        }

        try {
            startForeground(
                NotificationUtils.ID_RECEIVER_READY,
                createNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } catch (e: Exception) {
            if (Build.VERSION.SDK_INT >= 31 && e is ForegroundServiceStartNotAllowedException) {
                Log.e(TAG, "Service startup not allowed", e)
            } else {
                Log.e(TAG, "Service startup failed", e)
            }
            stopSelf()
            return
        }

        ShizukuUtils.getMacAddress(this, "p2p0") {
            if (it != null) {
                updateMacAddress(it)
            }
        }

        startAdv()

        registerInternalBroadcastReceiver(internalReceiver, IntentFilter().apply {
            addAction(ServiceState.ACTION_QUERY_RECEIVER_STATE)
            addAction(ServiceState.ACTION_STOP_SERVICE)
        })
        internalReceiverRegistered = true
        registerReceiver(
            bluetoothStateReceiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            getReceiverFlags(),
        )
        bluetoothStateReceiverRegistered = true
        sendBroadcast(ServiceState.getUpdateIntent(true))
    }

    private fun createNotification(): Notification {
        val pi = PendingIntent.getBroadcast(
            this,
            0,
            ServiceState.getStopIntent(),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NotificationUtils.RECEIVER_FG_CHAN_ID)
            .setSmallIcon(R.drawable.ic_sync_alt)
            .setContentTitle(getString(R.string.noti_receiver_title))
            .setContentText(getString(R.string.discoverable_desc))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .addAction(R.drawable.ic_close, getString(R.string.stop), pi)
            .build()
    }

    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    fun startAdv() {
        val advertiser = btAdvertiser ?: return
        val localBrandId = DeviceUtils.getLocalBrandId()
        val bleBrandId = if (localBrandId == 114514) 114 else localBrandId
        // Byte 2 of the service data UUID advertises 5 GHz support; senders pick the group band
        // from it. Fall back to the historical "supported" value if the query is unavailable.
        val supports5Ghz = runCatching {
            getSystemService(WifiManager::class.java)?.is5GHzBandSupported()
        }.getOrNull() ?: true

        val advData = AdvertiseData.Builder().apply {
            addServiceUuid(ParcelUuid(BleUtils.ADV_SERVICE_UUID))
            addServiceData(
                ParcelUuid.fromString(
                    String.format(
                        "0000%02x%02x-0000-1000-8000-00805f9b34fb",
                        if (supports5Ghz) 1 else 0,
                        bleBrandId and 0xff,
                    )
                ), Arrays.copyOfRange(BleUtils.RANDOM_DATA, 0, 6)
            )
        }.build()
        val scanRespData = AdvertiseData.Builder().apply {
            val data = ByteArray(27)
            System.arraycopy(ByteArray(8), 0, data, 0, 8)
            System.arraycopy(BleUtils.RANDOM_DATA, 0, data, 8, 2)

            val nameBytes = BleUtils.advertisementNameBytes(
                AppSettings(this@GattServerService).deviceName,
            )
            System.arraycopy(nameBytes, 0, data, 10, min(nameBytes.size, 16))

            data[26] = 1

            addServiceData(ParcelUuid.fromString("0000ffff-0000-1000-8000-00805f9b34fb"), data)
        }.build()

        val params = AdvertisingSetParameters.Builder().apply {
            setLegacyMode(true)
            setConnectable(true)
            setScannable(true)
            setInterval(160)
            setTxPowerLevel(1)
        }.build()

        try {
            advertiser.startAdvertisingSet(
                params, advData, scanRespData, null, null, 0, 0, advSetCallback
            )

            gattServer = btManager.openGattServer(this, gattServerCallback).apply {
                addService(buildGattService())
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Got SecurityException when trying to advertise", e)
            stopSelf()
        }
    }

    private fun buildGattService(): BluetoothGattService {
        val svc = BluetoothGattService(
            BleUtils.SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY
        )
        svc.addCharacteristic(
            BluetoothGattCharacteristic(BleUtils.CHAR_STATUS_UUID, 10, 17)
        )
        svc.addCharacteristic(
            BluetoothGattCharacteristic(BleUtils.CHAR_P2P_UUID, 10, 17)
        )
        return svc
    }

    @SuppressLint("MissingPermission")
    override fun onDestroy() {
        destroyed = true
        if (internalReceiverRegistered) {
            unregisterReceiver(internalReceiver)
            internalReceiverRegistered = false
        }
        if (bluetoothStateReceiverRegistered) {
            unregisterReceiver(bluetoothStateReceiver)
            bluetoothStateReceiverRegistered = false
        }
        sendBroadcast(ServiceState.getUpdateIntent(false))

        try {
            btAdvertiser?.stopAdvertisingSet(advSetCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop advertising", e)
        }
        advertisingSet = null


        try {
            gattServer?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop GATT server", e)
        }
        gattServer = null
        pendingGattWrites.clear()
        pendingChunkAssemblies.clear()
        super.onDestroy()
    }

    private fun updateMacAddress(mac: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, "Updating local P2P interface metadata")
        synchronized(localDeviceInfoLock) {
            localDeviceInfo = DeviceInfo(
                state = localDeviceInfo.state,
                mac = mac,
                key = localDeviceInfo.key,
                easyShare = BuildConfig.VERSION_CODE,
                cryptoVersion = BleSecurity.PROTECTED_SESSION_CRYPTO_VERSION,
            )
            localDeviceStatusBytes = Json.encodeToString(localDeviceInfo).toByteArray()
        }
    }

    private fun rotateSessionKey() {
        synchronized(localDeviceInfoLock) {
            sessionKeyPair = BleSecurity.SessionKeyPair.generate()
            localDeviceInfo = localDeviceInfo.copy(key = sessionKeyPair.encodedPublicKey)
            localDeviceStatusBytes = Json.encodeToString(localDeviceInfo).toByteArray()
        }
    }

    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
            if (state == BluetoothAdapter.STATE_TURNING_OFF || state == BluetoothAdapter.STATE_OFF) {
                // Advertising and the GATT server die with the adapter; stop instead of showing
                // an "active" receiver that nobody can reach.
                Log.i(TAG, "Bluetooth is turning off; stopping the receiver")
                stopSelf()
            }
        }
    }
    private var bluetoothStateReceiverRegistered = false

    companion object {
        private const val MAX_GATT_PAYLOAD_BYTES = BleUtils.MAX_P2P_GATT_PAYLOAD_BYTES
        private const val MAX_PENDING_GATT_DEVICES = 8
        private const val GATT_WRITE_TIMEOUT_MS = 30_000L
        private const val GATT_INVALID_OFFSET = 7
        private const val GATT_INVALID_ATTRIBUTE_LENGTH = 13
        private const val MAX_SSID_BYTES = 128
        private val P2P_NETWORK_NAME_PATTERN = Regex("^DIRECT-[A-Za-z0-9]{2}.*")
        private const val MIN_PSK_CHARS = 8
        private const val MAX_PSK_CHARS = 63
        private const val MIN_PSK_BYTES = 8
        private const val MAX_PSK_BYTES = 128
        private const val MAX_MAC_BYTES = 64

        fun getIntent(context: Context): Intent {
            return Intent(context, GattServerService::class.java)
        }

        fun start(context: Context) {
            context.startService(getIntent(context))
        }

        fun stop(context: Context) {
            context.stopService(getIntent(context))
        }
    }
}
