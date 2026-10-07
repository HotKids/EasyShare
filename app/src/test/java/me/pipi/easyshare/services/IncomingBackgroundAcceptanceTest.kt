package me.pipi.easyshare.services

import kotlinx.coroutines.runBlocking
import me.pipi.easyshare.models.IncomingTransferUiState
import me.pipi.easyshare.models.IncomingTransferUiStatus
import me.pipi.easyshare.utils.IncomingRequestDecision
import me.pipi.easyshare.utils.IncomingTransferUiCoordinator
import me.pipi.easyshare.utils.LiveStage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class IncomingBackgroundAcceptanceTest {
    @Before
    @After
    fun clearRequests() {
        IncomingTransferUiCoordinator.clearAll()
    }

    @Test
    fun notificationAcceptanceKeepsTheHiddenTransferInBackgroundThroughProgress() = runBlocking {
        beginRequest(7)
        assertTrue(IncomingTransferUiCoordinator.takeRequestAlert(7, 100L))
        assertTrue(IncomingTransferUiCoordinator.decide(7, IncomingRequestDecision.ACCEPTED, 101L))
        assertEquals(IncomingRequestDecision.ACCEPTED, IncomingTransferUiCoordinator.awaitDecision(7, 102L))

        IncomingTransferUiCoordinator.markReceiving(7, progress = 37, fileName = "file.txt")
        val state = requireNotNull(IncomingTransferUiCoordinator.get(7))
        assertTrue(state.monitorInBackground)
        assertEquals(IncomingTransferUiStatus.RECEIVING, state.status)
        assertEquals(LiveStage.TRANSFERRING, state.stage)
        assertEquals(37, state.progress)
        assertFalse(IncomingTransferUiCoordinator.takeRequestAlert(7, 103L))
        assertFalse(IncomingTransferUiCoordinator.decide(7, IncomingRequestDecision.REJECTED, 103L))
    }

    @Test
    fun staleNotificationAcceptanceCannotDecideTheNextRequest() {
        beginRequest(7)
        assertTrue(IncomingTransferUiCoordinator.decide(7, IncomingRequestDecision.ACCEPTED, 100L))
        IncomingTransferUiCoordinator.releaseDecision(7)
        beginRequest(8)

        assertFalse(IncomingTransferUiCoordinator.decide(7, IncomingRequestDecision.ACCEPTED, 101L))
        assertEquals(IncomingTransferUiStatus.REQUESTED, IncomingTransferUiCoordinator.get(8)?.status)
        assertEquals(0, IncomingTransferUiCoordinator.get(8)?.progress)
    }

    private fun beginRequest(taskId: Int) {
        assertTrue(IncomingTransferUiCoordinator.beginRequest(
            IncomingTransferUiState(
                taskId = taskId,
                senderName = "peer",
                brandId = null,
                fileName = "file.txt",
                fileCount = 1,
                totalSize = 50L,
                status = IncomingTransferUiStatus.REQUESTED,
            ),
            expiresAtMillis = 5_000L,
            timeoutMessage = "request timeout",
        ))
    }
}
