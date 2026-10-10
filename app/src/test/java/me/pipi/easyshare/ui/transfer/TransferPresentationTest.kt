package me.pipi.easyshare.ui.transfer

import me.pipi.easyshare.R
import me.pipi.easyshare.incomingCancelGuardRemainingMillis
import me.pipi.easyshare.incomingTransferTitle
import me.pipi.easyshare.models.IncomingTransferUiState
import me.pipi.easyshare.models.IncomingTransferUiStatus
import me.pipi.easyshare.models.TransferUiState
import me.pipi.easyshare.models.TransferUiStatus
import me.pipi.easyshare.outgoingTransferTitle
import me.pipi.easyshare.outgoingTransferVisual
import me.pipi.easyshare.utils.DeviceUtils
import me.pipi.easyshare.utils.IncomingTransferUiCoordinator
import me.pipi.easyshare.utils.IncomingRequestDecision
import me.pipi.easyshare.utils.LiveStage
import me.pipi.easyshare.utils.TransferUiCoordinator
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class TransferPresentationTest {
    @Test
    fun receiveCancelGuardUsesTheRestoredAbsoluteExpiryWithoutRestarting() {
        val restoredExpiry = 11_500L
        assertEquals(1_500L, incomingCancelGuardRemainingMillis(restoredExpiry, 10_000L))
        assertEquals(500L, incomingCancelGuardRemainingMillis(restoredExpiry, 11_000L))
        assertEquals(0L, incomingCancelGuardRemainingMillis(restoredExpiry, 11_500L))
        assertEquals(0L, incomingCancelGuardRemainingMillis(restoredExpiry, 12_000L))
        assertEquals(0L, incomingCancelGuardRemainingMillis(Long.MIN_VALUE, 10_000L))
    }

    @After
    fun clearState() {
        TransferUiCoordinator.clear()
        IncomingTransferUiCoordinator.clearAll()
    }

    @Test
    fun sendProgressIsOnlyShownDuringTheAcceptedDownload() {
        val state = TransferUiState(1, "peer", TransferUiStatus.WAITING)
        assertEquals(R.string.noti_connecting, outgoingTransferTitle(state))
        assertEquals(TransferVisualState.FILE, outgoingTransferVisual(state))
        assertEquals(R.string.noti_connecting, outgoingTransferTitle(state.copy(stage = LiveStage.WAITING_AUTH)))
        assertEquals(TransferVisualState.FILE, outgoingTransferVisual(state.copy(stage = LiveStage.WAITING_AUTH)))
        assertEquals(TransferVisualState.PROGRESS, outgoingTransferVisual(state.copy(status = TransferUiStatus.SENDING, stage = LiveStage.TRANSFERRING)))
        val confirming = state.copy(status = TransferUiStatus.SENDING, stage = LiveStage.FINALIZING)
        assertEquals(R.string.finishing_send, outgoingTransferTitle(confirming))
        assertEquals(TransferVisualState.FINALIZING, outgoingTransferVisual(confirming))
        assertEquals(TransferVisualState.PARTIAL, outgoingTransferVisual(state.copy(status = TransferUiStatus.PARTIAL)))
        assertEquals(TransferVisualState.CANCELED, outgoingTransferVisual(state.copy(status = TransferUiStatus.CANCELED)))
    }

    @Test
    fun receivePreparationAndSavingHaveDistinctTitles() {
        assertEquals(R.string.auth_waiting, incomingTransferTitle(IncomingTransferUiStatus.REQUESTED))
        assertEquals(R.string.preparing_receive, incomingTransferTitle(IncomingTransferUiStatus.RECEIVING, LiveStage.PREPARING))
        assertEquals(R.string.receiving, incomingTransferTitle(IncomingTransferUiStatus.RECEIVING, LiveStage.TRANSFERRING))
        assertEquals(R.string.finishing_receive, incomingTransferTitle(IncomingTransferUiStatus.RECEIVING, LiveStage.FINALIZING))
    }

    @Test
    fun outgoingConnectionCopyDoesNotChangeSavingOrTerminalStates() {
        val waiting = TransferUiState(1, "peer", TransferUiStatus.WAITING)
        assertEquals(R.string.noti_connecting, outgoingTransferTitle(waiting.copy(stage = LiveStage.INIT)))
        assertEquals(R.string.noti_connecting, outgoingTransferTitle(waiting.copy(stage = LiveStage.PREPARING)))
        assertEquals(R.string.noti_connecting, outgoingTransferTitle(waiting.copy(stage = LiveStage.HANDSHAKE)))
        assertEquals(R.string.noti_connecting, outgoingTransferTitle(waiting.copy(stage = LiveStage.REQUESTED)))
        assertEquals(R.string.noti_connecting, outgoingTransferTitle(waiting.copy(stage = LiveStage.WAITING_AUTH)))
    }

    @Test
    fun pendingCancelReplacesWaitingAndSendingProgressWithoutClaimingAResult() {
        for (status in listOf(TransferUiStatus.WAITING, TransferUiStatus.SENDING)) {
            val state = TransferUiState(1, "peer", status, 40, LiveStage.TRANSFERRING, cancelRequested = true)
            assertEquals(R.string.transfer_canceling, outgoingTransferTitle(state))
            assertEquals(TransferVisualState.FINALIZING, outgoingTransferVisual(state))
        }
    }

    @Test
    fun metadataDistinguishesTextMissingNamesAndMultipleFiles() {
        assertEquals(AttachmentKind.TEXT, attachmentKind("", null, true, 1))
        assertEquals(AttachmentKind.FILE, attachmentKind("", null, false, 1))
        assertEquals(AttachmentKind.IMAGE, attachmentKind("first.jpg", "image/jpeg", false, 10))
        assertEquals(AttachmentKind.VIDEO, attachmentKind("first.mp4", "video/mp4", false, 10))
        assertEquals(AttachmentKind.MULTIPLE, attachmentKind("first.jpg", "*/*", false, 10))
        assertEquals(AttachmentKind.MULTIPLE, attachmentKind("first.jpg", null, false, 10))
        assertEquals(AttachmentKind.IMAGE, attachmentKind("photo", "image/heic", false, 1))
        assertEquals(AttachmentKind.VIDEO, attachmentKind("movie.MP4", null, false, 1))
    }

    @Test
    fun unconfirmedSendIsNotSuccessOrACanceledTransfer() {
        val state = TransferUiState(1, "peer", TransferUiStatus.UNCONFIRMED)
        assertEquals(R.string.device_status_unconfirmed, outgoingTransferTitle(state))
        assertEquals(TransferVisualState.FAILURE, outgoingTransferVisual(state))
    }

    @Test
    fun peerIdentityUsesExplicitBrandBeforeNumericFallback() {
        assertEquals(130, DeviceUtils.resolvePeerBrandId(70, "Google"))
        assertEquals(10, DeviceUtils.resolvePeerBrandId(70, " OPPO "))
        assertEquals(130, DeviceUtils.resolvePeerBrandId(255, "Google Pixel"))
        assertEquals(30, DeviceUtils.resolvePeerBrandId(null, "REDMI"))
    }

    @Test
    fun peerIdentityUsesKnownIdOnlyWhenBrandTextIsMissing() {
        assertEquals(10, DeviceUtils.resolvePeerBrandId(10, null))
        assertEquals(19, DeviceUtils.resolvePeerBrandId(19, "  "))
        assertEquals(null, DeviceUtils.resolvePeerBrandId(255, null))
        assertEquals(null, DeviceUtils.resolvePeerBrandId(null, null))
        assertEquals(null, DeviceUtils.resolvePeerBrandId(10, "Unknown"))
        assertEquals(null, DeviceUtils.resolvePeerBrandId(10, "New manufacturer"))
    }

    @Test
    fun peerIdentityDoesNotGuessFromNicknames() {
        assertEquals(null, DeviceUtils.resolvePeerBrandId(null, "JO2EY"))
        assertEquals(null, DeviceUtils.resolvePeerBrandId(null, "Pixel from Jo"))
    }

    @Test
    fun receiveBrandSurvivesAcceptanceProgressAndCompletion() {
        val brandId = DeviceUtils.resolvePeerBrandId(70, "OPPO")
        val request = IncomingTransferUiState(1, "peer", brandId, "file.jpg", 1, 100,
            IncomingTransferUiStatus.REQUESTED)
        assertEquals(R.drawable.device_oppo, DeviceUtils.deviceIconById(brandId))
        IncomingTransferUiCoordinator.beginRequest(request, 1_000L, "expired")
        IncomingTransferUiCoordinator.decide(1, IncomingRequestDecision.ACCEPTED, 100L)
        assertEquals(brandId, IncomingTransferUiCoordinator.get(1)!!.brandId)
        IncomingTransferUiCoordinator.markReceiving(1, 50)
        assertEquals(brandId, IncomingTransferUiCoordinator.get(1)!!.brandId)
        IncomingTransferUiCoordinator.complete(1, emptyList(), false)
        assertEquals(IncomingTransferUiStatus.SUCCESS, IncomingTransferUiCoordinator.get(1)!!.status)
        assertEquals(R.drawable.device_oppo,
            DeviceUtils.deviceIconById(IncomingTransferUiCoordinator.get(1)!!.brandId))
    }

    @Test
    fun receiveProgressPreservesOriginalTaskIdentityAndCannotClaimCompletion() {
        IncomingTransferUiCoordinator.publish(IncomingTransferUiState(1, "peer", null, "first.jpg", 10, 100,
            IncomingTransferUiStatus.REQUESTED))
        IncomingTransferUiCoordinator.markReceiving(1, progress = 100, fileName = "last.pdf")
        val current = IncomingTransferUiCoordinator.get(1)!!
        assertEquals("first.jpg", current.fileName)
        assertEquals("last.pdf", current.currentFileName)
        assertEquals(99, current.progress)
    }

    @Test
    fun lateConnectingEventsCannotReplaceTransferOrCompletion() {
        val active = TransferUiState(1, "peer", TransferUiStatus.SENDING, 40, LiveStage.TRANSFERRING)
        TransferUiCoordinator.publish(active)
        TransferUiCoordinator.publish(active.copy(status = TransferUiStatus.WAITING, progress = 0, stage = LiveStage.WAITING_AUTH))
        assertEquals(active, TransferUiCoordinator.states.value["peer"])
        val complete = active.copy(status = TransferUiStatus.SUCCESS, progress = 100, stage = LiveStage.COMPLETED)
        TransferUiCoordinator.publish(complete)
        TransferUiCoordinator.publish(active)
        assertEquals(complete, TransferUiCoordinator.states.value["peer"])
    }
}
