package me.pipi.easyshare.utils

import me.pipi.easyshare.models.IncomingTransferUiState
import me.pipi.easyshare.models.IncomingTransferUiStatus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class IncomingTransferUiCoordinatorTest {
    private val taskId = 42

    @Before
    fun setUp() {
        IncomingTransferUiCoordinator.clearAll()
        IncomingTransferUiCoordinator.publish(
            IncomingTransferUiState(
                taskId = taskId,
                senderName = "Pixel",
                brandId = 0,
                fileName = "test.txt",
                fileCount = 1,
                totalSize = 1024,
                status = IncomingTransferUiStatus.REQUESTED,
            ),
        )
    }

    @After
    fun tearDown() {
        IncomingTransferUiCoordinator.clearAll()
    }

    @Test
    fun acceptedRequestMovesThroughProgressToSuccess() {
        IncomingTransferUiCoordinator.markReceiving(taskId, progress = 37)
        assertEquals(
            IncomingTransferUiStatus.RECEIVING,
            IncomingTransferUiCoordinator.get(taskId)?.status,
        )
        assertEquals(37, IncomingTransferUiCoordinator.get(taskId)?.progress)

        IncomingTransferUiCoordinator.complete(taskId, files = emptyList(), partial = false)
        assertEquals(
            IncomingTransferUiStatus.SUCCESS,
            IncomingTransferUiCoordinator.get(taskId)?.status,
        )
        assertEquals(100, IncomingTransferUiCoordinator.get(taskId)?.progress)
    }

    @Test
    fun disconnectedRequestMovesToFailurePage() {
        IncomingTransferUiCoordinator.fail(taskId, message = "connection lost")
        assertEquals(
            IncomingTransferUiStatus.FAILED,
            IncomingTransferUiCoordinator.get(taskId)?.status,
        )
        assertEquals("connection lost", IncomingTransferUiCoordinator.get(taskId)?.errorMessage)
    }

    @Test
    fun successIsNotOverwrittenByLateCancellation() {
        IncomingTransferUiCoordinator.markReceiving(taskId, progress = 100)
        IncomingTransferUiCoordinator.complete(taskId, files = emptyList(), partial = false)

        IncomingTransferUiCoordinator.fail(taskId, message = "late cancellation", canceled = true)

        assertEquals(
            IncomingTransferUiStatus.SUCCESS,
            IncomingTransferUiCoordinator.get(taskId)?.status,
        )
    }

    @Test
    fun failureIsNotOverwrittenByLateProgress() {
        IncomingTransferUiCoordinator.markReceiving(taskId, progress = 50)
        IncomingTransferUiCoordinator.fail(taskId, message = "network error")

        IncomingTransferUiCoordinator.markReceiving(taskId, progress = 90)
        IncomingTransferUiCoordinator.complete(taskId, files = emptyList(), partial = false)

        assertEquals(
            IncomingTransferUiStatus.FAILED,
            IncomingTransferUiCoordinator.get(taskId)?.status,
        )
        assertEquals("network error", IncomingTransferUiCoordinator.get(taskId)?.errorMessage)
    }
}
