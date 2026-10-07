package me.pipi.easyshare.utils

import me.pipi.easyshare.models.LiveUpdateState
import me.pipi.easyshare.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class TransferNotificationPolicyTest {
    @Test
    fun preparationConsentAndSavingShowTheAttachmentInsteadOfRepeatingTheStageTitle() {
        listOf(LiveStage.INIT, LiveStage.PREPARING, LiveStage.HANDSHAKE,
            LiveStage.REQUESTED, LiveStage.WAITING_AUTH, LiveStage.FINALIZING).forEach { stage ->
            assertEquals("3 files", stage.notificationContent("photo.jpg", "3 files", "Saved"))
            assertEquals("Text", stage.notificationContent(null, "Text", "Copied"))
        }
        assertEquals("", LiveStage.HANDSHAKE.notificationContent(null, "", "Saved"))
    }

    @Test
    fun transferringKeepsRealFileNamesEvenWhenTheyMatchStatusCopyAndCompletionUsesItsResult() {
        assertEquals("Preparing to send", LiveStage.TRANSFERRING.notificationContent(
            "Preparing to send", "1 file", "Saved"))
        assertEquals("2 files", LiveStage.TRANSFERRING.notificationContent(null, "2 files", "Saved"))
        assertEquals("Saved", LiveStage.COMPLETED.notificationContent("photo.jpg", "2 files", "Saved"))
    }

    @Test
    fun notificationTitlesUseConnectionForOutgoingWaitingAndKeepTerminalStagesDistinct() {
        val expected = mapOf(
            LiveStage.INIT to (R.string.noti_connecting to R.string.preparing_receive),
            LiveStage.PREPARING to (R.string.noti_connecting to R.string.preparing_receive),
            LiveStage.HANDSHAKE to (R.string.noti_connecting to R.string.noti_connecting),
            LiveStage.REQUESTED to (R.string.noti_connecting to R.string.auth_waiting),
            LiveStage.WAITING_AUTH to (R.string.noti_connecting to R.string.auth_waiting),
            LiveStage.TRANSFERRING to (R.string.sending to R.string.receiving),
            LiveStage.FINALIZING to (R.string.finishing_send to R.string.finishing_receive),
            LiveStage.COMPLETED to (R.string.send_ok to R.string.recv_ok),
        )
        LiveStage.entries.forEach { stage ->
            assertEquals(expected.getValue(stage).first, stage.titleResource(sending = true))
            assertEquals(expected.getValue(stage).second, stage.titleResource(sending = false))
        }
    }

    @Test
    fun fileCardsUseOnlyTheMainCopyWhileTextAndFinalizationKeepTheirDetails() {
        assertTrue(LiveStage.PREPARING.usesSingleLineFileCopy(isText = false))
        assertTrue(LiveStage.WAITING_AUTH.usesSingleLineFileCopy(isText = false))
        assertTrue(LiveStage.TRANSFERRING.usesSingleLineFileCopy(isText = false))
        assertFalse(LiveStage.WAITING_AUTH.usesSingleLineFileCopy(isText = true))
        assertFalse(LiveStage.TRANSFERRING.usesSingleLineFileCopy(isText = true))
        assertFalse(LiveStage.FINALIZING.usesSingleLineFileCopy(isText = false))
        assertFalse(LiveStage.COMPLETED.usesSingleLineFileCopy(isText = false))
    }

    @Test
    fun transferNotificationsUseTheRemoteBrandArtwork() {
        listOf(NotificationUtils.SENDER_CHAN_ID, NotificationUtils.RECEIVER_CHAN_ID).forEach { channel ->
            val state = LiveUpdateState(channelId = channel, peerBrandId = 10)
            assertEquals(R.drawable.device_oppo, NotificationUtils.peerIconResource(state))
            assertEquals(R.drawable.device_xiaomi, NotificationUtils.peerIconResource(state.copy(peerBrandId = 30)))
            assertEquals(R.drawable.device_google, NotificationUtils.peerIconResource(state.copy(peerBrandId = 130)))
            assertEquals(R.drawable.device_oppo, NotificationUtils.peerIconResource(state.copy(progress = 67)))
            assertEquals(R.drawable.device_oppo, NotificationUtils.peerIconResource(state.copy(ongoing = false)))
        }
    }

    @Test
    fun unknownPeersOmitArtworkWithoutInheritingThePreviousTaskBrand() {
        listOf(null, -1, 0, 255, Int.MAX_VALUE).forEach { brand ->
            val state = LiveUpdateState(channelId = NotificationUtils.RECEIVER_CHAN_ID, peerBrandId = brand)
            assertNull(NotificationUtils.peerIconResource(state))
        }
        val previous = LiveUpdateState(channelId = NotificationUtils.SENDER_CHAN_ID, peerBrandId = 10)
        assertEquals(R.drawable.device_oppo, NotificationUtils.peerIconResource(previous))
        assertNull(NotificationUtils.peerIconResource(
            LiveUpdateState(channelId = NotificationUtils.SENDER_CHAN_ID),
        ))
    }

    @Test
    fun brandsWithoutDedicatedArtworkDoNotPresentTheAndroidFallbackAsTheirLogo() {
        listOf(114514, 90, 120).forEach { brand ->
            assertNull(NotificationUtils.peerIconResource(LiveUpdateState(
                channelId = NotificationUtils.RECEIVER_CHAN_ID, peerBrandId = brand,
            )))
        }
    }

    @Test
    fun standbyAndOtherNotificationsDoNotPresentARemotePeer() {
        listOf(null, NotificationUtils.RECEIVER_FG_CHAN_ID, "RECEIVER_READY_V3", NotificationUtils.OTHER_CHAN_ID)
            .forEach { channel ->
                assertNull(NotificationUtils.peerIconResource(LiveUpdateState(channelId = channel, peerBrandId = 10)))
            }
    }

    @Test
    fun pendingReceiveRequestsRemainLiveWhileTheHalfSheetIsVisible() {
        assertTrue(LiveStage.WAITING_AUTH.requestsPromotion(userInitiated = false))
        assertEquals(-1, LiveStage.WAITING_AUTH.notificationProgress(0))
        assertFalse(LiveStage.WAITING_AUTH.hasIndeterminateProgress(userInitiated = false))
    }

    @Test
    fun determinateProgressUsesRealBytesAndReservesCompletionForTheResult() {
        assertEquals(0, LiveStage.TRANSFERRING.notificationProgress(-10))
        assertEquals(37, LiveStage.TRANSFERRING.notificationProgress(37))
        assertEquals(99, LiveStage.TRANSFERRING.notificationProgress(100))
        assertEquals(99, LiveStage.TRANSFERRING.notificationProgress(130))
        LiveStage.entries.filter { it != LiveStage.TRANSFERRING }.forEach { stage ->
            assertEquals(-1, stage.notificationProgress(37))
        }
    }

    @Test
    fun preparationAndFinalizationHaveUnknownProgressButWaitingDoesNotHaveABar() {
        val indeterminateStages = setOf(
            LiveStage.INIT,
            LiveStage.PREPARING,
            LiveStage.HANDSHAKE,
            LiveStage.FINALIZING,
        )
        LiveStage.entries.forEach { stage ->
            assertEquals(stage in indeterminateStages, stage.hasIndeterminateProgress(userInitiated = true))
            assertFalse(stage.hasIndeterminateProgress(userInitiated = false))
        }
    }

    @Test
    fun onlyPendingConsentAndUserInitiatedTasksRequestPromotionAndCompletionIsNotLive() {
        LiveStage.entries.forEach { stage ->
            assertEquals(stage == LiveStage.WAITING_AUTH, stage.requestsPromotion(userInitiated = false))
            assertEquals(stage != LiveStage.COMPLETED, stage.requestsPromotion(userInitiated = true))
        }
        assertFalse(LiveUpdateState().promoted)
        assertTrue(LiveStage.PREPARING.requestsPromotion(userInitiated = true))
    }

    @Test
    fun readyChannelSelectionPreservesAnExistingUsersChannel() {
        assertEquals("RECEIVER_READY_V3", NotificationUtils.readyChannelId(hasLegacyChannel = true))
        assertEquals("RECEIVER_READY_V4", NotificationUtils.readyChannelId(hasLegacyChannel = false))
    }

    @Test
    fun promotionRequiresAnOngoingTaskAndAnExplicitLocalChoice() {
        val policy = LiveUpdatePromotionPolicy()
        val pending = LiveUpdateState(taskKey = NotificationUtils.taskKey("receive", 7))
        assertFalse(policy.shouldPromote(pending))
        assertTrue(policy.shouldPromote(pending.copy(promoted = true)))
        assertFalse(policy.shouldPromote(pending.copy(promoted = true, ongoing = false)))
        assertFalse(policy.shouldPromote(pending.copy(promoted = true, taskKey = null)))
        assertFalse(policy.shouldPromote(pending.copy(promoted = true, taskKey = " ")))
        assertEquals(-1, pending.progress)
        assertFalse(pending.indeterminate)
    }

    @Test
    fun dismissalPreventsLaterPromotionWithoutChangingTransferState() {
        val policy = LiveUpdatePromotionPolicy()
        val state = LiveUpdateState(
            taskKey = NotificationUtils.taskKey("receive", 7),
            promoted = true,
            progress = 46,
            ongoing = true,
        )
        policy.dismiss(requireNotNull(state.taskKey))
        assertFalse(policy.shouldPromote(state))
        assertFalse(policy.shouldPromote(state.copy(progress = 99)))
        assertTrue(state.ongoing)
        assertEquals(46, state.progress)
        assertTrue(policy.shouldPromote(state.copy(taskKey = NotificationUtils.taskKey("receive", 8))))
        assertTrue(policy.shouldPromote(state.copy(taskKey = NotificationUtils.taskKey("send", 7))))
    }

    @Test
    fun aNewTaskDoesNotReplaceStandbyOrPreviousTransferResults() {
        assertEquals(2, NotificationUtils.availableTransferNotificationId(emptySet()))
        assertEquals(2, NotificationUtils.availableTransferNotificationId(setOf(1)))
        assertEquals(4, NotificationUtils.availableTransferNotificationId(setOf(1, 2, 3)))
        assertEquals(3, NotificationUtils.availableTransferNotificationId(setOf(1, 2, 4)))
    }

    @Test
    fun notificationIdsRemainDistinctAfterAProcessRestartWithRetainedResults() {
        val retainedIds = setOf(1, 2, 3, 7, 12)
        val first = NotificationUtils.availableTransferNotificationId(retainedIds)
        val second = NotificationUtils.availableTransferNotificationId(retainedIds + first)
        assertEquals(4, first)
        assertEquals(5, second)
    }

    @Test
    fun queuedProgressCannotPublishAfterCompletionOrForANewOwner() {
        assertTrue(NotificationUtils.canPublishTransferNotification(7, 7, terminalStarted = false))
        assertFalse(NotificationUtils.canPublishTransferNotification(7, 7, terminalStarted = true))
        assertFalse(NotificationUtils.canPublishTransferNotification(7, 8, terminalStarted = false))
        assertFalse(NotificationUtils.canPublishTransferNotification(7, null, terminalStarted = false))
    }

    @Test
    fun suppressionSurvivesTerminalRenderingUntilTheOwnerReleasesTheTask() {
        val policy = LiveUpdatePromotionPolicy()
        val taskKey = NotificationUtils.taskKey("receive", 7)
        val active = LiveUpdateState(taskKey = taskKey, promoted = true)
        policy.dismiss(taskKey)
        assertFalse(policy.shouldPromote(active.copy(ongoing = false)))
        assertFalse(policy.shouldPromote(active))
        policy.release(taskKey)
        assertFalse(policy.shouldPromote(active.copy(ongoing = false)))
        assertTrue(policy.shouldPromote(active))
    }
}
