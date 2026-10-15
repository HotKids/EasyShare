package me.pipi.easyshare.services

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.pipi.easyshare.utils.TRANSFER_COMPLETION_DISPLAY_MILLIS
import me.pipi.easyshare.utils.awaitTransferCompletionDisplay
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class IncomingCompletionDisplayTest {
    @Test(timeout = 10_000)
    fun peerCancellationCannotSkipTheRemainingCompletionDisplay() = runBlocking {
        val published = CompletableDeferred<Unit>()
        val startedAt = System.nanoTime()
        val receive = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                published.await()
            } finally {
                awaitTransferCompletionDisplay(TRANSFER_COMPLETION_DISPLAY_MILLIS, 0L)
            }
        }
        receive.cancelAndJoin()
        val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
        assertTrue("Completion was removed after only $elapsed ms", elapsed >= 5_000L)
    }

    @Test(timeout = 1_000)
    fun slowProtocolCleanupDoesNotStartAnotherFiveSecondWait() = runBlocking {
        awaitTransferCompletionDisplay(TRANSFER_COMPLETION_DISPLAY_MILLIS, 6_000L)
        awaitTransferCompletionDisplay(0L, 6_000L)
    }
}
