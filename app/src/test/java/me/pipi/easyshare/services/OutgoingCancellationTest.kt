package me.pipi.easyshare.services

import kotlinx.coroutines.Job
import me.pipi.easyshare.models.OutgoingTransferPresentation
import me.pipi.easyshare.models.TransferUiState
import me.pipi.easyshare.utils.TransferUiCoordinator
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OutgoingCancellationTest {
    private val task = OutgoingTransferPresentation(1, "peer", "Device", null,
        "file.jpg", "image/jpeg", 1, 50, false)

    @Before
    @After
    fun clearState() = TransferUiCoordinator.clear()

    @Test
    fun sheetRequestAlreadyRecordedByTheOwnerStillReachesTheJobOnce() {
        TransferUiCoordinator.begin(task)
        TransferUiCoordinator.requestCancel(1, "peer")
        val job = Job()

        assertTrue(cancelOutgoingJob(job, 1, "peer"))
        assertTrue(job.isCancelled)
        assertFalse(cancelOutgoingJob(job, 1, "peer"))
    }

    @Test
    fun notificationRequestPublishesCancellationBeforeStoppingTheJob() {
        TransferUiCoordinator.begin(task)
        val job = Job()
        var stateAtCancellation: TransferUiState? = null
        job.invokeOnCompletion { stateAtCancellation = TransferUiCoordinator.states.value["peer"] }

        assertTrue(cancelOutgoingJob(job, 1, "peer"))
        assertEquals(true, stateAtCancellation?.cancelRequested)
        assertFalse(TransferUiCoordinator.requestCancel(1, "peer"))
        assertFalse(cancelOutgoingJob(job, 1, "peer"))
    }

    @Test
    fun staleNotificationCannotCancelTheCurrentTask() {
        TransferUiCoordinator.begin(task)
        val job = Job()
        assertFalse(cancelOutgoingJob(job, 2, "peer"))
        assertFalse(cancelOutgoingJob(job, 1, "another-peer"))
        assertTrue(job.isActive)
        assertEquals(false, TransferUiCoordinator.states.value["peer"]?.cancelRequested)
        job.cancel()
    }
}
