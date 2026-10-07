package me.pipi.easyshare.ui.main

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import me.pipi.easyshare.AppSettings
import me.pipi.easyshare.MyApplication
import me.pipi.easyshare.R
import me.pipi.easyshare.utils.DeviceUtils
import me.pipi.easyshare.utils.BleUtils
import me.pipi.easyshare.utils.IncomingTransferUiCoordinator
import me.pipi.easyshare.utils.LiveUpdateCoordinator
import me.pipi.easyshare.utils.TransferUiCoordinator
import me.pipi.easyshare.utils.ServiceState
import me.pipi.easyshare.utils.ShizukuUtils
import me.pipi.easyshare.utils.TAG
import me.pipi.easyshare.utils.getReceiverFlags
import me.pipi.easyshare.utils.registerInternalBroadcastReceiver
import rikka.shizuku.Shizuku

data class MainUiState(
    val receiverEnabled: Boolean = false,
    val receiverRunning: Boolean = false,
    val wifiEnabled: Boolean = false,
    val bluetoothEnabled: Boolean = false,
    val busy: Boolean = false,
    val transferStatus: HomeTransferStatus? = null,
    val deviceName: String = "Android",
    val configuredBrandId: Int = -1,
    val effectiveBrandId: Int = 0,
    val receivePath: String? = null,
    val secureReceiveOnly: Boolean = false,
    val shizukuAvailable: Boolean = false,
    val shizukuGranted: Boolean = false,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val context: Application
        get() = getApplication()
    private val settings = AppSettings(context)
    private var enableEnhancedModeAfterPermission = false

    private val _state = MutableStateFlow(
        MainUiState(
            receiverEnabled = settings.backgroundReceiveEnabled,
            receiverRunning = MyApplication.getInstance().receiverRunning,
            wifiEnabled = isWifiEnabled(),
            bluetoothEnabled = isBluetoothEnabled(),
            busy = MyApplication.getInstance().getBusy(),
            deviceName = settings.deviceName,
            configuredBrandId = settings.brandId,
            effectiveBrandId = DeviceUtils.getLocalBrandId(),
            receivePath = settings.downloadUri,
            secureReceiveOnly = settings.secureReceiveOnly,
        )
    )
    val state: StateFlow<MainUiState> = _state.asStateFlow()

    private val appStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ServiceState.ACTION_UPDATE_RECEIVER_STATE -> {
                    _state.value = _state.value.copy(
                        receiverRunning = intent.getBooleanExtra("isRunning", false)
                    )
                }
                MyApplication.ACTION_BACKGROUND_RECEIVE_CHANGED -> {
                    _state.value = _state.value.copy(
                        receiverEnabled = settings.backgroundReceiveEnabled
                    )
                }
                MyApplication.ACTION_BUSY_CHANGED -> {
                    _state.value = _state.value.copy(
                        busy = intent.getBooleanExtra("busy", false)
                    )
                }
            }
        }
    }

    private val radioStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                WifiManager.WIFI_STATE_CHANGED_ACTION -> {
                    _state.value = _state.value.copy(
                        wifiEnabled = intent.getIntExtra(
                            WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_UNKNOWN
                        ) == WifiManager.WIFI_STATE_ENABLED
                    )
                }
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    _state.value = _state.value.copy(
                        bluetoothEnabled = intent.getIntExtra(
                            BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR
                        ) == BluetoothAdapter.STATE_ON
                    )
                }
            }
        }
    }

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { _, result ->
        val granted = result == PackageManager.PERMISSION_GRANTED
        if (enableEnhancedModeAfterPermission) {
            ShizukuUtils.setEnhancedModeEnabled(context, granted)
            enableEnhancedModeAfterPermission = false
        }
        _state.value = _state.value.copy(
            shizukuGranted = granted && settings.enhancedModeEnabled
        )
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        _state.value = _state.value.copy(
            shizukuAvailable = true,
            shizukuGranted = settings.enhancedModeEnabled &&
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED,
        )
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        _state.value = _state.value.copy(
            shizukuAvailable = false,
            shizukuGranted = false,
        )
    }

    init {
        viewModelScope.launch {
            combine(
                LiveUpdateCoordinator.state,
                IncomingTransferUiCoordinator.states,
                TransferUiCoordinator.states,
                ::homeTransferStatus,
            ).collect { status ->
                _state.value = _state.value.copy(transferStatus = status)
            }
        }
        context.registerInternalBroadcastReceiver(
            appStateReceiver,
            IntentFilter().apply {
                addAction(ServiceState.ACTION_UPDATE_RECEIVER_STATE)
                addAction(MyApplication.ACTION_BUSY_CHANGED)
                addAction(MyApplication.ACTION_BACKGROUND_RECEIVE_CHANGED)
            },
        )
        context.registerReceiver(
            radioStateReceiver,
            IntentFilter().apply {
                addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            },
            null,
            null,
            getReceiverFlags(),
        )
        Shizuku.addRequestPermissionResultListener(permissionListener)
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        context.sendBroadcast(ServiceState.getQueryIntent())
    }

    fun setReceiverEnabled(enabled: Boolean) {
        MyApplication.getInstance().setBackgroundReceiveEnabled(enabled)
        _state.value = _state.value.copy(receiverEnabled = enabled)
        refreshRadioState()
    }

    fun refreshRadioState() {
        _state.value = _state.value.copy(
            wifiEnabled = isWifiEnabled(),
            bluetoothEnabled = isBluetoothEnabled(),
        )
    }

    private fun isWifiEnabled(): Boolean = try {
        context.getSystemService(WifiManager::class.java).isWifiEnabled
    } catch (_: SecurityException) {
        false
    }

    private fun isBluetoothEnabled(): Boolean = try {
        context.getSystemService(BluetoothManager::class.java).adapter?.isEnabled == true
    } catch (_: SecurityException) {
        false
    }

    fun setDeviceName(name: String) {
        val safeName = BleUtils.normalizeDeviceName(name)
        settings.deviceName = safeName
        _state.value = _state.value.copy(deviceName = safeName)
        MyApplication.getInstance().refreshReceiverIdentity()
    }

    fun setBrand(brandId: Int) {
        settings.brandId = brandId
        _state.value = _state.value.copy(
            configuredBrandId = brandId,
            effectiveBrandId = DeviceUtils.getLocalBrandId(),
        )
        MyApplication.getInstance().refreshReceiverIdentity()
    }

    fun setReceivePath(uri: Uri) {
        settings.downloadUri = uri.toString()
        _state.value = _state.value.copy(receivePath = uri.toString())
    }

    fun setSecureReceiveOnly(enabled: Boolean) {
        settings.secureReceiveOnly = enabled
        _state.value = _state.value.copy(secureReceiveOnly = enabled)
    }

    fun setEnhancedMode(enabled: Boolean) {
        val current = _state.value
        when {
            !current.shizukuAvailable -> {
                Toast.makeText(context, R.string.shizuku_unavailable, Toast.LENGTH_LONG).show()
            }
            !enabled -> {
                enableEnhancedModeAfterPermission = false
                ShizukuUtils.setEnhancedModeEnabled(context, false)
                _state.value = current.copy(shizukuGranted = false)
            }
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> {
                ShizukuUtils.setEnhancedModeEnabled(context, true)
                _state.value = current.copy(shizukuGranted = true)
            }
            else -> try {
                enableEnhancedModeAfterPermission = true
                Shizuku.requestPermission(0)
            } catch (error: Throwable) {
                enableEnhancedModeAfterPermission = false
                Log.e(TAG, "Failed to request Shizuku permission", error)
                Toast.makeText(context, R.string.shizuku_unavailable, Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onCleared() {
        context.unregisterReceiver(appStateReceiver)
        context.unregisterReceiver(radioStateReceiver)
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
    }
}
