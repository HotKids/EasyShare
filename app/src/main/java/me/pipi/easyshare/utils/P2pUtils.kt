package me.pipi.easyshare.utils

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import me.pipi.easyshare.R
import me.pipi.easyshare.exceptions.ExceptionWithMessage

suspend fun Context.awaitP2pNetwork(
    interfaceName: String,
    timeoutMillis: Long = 5_000L,
): Network? {
    val connectivityManager = getSystemService(ConnectivityManager::class.java)

    return withTimeoutOrNull(timeoutMillis) {
        val networkFuture = CompletableDeferred<Network>()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            private fun completeIfP2pNetwork(network: Network) {
                if (connectivityManager.getLinkProperties(network)?.interfaceName == interfaceName) {
                    networkFuture.complete(network)
                }
            }

            override fun onAvailable(network: Network) {
                completeIfP2pNetwork(network)
            }

            override fun onLinkPropertiesChanged(
                network: Network,
                linkProperties: LinkProperties,
            ) {
                if (linkProperties.interfaceName == interfaceName) {
                    networkFuture.complete(network)
                }
            }
        }

        connectivityManager.registerNetworkCallback(request, callback)
        try {
            networkFuture.await()
        } finally {
            runCatching { connectivityManager.unregisterNetworkCallback(callback) }
        }
    }
}

@SuppressLint("MissingPermission")
suspend fun WifiP2pManager.requestGroupInfo(channel: WifiP2pManager.Channel): WifiP2pGroup? {
    val groupInfoFuture = CompletableDeferred<WifiP2pGroup?>()
    requestGroupInfo(channel) {
        groupInfoFuture.complete(it)
    }
    return groupInfoFuture.await()
}

class P2pFutureActionListener : WifiP2pManager.ActionListener {
    val deferred = CompletableDeferred<Unit>()

    override fun onSuccess() {
        deferred.complete(Unit)
    }

    override fun onFailure(reason: Int) {
        val message = when (reason) {
            WifiP2pManager.ERROR -> "ERROR"
            WifiP2pManager.P2P_UNSUPPORTED -> "P2P_UNSUPPORTED"
            WifiP2pManager.BUSY -> "BUSY"
            else -> "code $reason"
        }
        deferred.completeExceptionally(RuntimeException("WiFi P2P operation failed: $message"))
    }
}

@SuppressLint("MissingPermission")
suspend fun WifiP2pManager.createGroupSuspend(
    channel: WifiP2pManager.Channel,
    config: WifiP2pConfig
) {
    val l = P2pFutureActionListener()
    createGroup(channel, config, l)
    try {
        l.deferred.await()
    } catch (e: Throwable) {
        throw ExceptionWithMessage("Failed to create P2P group", e, R.string.error_p2p_failed)
    }
}

suspend fun WifiP2pManager.removeGroupSuspend(channel: WifiP2pManager.Channel) {
    val l = P2pFutureActionListener()
    removeGroup(channel, l)
    try {
        l.deferred.await()
    } catch (e: Throwable) {
        throw ExceptionWithMessage("Failed to remove P2P group", e, R.string.error_p2p_failed)
    }
}

@SuppressLint("MissingPermission")
suspend fun WifiP2pManager.connectSuspend(channel: WifiP2pManager.Channel, config: WifiP2pConfig) {
    val l = P2pFutureActionListener()
    connect(channel, config, l)
    try {
        l.deferred.await()
    } catch (e: Throwable) {
        throw ExceptionWithMessage("Failed to connect P2P", e, R.string.error_p2p_failed)
    }
}
