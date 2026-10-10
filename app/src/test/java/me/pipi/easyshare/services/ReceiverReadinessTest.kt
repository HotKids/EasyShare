package me.pipi.easyshare.services

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiverReadinessTest {
    @Test
    fun advertisingSuccessWaitsForGattServiceAndPublishesOnlyOnce() {
        val readiness = ReceiverReadiness()
        assertFalse(readiness.isReady)
        assertFalse(readiness.advertisingStarted())
        assertFalse(readiness.advertisingStarted())
        assertFalse(readiness.isReady)
        assertTrue(readiness.serviceAdded())
        assertTrue(readiness.isReady)
        assertFalse(readiness.serviceAdded())
        assertFalse(readiness.advertisingStarted())
    }

    @Test
    fun gattServiceSuccessWaitsForAdvertising() {
        val readiness = ReceiverReadiness()
        assertFalse(readiness.serviceAdded())
        assertFalse(readiness.isReady)
        assertTrue(readiness.advertisingStarted())
        assertTrue(readiness.isReady)
    }

    @Test
    fun startupFailureRejectsLateSuccessInEitherOrder() {
        for (completed in 0..2) {
            val readiness = ReceiverReadiness()
            when (completed) {
                1 -> readiness.advertisingStarted()
                2 -> readiness.serviceAdded()
            }
            readiness.stop()
            assertTrue(readiness.isStopped)
            assertFalse(readiness.advertisingStarted())
            assertFalse(readiness.serviceAdded())
            assertFalse(readiness.isReady)
        }
    }

    @Test
    fun destructionClearsReadyAndCannotReviveTheInstance() {
        val readiness = ReceiverReadiness()
        readiness.serviceAdded()
        readiness.advertisingStarted()
        readiness.stop()
        assertFalse(readiness.isReady)
        assertFalse(readiness.serviceAdded())
        assertFalse(readiness.advertisingStarted())
        assertFalse(readiness.isReady)
    }
}
