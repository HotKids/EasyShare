package me.pipi.easyshare.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class TransferStatusProtocolTest {
    @Test
    fun distinguishesExplicitRejectionFromNoResponseTimeout() {
        assertEquals(
            RemoteTransferOutcome.REJECTED,
            TransferStatusProtocol.classify(3, "user refuse"),
        )
        assertEquals(
            RemoteTransferOutcome.TIMED_OUT,
            TransferStatusProtocol.classify(3, "timeout"),
        )
    }

    @Test
    fun preservesFullAndPartialSuccess() {
        assertEquals(RemoteTransferOutcome.SUCCESS, TransferStatusProtocol.classify(1, "ok"))
        assertEquals(RemoteTransferOutcome.PARTIAL, TransferStatusProtocol.classify(1, "partial"))
    }
}
