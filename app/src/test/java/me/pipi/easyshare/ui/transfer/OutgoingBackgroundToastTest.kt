package me.pipi.easyshare.ui.transfer

import me.pipi.easyshare.models.TransferUiState
import me.pipi.easyshare.models.TransferUiStatus
import me.pipi.easyshare.shouldShowBackgroundSendingToast
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class OutgoingBackgroundToastTest {
    @Test
    fun onlyUncanceledActiveTransfersShowTheBackgroundMessage() {
        assertFalse(shouldShowBackgroundSendingToast(null))
        for (status in TransferUiStatus.entries) {
            val state = TransferUiState(1, "peer", status)
            assertEquals(status == TransferUiStatus.WAITING || status == TransferUiStatus.SENDING,
                shouldShowBackgroundSendingToast(state))
            assertFalse(shouldShowBackgroundSendingToast(state.copy(cancelRequested = true)))
        }
    }
}
