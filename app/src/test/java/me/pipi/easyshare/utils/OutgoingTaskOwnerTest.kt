package me.pipi.easyshare.utils

import me.pipi.easyshare.models.OutgoingTransferPresentation
import me.pipi.easyshare.models.TransferUiState
import me.pipi.easyshare.models.TransferUiStatus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OutgoingTaskOwnerTest {
    private val task = OutgoingTransferPresentation(1, "peer", "Device", null,
        "file.jpg", "image/jpeg", 1, 50, false)

    @Before
    @After
    fun clearState() = TransferUiCoordinator.clear()

    @Test
    fun oneOwnerIsReservedBeforeTheServiceStarts() {
        assertTrue(TransferUiCoordinator.begin(task))
        assertFalse(TransferUiCoordinator.begin(task.copy(taskId = 2)))
        assertEquals(task, TransferUiCoordinator.activePresentation.value)
        assertEquals(TransferUiStatus.WAITING, TransferUiCoordinator.states.value["peer"]?.status)
    }

    @Test
    fun immediateCancelSurvivesUntilTheServiceRegistersItsReceiver() {
        TransferUiCoordinator.begin(task)
        assertTrue(TransferUiCoordinator.requestCancel(1, "peer"))
        assertTrue(TransferUiCoordinator.isCancelRequested(1, "peer"))
        assertFalse(TransferUiCoordinator.requestCancel(1, "other-peer"))
        assertFalse(TransferUiCoordinator.isCancelRequested(2, "peer"))
    }

    @Test
    fun terminalResultRetainsOwnershipUntilCleanupButIsNotAnActiveHomeEntry() {
        TransferUiCoordinator.begin(task)
        val result = TransferUiState(1, "peer", TransferUiStatus.SUCCESS, 100, LiveStage.COMPLETED)
        TransferUiCoordinator.publish(result)
        assertNull(TransferUiCoordinator.activePresentation.value)
        assertTrue(TransferUiCoordinator.owns(1, "peer"))
        assertFalse(TransferUiCoordinator.requestCancel(1, "peer"))
        assertFalse(TransferUiCoordinator.begin(task.copy(taskId = 2)))
        assertTrue(TransferUiCoordinator.finish(1, "peer"))
        assertEquals(result, TransferUiCoordinator.states.value["peer"])
    }

    @Test
    fun oldActionsAndCleanupCannotTouchTheNextTask() {
        TransferUiCoordinator.begin(task)
        assertTrue(TransferUiCoordinator.finish(1, "peer"))
        val next = task.copy(taskId = 2)
        assertTrue(TransferUiCoordinator.begin(next))
        TransferUiCoordinator.publish(TransferUiState(1, "peer", TransferUiStatus.FAILED))
        assertFalse(TransferUiCoordinator.finish(1, "peer"))
        assertFalse(TransferUiCoordinator.requestCancel(1, "peer"))
        assertEquals(next, TransferUiCoordinator.activePresentation.value)
        assertEquals(2, TransferUiCoordinator.states.value["peer"]?.taskId)
    }

    @Test
    fun explicitCancelAndCompletionHaveOneAtomicDecisionBoundary() {
        TransferUiCoordinator.begin(task)
        assertTrue(TransferUiCoordinator.requestCancel(1, "peer"))
        assertFalse(TransferUiCoordinator.publish(TransferUiState(1, "peer",
            TransferUiStatus.SUCCESS, 100, LiveStage.COMPLETED)))
        assertTrue(TransferUiCoordinator.publish(TransferUiState(1, "peer", TransferUiStatus.CANCELED)))
        assertFalse(TransferUiCoordinator.requestCancel(1, "peer"))
        assertEquals(TransferUiStatus.CANCELED, TransferUiCoordinator.states.value["peer"]?.status)
    }
}
