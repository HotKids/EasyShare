package me.pipi.easyshare.ui.transfer

import me.pipi.easyshare.R
import me.pipi.easyshare.incomingTransferTitle
import me.pipi.easyshare.models.IncomingTransferUiStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class TransferSheetTextTest {
    @Test
    fun receiveTitleReflectsEveryStateWithoutEmbeddingSenderIdentity() {
        val expected = mapOf(
            IncomingTransferUiStatus.REQUESTED to R.string.auth_waiting,
            IncomingTransferUiStatus.RECEIVING to R.string.receiving,
            IncomingTransferUiStatus.SUCCESS to R.string.recv_ok,
            IncomingTransferUiStatus.PARTIAL to R.string.recv_partial,
            IncomingTransferUiStatus.FAILED to R.string.recv_fail,
            IncomingTransferUiStatus.CANCELED to R.string.cancelled_by_user_local,
        )
        assertEquals(IncomingTransferUiStatus.entries.toSet(), expected.keys)
        expected.forEach { (status, title) -> assertEquals(title, incomingTransferTitle(status)) }
    }
}
