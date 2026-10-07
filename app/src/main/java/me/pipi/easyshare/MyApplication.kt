package me.pipi.easyshare

import android.app.Activity
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import me.pipi.easyshare.services.GattServerService
import me.pipi.easyshare.utils.INTERNAL_BROADCAST_PERMISSION
import me.pipi.easyshare.utils.NotificationUtils
import me.pipi.easyshare.utils.ReceiverPolicy
import me.pipi.easyshare.utils.ServiceState
import me.pipi.easyshare.utils.TAG
import me.pipi.easyshare.utils.checkBluetoothPermissions
import me.pipi.easyshare.utils.checkNotificationPermission
import me.pipi.easyshare.utils.checkP2pPermissions
import me.pipi.easyshare.utils.getReceiverFlags
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class MyApplication : Application() {
    private val isBusy = AtomicBoolean()
    private val visibleActivityCount = AtomicInteger()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val receiverPolicy = ReceiverPolicy()
    private val reconcileAfterBackground = Runnable { reconcileReceiver() }
    var receiverRunning = false
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        NotificationUtils.createChannels(this)
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) {
                reconcileReceiver()
            }
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit

            override fun onActivityStarted(activity: Activity) {
                mainHandler.removeCallbacks(reconcileAfterBackground)
                visibleActivityCount.incrementAndGet()
                reconcileReceiver()
            }

            override fun onActivityStopped(activity: Activity) {
                if (visibleActivityCount.updateAndGet { count -> (count - 1).coerceAtLeast(0) } == 0) {
                    // Keep discovery across configuration changes and activity handoffs.
                    mainHandler.postDelayed(reconcileAfterBackground, 700L)
                }
            }
        })
        registerReceiver(
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    when (intent.action) {
                        BluetoothAdapter.ACTION_STATE_CHANGED -> refreshReceiverIdentity()
                        WifiManager.WIFI_STATE_CHANGED_ACTION -> reconcileReceiver()
                    }
                }
            },
            IntentFilter().apply {
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            },
            null,
            null,
            getReceiverFlags(),
        )
    }

    fun setBusy() = if (isBusy.compareAndSet(false, true)) {
        Log.i(TAG, "Setting busy flag")
        sendBroadcast(
            ServiceState.getBusyChangedIntent(true),
            INTERNAL_BROADCAST_PERMISSION
        )
        true
    } else {
        false
    }

    fun clearBusy() {
        Log.i(TAG, "Clearing busy flag")
        isBusy.set(false)
        sendBroadcast(
            ServiceState.getBusyChangedIntent(false),
            INTERNAL_BROADCAST_PERMISSION
        )
        reconcileReceiver()
    }

    fun getBusy() = isBusy.get()

    fun hasVisibleActivity() = visibleActivityCount.get() > 0

    fun withGattResponse(response: () -> Unit) = receiverPolicy.withGattResponse(response)

    fun setBackgroundReceiveEnabled(enabled: Boolean) {
        AppSettings(this).backgroundReceiveEnabled = enabled
        sendBroadcast(
            Intent(ACTION_BACKGROUND_RECEIVE_CHANGED).setPackage(packageName)
                .putExtra("enabled", enabled),
            INTERNAL_BROADCAST_PERMISSION,
        )
        reconcileReceiver()
    }

    fun refreshReceiverIdentity() {
        mainHandler.post {
            receiverPolicy.requestRestart()
            reconcileReceiver()
        }
    }

    fun reconcileReceiver() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { reconcileReceiver() }
            return
        }
        val bluetoothEnabled = try {
            getSystemService(BluetoothManager::class.java).adapter?.isEnabled == true
        } catch (_: SecurityException) {
            false
        }
        val wifiEnabled = getSystemService(WifiManager::class.java).isWifiEnabled
        val available = checkBluetoothPermissions() && checkP2pPermissions() &&
            checkNotificationPermission() && bluetoothEnabled && wifiEnabled
        when (receiverPolicy.reconcile(
            visible = hasVisibleActivity() || mainHandler.hasCallbacks(reconcileAfterBackground),
            backgroundEnabled = AppSettings(this).backgroundReceiveEnabled,
            busy = getBusy(),
            available = available,
        )) {
            ReceiverPolicy.Action.START -> try {
                checkNotNull(startService(GattServerService.getIntent(this)))
            } catch (error: Exception) {
                receiverPolicy.serviceStopped()
                Log.w(TAG, "Failed to start receiving service", error)
            }
            ReceiverPolicy.Action.STOP -> {
                if (!stopService(GattServerService.getIntent(this))) {
                    onReceiverServiceStopped()
                }
            }
            ReceiverPolicy.Action.NONE -> Unit
        }
    }

    fun onReceiverServiceStarted() {
        receiverRunning = true
        receiverPolicy.serviceStarted()
        reconcileReceiver()
    }

    fun onReceiverServiceStopped() {
        receiverRunning = false
        if (receiverPolicy.serviceStopped()) mainHandler.post { reconcileReceiver() }
    }

    companion object {
        const val ACTION_BUSY_CHANGED = "me.pipi.easyshare.BUSY_CHANGED"
        const val ACTION_BACKGROUND_RECEIVE_CHANGED = "me.pipi.easyshare.BACKGROUND_RECEIVE_CHANGED"

        private var instance: MyApplication? = null
        fun getInstance() = instance!!
    }
}
