package me.pipi.easyshare

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import me.pipi.easyshare.utils.checkBluetoothPermissions
import me.pipi.easyshare.utils.checkNotificationPermission
import me.pipi.easyshare.utils.checkP2pPermissions

class StartReceiverActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val enabled = !intent.getBooleanExtra("shouldStop", false)
        MyApplication.getInstance().setBackgroundReceiveEnabled(enabled)
        if (enabled && (!checkBluetoothPermissions() || !checkP2pPermissions() ||
                !checkNotificationPermission())) {
            Toast.makeText(this, R.string.permission_not_granted, Toast.LENGTH_LONG).show()
        }

        finish()
    }

    companion object {
        fun getIntent(context: Context, shouldStop: Boolean): Intent {
            return Intent(context, StartReceiverActivity::class.java).apply {
                putExtra("shouldStop", shouldStop)
            }
        }
    }
}
