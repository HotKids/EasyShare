package me.pipi.easyshare.ui.main

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.pipi.easyshare.AppSettings
import me.pipi.easyshare.MyApplication
import me.pipi.easyshare.R
import me.pipi.easyshare.services.GattServerService
import me.pipi.easyshare.utils.DeviceUtils
import me.pipi.easyshare.utils.BleUtils
import me.pipi.easyshare.utils.ServiceState
import me.pipi.easyshare.utils.ShizukuUtils
import me.pipi.easyshare.utils.TAG
import me.pipi.easyshare.utils.registerInternalBroadcastReceiver
import rikka.shizuku.Shizuku

data class MainUiState(
    val receiverEnabled: Boolean = false,
    val busy: Boolean = false,
    val deviceName: String = "Android",
    val configuredBrandId: Int = -1,
    val effectiveBrandId: Int = 0,
    val receivePath: String? = null,
    val secureReceiveOnly: Boolean = false,
    val secureSendOnly: Boolean = false,
    val shizukuAvailable: Boolean = false,
    val shizukuGranted: Boolean = false,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val context = application.applicationContext
    private val settings = AppSettings(context)
    private var enableEnhancedModeAfterPermission = false

    private val _state = MutableStateFlow(
        MainUiState(
            busy = MyApplication.getInstance().getBusy(),
            deviceName = settings.deviceName,
            configuredBrandId = settings.brandId,
            effectiveBrandId = DeviceUtils.getLocalBrandId(),
            receivePath = usableReceivePath(),
            secureReceiveOnly = settings.secureReceiveOnly,
            secureSendOnly = settings.secureSendOnly,
        )
    )
    val state: StateFlow<MainUiState> = _state.asStateFlow()

    private val appStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ServiceState.ACTION_UPDATE_RECEIVER_STATE -> {
                    _state.value = _state.value.copy(
                        receiverEnabled = intent.getBooleanExtra("isRunning", false)
                    )
                }
                ServiceState.ACTION_BUSY_CHANGED -> {
                    _state.value = _state.value.copy(
                        busy = intent.getBooleanExtra("busy", false)
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
        context.registerInternalBroadcastReceiver(
            appStateReceiver,
            IntentFilter().apply {
                addAction(ServiceState.ACTION_UPDATE_RECEIVER_STATE)
                addAction(ServiceState.ACTION_BUSY_CHANGED)
            },
        )
        Shizuku.addRequestPermissionResultListener(permissionListener)
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        context.sendBroadcast(ServiceState.getQueryIntent())
    }

    fun setReceiverEnabled(enabled: Boolean) {
        if (_state.value.busy) return
        if (enabled) GattServerService.start(context) else GattServerService.stop(context)
    }

    fun setDeviceName(name: String) {
        val safeName = BleUtils.normalizeDeviceName(name)
        settings.deviceName = safeName
        _state.value = _state.value.copy(deviceName = safeName)
        stopReceiverForIdentityChange()
    }

    fun setBrand(brandId: Int) {
        settings.brandId = brandId
        _state.value = _state.value.copy(
            configuredBrandId = brandId,
            effectiveBrandId = DeviceUtils.getLocalBrandId(),
        )
        stopReceiverForIdentityChange()
    }

    fun setReceivePath(uri: Uri) {
        settings.downloadUri = uri.toString()
        _state.value = _state.value.copy(receivePath = uri.toString())
    }

    fun setSecureReceiveOnly(enabled: Boolean) {
        settings.secureReceiveOnly = enabled
        _state.value = _state.value.copy(secureReceiveOnly = enabled)
    }

    fun setSecureSendOnly(enabled: Boolean) {
        settings.secureSendOnly = enabled
        _state.value = _state.value.copy(secureSendOnly = enabled)
    }

    /**
     * The receiver silently falls back to the default folder when the persisted grant for a
     * custom location is gone; drop the setting in that case so the UI does not show a path
     * that will not be used.
     */
    private fun usableReceivePath(): String? {
        val configured = settings.downloadUri ?: return null
        val granted = context.contentResolver.persistedUriPermissions.any {
            it.uri.toString() == configured && it.isWritePermission
        }
        if (!granted) {
            Log.w(TAG, "Dropping the receive path because its access grant is gone")
            settings.downloadUri = null
            return null
        }
        return configured
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

    private fun stopReceiverForIdentityChange() {
        if (_state.value.receiverEnabled) GattServerService.stop(context)
    }

    override fun onCleared() {
        context.unregisterReceiver(appStateReceiver)
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        super.onCleared()
    }
}
