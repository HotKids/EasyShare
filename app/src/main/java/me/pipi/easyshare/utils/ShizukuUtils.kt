package me.pipi.easyshare.utils

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.pipi.easyshare.AppSettings
import me.pipi.easyshare.BuildConfig
import me.pipi.easyshare.IMacAddressService
import me.pipi.easyshare.MyApplication
import me.pipi.easyshare.services.MacAddressService
import rikka.shizuku.Shizuku
import java.net.NetworkInterface
import kotlin.collections.iterator

object ShizukuUtils {
    private val binderLock = Object()
    private var macService: IMacAddressService? = null
    private var serviceReady = CompletableDeferred<IMacAddressService>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        Shizuku.addBinderReceivedListenerSticky {
            if (AppSettings(MyApplication.getInstance()).enhancedModeEnabled) {
                bindService()
            }
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder?) {
            if (service != null && service.pingBinder()) {
                if (BuildConfig.DEBUG) Log.d(ShizukuUtils.TAG, "MAC service connected")
                val connectedService = IMacAddressService.Stub.asInterface(service)

                synchronized(binderLock) {
                    macService = connectedService
                    if (!serviceReady.isCompleted) serviceReady.complete(connectedService)
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            if (BuildConfig.DEBUG) Log.d(ShizukuUtils.TAG, "MAC service disconnected")
            synchronized(binderLock) {
                macService = null
                serviceReady = CompletableDeferred()
            }
        }
    }

    private fun userServiceArgs(): Shizuku.UserServiceArgs {
        val cn = ComponentName(
            BuildConfig.APPLICATION_ID, MacAddressService::class.java.name
        )
        return Shizuku.UserServiceArgs(cn)
            .daemon(false)
            .processNameSuffix("service")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
    }

    fun unsafeBindService() {
        Shizuku.bindUserService(userServiceArgs(), serviceConnection)
    }

    fun bindService() {
        if (!AppSettings(MyApplication.getInstance()).enhancedModeEnabled) return
        try {
            unsafeBindService()
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to bind service", e)
        }
    }

    fun setEnhancedModeEnabled(context: Context, enabled: Boolean) {
        AppSettings(context).enhancedModeEnabled = enabled
        if (enabled) {
            bindService()
        } else {
            try {
                Shizuku.unbindUserService(userServiceArgs(), serviceConnection, true)
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to unbind Shizuku user service", e)
            }
            synchronized(binderLock) {
                macService = null
                serviceReady = CompletableDeferred()
            }
        }
    }

    fun getMacAddress(context: Context, name: String, l: (String?) -> Unit) {
        scope.launch {
            l(getMacAddress(context, name))
        }
    }

    @OptIn(ExperimentalStdlibApi::class)
    private fun nativeGetMacAddressByName(name: String): String? {
        val ifs = NetworkInterface.getNetworkInterfaces()
        for (intf in ifs) {
            if (intf.name == name) {
                return intf.hardwareAddress?.toHexString(HexFormat {
                    bytes.byteSeparator = ":"
                })
            }
        }
        return null
    }

    suspend fun getMacAddress(context: Context, name: String): String? {
        if (!AppSettings(context).enhancedModeEnabled) return null
        if (context.checkSelfPermission("android.permission.LOCAL_MAC_ADDRESS") ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return withContext(Dispatchers.IO) { nativeGetMacAddressByName(name) }
        }

        return try {
            val current = synchronized(binderLock) { macService }
            val service = current ?: run {
                val signal = synchronized(binderLock) {
                    macService?.let { return@synchronized CompletableDeferred(it) }
                    if (serviceReady.isCancelled) serviceReady = CompletableDeferred()
                    serviceReady
                }
                unsafeBindService()
                withTimeoutOrNull(SERVICE_CONNECT_TIMEOUT_MS) { signal.await() }
            } ?: return null
            withContext(Dispatchers.IO) { service.getMacAddressByName(name) }
        } catch (e: Throwable) {
            Log.e(ShizukuUtils.TAG, "Failed to obtain requested interface address", e)
            null
        }
    }

    private const val SERVICE_CONNECT_TIMEOUT_MS = 5_000L
}
