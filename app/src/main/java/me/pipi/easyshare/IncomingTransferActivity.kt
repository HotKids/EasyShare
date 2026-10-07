package me.pipi.easyshare

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.core.graphics.drawable.toDrawable
import androidx.core.net.toUri
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import me.pipi.easyshare.models.IncomingTransferUiState
import me.pipi.easyshare.models.IncomingTransferUiStatus
import me.pipi.easyshare.models.ReceivedFile
import me.pipi.easyshare.services.P2pReceiverService
import me.pipi.easyshare.ui.theme.EasyShareTheme
import me.pipi.easyshare.ui.transfer.EasyShareSheetContainer
import me.pipi.easyshare.ui.transfer.TransferSheetContent
import me.pipi.easyshare.ui.transfer.TransferCopy
import me.pipi.easyshare.ui.transfer.TransferVisualState
import me.pipi.easyshare.ui.transfer.AttachmentKind
import me.pipi.easyshare.ui.transfer.attachmentKind
import me.pipi.easyshare.utils.LiveStage
import me.pipi.easyshare.utils.DeviceUtils
import me.pipi.easyshare.utils.IncomingRequestDecision
import me.pipi.easyshare.utils.IncomingTransferUiCoordinator
import me.pipi.easyshare.utils.ReceivedFilesSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class IncomingTransferActivity : ComponentActivity() {
    private var transferTaskId by mutableIntStateOf(Int.MIN_VALUE)
    private var lastPresentation by mutableStateOf<IncomingTransferUiState?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!readEntry(intent, savedInstanceState)) return

        window.setBackgroundDrawable(android.graphics.Color.TRANSPARENT.toDrawable())
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.attributes = window.attributes.apply { dimAmount = 0.18f }
        window.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
        )
        enableEdgeToEdge()

        setContent {
            EasyShareTheme {
                val states by IncomingTransferUiCoordinator.states.collectAsState()
                val shownTaskId = transferTaskId
                val presentation = states[shownTaskId] ?: lastPresentation ?: return@EasyShareTheme
                var loadedFiles by remember(shownTaskId, presentation.receivedFilesToken) {
                    mutableStateOf(emptyList<ReceivedFile>())
                }
                LaunchedEffect(shownTaskId, presentation.receivedFilesToken, presentation.receivedFiles.isEmpty()) {
                    val token = presentation.receivedFilesToken
                    if (token != null && presentation.receivedFiles.isEmpty()) {
                        try {
                            loadedFiles = withContext(Dispatchers.IO) {
                                ReceivedFilesSnapshot.load(this@IncomingTransferActivity, token)
                            }
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            Log.w(TAG, "Failed to restore received-file navigation", error)
                        }
                    }
                }
                val state = if (presentation.receivedFiles.isEmpty() && loadedFiles.isNotEmpty()) {
                    presentation.copy(receivedFiles = loadedFiles)
                } else {
                    presentation
                }
                androidx.compose.runtime.SideEffect {
                    if (transferTaskId == shownTaskId) lastPresentation = state
                }
                var cancelEnabled by remember(shownTaskId, state.cancelEnabledAtMillis) {
                    mutableStateOf(incomingCancelGuardRemainingMillis(state.cancelEnabledAtMillis, SystemClock.elapsedRealtime()) == 0L)
                }
                LaunchedEffect(shownTaskId, state.cancelEnabledAtMillis) {
                    delay(incomingCancelGuardRemainingMillis(state.cancelEnabledAtMillis, SystemClock.elapsedRealtime()))
                    cancelEnabled = true
                }
                BackHandler { hideAndFinish(shownTaskId) }
                IncomingTransferScreen(
                    state = state,
                    cancelEnabled = cancelEnabled && !state.cancelRequested,
                    onDismiss = { hideAndFinish(shownTaskId) },
                    onReject = { rejectAndFinish(shownTaskId) },
                    onAccept = { accept(shownTaskId) },
                    onCancel = { cancelAndFinish(shownTaskId) },
                    onClose = { hideAndFinish(shownTaskId) },
                    onOpen = { openReceivedFiles(state, shownTaskId) },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readEntry(intent)
    }

    override fun onStart() {
        super.onStart()
        IncomingTransferUiCoordinator.show(transferTaskId, this)
    }

    override fun onStop() {
        IncomingTransferUiCoordinator.hide(transferTaskId, this)
        super.onStop()
    }

    @Suppress("DEPRECATION")
    private fun readEntry(entry: Intent, savedInstanceState: Bundle? = null): Boolean {
        val snapshot = entry.getParcelableExtra<IncomingTransferUiState>(EXTRA_PRESENTATION)
        val entryTaskId = snapshot?.taskId ?: entry.getIntExtra(EXTRA_TASK_ID, Int.MIN_VALUE)
        if (entryTaskId == Int.MIN_VALUE) {
            finish()
            return false
        }
        val metadata = snapshot ?: IncomingTransferUiState(
            taskId = entryTaskId,
            senderName = entry.getStringExtra(EXTRA_SENDER_NAME).orEmpty(),
            fileName = entry.getStringExtra(EXTRA_FILE_NAME).orEmpty(),
            fileCount = entry.getIntExtra(EXTRA_FILE_COUNT, 1).coerceAtLeast(1),
            totalSize = entry.getLongExtra(EXTRA_TOTAL_SIZE, 0L).coerceAtLeast(0L),
            brandId = entry.getIntExtra(EXTRA_BRAND_ID, -1).takeIf { it >= 0 },
            status = IncomingTransferUiStatus.REQUESTED,
            isText = entry.getBooleanExtra(EXTRA_IS_TEXT, false),
            mimeType = entry.getStringExtra(EXTRA_MIME_TYPE),
        )
        val saved = savedInstanceState?.takeIf { it.getInt(STATE_TASK_ID, entryTaskId) == entryTaskId }
        if (transferTaskId != entryTaskId) {
            IncomingTransferUiCoordinator.hide(transferTaskId, this)
        }
        transferTaskId = entryTaskId
        lastPresentation = IncomingTransferUiCoordinator.get(transferTaskId) ?: restoredIncomingState(
            saved?.getParcelable<IncomingTransferUiState>(STATE_PRESENTATION) ?: snapshot,
            metadata,
            getString(R.string.noti_recv_interrupted),
        )
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            IncomingTransferUiCoordinator.show(transferTaskId, this)
        }
        return true
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(STATE_TASK_ID, transferTaskId)
        (IncomingTransferUiCoordinator.get(transferTaskId) ?: lastPresentation)
            ?.let { outState.putParcelable(STATE_PRESENTATION, it) }
        super.onSaveInstanceState(outState)
    }

    private fun hideAndFinish(expectedTaskId: Int) {
        if (transferTaskId != expectedTaskId) return
        IncomingTransferUiCoordinator.hide(expectedTaskId, this)
        finish()
    }

    private fun accept(expectedTaskId: Int) {
        if (transferTaskId != expectedTaskId) return
        if (IncomingTransferUiCoordinator.decide(expectedTaskId, IncomingRequestDecision.ACCEPTED)) {
            Log.i(TAG, "Incoming transfer accepted")
        }
    }

    private fun rejectAndFinish(expectedTaskId: Int) {
        if (transferTaskId != expectedTaskId) return
        if (IncomingTransferUiCoordinator.decide(expectedTaskId, IncomingRequestDecision.REJECTED)) {
            Log.i(TAG, "Incoming transfer rejected")
        }
        hideAndFinish(expectedTaskId)
    }

    private fun cancelAndFinish(expectedTaskId: Int) {
        if (transferTaskId != expectedTaskId) return
        val state = IncomingTransferUiCoordinator.get(expectedTaskId) ?: lastPresentation ?: return
        if (state.status != IncomingTransferUiStatus.RECEIVING) return
        if (incomingCancelGuardRemainingMillis(state.cancelEnabledAtMillis, SystemClock.elapsedRealtime()) > 0L) {
            Log.i(TAG, "Ignoring cancel tap immediately after accepting")
            return
        }
        Log.i(TAG, "Incoming transfer canceled")
        P2pReceiverService.cancelTask(this, expectedTaskId)
    }

    private fun openReceivedFiles(state: IncomingTransferUiState, expectedTaskId: Int) {
        val files = state.receivedFiles
        lifecycleScope.launch {
            try {
                val openIntent = if (files.size == 1) {
                    val file = files.first()
                    Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(file.uri, file.mimeType)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                } else {
                    P2pReceiverService.receivedDirectoryIntent(
                        this@IncomingTransferActivity, requireNotNull(state.receiveDirectoryUri).toUri(),
                    )
                }
                if (transferTaskId != expectedTaskId) return@launch
                startActivity(openIntent)
                hideAndFinish(expectedTaskId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                if (transferTaskId == expectedTaskId) {
                    Toast.makeText(this@IncomingTransferActivity, R.string.open_received_file_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    companion object {
        private const val EXTRA_TASK_ID = "taskId"
        private const val EXTRA_SENDER_NAME = "senderName"
        private const val EXTRA_FILE_NAME = "fileName"
        private const val EXTRA_FILE_COUNT = "fileCount"
        private const val EXTRA_TOTAL_SIZE = "totalSize"
        private const val EXTRA_BRAND_ID = "brandId"
        private const val EXTRA_IS_TEXT = "isText"
        private const val EXTRA_MIME_TYPE = "mimeType"
        private const val EXTRA_PRESENTATION = "incomingPresentation"
        private const val EXTRA_MANUAL_RESULT = "manualResult"
        private const val STATE_TASK_ID = "incomingTaskId"
        private const val STATE_PRESENTATION = "incomingPresentation"
        private const val TAG = "IncomingTransfer"

        fun createIntent(
            context: Context,
            state: IncomingTransferUiState,
            manualResult: Boolean = false,
        ): Intent = Intent(context, IncomingTransferActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            data = Uri.Builder().scheme("easyshare").authority("incoming")
                .appendPath(state.taskId.toString())
                .appendPath(if (manualResult) "result" else "view")
                .build()
            putExtra(EXTRA_TASK_ID, state.taskId)
            putExtra(EXTRA_PRESENTATION, state.copy(receivedFiles = emptyList()))
            putExtra(EXTRA_MANUAL_RESULT, manualResult)
        }

        fun createIntent(
            context: Context,
            taskId: Int,
            senderName: String,
            fileName: String,
            fileCount: Int,
            totalSize: Long,
            brandId: Int?,
            isText: Boolean = false,
            mimeType: String? = null,
        ): Intent = createIntent(
            context,
            IncomingTransferUiState(
                taskId = taskId,
                senderName = senderName,
                fileName = fileName,
                fileCount = fileCount,
                totalSize = totalSize,
                brandId = brandId,
                status = IncomingTransferUiStatus.REQUESTED,
                isText = isText,
                mimeType = mimeType,
            ),
        )
    }
}

internal fun restoredIncomingState(
    saved: IncomingTransferUiState?,
    metadata: IncomingTransferUiState,
    interruptedMessage: String,
): IncomingTransferUiState {
    val presentation = saved?.takeIf { it.taskId == metadata.taskId } ?: metadata
    return when (presentation.status) {
        IncomingTransferUiStatus.REQUESTED, IncomingTransferUiStatus.RECEIVING -> presentation.copy(
            status = IncomingTransferUiStatus.FAILED, progress = 0, errorMessage = interruptedMessage,
        )
        else -> presentation
    }
}

@Composable
private fun IncomingTransferScreen(
    state: IncomingTransferUiState,
    cancelEnabled: Boolean,
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
    val isText = state.isText
    val savedFiles = state.receivedFiles.takeIf { state.status == IncomingTransferUiStatus.SUCCESS && it.isNotEmpty() }
    val fileName = savedFiles?.first()?.name ?: state.fileName.ifBlank { state.currentFileName.orEmpty() }
    val mimeType = if (savedFiles != null) savedFiles.map { it.mimeType }.distinct().singleOrNull() else state.mimeType
    val fileCount = savedFiles?.size ?: state.fileCount
    val kind = attachmentKind(fileName, mimeType, isText, fileCount)
    val countLabel = TransferCopy.itemLabel(context, fileName, mimeType, isText, fileCount)
    val statusLabel = stringResource(incomingTransferTitle(state.status, state.stage))
    val partyText = when (state.status) {
        IncomingTransferUiStatus.REQUESTED -> TransferCopy.request(context, state.senderName, countLabel)
        IncomingTransferUiStatus.RECEIVING -> when {
                state.cancelRequested -> stringResource(R.string.transfer_canceling)
                state.stage == LiveStage.FINALIZING -> stringResource(R.string.transfer_saving)
                state.stage == LiveStage.TRANSFERRING -> TransferCopy.receiving(context, countLabel)
                else -> statusLabel
            }
        IncomingTransferUiStatus.SUCCESS -> if (isText) stringResource(R.string.msg_copied_to_clipboard)
            else TransferCopy.received(context, state.senderName, countLabel)
        IncomingTransferUiStatus.PARTIAL -> if (state.receivedFiles.isEmpty()) statusLabel
            else pluralStringResource(R.plurals.transfer_saved_partial, state.fileCount, state.receivedFiles.size, state.fileCount)
        IncomingTransferUiStatus.FAILED,
        IncomingTransferUiStatus.CANCELED -> stringResource(R.string.transfer_status_peer, statusLabel, state.senderName)
    }

    val visualState = when (state.status) {
        IncomingTransferUiStatus.REQUESTED -> TransferVisualState.FILE
        IncomingTransferUiStatus.RECEIVING -> when {
            state.cancelRequested -> TransferVisualState.FINALIZING
            state.stage == LiveStage.PREPARING -> TransferVisualState.CONNECTING
            state.stage == LiveStage.FINALIZING -> TransferVisualState.FINALIZING
            else -> TransferVisualState.PROGRESS
        }
        IncomingTransferUiStatus.SUCCESS -> TransferVisualState.SUCCESS
        IncomingTransferUiStatus.PARTIAL -> TransferVisualState.PARTIAL
        IncomingTransferUiStatus.FAILED -> TransferVisualState.FAILURE
        IncomingTransferUiStatus.CANCELED -> TransferVisualState.CANCELED
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
                .fillMaxSize()
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onDismiss,
                )
                .clearAndSetSemantics {},
        )
        Box(modifier = Modifier.align(Alignment.BottomCenter)) {
            EasyShareSheetContainer(
                title = stringResource(R.string.app_name),
                centerTitle = true,
                onDismiss = onDismiss,
            ) {
                TransferSheetContent(
                    partyText = partyText,
                    partyIconRes = DeviceUtils.knownDeviceIconById(state.brandId),
                    attachmentKind = kind,
                    visualState = visualState,
                    progress = state.progress.takeIf {
                        visualState == TransferVisualState.PROGRESS
                    },
                    secondaryActionLabel = secondaryActionLabel,
                    onSecondaryAction = onSecondaryAction,
                    primaryActionLabel = primaryActionLabel,
                    onPrimaryAction = onPrimaryAction,
                    primaryActionEnabled = state.status != IncomingTransferUiStatus.RECEIVING || cancelEnabled,
                    message = state.errorMessage?.takeIf { !state.cancelRequested && it != statusLabel },
                    emphasizePrimary = secondaryActionLabel != null,
                    fileSize = sizeLabel?.takeUnless { isText },
                )
            }
        }
    }
}

internal fun incomingCancelGuardRemainingMillis(enabledAtMillis: Long, elapsedRealtimeMillis: Long): Long =
    if (enabledAtMillis > elapsedRealtimeMillis) enabledAtMillis - elapsedRealtimeMillis else 0L

internal fun incomingTransferTitle(status: IncomingTransferUiStatus, stage: LiveStage = LiveStage.TRANSFERRING): Int = when (status) {
    IncomingTransferUiStatus.REQUESTED -> LiveStage.WAITING_AUTH.titleResource(sending = false)
    IncomingTransferUiStatus.RECEIVING -> stage.titleResource(sending = false)
    IncomingTransferUiStatus.SUCCESS -> R.string.recv_ok
    IncomingTransferUiStatus.PARTIAL -> R.string.recv_partial
    IncomingTransferUiStatus.FAILED -> R.string.recv_fail
    IncomingTransferUiStatus.CANCELED -> R.string.cancelled_by_user_local
}
