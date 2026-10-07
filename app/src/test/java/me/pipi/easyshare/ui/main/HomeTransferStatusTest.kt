package me.pipi.easyshare.ui.main

import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.pipi.easyshare.R
import me.pipi.easyshare.models.IncomingTransferUiState
import me.pipi.easyshare.models.IncomingTransferUiStatus
import me.pipi.easyshare.models.LiveUpdateState
import me.pipi.easyshare.models.TransferUiState
import me.pipi.easyshare.models.TransferUiStatus
import me.pipi.easyshare.utils.IncomingTransferUiCoordinator
import me.pipi.easyshare.utils.IncomingRequestDecision
import me.pipi.easyshare.utils.LiveStage
import me.pipi.easyshare.utils.LiveUpdateCoordinator
import me.pipi.easyshare.utils.NotificationUtils
import me.pipi.easyshare.utils.TransferUiCoordinator
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeTransferStatusTest {
    private fun live(sending: Boolean, stage: LiveStage, progress: Int = -1, isText: Boolean = false) = LiveUpdateState(
        taskKey = NotificationUtils.taskKey(if (sending) "send" else "receive", 7),
        channelId = if (sending) NotificationUtils.SENDER_CHAN_ID else NotificationUtils.RECEIVER_CHAN_ID,
        stage = stage,
        progress = progress,
        isText = isText,
    )

    private fun incoming(status: IncomingTransferUiStatus, stage: LiveStage = LiveStage.WAITING_AUTH,
        progress: Int = 0, isText: Boolean = false, taskId: Int = 7) = IncomingTransferUiState(
        taskId, "Peer", 10, "photo.jpg", 1, 1024L, status,
        stage = stage, progress = progress, isText = isText,
    )

    @After
    fun clearCoordinators() {
        LiveUpdateCoordinator.clearState("HOME_TEST")
        IncomingTransferUiCoordinator.clearAll()
        TransferUiCoordinator.clear()
    }

    @Test
    fun senderStagesHaveDistinctLabelsWithoutInventingConnectionOrCompletionProgress() {
        val labels = mapOf(
            LiveStage.INIT to R.string.home_status_preparing_send,
            LiveStage.PREPARING to R.string.home_status_preparing_send,
            LiveStage.HANDSHAKE to R.string.home_status_preparing_send,
            LiveStage.REQUESTED to R.string.home_status_waiting_peer,
            LiveStage.WAITING_AUTH to R.string.home_status_waiting_peer,
            LiveStage.TRANSFERRING to R.string.home_status_sending_progress,
            LiveStage.FINALIZING to R.string.home_status_waiting_result,
        )
        labels.forEach { (stage, label) ->
            assertEquals(HomeTransferStatus(label, if (stage == LiveStage.TRANSFERRING) 36 else null),
                homeTransferStatus(live(true, stage, 36), emptyMap(), emptyMap()))
        }
        assertNull(homeTransferStatus(live(true, LiveStage.COMPLETED, 100), emptyMap(), emptyMap()))
    }

    @Test
    fun receiverStagesSeparateConsentDownloadingAndSaving() {
        val labels = mapOf(
            LiveStage.INIT to R.string.home_status_preparing_receive,
            LiveStage.PREPARING to R.string.home_status_preparing_receive,
            LiveStage.HANDSHAKE to R.string.home_status_preparing_receive,
            LiveStage.REQUESTED to R.string.home_status_waiting_consent,
            LiveStage.WAITING_AUTH to R.string.home_status_waiting_consent,
            LiveStage.TRANSFERRING to R.string.home_status_receiving_progress,
            LiveStage.FINALIZING to R.string.home_status_saving,
        )
        labels.forEach { (stage, label) ->
            assertEquals(HomeTransferStatus(label, if (stage == LiveStage.TRANSFERRING) 36 else null),
                homeTransferStatus(live(false, stage, 36), emptyMap(), emptyMap()))
        }
    }

    @Test
    fun aPendingRequestCannotShowProgressEvenIfNotificationPresentationLags() {
        assertEquals(HomeTransferStatus(R.string.home_status_waiting_consent), homeTransferStatus(
            live(false, LiveStage.TRANSFERRING, 36),
            mapOf(7 to incoming(IncomingTransferUiStatus.REQUESTED)), emptyMap(),
        ))
        assertEquals(HomeTransferStatus(R.string.home_status_preparing_receive), homeTransferStatus(
            live(false, LiveStage.WAITING_AUTH),
            mapOf(7 to incoming(IncomingTransferUiStatus.RECEIVING, LiveStage.PREPARING)), emptyMap(),
        ))
    }

    @Test
    fun textNeverHasAFilePercentageAndFileProgressIsBoundedBelowCompletion() {
        listOf(true, false).forEach { sending ->
            assertEquals(HomeTransferStatus(if (sending) R.string.home_status_sending else R.string.home_status_receiving),
                homeTransferStatus(live(sending, LiveStage.TRANSFERRING, 36, isText = true), emptyMap(), emptyMap()))
            assertEquals(99, homeTransferStatus(live(sending, LiveStage.TRANSFERRING, 100), emptyMap(), emptyMap())?.progress)
            assertNull(homeTransferStatus(live(sending, LiveStage.FINALIZING, 100), emptyMap(), emptyMap())?.progress)
        }
    }

    @Test
    fun terminalDomainStatesHideOldOngoingPresentationImmediately() {
        IncomingTransferUiStatus.entries.filter {
            it != IncomingTransferUiStatus.REQUESTED && it != IncomingTransferUiStatus.RECEIVING
        }.forEach { status ->
            assertNull(homeTransferStatus(live(false, LiveStage.WAITING_AUTH),
                mapOf(7 to incoming(status)), emptyMap()))
        }
        TransferUiStatus.entries.filter { it != TransferUiStatus.WAITING && it != TransferUiStatus.SENDING }.forEach { status ->
            assertNull(homeTransferStatus(live(true, LiveStage.TRANSFERRING, 36), emptyMap(),
                mapOf("peer" to TransferUiState(7, "peer", status))))
        }
    }

    @Test
    fun unrelatedRetainedResultsDoNotReplaceTheCurrentTask() {
        assertEquals(HomeTransferStatus(R.string.home_status_preparing_receive), homeTransferStatus(
            live(false, LiveStage.PREPARING),
            mapOf(8 to incoming(IncomingTransferUiStatus.FAILED, taskId = 8)),
            mapOf("peer" to TransferUiState(7, "peer", TransferUiStatus.SUCCESS)),
        ))
        assertNull(homeTransferStatus(LiveUpdateState.IDLE, emptyMap(), emptyMap()))
        assertNull(homeTransferStatus(live(false, LiveStage.TRANSFERRING, 36).copy(ongoing = false), emptyMap(), emptyMap()))
        assertNull(homeTransferStatus(live(false, LiveStage.PREPARING).copy(channelId = NotificationUtils.RECEIVER_FG_CHAN_ID),
            emptyMap(), emptyMap()))
    }

    @Test
    fun homeUsesAcceptedReceiveProgressInsteadOfAnOlderNotificationSnapshot() {
        assertEquals(HomeTransferStatus(R.string.home_status_receiving_progress, 67), homeTransferStatus(
            live(false, LiveStage.WAITING_AUTH),
            mapOf(7 to incoming(IncomingTransferUiStatus.RECEIVING, LiveStage.TRANSFERRING, 67)), emptyMap(),
        ))
        assertEquals(HomeTransferStatus(R.string.home_status_sending_progress, 52), homeTransferStatus(
            live(true, LiveStage.WAITING_AUTH), emptyMap(),
            mapOf("peer" to TransferUiState(7, "peer", TransferUiStatus.SENDING, 52, LiveStage.TRANSFERRING)),
        ))
    }

    @Test
    fun theSameFlowProjectionUsedByHomeReturnsToIdleAfterTimeout() = runBlocking {
        val live = live(false, LiveStage.WAITING_AUTH)
        LiveUpdateCoordinator.publishState("HOME_TEST", live)
        IncomingTransferUiCoordinator.beginRequest(
            incoming(IncomingTransferUiStatus.REQUESTED), expiresAtMillis = 200L,
            timeoutMessage = "Request timed out",
        )
        val projection = combine(LiveUpdateCoordinator.state, IncomingTransferUiCoordinator.states,
            TransferUiCoordinator.states, ::homeTransferStatus)
        assertEquals(HomeTransferStatus(R.string.home_status_waiting_consent), projection.first())
        assertEquals(IncomingRequestDecision.TIMED_OUT,
            IncomingTransferUiCoordinator.awaitDecision(7, nowMillis = 201L))
        assertNull(projection.first())
        assertEquals(R.string.brand_receive_ready, receiveAvailabilityText(true, true))
        assertEquals(R.string.brand_receive_bluetooth_off, receiveAvailabilityText(true, false))
    }
}
