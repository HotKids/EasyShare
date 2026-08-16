package me.pipi.easyshare.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class ProgressCounterTest {
    @Test
    fun reportsImmediatelyThenThrottlesForOneSecond() {
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
    fun completionForcesTheLastDistinctValue() {
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
}
