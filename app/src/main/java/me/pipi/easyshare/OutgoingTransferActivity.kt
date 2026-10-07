package me.pipi.easyshare

import android.os.Bundle
import android.content.Intent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import me.pipi.easyshare.models.OutgoingTransferPresentation
import me.pipi.easyshare.models.TransferUiState
import me.pipi.easyshare.ui.theme.EasyShareTheme

class OutgoingTransferActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showTransfer(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        showTransfer(intent)
    }

    private fun showTransfer(intent: Intent) {
        @Suppress("DEPRECATION")
        val presentation = intent.getParcelableExtra<OutgoingTransferPresentation>(EXTRA_PRESENTATION)
        if (presentation == null) {
            Toast.makeText(this, R.string.noti_send_interrupted, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        @Suppress("DEPRECATION")
        val snapshot = intent.getParcelableExtra<TransferUiState>(EXTRA_STATE)
        enableEdgeToEdge()
        setContent {
            EasyShareTheme {
                androidx.compose.runtime.key(presentation.deviceId, presentation.taskId, snapshot) {
                    ShareActivityContent(emptyList(), ::finish, presentation, snapshot)
                }
            }
        }
    }

    companion object {
        const val EXTRA_PRESENTATION = "transferPresentation"
        const val EXTRA_STATE = "transferState"
    }
}
