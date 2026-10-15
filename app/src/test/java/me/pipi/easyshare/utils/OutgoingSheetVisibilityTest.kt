package me.pipi.easyshare.utils

import me.pipi.easyshare.models.OutgoingTransferPresentation
import me.pipi.easyshare.models.TransferUiState
import me.pipi.easyshare.models.TransferUiStatus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OutgoingSheetVisibilityTest {
    private val task = OutgoingTransferPresentation(1, "peer", "Device", null,
        "file.jpg", "image/jpeg", 1, 50, false)

    @Before
    @After
    fun clearState() = TransferUiCoordinator.clear()

    @Test
    fun initiatingSheetIsVisibleBeforeTheServiceStartsAndThroughItsResult() {
        val sheet = Any()
        assertTrue(TransferUiCoordinator.showSheet(1, "peer", sheet))
        assertTrue(TransferUiCoordinator.begin(task))
        assertTrue(TransferUiCoordinator.isSheetVisible(1, "peer"))

        assertTrue(TransferUiCoordinator.publish(TransferUiState(1, "peer",
            TransferUiStatus.SUCCESS, 100, LiveStage.COMPLETED)))
        assertTrue(TransferUiCoordinator.isSheetVisible(1, "peer"))
        assertEquals(setOf(1 to "peer"), TransferUiCoordinator.sheetVisibility.value)

        assertTrue(TransferUiCoordinator.finish(1, "peer"))
        assertTrue(TransferUiCoordinator.sheetVisibility.value.isEmpty())
        assertFalse(TransferUiCoordinator.hideSheet(1, "peer", sheet))
    }

    @Test
    fun onlyTheLastMatchingOwnerHidesTheSheet() {
        val oldSheet = Any()
        val newSheet = Any()
        TransferUiCoordinator.begin(task)
        assertTrue(TransferUiCoordinator.showSheet(1, "peer", oldSheet))
        assertFalse(TransferUiCoordinator.showSheet(1, "peer", newSheet))
        assertFalse(TransferUiCoordinator.hideSheet(1, "peer", Any()))
        assertFalse(TransferUiCoordinator.hideSheet(1, "peer", oldSheet))
        assertTrue(TransferUiCoordinator.isSheetVisible(1, "peer"))
        assertTrue(TransferUiCoordinator.hideSheet(1, "peer", newSheet))
        assertFalse(TransferUiCoordinator.isSheetVisible(1, "peer"))
        assertFalse(TransferUiCoordinator.hideSheet(1, "peer", newSheet))
    }

    @Test
    fun oldTaskVisibilityAndCleanupCannotAffectTheNextTask() {
        val oldSheet = Any()
        val newSheet = Any()
        TransferUiCoordinator.begin(task)
        TransferUiCoordinator.showSheet(1, "peer", oldSheet)
        TransferUiCoordinator.finish(1, "peer")
        assertTrue(TransferUiCoordinator.showSheet(2, "peer", newSheet))
        assertTrue(TransferUiCoordinator.begin(task.copy(taskId = 2)))
        assertFalse(TransferUiCoordinator.showSheet(1, "peer", oldSheet))
        assertFalse(TransferUiCoordinator.hideSheet(1, "peer", oldSheet))
        assertFalse(TransferUiCoordinator.finish(1, "peer"))
        assertFalse(TransferUiCoordinator.isSheetVisible(2, "other-peer"))
        assertEquals(setOf(2 to "peer"), TransferUiCoordinator.sheetVisibility.value)
    }
}
