package me.pipi.easyshare.services

import android.annotation.SuppressLint
import android.content.Context
import android.content.ContextWrapper
import android.content.AttributionSource
import android.net.wifi.p2p.WifiP2pManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log
import androidx.annotation.RequiresApi
import me.pipi.easyshare.IMacAddressService
import me.pipi.easyshare.utils.TAG
import java.net.NetworkInterface
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

class MacAddressService(private val context: Context): IMacAddressService.Stub() {
    override fun destroy() {
        exit()
    }

    override fun exit() {
        Log.i(TAG, "Exit")
        exitProcess(0)
    }

    @SuppressLint("MissingPermission")
    override fun getP2pMacAddress(): String? {
        val thread = HandlerThread("P2pIdentity").apply { start() }
        val handler = Handler(thread.looper)
        val result = CompletableFuture<String?>()
        var channel: WifiP2pManager.Channel? = null
        handler.post {
            try {
                // A Shizuku context keeps the app's operation package. Wi-Fi requires the
                // package and attribution to belong to the user service's actual UID.
                val uid = Process.myUid()
                val operationPackage = context.packageManager.getPackagesForUid(uid)?.firstOrNull()
                    ?: context.packageName
                val serviceContext = object : ContextWrapper(context) {
                    override fun getOpPackageName() = operationPackage
                    override fun getAttributionSource() = AttributionSource.Builder(uid)
                        .setPackageName(operationPackage).build()
                    @RequiresApi(34)
                    override fun createDeviceContext(deviceId: Int): Context {
                        require(deviceId == Context.DEVICE_ID_DEFAULT)
                        return this
                    }
                }
                val manager = context.getSystemService(WifiP2pManager::class.java)
                channel = manager.initialize(serviceContext, thread.looper) { result.complete(null) }
                manager.requestDeviceInfo(channel) { result.complete(it?.deviceAddress) }
            } catch (e: Exception) {
                result.completeExceptionally(e)
            }
        }
        return try {
            result.get(3, TimeUnit.SECONDS)
        } finally {
            handler.post {
                try {
                    channel?.close()
                } finally {
                    thread.quitSafely()
                }
            }
        }
    }

    @OptIn(ExperimentalStdlibApi::class)
    override fun getMacAddressByName(name: String): String? {
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
}
