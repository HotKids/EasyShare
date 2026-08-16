package me.pipi.easyshare.utils

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import me.pipi.easyshare.BuildConfig

val INTERNAL_BROADCAST_PERMISSION = "${BuildConfig.APPLICATION_ID}.INTERNAL_BROADCASTS"

fun Context.checkBluetoothPermissions(): Boolean {
    if (Build.VERSION.SDK_INT <= 32) {
        if (!hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ||
            !hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        ) {
            return false
        }
    }

    if (Build.VERSION.SDK_INT >= 31) {
        for (perm in listOf(
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT
        )) {
            if (!hasPermission(perm)) {
                return false
            }
        }
    }

    return true
}

fun Context.checkP2pPermissions(): Boolean {
    if (!checkLocalNetworkPermission()) {
        return false
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && ContextCompat.checkSelfPermission(
            this, Manifest.permission.NEARBY_WIFI_DEVICES
        ) != PackageManager.PERMISSION_GRANTED
    ) {
        return false
    }

    if (Build.VERSION.SDK_INT <= 32) {
        if (!hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ||
            !hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        ) {
            return false
        }
    }

    return true
}

fun Context.missingTransferPermissions(includeNotifications: Boolean): List<String> {
    return buildList {
        if (Build.VERSION.SDK_INT >= 37) add(Manifest.permission.ACCESS_LOCAL_NETWORK)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.NEARBY_WIFI_DEVICES)
        if (includeNotifications && Build.VERSION.SDK_INT >= 33) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT <= 32) {
            // Android 12/12L require approximate and precise location to be requested together.
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= 31) {
            add(Manifest.permission.BLUETOOTH_ADVERTISE)
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }.distinct().filterNot(::hasPermission)
}

private fun Context.hasPermission(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

fun Context.checkLocalNetworkPermission(): Boolean {
    return Build.VERSION.SDK_INT < 37 || ContextCompat.checkSelfPermission(
        this,
        Manifest.permission.ACCESS_LOCAL_NETWORK,
    ) == PackageManager.PERMISSION_GRANTED
}

fun Context.checkNotificationPermission(): Boolean =
    Build.VERSION.SDK_INT < 33 || hasPermission(Manifest.permission.POST_NOTIFICATIONS)

fun Context.registerInternalBroadcastReceiver(receiver: BroadcastReceiver, filter: IntentFilter) {
    registerReceiver(receiver, filter, INTERNAL_BROADCAST_PERMISSION, null, getReceiverFlags())
}

fun getReceiverFlags(): Int {
    return if (Build.VERSION.SDK_INT >= 33) {
        Context.RECEIVER_EXPORTED
    } else {
        0
    }
}
