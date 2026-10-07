package me.pipi.easyshare.utils

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.pipi.easyshare.models.IncomingTransferUiState
import me.pipi.easyshare.models.IncomingTransferUiStatus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun decisionCanArriveBeforeServiceStartsWaiting() = runBlocking {
        beginRequest()
        assertTrue(IncomingTransferUiCoordinator.decide(taskId, IncomingRequestDecision.ACCEPTED, 100L))
        assertEquals(IncomingRequestDecision.ACCEPTED, IncomingTransferUiCoordinator.awaitDecision(taskId, 101L))
        val state = IncomingTransferUiCoordinator.get(taskId)!!
        assertEquals(IncomingTransferUiStatus.RECEIVING, state.status)
        assertEquals(LiveStage.PREPARING, state.stage)
        assertEquals(1_600L, state.cancelEnabledAtMillis)
        assertEquals(200L, state.requestExpiresAtMillis)
        assertFalse(IncomingTransferUiCoordinator.decide(taskId, IncomingRequestDecision.REJECTED, 101L))
    }

    @Test
    fun pendingRequestCannotShowTransferProgressOrComplete() {
        beginRequest()
        IncomingTransferUiCoordinator.markReceiving(taskId, 50)
        IncomingTransferUiCoordinator.complete(taskId, emptyList(), false)
        assertEquals(IncomingTransferUiStatus.REQUESTED, IncomingTransferUiCoordinator.get(taskId)?.status)
        assertEquals(0, IncomingTransferUiCoordinator.get(taskId)?.progress)
    }

    @Test
    fun acceptAtDeadlineResolvesAsTimeout() = runBlocking {
        beginRequest()
        assertFalse(IncomingTransferUiCoordinator.decide(taskId, IncomingRequestDecision.ACCEPTED, 200L))
        assertEquals(IncomingRequestDecision.TIMED_OUT, IncomingTransferUiCoordinator.awaitDecision(taskId, 200L))
        assertEquals(IncomingTransferUiStatus.FAILED, IncomingTransferUiCoordinator.get(taskId)?.status)
        assertEquals("request timeout", IncomingTransferUiCoordinator.get(taskId)?.errorMessage)
        assertFalse(IncomingTransferUiCoordinator.decide(taskId, IncomingRequestDecision.REJECTED, 201L))
    }

    @Test
    fun expiredOrUnansweredRequestHasFiniteWait() = runBlocking {
        beginRequest(expiresAtMillis = 2L)
        assertFalse(IncomingTransferUiCoordinator.decide(taskId, IncomingRequestDecision.TIMED_OUT, 1L))
        val outcome = withTimeout(1_000L) { IncomingTransferUiCoordinator.awaitDecision(taskId, 1L) }
        assertEquals(IncomingRequestDecision.TIMED_OUT, outcome)
    }

    @Test
    fun concurrentAcceptAndRejectHaveOnlyOneWinner() = runBlocking {
        beginRequest()
        val gate = CompletableDeferred<Unit>()
        val accept = async(Dispatchers.Default) {
            gate.await()
            IncomingTransferUiCoordinator.decide(taskId, IncomingRequestDecision.ACCEPTED, 100L)
        }
        val reject = async(Dispatchers.Default) {
            gate.await()
            IncomingTransferUiCoordinator.decide(taskId, IncomingRequestDecision.REJECTED, 100L)
        }
        gate.complete(Unit)
        assertEquals(1, listOf(accept.await(), reject.await()).count { it })
        val outcome = IncomingTransferUiCoordinator.awaitDecision(taskId, 101L)
        assertTrue(outcome == IncomingRequestDecision.ACCEPTED || outcome == IncomingRequestDecision.REJECTED)
    }

    @Test
    fun rejectKeepsItsReasonAndCannotBeOverwrittenByTimeout() = runBlocking {
        beginRequest()
        assertTrue(IncomingTransferUiCoordinator.decide(taskId, IncomingRequestDecision.REJECTED, 100L))
        assertFalse(IncomingTransferUiCoordinator.decide(taskId, IncomingRequestDecision.TIMED_OUT, 201L))
        assertEquals(IncomingRequestDecision.REJECTED, IncomingTransferUiCoordinator.awaitDecision(taskId, 201L))
        assertEquals(IncomingTransferUiStatus.CANCELED, IncomingTransferUiCoordinator.get(taskId)?.status)
        assertEquals("request rejected", IncomingTransferUiCoordinator.get(taskId)?.errorMessage)
    }

    @Test
    fun hidingAnAlreadyVisibleRequestDoesNotCreateANewArrivalAlert() {
        beginRequest()
        IncomingTransferUiCoordinator.show(taskId, this)
        assertFalse(IncomingTransferUiCoordinator.takeRequestAlert(taskId, 100L))
        IncomingTransferUiCoordinator.hide(taskId, this)
        assertFalse(IncomingTransferUiCoordinator.takeRequestAlert(taskId, 101L))
    }

    @Test
    fun pendingRequestAlertsOnlyOnceWhenNotVisible() {
        beginRequest()
        assertTrue(IncomingTransferUiCoordinator.takeRequestAlert(taskId, 100L))
        assertFalse(IncomingTransferUiCoordinator.takeRequestAlert(taskId, 101L))
        IncomingTransferUiCoordinator.show(taskId, this)
        IncomingTransferUiCoordinator.hide(taskId, this)
        assertFalse(IncomingTransferUiCoordinator.takeRequestAlert(taskId, 102L))
        assertEquals(IncomingTransferUiStatus.REQUESTED, IncomingTransferUiCoordinator.get(taskId)?.status)
        assertEquals(200L, IncomingTransferUiCoordinator.get(taskId)?.requestExpiresAtMillis)
    }

    @Test
    fun acceptedRequestDoesNotReplayAnArrivalAlert() {
        beginRequest()
        IncomingTransferUiCoordinator.decide(taskId, IncomingRequestDecision.ACCEPTED, 100L)
        assertFalse(IncomingTransferUiCoordinator.takeRequestAlert(taskId, 100L))
        IncomingTransferUiCoordinator.hide(taskId, this)
        assertFalse(IncomingTransferUiCoordinator.takeRequestAlert(taskId, 101L))
    }

    @Test
    fun nextRequestHasAnIndependentArrivalAlert() {
        beginRequest()
        assertTrue(IncomingTransferUiCoordinator.takeRequestAlert(taskId, 100L))
        val nextId = taskId + 1
        assertTrue(IncomingTransferUiCoordinator.beginRequest(
            IncomingTransferUiCoordinator.get(taskId)!!.copy(taskId = nextId),
            300L, "request timeout",
        ))
        assertTrue(IncomingTransferUiCoordinator.takeRequestAlert(nextId, 100L))
        assertFalse(IncomingTransferUiCoordinator.takeRequestAlert(taskId, 101L))
        assertFalse(IncomingTransferUiCoordinator.takeRequestAlert(nextId, 101L))
    }

    @Test
    fun expiredRequestDoesNotAlertBeforeItsTimeoutIsCollected() {
        beginRequest()
        assertFalse(IncomingTransferUiCoordinator.takeRequestAlert(taskId, 200L))
        assertFalse(IncomingTransferUiCoordinator.takeRequestAlert(taskId, 201L))
        assertEquals(IncomingTransferUiStatus.REQUESTED, IncomingTransferUiCoordinator.get(taskId)?.status)
    }

    @Test
    fun newRequestKeepsBackgroundFallbackUntilASheetIsVisible() {
        beginRequest()
        val pending = IncomingTransferUiCoordinator.get(taskId)!!
        assertTrue(pending.monitorInBackground)
        assertEquals(IncomingTransferUiStatus.REQUESTED, pending.status)
        assertEquals(LiveStage.WAITING_AUTH, pending.stage)
        assertEquals(0, pending.progress)
        assertEquals(200L, pending.requestExpiresAtMillis)
    }

    @Test
    fun stoppingAnOlderSheetDoesNotHideTheNewVisibleSheet() {
        beginRequest()
        val older = Any()
        val newer = Any()
        assertTrue(IncomingTransferUiCoordinator.show(taskId, older))
        assertTrue(IncomingTransferUiCoordinator.show(taskId, newer))
        IncomingTransferUiCoordinator.hide(taskId, older)
        IncomingTransferUiCoordinator.hide(taskId, older)
        assertFalse(IncomingTransferUiCoordinator.get(taskId)!!.monitorInBackground)
        IncomingTransferUiCoordinator.hide(taskId, newer)
        assertTrue(IncomingTransferUiCoordinator.get(taskId)!!.monitorInBackground)
        assertEquals(IncomingTransferUiStatus.REQUESTED, IncomingTransferUiCoordinator.get(taskId)?.status)
        assertEquals(200L, IncomingTransferUiCoordinator.get(taskId)?.requestExpiresAtMillis)
    }

    @Test
    fun repeatedVisibilityAcknowledgementsAreIdempotent() {
        beginRequest()
        val owner = Any()
        IncomingTransferUiCoordinator.show(taskId, owner)
        IncomingTransferUiCoordinator.show(taskId, owner)
        IncomingTransferUiCoordinator.hide(taskId, owner)
        assertTrue(IncomingTransferUiCoordinator.get(taskId)!!.monitorInBackground)
        IncomingTransferUiCoordinator.show(taskId, owner)
        assertFalse(IncomingTransferUiCoordinator.get(taskId)!!.monitorInBackground)
        assertEquals(200L, IncomingTransferUiCoordinator.get(taskId)?.requestExpiresAtMillis)
    }

    @Test
    fun switchingTasksReleasesOnlyThePreviousSheetIdentity() {
        beginRequest()
        val owner = Any()
        IncomingTransferUiCoordinator.show(taskId, owner)
        val nextId = taskId + 1
        assertTrue(IncomingTransferUiCoordinator.beginRequest(
            IncomingTransferUiCoordinator.get(taskId)!!.copy(taskId = nextId),
            300L, "request timeout",
        ))
        IncomingTransferUiCoordinator.hide(taskId, owner)
        IncomingTransferUiCoordinator.show(nextId, owner)
        IncomingTransferUiCoordinator.hide(taskId, owner)
        assertTrue(IncomingTransferUiCoordinator.get(taskId)!!.monitorInBackground)
        assertFalse(IncomingTransferUiCoordinator.get(nextId)!!.monitorInBackground)
        assertEquals(200L, IncomingTransferUiCoordinator.get(taskId)?.requestExpiresAtMillis)
        assertEquals(300L, IncomingTransferUiCoordinator.get(nextId)?.requestExpiresAtMillis)
    }

    @Test
    fun hidingAnAcceptedTransferPreservesRealProgress() {
        beginRequest()
        IncomingTransferUiCoordinator.show(taskId, this)
        IncomingTransferUiCoordinator.decide(taskId, IncomingRequestDecision.ACCEPTED, 100L)
        IncomingTransferUiCoordinator.markReceiving(taskId, 37)
        IncomingTransferUiCoordinator.hide(taskId, this)
        val hidden = IncomingTransferUiCoordinator.get(taskId)!!
        assertTrue(hidden.monitorInBackground)
        assertEquals(IncomingTransferUiStatus.RECEIVING, hidden.status)
        assertEquals(LiveStage.TRANSFERRING, hidden.stage)
        assertEquals(37, hidden.progress)
        assertFalse(IncomingTransferUiCoordinator.decide(taskId, IncomingRequestDecision.REJECTED, 101L))
    }

    @Test
    fun lateVisibilityCallbacksCannotReviveACompletedOrClearedTask() {
        beginRequest()
        IncomingTransferUiCoordinator.show(taskId, this)
        IncomingTransferUiCoordinator.decide(taskId, IncomingRequestDecision.ACCEPTED, 100L)
        IncomingTransferUiCoordinator.complete(taskId, emptyList(), false)
        IncomingTransferUiCoordinator.hide(taskId, this)
        IncomingTransferUiCoordinator.show(taskId, this)
        val completed = IncomingTransferUiCoordinator.get(taskId)!!
        assertEquals(IncomingTransferUiStatus.SUCCESS, completed.status)
        assertEquals(100, completed.progress)
        assertEquals(LiveStage.COMPLETED, completed.stage)
        assertFalse(completed.stage.requestsPromotion(userInitiated = true))
        IncomingTransferUiCoordinator.clear(taskId)
        assertFalse(IncomingTransferUiCoordinator.show(taskId, this))
        assertFalse(IncomingTransferUiCoordinator.hide(taskId, this))
        assertNull(IncomingTransferUiCoordinator.get(taskId))
    }

    @Test
    fun hidingAndReopeningRequestDoesNotDecideOrResetDeadline() {
        beginRequest()
        assertTrue(IncomingTransferUiCoordinator.hide(taskId, this))
        val hidden = IncomingTransferUiCoordinator.get(taskId)!!
        assertTrue(hidden.monitorInBackground)
        assertEquals(IncomingTransferUiStatus.REQUESTED, hidden.status)
        assertEquals(200L, hidden.requestExpiresAtMillis)
        assertFalse(beginRequest(expiresAtMillis = 300L))
        assertTrue(IncomingTransferUiCoordinator.show(taskId, this))
        assertFalse(IncomingTransferUiCoordinator.get(taskId)!!.monitorInBackground)
        assertEquals(200L, IncomingTransferUiCoordinator.get(taskId)?.requestExpiresAtMillis)
        assertTrue(IncomingTransferUiCoordinator.decide(taskId, IncomingRequestDecision.ACCEPTED, 100L))
    }

    @Test
    fun staleTaskActionsAndReleasedDecisionsCannotAlterResults() {
        beginRequest()
        assertFalse(IncomingTransferUiCoordinator.decide(taskId + 1, IncomingRequestDecision.ACCEPTED, 100L))
        assertTrue(IncomingTransferUiCoordinator.decide(taskId, IncomingRequestDecision.ACCEPTED, 100L))
        IncomingTransferUiCoordinator.hide(taskId, this)
        assertEquals(IncomingTransferUiStatus.RECEIVING, IncomingTransferUiCoordinator.getActive()?.status)
        IncomingTransferUiCoordinator.complete(taskId, emptyList(), true, "result-token")
        IncomingTransferUiCoordinator.releaseDecision(taskId)
        assertFalse(IncomingTransferUiCoordinator.decide(taskId, IncomingRequestDecision.REJECTED, 101L))
        IncomingTransferUiCoordinator.markReceiving(taskId, 50)
        IncomingTransferUiCoordinator.fail(taskId, "late interruption")
        val state = IncomingTransferUiCoordinator.get(taskId)!!
        assertEquals(IncomingTransferUiStatus.PARTIAL, state.status)
        assertEquals("result-token", state.receivedFilesToken)
        assertNull(IncomingTransferUiCoordinator.getActive())
        assertFalse(beginRequest())
    }

    @Test
    fun failedPendingSessionStopsWaitingAndDisablesStaleAcceptance() = runBlocking {
        beginRequest()
        IncomingTransferUiCoordinator.fail(taskId, "connection lost")
        val error = runCatching { IncomingTransferUiCoordinator.awaitDecision(taskId, 100L) }.exceptionOrNull()
        assertTrue(error is CancellationException)
        assertFalse(IncomingTransferUiCoordinator.decide(taskId, IncomingRequestDecision.ACCEPTED, 101L))
    }

    @Test
    fun serializedPresentationKeepsTaskDeadlinesAndVisibility() {
        beginRequest()
        IncomingTransferUiCoordinator.decide(taskId, IncomingRequestDecision.ACCEPTED, 100L)
        IncomingTransferUiCoordinator.hide(taskId, this)
        val state = IncomingTransferUiCoordinator.get(taskId)!!
        val json = Json.encodeToString(state)
        assertFalse(json.contains("\"receivedFiles\":"))
        assertEquals(state, Json.decodeFromString<IncomingTransferUiState>(json))
    }

    @Test
    fun explicitCancelHonorsGuardAndWaitsForStorageOutcome() {
        beginRequest()
        IncomingTransferUiCoordinator.decide(taskId, IncomingRequestDecision.ACCEPTED, 100L)
        assertFalse(IncomingTransferUiCoordinator.cancelReceiving(taskId, "canceled", 1_599L))
        assertTrue(IncomingTransferUiCoordinator.cancelReceiving(taskId, "canceled", 1_600L))
        assertTrue(IncomingTransferUiCoordinator.get(taskId)!!.cancelRequested)
        assertFalse(IncomingTransferUiCoordinator.cancelReceiving(taskId, "canceled", 1_601L))
        IncomingTransferUiCoordinator.markReceiving(taskId, 87)
        assertEquals(0, IncomingTransferUiCoordinator.get(taskId)?.progress)
        assertEquals(IncomingTransferUiStatus.RECEIVING, IncomingTransferUiCoordinator.get(taskId)?.status)
        IncomingTransferUiCoordinator.fail(taskId, "canceled", canceled = true)
        assertEquals(IncomingTransferUiStatus.CANCELED, IncomingTransferUiCoordinator.get(taskId)?.status)
    }

    @Test
    fun cancellationDoesNotRevokeAlreadySavedOutcome() {
        beginRequest()
        IncomingTransferUiCoordinator.decide(taskId, IncomingRequestDecision.ACCEPTED, 100L)
        assertTrue(IncomingTransferUiCoordinator.cancelReceiving(taskId, "canceled", 1_600L))
        IncomingTransferUiCoordinator.complete(taskId, emptyList(), false)
        assertEquals(IncomingTransferUiStatus.SUCCESS, IncomingTransferUiCoordinator.get(taskId)?.status)
        IncomingTransferUiCoordinator.fail(taskId, "late cleanup", canceled = true)
        assertEquals(IncomingTransferUiStatus.SUCCESS, IncomingTransferUiCoordinator.get(taskId)?.status)
    }

    @Test
    fun cancellationPublishesPartialSavedOutcomeBeforeCleanup() {
        beginRequest()
        IncomingTransferUiCoordinator.decide(taskId, IncomingRequestDecision.ACCEPTED, 100L)
        assertTrue(IncomingTransferUiCoordinator.cancelReceiving(taskId, "canceled", 1_600L))
        IncomingTransferUiCoordinator.complete(taskId, emptyList(), true, "partial-files")
        IncomingTransferUiCoordinator.fail(taskId, "late cleanup", canceled = true)
        assertEquals(IncomingTransferUiStatus.PARTIAL, IncomingTransferUiCoordinator.get(taskId)?.status)
        assertEquals("partial-files", IncomingTransferUiCoordinator.get(taskId)?.receivedFilesToken)
    }

    @Test
    fun completedTaskRejectsStaleCancelWithoutStoppingItsJob() {
        beginRequest()
        IncomingTransferUiCoordinator.decide(taskId, IncomingRequestDecision.ACCEPTED, 100L)
        IncomingTransferUiCoordinator.complete(taskId, emptyList(), false)
        assertFalse(IncomingTransferUiCoordinator.cancelReceiving(taskId, "late cancel", 2_000L))
        assertEquals(IncomingTransferUiStatus.SUCCESS, IncomingTransferUiCoordinator.get(taskId)?.status)
    }

    @Test
    fun nextRequestReleasesOldLiveStateButNotItsResultSnapshot() {
        beginRequest()
        IncomingTransferUiCoordinator.decide(taskId, IncomingRequestDecision.ACCEPTED, 100L)
        IncomingTransferUiCoordinator.complete(taskId, emptyList(), false, "stored-files")
        val resultSnapshot = IncomingTransferUiCoordinator.get(taskId)!!
        IncomingTransferUiCoordinator.releaseDecision(taskId)
        assertTrue(IncomingTransferUiCoordinator.beginRequest(
            resultSnapshot.copy(taskId = taskId + 1, status = IncomingTransferUiStatus.REQUESTED),
            300L, "timeout",
        ))
        assertNull(IncomingTransferUiCoordinator.get(taskId))
        assertEquals(IncomingTransferUiStatus.SUCCESS, resultSnapshot.status)
        assertEquals("stored-files", resultSnapshot.receivedFilesToken)
    }

    private fun beginRequest(expiresAtMillis: Long = 200L): Boolean = IncomingTransferUiCoordinator.beginRequest(
        IncomingTransferUiCoordinator.get(taskId)!!.copy(status = IncomingTransferUiStatus.REQUESTED),
        expiresAtMillis = expiresAtMillis,
        timeoutMessage = "request timeout",
        rejectMessage = "request rejected",
    )
}
