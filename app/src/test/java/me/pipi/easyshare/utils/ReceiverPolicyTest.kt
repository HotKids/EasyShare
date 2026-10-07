package me.pipi.easyshare.utils

import me.pipi.easyshare.utils.ReceiverPolicy.Action
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiverPolicyTest {
    @Test
    fun foregroundReceivesWithBackgroundPreferenceOff() {
        val policy = ReceiverPolicy()
        assertEquals(Action.START, policy.reconcile(true, false, false, true))
        policy.serviceStarted()
        assertEquals(Action.NONE, policy.reconcile(true, false, false, true))
        assertEquals(Action.STOP, policy.reconcile(false, false, false, true))
        assertTrue(policy.serviceStopped())
        assertEquals(Action.NONE, policy.reconcile(false, false, false, true))
    }

    @Test
    fun backgroundPreferenceKeepsDiscoveryAfterLeaving() {
        val policy = ReceiverPolicy()
        assertEquals(Action.START, policy.reconcile(true, true, false, true))
        policy.serviceStarted()
        assertEquals(Action.NONE, policy.reconcile(false, true, false, true))
        assertEquals(Action.STOP, policy.reconcile(false, false, false, true))
    }

    @Test
    fun turningBackgroundOffPreservesArrivedRequestUntilBusyClears() {
        val policy = ReceiverPolicy()
        assertEquals(Action.START, policy.reconcile(false, true, false, true))
        policy.serviceStarted()
        assertEquals(Action.NONE, policy.reconcile(false, false, true, true))
        assertEquals(Action.NONE, policy.reconcile(false, false, true, false))
        assertEquals(Action.STOP, policy.reconcile(false, false, false, true))
    }

    @Test
    fun startAndStopRemainIdempotentAcrossActivityHandoff() {
        val policy = ReceiverPolicy()
        assertEquals(Action.START, policy.reconcile(true, false, false, true))
        assertEquals(Action.NONE, policy.reconcile(true, false, false, true))
        policy.serviceStarted()
        assertEquals(Action.NONE, policy.reconcile(true, false, false, true))
        assertEquals(Action.STOP, policy.reconcile(false, false, false, true))
        assertEquals(Action.NONE, policy.reconcile(true, false, false, true))
        assertTrue(policy.serviceStopped())
        assertEquals(Action.START, policy.reconcile(true, false, false, true))
    }

    @Test
    fun identityRestartWaitsForTransferAndServiceDestruction() {
        val policy = ReceiverPolicy()
        assertEquals(Action.START, policy.reconcile(true, false, false, true))
        policy.serviceStarted()
        policy.requestRestart()
        assertEquals(Action.NONE, policy.reconcile(true, false, true, true))
        assertEquals(Action.STOP, policy.reconcile(true, false, false, true))
        assertEquals(Action.NONE, policy.reconcile(true, false, false, true))
        assertTrue(policy.serviceStopped())
        assertEquals(Action.START, policy.reconcile(true, false, false, true))
    }

    @Test
    fun unavailableOrFailedServiceDoesNotSpinOrStartForBusyAlone() {
        val policy = ReceiverPolicy()
        assertEquals(Action.NONE, policy.reconcile(true, true, false, false))
        assertEquals(Action.NONE, policy.reconcile(false, false, true, true))
        assertEquals(Action.START, policy.reconcile(true, false, false, true))
        assertFalse(policy.serviceStopped())
        assertEquals(Action.START, policy.reconcile(true, false, false, true))
    }

    @Test
    fun activeTaskDefersNewDiscoveryUntilBusyClears() {
        val policy = ReceiverPolicy()
        assertEquals(Action.NONE, policy.reconcile(true, false, true, true))
        assertEquals(Action.NONE, policy.reconcile(false, true, true, true))
        assertEquals(Action.START, policy.reconcile(true, false, false, true))
    }

    @Test
    fun acceptedGattMetadataFinishesItsResponseBeforeDiscoveryStops() {
        val policy = ReceiverPolicy()
        assertEquals(Action.START, policy.reconcile(true, false, false, true))
        policy.serviceStarted()
        val responseEntered = CountDownLatch(1)
        val allowResponse = CountDownLatch(1)
        val stopAttempted = CountDownLatch(1)
        val stopFinished = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val response = executor.submit<Boolean> {
                policy.withGattResponse {
                    responseEntered.countDown()
                    check(allowResponse.await(2, TimeUnit.SECONDS))
                }
            }
            assertTrue(responseEntered.await(2, TimeUnit.SECONDS))
            val stop = executor.submit<Action> {
                stopAttempted.countDown()
                policy.reconcile(false, false, false, true).also { stopFinished.countDown() }
            }
            assertTrue(stopAttempted.await(2, TimeUnit.SECONDS))
            assertFalse("Discovery stopped before the GATT response finished",
                stopFinished.await(100, TimeUnit.MILLISECONDS))
            allowResponse.countDown()
            assertTrue(response.get(2, TimeUnit.SECONDS))
            assertEquals(Action.STOP, stop.get(2, TimeUnit.SECONDS))
        } finally {
            allowResponse.countDown()
            executor.shutdownNow()
            executor.awaitTermination(2, TimeUnit.SECONDS)
        }
    }

    @Test
    fun stoppingDiscoveryDoesNotAcceptNewGattMetadata() {
        val policy = ReceiverPolicy()
        assertEquals(Action.START, policy.reconcile(true, false, false, true))
        policy.serviceStarted()
        assertEquals(Action.STOP, policy.reconcile(false, false, false, true))
        var accepted = false
        assertFalse(policy.withGattResponse { accepted = true })
        policy.serviceStopped()
        assertFalse(policy.withGattResponse { accepted = true })
        assertFalse(accepted)
    }
}
