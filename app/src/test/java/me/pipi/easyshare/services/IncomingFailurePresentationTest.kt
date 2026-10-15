package me.pipi.easyshare.services

import kotlinx.coroutines.CancellationException
import me.pipi.easyshare.exceptions.CancelledByUserException
import me.pipi.easyshare.models.IncomingTransferUiStatus
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

class IncomingFailurePresentationTest {
    @Test
    fun cancellationBeforeRequestMetadataIsNotAReceiveFailure() {
        listOf(false, true).forEach { remote ->
            assertEquals(IncomingTransferUiStatus.CANCELED, incomingFailureStatus(CancelledByUserException(remote)))
        }
    }

    @Test
    fun unexpectedConnectionAndCoroutineErrorsRemainFailures() {
        listOf(IOException(), CancellationException(), null).forEach { error ->
            assertEquals(IncomingTransferUiStatus.FAILED, incomingFailureStatus(error))
        }
    }
}
