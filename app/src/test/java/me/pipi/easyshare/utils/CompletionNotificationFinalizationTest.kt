package me.pipi.easyshare.utils

import android.app.Notification
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class CompletionNotificationFinalizationTest {
    private val foreground = Notification.FLAG_FOREGROUND_SERVICE or Notification.FLAG_NO_CLEAR
    private val detached = Notification.FLAG_NO_CLEAR
    private val ordinary = Notification.FLAG_AUTO_CANCEL or Notification.FLAG_ONLY_ALERT_ONCE

    @Test
    fun normalizationPreservesUnrelatedFlagsAndRestoresOrdinaryDismissal() {
        val flags = foreground or Notification.FLAG_ONGOING_EVENT or Notification.FLAG_PROMOTED_ONGOING or
            Notification.FLAG_LOCAL_ONLY
        assertEquals(ordinary or Notification.FLAG_LOCAL_ONLY, ordinaryCompletionFlags(flags))
        assertEquals(ordinary, ordinaryCompletionFlags(ordinary))
    }

    @Test
    fun missingOrAlreadyNormalizedNotificationsDoNotGetReposted() = runBlocking {
        for ((flags, expected) in listOf(
            null to CompletionNotificationFinalization.ABSENT,
            ordinary to CompletionNotificationFinalization.NORMALIZED,
        )) {
            val outcome = finalizeCompletionNotificationState(
                readFlags = { flags },
                updateIfPresent = { error("Must not repost an absent or normalized notification") },
                pause = { error("No polling is needed") },
            )
            assertEquals(expected, outcome)
        }
    }

    @Test
    fun detachMustBeObservedBeforeUpdatingAndTheUpdateMustBeReadBack() = runBlocking {
        var flags = foreground
        var waits = 0
        var updates = 0
        val outcome = finalizeCompletionNotificationState(
            readFlags = { flags },
            updateIfPresent = {
                assertEquals(detached, flags)
                updates++
                true
            },
            pause = {
                waits++
                flags = when (waits) {
                    1 -> foreground
                    2 -> detached
                    else -> ordinary
                }
            },
        )
        assertEquals(CompletionNotificationFinalization.NORMALIZED, outcome)
        assertEquals(1, updates)
        assertEquals(3, waits)
    }

    @Test
    fun anEnqueuedUpdateThatRegainsForegroundStateIsRetriedAfterDetach() = runBlocking {
        var flags = detached
        var updates = 0
        var waits = 0
        val outcome = finalizeCompletionNotificationState(
            readFlags = { flags },
            updateIfPresent = { updates++; true },
            pause = {
                waits++
                flags = when (waits) {
                    1 -> foreground
                    2 -> detached
                    else -> ordinary
                }
            },
        )
        assertEquals(CompletionNotificationFinalization.NORMALIZED, outcome)
        assertEquals(2, updates)
        assertEquals(3, waits)
    }

    @Test
    fun aNotificationDismissedDuringTheWaitIsNotRecreated() = runBlocking {
        var flags: Int? = foreground
        val outcome = finalizeCompletionNotificationState(
            readFlags = { flags },
            updateIfPresent = { error("A dismissed notification must not be recreated") },
            pause = { flags = null },
        )
        assertEquals(CompletionNotificationFinalization.ABSENT, outcome)
    }

    @Test
    fun disappearanceAtTheUpdateRecheckStopsWithoutAnotherPoll() = runBlocking {
        var reads = 0
        val outcome = finalizeCompletionNotificationState(
            readFlags = { reads++; detached },
            updateIfPresent = { false },
            pause = { error("The notification is already absent") },
        )
        assertEquals(CompletionNotificationFinalization.ABSENT, outcome)
        assertEquals(1, reads)
    }

    @Test
    fun persistentForegroundAndIgnoredUpdatesBothHaveAFinitePollingBudget() = runBlocking {
        for (flags in listOf(foreground, detached)) {
            var reads = 0
            var updates = 0
            var waits = 0
            val outcome = finalizeCompletionNotificationState(
                readFlags = { reads++; flags },
                updateIfPresent = { updates++; true },
                pause = { waits++ },
            )
            assertEquals(CompletionNotificationFinalization.TIMED_OUT, outcome)
            assertEquals(11, reads)
            assertEquals(10, waits)
            assertEquals(if (flags == foreground) 0 else 10, updates)
        }
    }
}
