package me.pipi.easyshare

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.Formatter
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import me.pipi.easyshare.models.IncomingTransferUiState
import me.pipi.easyshare.models.IncomingTransferUiStatus
import me.pipi.easyshare.models.ReceivedFile
import me.pipi.easyshare.services.P2pReceiverService
import me.pipi.easyshare.ui.theme.EasyShareTheme
import me.pipi.easyshare.ui.transfer.TransferSheet
import me.pipi.easyshare.ui.transfer.TransferVisualState
import me.pipi.easyshare.ui.transfer.fileTypeLabel
import me.pipi.easyshare.utils.DeviceUtils
import me.pipi.easyshare.utils.INTERNAL_BROADCAST_PERMISSION
import me.pipi.easyshare.utils.IncomingTransferUiCoordinator
import java.util.concurrent.atomic.AtomicBoolean

class IncomingTransferActivity : ComponentActivity() {
    private val responded = AtomicBoolean()
    private val timeoutHandler = Handler(Looper.getMainLooper())
    private var remainingSeconds by mutableIntStateOf(REQUEST_TIMEOUT_SECONDS)
    private var taskId: Int = Int.MIN_VALUE

    private val countdownRunnable = object : Runnable {
        override fun run() {
            if (responded.get()) return
            remainingSeconds--
            if (remainingSeconds <= 0) {
                rejectAndFinish(showTimeout = true)
                return
            }
            timeoutHandler.postDelayed(this, 1_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        taskId = intent.getIntExtra(EXTRA_TASK_ID, Int.MIN_VALUE)
        if (taskId == Int.MIN_VALUE) {
            finish()
            return
        }

        val fallbackState = IncomingTransferUiState(
            taskId = taskId,
            senderName = intent.getStringExtra(EXTRA_SENDER_NAME).orEmpty(),
            fileName = intent.getStringExtra(EXTRA_FILE_NAME).orEmpty(),
            fileCount = intent.getIntExtra(EXTRA_FILE_COUNT, 1).coerceAtLeast(1),
            totalSize = intent.getLongExtra(EXTRA_TOTAL_SIZE, 0L).coerceAtLeast(0L),
            brandId = intent.getIntExtra(EXTRA_BRAND_ID, -1).takeIf { it >= 0 },
            status = IncomingTransferUiStatus.REQUESTED,
        )
        if (IncomingTransferUiCoordinator.get(taskId) == null) {
            IncomingTransferUiCoordinator.publish(fallbackState)
        }

        val initialStatus = IncomingTransferUiCoordinator.get(taskId)?.status
        if (initialStatus != IncomingTransferUiStatus.REQUESTED) {
            responded.set(true)
        }

        window.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.attributes = window.attributes.apply { dimAmount = 0.24f }
        window.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
        )
        enableEdgeToEdge()

        setContent {
            EasyShareTheme {
                val states by IncomingTransferUiCoordinator.states.collectAsState()
                val state = states[taskId] ?: fallbackState
                BackHandler { dismissForState(state) }
                IncomingTransferScreen(
                    state = state,
                    remainingSeconds = remainingSeconds,
                    onDismiss = { dismissForState(state) },
                    onReject = { rejectAndFinish(showTimeout = false) },
                    onAccept = ::accept,
                    onCancel = ::cancelAndFinish,
                    onClose = ::finish,
                    onOpen = { openReceivedFiles(state.receivedFiles) },
                )
            }
        }

        if (!responded.get()) {
            timeoutHandler.postDelayed(countdownRunnable, 1_000L)
        }
    }

    override fun onDestroy() {
        timeoutHandler.removeCallbacks(countdownRunnable)
        if (isFinishing && taskId != Int.MIN_VALUE) {
            IncomingTransferUiCoordinator.clear(taskId)
        }
        super.onDestroy()
    }

    private fun dismissForState(state: IncomingTransferUiState) {
        when (state.status) {
            IncomingTransferUiStatus.REQUESTED -> rejectAndFinish(showTimeout = false)
            IncomingTransferUiStatus.RECEIVING -> cancelAndFinish()
            else -> finish()
        }
    }

    private fun accept() {
        if (!responded.compareAndSet(false, true)) return
        timeoutHandler.removeCallbacks(countdownRunnable)
        IncomingTransferUiCoordinator.markReceiving(taskId)
        sendResponse(true)
    }

    private fun rejectAndFinish(showTimeout: Boolean) {
        if (responded.compareAndSet(false, true)) {
            timeoutHandler.removeCallbacks(countdownRunnable)
            sendResponse(false)
            if (showTimeout) {
                Toast.makeText(this, R.string.incoming_transfer_timeout, Toast.LENGTH_SHORT).show()
            }
        }
        finish()
    }

    private fun cancelAndFinish() {
        timeoutHandler.removeCallbacks(countdownRunnable)
        P2pReceiverService.cancelTask(this, taskId)
        finish()
    }

    private fun sendResponse(accepted: Boolean) {
        sendBroadcast(
            P2pReceiverService.getResponseIntent(this, taskId, accepted),
            INTERNAL_BROADCAST_PERMISSION,
        )
    }

    private fun openReceivedFiles(files: List<ReceivedFile>) {
        val openIntent = if (files.size == 1) {
            val file = files.first()
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(file.uri, file.mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } else {
            Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)
        }

        try {
            startActivity(openIntent)
            finish()
        } catch (_: Throwable) {
            Toast.makeText(this, R.string.open_received_file_failed, Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val EXTRA_TASK_ID = "taskId"
        private const val EXTRA_SENDER_NAME = "senderName"
        private const val EXTRA_FILE_NAME = "fileName"
        private const val EXTRA_FILE_COUNT = "fileCount"
        private const val EXTRA_TOTAL_SIZE = "totalSize"
        private const val EXTRA_BRAND_ID = "brandId"
        private const val REQUEST_TIMEOUT_SECONDS = 10

        fun createIntent(
            context: Context,
            taskId: Int,
            senderName: String,
            fileName: String,
            fileCount: Int,
            totalSize: Long,
            brandId: Int?,
        ): Intent = Intent(context, IncomingTransferActivity::class.java).apply {
            putExtra(EXTRA_TASK_ID, taskId)
            putExtra(EXTRA_SENDER_NAME, senderName)
            putExtra(EXTRA_FILE_NAME, fileName)
            putExtra(EXTRA_FILE_COUNT, fileCount)
            putExtra(EXTRA_TOTAL_SIZE, totalSize)
            brandId?.let { putExtra(EXTRA_BRAND_ID, it) }
        }
    }
}

@Composable
private fun IncomingTransferScreen(
    state: IncomingTransferUiState,
    remainingSeconds: Int,
    onDismiss: () -> Unit,
    onReject: () -> Unit,
    onAccept: () -> Unit,
    onCancel: () -> Unit,
    onClose: () -> Unit,
    onOpen: () -> Unit,
) {
    val context = LocalContext.current
    val interactionSource = remember { MutableInteractionSource() }
    val sizeLabel = state.totalSize.takeIf { it > 0L }?.let {
        Formatter.formatFileSize(context, it)
    }
    val isText = state.fileName.isBlank()
    val receivedCount = state.receivedFiles.size.takeIf { it > 0 } ?: state.fileCount
    val headline = when {
        isText -> stringResource(R.string.shared_text)
        state.fileName.isNotBlank() -> state.fileName
        else -> pluralStringResource(
            R.plurals.incoming_transfer_multiple,
            state.fileCount,
            state.fileCount,
        )
    }

    val partyText = when (state.status) {
        IncomingTransferUiStatus.REQUESTED -> if (isText) {
            stringResource(R.string.incoming_request_text_summary, state.senderName)
        } else {
            pluralStringResource(
                R.plurals.incoming_request_summary,
                state.fileCount,
                state.senderName,
                state.fileCount,
            )
        }

        IncomingTransferUiStatus.RECEIVING -> if (isText) {
            stringResource(R.string.incoming_receiving_text_summary, state.senderName)
        } else {
            pluralStringResource(
                R.plurals.incoming_receiving_summary,
                state.fileCount,
                state.fileCount,
                state.senderName,
            )
        }

        IncomingTransferUiStatus.SUCCESS,
        IncomingTransferUiStatus.PARTIAL -> if (isText) {
            stringResource(R.string.incoming_received_text_summary, state.senderName)
        } else {
            pluralStringResource(
                R.plurals.incoming_received_summary,
                receivedCount,
                receivedCount,
                state.senderName,
            )
        }

        IncomingTransferUiStatus.FAILED,
        IncomingTransferUiStatus.CANCELED -> stringResource(
            R.string.incoming_transfer_from,
            state.senderName,
        )
    }

    val displayHeadline = when (state.status) {
        IncomingTransferUiStatus.RECEIVING -> headline
        else -> partyText
    }

    val supportingText = when (state.status) {
        IncomingTransferUiStatus.REQUESTED -> listOfNotNull(
            sizeLabel,
            stringResource(R.string.incoming_receive_timeout_hint, remainingSeconds),
        ).joinToString(" · ")

        IncomingTransferUiStatus.RECEIVING,
        IncomingTransferUiStatus.SUCCESS -> null
        IncomingTransferUiStatus.PARTIAL -> stringResource(R.string.recv_partial)

        IncomingTransferUiStatus.FAILED,
        IncomingTransferUiStatus.CANCELED -> state.errorMessage
            ?: stringResource(R.string.noti_recv_interrupted)
    }

    val visualState = when (state.status) {
        IncomingTransferUiStatus.REQUESTED -> TransferVisualState.FILE
        IncomingTransferUiStatus.RECEIVING -> TransferVisualState.PROGRESS
        IncomingTransferUiStatus.SUCCESS,
        IncomingTransferUiStatus.PARTIAL -> TransferVisualState.SUCCESS
        IncomingTransferUiStatus.FAILED,
        IncomingTransferUiStatus.CANCELED -> TransferVisualState.FAILURE
    }

    val secondaryActionLabel: String?
    val onSecondaryAction: (() -> Unit)?
    val primaryActionLabel: String
    val onPrimaryAction: () -> Unit
    when (state.status) {
        IncomingTransferUiStatus.REQUESTED -> {
            secondaryActionLabel = stringResource(R.string.reject)
            onSecondaryAction = onReject
            primaryActionLabel = stringResource(R.string.accept)
            onPrimaryAction = onAccept
        }

        IncomingTransferUiStatus.RECEIVING -> {
            secondaryActionLabel = null
            onSecondaryAction = null
            primaryActionLabel = stringResource(R.string.cancel)
            onPrimaryAction = onCancel
        }

        IncomingTransferUiStatus.SUCCESS,
        IncomingTransferUiStatus.PARTIAL -> {
            if (state.receivedFiles.isNotEmpty()) {
                secondaryActionLabel = stringResource(R.string.close)
                onSecondaryAction = onClose
                primaryActionLabel = stringResource(R.string.open)
                onPrimaryAction = onOpen
            } else {
                secondaryActionLabel = null
                onSecondaryAction = null
                primaryActionLabel = stringResource(R.string.close)
                onPrimaryAction = onClose
            }
        }

        IncomingTransferUiStatus.FAILED,
        IncomingTransferUiStatus.CANCELED -> {
            secondaryActionLabel = null
            onSecondaryAction = null
            primaryActionLabel = stringResource(R.string.close)
            onPrimaryAction = onClose
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.42f)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onDismiss,
                ),
        )
        Box(modifier = Modifier.align(Alignment.BottomCenter)) {
            TransferSheet(
                title = stringResource(R.string.app_name),
                partyText = partyText,
                partyIconRes = DeviceUtils.deviceIconById(state.brandId),
                headlineText = displayHeadline,
                supportingText = supportingText,
                fileTypeLabel = fileTypeLabel(
                    state.fileName,
                    isText = isText,
                    textLabel = stringResource(R.string.text_file_type),
                    fallbackLabel = stringResource(R.string.generic_file_type),
                ),
                visualState = visualState,
                progress = state.progress.takeIf {
                    state.status == IncomingTransferUiStatus.RECEIVING
                },
                secondaryActionLabel = secondaryActionLabel,
                onSecondaryAction = onSecondaryAction,
                primaryActionLabel = primaryActionLabel,
                onPrimaryAction = onPrimaryAction,
            )
        }
    }
}
