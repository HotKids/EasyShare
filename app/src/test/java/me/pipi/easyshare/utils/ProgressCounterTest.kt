package me.pipi.easyshare.utils

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

class ProgressCounterTest {
    @Test
    fun reportsImmediatelyThenThrottlesForOneSecond() = runBlocking {
        var now = 10L
        val updates = mutableListOf<Long>()
        val counter = ProgressCounter(100L, nowNanos = { now }) { _, processed ->
            updates += processed
        }

        counter.update(10)
        now += 999_999_999L
        counter.update(20)
        now += 1L
        counter.update(30)

        assertEquals(listOf(10L, 30L), updates)
    }

    @Test
    fun completionForcesTheLastDistinctValue() = runBlocking {
        var now = 10L
        val updates = mutableListOf<Long>()
        val counter = ProgressCounter(100L, nowNanos = { now }) { _, processed ->
            updates += processed
        }

        counter.update(10)
        now += 1L
        counter.complete(100)
        counter.complete(100)

        assertEquals(listOf(10L, 100L), updates)
    }

    @Test
    fun callbackFailurePropagatesToTheTransfer() = runBlocking {
        val expected = IllegalStateException("Notification update failed")
        val counter = ProgressCounter(100L) { _, _ -> throw expected }

        try {
            counter.update(10L)
            fail("A failed notification update must reach the transfer's recovery boundary")
        } catch (actual: IllegalStateException) {
            assertSame(expected, actual)
        }
    }
}
