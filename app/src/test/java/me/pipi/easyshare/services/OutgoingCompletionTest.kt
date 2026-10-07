package me.pipi.easyshare.services

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import me.pipi.easyshare.utils.RemoteTransferOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.io.EOFException

class OutgoingCompletionTest {
    @Test(timeout = 5_000)
    fun inlineTextHasNoDownloadButStillRequiresThePeersCopyReceipt() = runBlocking {
        val downloadStart = CompletableDeferred<Unit>()
        val local = async {
            assertFalse(awaitOutgoingDownloadStart(inlineText = true, transferStart = downloadStart))
        }
        val remote = CompletableDeferred<Pair<Int, String>>()
        val result = async { awaitOutgoingOutcome(local, remote) }
        yield()
        assertFalse(downloadStart.isCompleted)
        assertFalse(result.isCompleted)
        remote.complete(1 to "ok")
        assertEquals(RemoteTransferOutcome.SUCCESS, result.await())
    }

    @Test(timeout = 5_000)
    fun fileTransferStillWaitsForTheActualDownloadRequest() = runBlocking {
        val downloadStart = CompletableDeferred<Unit>()
        val result = async { awaitOutgoingDownloadStart(inlineText = false, transferStart = downloadStart) }
        yield()
        assertFalse(result.isCompleted)
        downloadStart.complete(Unit)
        assertEquals(true, result.await())
    }

    @Test(timeout = 5_000)
    fun earlyFullReceiptStillWaitsForTheLocalOutputToFinish() = runBlocking {
        val local = CompletableDeferred<Unit>()
        val remote = CompletableDeferred<Pair<Int, String>>()
        remote.complete(1 to "ok")
        val result = async { awaitOutgoingOutcome(local, remote) }
        yield()
        assertFalse(result.isCompleted)
        local.complete(Unit)
        assertEquals(RemoteTransferOutcome.SUCCESS, result.await())
    }

    @Test(timeout = 5_000)
    fun finishedOutputStillWaitsForThePeersReceipt() = runBlocking {
        val local = CompletableDeferred<Unit>().apply { complete(Unit) }
        val remote = CompletableDeferred<Pair<Int, String>>()
        val result = async { awaitOutgoingOutcome(local, remote) }
        yield()
        assertFalse(result.isCompleted)
        remote.complete(1 to "ok")
        assertEquals(RemoteTransferOutcome.SUCCESS, result.await())
    }

    @Test(timeout = 5_000)
    fun partialReceiptDoesNotDemandAllRemainingBytes() = runBlocking {
        val local = CompletableDeferred<Unit>()
        val remote = CompletableDeferred<Pair<Int, String>>().apply { complete(1 to "partial") }
        assertEquals(RemoteTransferOutcome.PARTIAL, awaitOutgoingOutcome(local, remote))
        assertFalse(local.isCompleted)
    }

    @Test(timeout = 5_000)
    fun rejectionAndTimeoutDoNotWaitForAFileDownload() = runBlocking {
        for ((reason, expected) in listOf(
            "user refuse" to RemoteTransferOutcome.REJECTED,
            "timeout" to RemoteTransferOutcome.TIMED_OUT,
            "connection failed" to RemoteTransferOutcome.FAILED,
        )) {
            val local = CompletableDeferred<Unit>()
            val remote = CompletableDeferred<Pair<Int, String>>().apply { complete(3 to reason) }
            assertEquals(expected, awaitOutgoingOutcome(local, remote))
            assertFalse(local.isCompleted)
        }
    }

    @Test(timeout = 5_000)
    fun fullReceiptCannotHideALocalOutputFailure() = runBlocking {
        val failure = IOException("output failed")
        val local = CompletableDeferred<Unit>().apply { completeExceptionally(failure) }
        val remote = CompletableDeferred<Pair<Int, String>>().apply { complete(1 to "ok") }
        try {
            awaitOutgoingOutcome(local, remote)
            fail("Full success requires a normal local completion")
        } catch (error: IOException) {
            assertEquals(failure.message, error.message)
            assertTrue("Coroutine recovery must retain the original output failure",
                generateSequence<Throwable>(error) { it.cause }.any { it === failure })
        }
    }

    @Test(timeout = 5_000)
    fun completedOutputWithoutReceiptIsUnconfirmedNotSuccessOrRequestTimeout() = runBlocking {
        val local = CompletableDeferred<Unit>().apply { complete(Unit) }
        val remote = CompletableDeferred<Pair<Int, String>>()
        val error = runCatching { awaitOutgoingOutcome(local, remote, confirmationTimeoutMillis = 10L) }
            .exceptionOrNull()
        assertTrue(error is UnconfirmedTransferException)
        assertEquals(me.pipi.easyshare.models.TransferUiStatus.UNCONFIRMED, outgoingFailureStatus(error))
        assertFalse(remote.isCompleted)
    }

    @Test(timeout = 5_000)
    fun lostReceiptAfterCompletedOutputIsUnconfirmedButEarlierFailureIsNot() = runBlocking {
        val remote = CompletableDeferred<Pair<Int, String>>().apply { completeExceptionally(EOFException()) }
        val complete = CompletableDeferred<Unit>().apply { complete(Unit) }
        assertTrue(runCatching { awaitOutgoingOutcome(complete, remote) }.exceptionOrNull() is UnconfirmedTransferException)
        val incomplete = CompletableDeferred<Unit>()
        assertTrue(runCatching { awaitOutgoingOutcome(incomplete, remote) }.exceptionOrNull() is EOFException)
    }
}
