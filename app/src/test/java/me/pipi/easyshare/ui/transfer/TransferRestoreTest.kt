package me.pipi.easyshare.ui.transfer

import me.pipi.easyshare.models.OutgoingTransferPresentation
import me.pipi.easyshare.models.TransferUiState
import me.pipi.easyshare.models.TransferUiStatus
import me.pipi.easyshare.models.IncomingTransferUiState
import me.pipi.easyshare.models.IncomingTransferUiStatus
import me.pipi.easyshare.restoredIncomingState
import me.pipi.easyshare.restoredTransferState
import me.pipi.easyshare.utils.LiveStage
import org.junit.Assert.assertEquals
import org.junit.Test

class TransferRestoreTest {
    private val presentation = OutgoingTransferPresentation(1, "peer", "Device", 30, "file.jpg", "image/jpeg", 1, 50, false)

    @Test
    fun recreatedSheetUsesTheRunningServiceState() {
        val current = TransferUiState(1, "peer", TransferUiStatus.SENDING, 45, LiveStage.TRANSFERRING)
        val snapshot = current.copy(status = TransferUiStatus.WAITING, progress = 0)
        assertEquals(current, restoredTransferState(presentation, current, snapshot, "interrupted"))
    }

    @Test
    fun oldResultNotificationRetainsItsOwnTerminalOutcome() {
        val snapshot = TransferUiState(1, "peer", TransferUiStatus.PARTIAL, 100, LiveStage.COMPLETED)
        val otherTask = snapshot.copy(taskId = 2, status = TransferUiStatus.SENDING, progress = 20)
        assertEquals(snapshot, restoredTransferState(presentation, otherTask, snapshot, "interrupted"))
    }

    @Test
    fun lostActiveSessionDoesNotInventWaitingOrResumeTheSend() {
        val snapshot = TransferUiState(1, "peer", TransferUiStatus.SENDING, 45, LiveStage.TRANSFERRING)
        val result = restoredTransferState(presentation, null, snapshot, "interrupted")
        assertEquals(TransferUiStatus.FAILED, result.status)
        assertEquals(0, result.progress)
        assertEquals("interrupted", result.errorMessage)
    }

    @Test
    fun notificationForAnotherPeerCannotSupplyAResult() {
        val snapshot = TransferUiState(1, "another-peer", TransferUiStatus.SUCCESS, 100, LiveStage.COMPLETED)
        assertEquals(TransferUiStatus.FAILED, restoredTransferState(presentation, null, snapshot, "interrupted").status)
    }

    @Test
    fun lostReceiveSessionCannotBecomeAnotherConsentRequest() {
        val metadata = IncomingTransferUiState(1, "peer", 30, "file.jpg", 1, 50, IncomingTransferUiStatus.REQUESTED)
        val receiving = metadata.copy(status = IncomingTransferUiStatus.RECEIVING, progress = 30)
        assertEquals(IncomingTransferUiStatus.FAILED, restoredIncomingState(null, metadata, "interrupted").status)
        assertEquals(IncomingTransferUiStatus.FAILED, restoredIncomingState(receiving, metadata, "interrupted").status)
    }

    @Test
    fun completedReceiveKeepsItsOutcomeAndDurableFileToken() {
        val metadata = IncomingTransferUiState(1, "peer", 30, "file.jpg", 1, 50,
            IncomingTransferUiStatus.REQUESTED, receiveDirectoryUri = "content://documents/tree/download-B")
        val completed = metadata.copy(status = IncomingTransferUiStatus.PARTIAL, progress = 100,
            receivedFilesToken = "a".repeat(64), stage = LiveStage.COMPLETED,
            receiveDirectoryUri = "content://documents/tree/download-A")
        assertEquals(completed, restoredIncomingState(completed, metadata, "interrupted"))
        assertEquals(IncomingTransferUiStatus.FAILED,
            restoredIncomingState(completed.copy(taskId = 2), metadata, "interrupted").status)
    }
}
