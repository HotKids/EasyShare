package me.pipi.easyshare

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import me.pipi.easyshare.services.GattServerService

class StartReceiverActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (intent.getBooleanExtra("shouldStop", false)) {
            GattServerService.stop(this)
        } else {
            GattServerService.start(this)
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
