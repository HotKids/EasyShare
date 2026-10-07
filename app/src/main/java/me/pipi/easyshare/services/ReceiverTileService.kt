package me.pipi.easyshare.services


import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import me.pipi.easyshare.AppSettings
import me.pipi.easyshare.MyApplication
import me.pipi.easyshare.StartReceiverActivity
import me.pipi.easyshare.utils.TAG
import me.pipi.easyshare.utils.registerInternalBroadcastReceiver
import java.lang.ref.WeakReference
import kotlin.random.Random

class ReceiverTileService : TileService() {
    private class MyReceiver(tileService: ReceiverTileService) : BroadcastReceiver() {
        private val serviceRef = WeakReference(tileService)

        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == MyApplication.ACTION_BACKGROUND_RECEIVE_CHANGED) {
                serviceRef.get()?.setState(
                    AppSettings(context).backgroundReceiveEnabled
                )
            }
        }

    }

    private var receiver: MyReceiver? = null

    @SuppressLint("StartActivityAndCollapseDeprecated")
    @Suppress("DEPRECATION")
    override fun onClick() {
        val intent = StartReceiverActivity.getIntent(
            this, AppSettings(this).backgroundReceiveEnabled
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                PendingIntent.getActivity(
                    this,
                    Random.nextInt(),
                    intent,
                    PendingIntent.FLAG_IMMUTABLE
                )
            )
        } else {
            startActivityAndCollapse(intent)
        }
    }

    private fun setState(enabled: Boolean) {
        qsTile?.state = if (enabled) {
            Tile.STATE_ACTIVE
        } else {
            Tile.STATE_INACTIVE
        }
        qsTile?.updateTile()
    }

    override fun onStartListening() {
        super.onStartListening()
        Log.d(TAG, "onStartListening")
        setState(AppSettings(this).backgroundReceiveEnabled)

        val r = MyReceiver(this)
        registerInternalBroadcastReceiver(
            r, IntentFilter().apply {
                addAction(MyApplication.ACTION_BACKGROUND_RECEIVE_CHANGED)
            }
        )
        receiver = r
    }

    override fun onStopListening() {
        super.onStopListening()
        Log.d(TAG, "onStopListening")
        receiver?.let {
            unregisterReceiver(it)
        }
        receiver = null
    }
}
